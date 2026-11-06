package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;
import static me.kev.sva.chat.WikiEvidence.*;
import static me.kev.sva.chat.WikiQueryClassifier.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.configuration.ConfigurationSection;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;

/**
 * Local wiki retrieval before the single model request: the in-memory index,
 * section ranking and the tiny per-player subject anchor for follow-ups.
 */
final class WikiRetriever {
  private final ServerAssistantPlugin plugin;
  private final Deque<SceneMemory> sceneHistory;

  /** Pre-normalized wiki snapshot rebuilt on plugin load/reload. */
  private final List<WikiIndexEntry> wikiIndex;

  private static final long WIKI_SUBJECT_ANCHOR_MAX_AGE_MS = 120_000L;

  /**
   * Last self-contained wiki subject per player. This tiny local-only anchor is used
   * only for genuinely incomplete factual follow-ups such as "y donde sale?". A new
   * self-contained factual question replaces it immediately, so adjacent knowledge
   * topics can never contaminate each other.
   */
  private final Map<UUID, WikiSubjectAnchor> wikiSubjectAnchorByPlayer = new HashMap<>();

  WikiRetriever(ServerAssistantPlugin plugin, ConversationLog log) {
    this.plugin = plugin;
    this.sceneHistory = log.sceneHistory();
    this.wikiIndex = buildWikiIndex();
    plugin.getLogger().info("Local wiki index cached in RAM: " + wikiIndex.size() + " sections.");
  }

  void forgetPlayer(UUID playerId) {
    wikiSubjectAnchorByPlayer.remove(playerId);
  }

  void clear() {
    wikiSubjectAnchorByPlayer.clear();
  }

  String retrieveLocalWiki(List<ChatMessage> currentMessages) {
    if (plugin.getToolManager() != null && !plugin.getToolManager().isToolEnabled("wiki")) {
      return "";
    }
    if (!plugin.getWikiConfig().getBoolean("lazy-mode", true)) {
      return fullWikiContext();
    }
    if (!plugin.getWikiConfig().getBoolean("local-retrieval.enabled", true) || wikiIndex.isEmpty()) {
      return "";
    }

    int maxQueries = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.max-queries-per-scene", 3), 1);
    List<WikiQuerySelection> selections = selectWikiQueries(currentMessages, maxQueries);
    if (selections.isEmpty()) {
      if (plugin.getWikiConfig().getBoolean("local-retrieval.debug-log", false)) {
        plugin.getLogger().info("Wiki retrieval skipped=no-knowledge-intent");
      }
      return "";
    }

    int minScore = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.min-score", 2), 1);
    int maxSectionsPerQuery = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.max-sections", 2), 0);
    int maxTotalSections = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.max-total-sections", 4), 1);
    int maxSectionChars = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.max-section-chars", 3500), 200);
    int maxTotalChars = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.max-total-chars", 5200), 500);
    if (maxSectionsPerQuery == 0) return "";

    List<WikiRankedQuery> rankedQueries = new ArrayList<>();
    for (WikiQuerySelection selection : selections) {
      String rawQuery = selection.rawQuery();
      String directQuery = normalizeForSearch(rawQuery);

      // 1.7.17 direct-first retrieval:
      // - complete new factual questions NEVER inherit the previous wiki topic;
      // - only genuinely subjectless follow-ups inherit one tiny local subject anchor;
      // - a subjectless follow-up with no valid anchor is NOT allowed to search generic
      //   property words such as "vida", "drops" or "rareza" across the whole wiki.
      boolean subjectlessFollowUp = looksLikeWikiFollowUp(directQuery);
      WikiSubjectAnchor inheritedAnchor = (subjectlessFollowUp
          || looksLikeEllipticalWikiSubjectSwap(directQuery))
          ? resolveWikiFollowUpSubjectFlexible(directQuery, List.<ChatMessage>of(selection.message()))
          : null;
      boolean subjectSwap = inheritedAnchor != null && looksLikeEllipticalWikiSubjectSwap(directQuery);
      if (subjectlessFollowUp && inheritedAnchor == null) {
        if (plugin.getWikiConfig().getBoolean("local-retrieval.debug-log", false)) {
          plugin.getLogger().info("Wiki retrieval no_match=no-followup-subject speaker='"
              + selection.message().playerName + "' query='" + directQuery + "'");
        }
        // Keep this turn fail-closed inside the SAME model request. Previously an
        // unanchored factual continuation such as "dame los crafteos" returned no
        // wiki block at all, leaving the model free to invent server facts.
        rankedQueries.add(new WikiRankedQuery(
            selection, directQuery, directQuery, List.of(), false));
        continue;
      }
      boolean contextExpanded = inheritedAnchor != null
          && inheritedAnchor.subjectQuery() != null
          && !inheritedAnchor.subjectQuery().isBlank();
      String inheritedSubject = contextExpanded ? inheritedAnchor.subjectQuery() : null;
      String currentSubject = subjectSwap ? buildWikiSubjectAnchor(directQuery) : inheritedSubject;
      boolean metaRetry = contextExpanded && looksLikeWikiMetaRetry(directQuery);
      String effectiveFactQuery = (subjectSwap || metaRetry)
          ? applyWikiIntentToSubject(inheritedAnchor.intentKind(),
              subjectSwap ? currentSubject : inheritedSubject)
          : directQuery;
      String expandedRawQuery = subjectSwap || metaRetry
          ? effectiveFactQuery
          : contextExpanded
              ? ((rawQuery == null ? "" : rawQuery.trim()) + " " + inheritedSubject).trim()
              : rawQuery;
      String query = expandWikiAliases(canonicalWikiRoutingQuery(expandedRawQuery));
      Set<String> queryTerms = meaningfulTerms(query);
      if (queryTerms.isEmpty()) {
        if (plugin.getWikiConfig().getBoolean("local-retrieval.debug-log", false)) {
          plugin.getLogger().info("Wiki retrieval skipped=no-meaningful-terms speaker='"
              + selection.message().playerName + "' query='" + directQuery + "'");
        }
        continue;
      }
      List<WikiCandidate> candidates = rankWikiCandidates(query, queryTerms);
      // Entity existence is verified against the resolved SUBJECT only. Current
      // follow-up property words ("mobs", "dropean", "donde") must not become
      // identity requirements for the entity itself.
      String verificationQuery = subjectSwap
          ? effectiveFactQuery
          : contextExpanded
              ? inheritedAnchor.verificationQuery()
              : rawQuery;
      if (looksLikeConcreteNamedEntityQuery(verificationQuery)) {
        candidates = candidates.stream()
            .filter(candidate -> candidateStronglyMatchesConcreteEntity(
                verificationQuery, candidate.key(), candidate.description(), candidate.content()))
            .toList();
      }
      // Distinguish subject existence/category evidence from support for the exact
      // property being asked. A known entity may lack a documented stat and still be
      // a valid anchor; a made-up entity must never replace the previous trusted one.
      boolean subjectVerified = candidates.stream().anyMatch(candidate -> candidate.score() >= minScore);

      // Existence is not enough to answer a requested FACT. A page can prove that
      // Galumrog exists without containing his drops, exact life, weapon or price.
      // Filter such pages locally so the model receives result=no_match instead of
      // being tempted to fill a missing property from generic fantasy knowledge.
      candidates = candidates.stream()
          .filter(candidate -> candidateSupportsWikiFactIntent(
              effectiveFactQuery, verificationQuery,
              candidate.key(), candidate.description(), candidate.content()))
          .toList();

      // Remember only the resolved subject. Never accumulate previous player wording
      // or assistant replies into future retrieval queries. This costs zero AI tokens.
      String resolvedSubject = subjectSwap
          ? currentSubject
          : contextExpanded ? inheritedSubject : buildWikiSubjectAnchor(directQuery);
      String resolvedVerification = subjectSwap
          ? effectiveFactQuery
          : contextExpanded ? inheritedAnchor.verificationQuery() : rawQuery;
      boolean explicitNewSubject = wikiQueryHasIndependentSubject(directQuery)
          || looksLikeBroadWikiCategoryQuery(directQuery) || subjectSwap;
      if (subjectVerified && (explicitNewSubject || contextExpanded)) {
        rememberWikiSubject(
            selection.message().playerId, resolvedSubject, resolvedVerification,
            subjectSwap ? inheritedAnchor.intentKind() : wikiIntentKind(effectiveFactQuery));
      } else if (explicitNewSubject && !subjectVerified) {
        // A new unknown subject invalidates the old anchor. Otherwise a later
        // "y donde sale?" could incorrectly jump back to an unrelated earlier item.
        wikiSubjectAnchorByPlayer.remove(selection.message().playerId);
      }

      rankedQueries.add(new WikiRankedQuery(
          selection, directQuery, query, candidates, contextExpanded));
    }
    if (rankedQueries.isEmpty()) return "";

    boolean multiQueryScene = rankedQueries.size() > 1;
    int perQueryCap = multiQueryScene ? 1 : maxSectionsPerQuery;
    StringBuilder out = new StringBuilder();
    int totalSections = 0;

    for (WikiRankedQuery ranked : rankedQueries) {
      List<String> selectedKeys = new ArrayList<>();
      StringBuilder block = new StringBuilder();
      int usedForQuery = 0;
      for (WikiCandidate candidate : ranked.candidates()) {
        if (candidate.score() < minScore || usedForQuery >= perQueryCap
            || totalSections >= maxTotalSections) break;
        String content = candidate.content().trim();
        if (content.isBlank()) continue;
        if (content.length() > maxSectionChars) {
          content = content.substring(0, maxSectionChars).trim();
        }
        String rendered = "[" + candidate.key() + "] " + content;
        int headerReserve = 120;
        int remaining = maxTotalChars - out.length() - block.length() - headerReserve;
        if (remaining < 120) break;
        if (rendered.length() > remaining) rendered = rendered.substring(0, remaining).trim();
        if (!block.isEmpty()) block.append('\n');
        block.append(rendered);
        selectedKeys.add(candidate.key() + ":" + candidate.score());
        usedForQuery++;
        totalSections++;
      }

      if (plugin.getWikiConfig().getBoolean("local-retrieval.debug-log", false)) {
        String mode = ranked.contextExpanded() ? " mode=followup-context" : " mode=direct";
        plugin.getLogger().info("Wiki retrieval speaker='" + ranked.selection().message().playerName
            + "' query='" + ranked.query() + "'" + mode + " selected=" + selectedKeys);
      }

      if (!out.isEmpty()) out.append('\n');
      out.append("[WIKI REQUEST speaker=")
          .append(ranked.selection().message().playerName)
          .append(" query='").append(ranked.directQuery()).append("'");
      if (block.isEmpty()) {
        // Explicitly tell the same model request that local trusted knowledge had no
        // answer. Without this marker small models may fill the gap with vanilla or
        // generic Minecraft assumptions, which is unsafe for MDVCRAFT-specific facts.
        out.append(" result=no_match]\n")
            .append("No trusted wiki section matched this server-knowledge question; do not guess a server-specific fact.");
        continue;
      }
      out.append("]\n").append(block);
      if (out.length() >= maxTotalChars || totalSections >= maxTotalSections) break;
    }
    return out.length() > maxTotalChars ? out.substring(0, maxTotalChars).trim() : out.toString();
  }

  /**
   * Select at most one knowledge question per CURRENT addressed speaker. The newest
   * question from each speaker wins, then results are restored to chronological order.
   * This lets one model request answer 2-3 people without one player's wiki query
   * stealing the entire scene.
   */
  private List<WikiQuerySelection> selectWikiQueries(List<ChatMessage> currentMessages, int maxQueries) {
    if (currentMessages == null || currentMessages.isEmpty()) return List.of();
    LinkedHashMap<UUID, WikiQuerySelection> newestBySpeaker = new LinkedHashMap<>();
    for (int i = currentMessages.size() - 1; i >= 0; i--) {
      ChatMessage message = currentMessages.get(i);
      if (!(message instanceof PlayerChatMessage playerMessage)) continue;
      if (newestBySpeaker.containsKey(playerMessage.playerId)) continue;
      String raw = playerMessage.content == null ? "" : playerMessage.content.trim();
      String normalized = normalizeForSearch(raw);
      if (normalized.isBlank() || isObviousSocialSmallTalk(normalized)) continue;
      // Deictic opinion prompts such as "q opinas de eso?" are social continuity,
      // not server-knowledge lookups. In 1.7.12 they were accidentally treated as
      // wiki follow-ups because they contain "eso", which could create no_match
      // blocks and later trigger bogus local factual coverage.
      if (looksLikeDeicticOpinionSmallTalk(normalized)) continue;
      boolean recentWikiAnchor = hasRecentWikiSubjectAnchor(playerMessage.playerId);
      if (looksLikeWikiFollowUp(normalized)
          || looksLikeLikelyWikiKnowledgeRequest(normalized)
          || looksLikeImplicitServerEntityQuestion(raw, normalized)
          || looksLikeWikiMetaRetry(normalized)
          || (recentWikiAnchor && looksLikeEllipticalWikiSubjectSwap(normalized))
          || looksLikeKnownWikiTopicFallback(raw, normalized, recentWikiAnchor)) {
        newestBySpeaker.put(playerMessage.playerId, new WikiQuerySelection(playerMessage, raw));
        if (newestBySpeaker.size() >= Math.max(1, maxQueries)) break;
      }
    }
    List<WikiQuerySelection> selected = new ArrayList<>(newestBySpeaker.values());
    java.util.Collections.reverse(selected);
    return List.copyOf(selected);
  }

  private boolean hasRecentWikiSubjectAnchor(UUID playerId) {
    if (playerId == null) return false;
    WikiSubjectAnchor anchor = wikiSubjectAnchorByPlayer.get(playerId);
    if (anchor == null) return false;
    long age = System.currentTimeMillis() - anchor.timestampMs();
    if (age < 0L || age > WIKI_SUBJECT_ANCHOR_MAX_AGE_MS) {
      wikiSubjectAnchorByPlayer.remove(playerId);
      return false;
    }
    return true;
  }

  private boolean looksLikeKnownWikiTopicFallback(
      String raw, String normalized, boolean recentWikiAnchor) {
    if (normalized == null || normalized.isBlank() || isObviousSocialSmallTalk(normalized)
        || looksLikeDeicticOpinionSmallTalk(normalized)) return false;
    String routed = canonicalWikiRoutingQuery(normalized);
    boolean questionShape = raw != null && raw.contains("?")
        || routed.matches("^(?:que|como|donde|quien|quienes|cual|cuales|cuanto|cuanta|para)\\b.*")
        || routed.startsWith("dime ") || routed.startsWith("decime ")
        || routed.startsWith("contame ") || routed.startsWith("cuentame ")
        || routed.startsWith("hablame ") || routed.startsWith("explicame ")
        || routed.startsWith("mostrame ") || routed.startsWith("muestrame ");
    boolean elliptical = recentWikiAnchor && routed.startsWith("y ");
    if (!questionShape && !elliptical) return false;

    Set<String> terms = new LinkedHashSet<>(meaningfulTerms(routed));
    terms.removeAll(WIKI_ENTITY_QUERY_NOISE);
    if (terms.isEmpty()) return false;
    for (WikiIndexEntry indexed : wikiIndex) {
      Set<String> tokens = tokenSet(indexed.normalizedKey() + " " + indexed.normalizedDescription());
      for (String term : terms) {
        if (term.length() < 4) continue;
        if (containsWholeWordIgnoreCase(indexed.normalizedKey(), term)
            || containsWholeWordIgnoreCase(indexed.normalizedDescription(), term)
            || bestFuzzyTokenScore(term, tokens) > 0) return true;
      }
    }
    return false;
  }

  private List<WikiCandidate> rankWikiCandidates(String query, Set<String> queryTerms) {
    String meaningfulPhrase = String.join(" ", queryTerms);
    boolean obtainIntent = isWikiObtainIntent(query);
    boolean craftIntent = isWikiCraftIntent(query);
    boolean dropIntent = isWikiDropIntent(query);
    boolean inverseDropSourceIntent = isInverseDropSourceQuestion(query);
    boolean locationIntent = isWikiLocationIntent(query);
    boolean travelIntent = isWikiTravelIntent(query);
    boolean functionIntent = isWikiFunctionIntent(query);
    boolean recommendationIntent = isWikiRecommendationIntent(query);
    boolean broadCategoryIntent = looksLikeBroadWikiCategoryQuery(query);
    Set<String> topicTerms = wikiTopicTerms(query);

    List<WikiCandidate> candidates = new ArrayList<>();
    for (WikiIndexEntry indexed : wikiIndex) {
      int score = 0;
      if (!indexed.normalizedKey().isBlank() && query.contains(indexed.normalizedKey())) score += 8;

      if (queryTerms.size() >= 2 && meaningfulPhrase.length() >= 7) {
        if (indexed.normalizedKey().contains(meaningfulPhrase)) score += 14;
        if (indexed.normalizedDescription().contains(meaningfulPhrase)) score += 10;
        if (indexed.normalizedContent().contains(meaningfulPhrase)) score += 10;
        if (containsAllTerms(indexed.normalizedKey(), queryTerms)) score += 12;
        else if (containsAllTerms(indexed.normalizedDescription(), queryTerms)) score += 9;
        else if (containsAllTerms(indexed.normalizedContent(), queryTerms)) score += 9;
      }

      Set<String> keyTokens = tokenSet(indexed.normalizedKey());
      Set<String> keyDescriptionTokens = tokenSet(indexed.normalizedKey() + " " + indexed.normalizedDescription());
      for (String term : queryTerms) {
        boolean exactKey = containsWholeWordIgnoreCase(indexed.normalizedKey(), term);
        boolean exactDescription = containsWholeWordIgnoreCase(indexed.normalizedDescription(), term);
        boolean exactContent = containsWholeWordIgnoreCase(indexed.normalizedContent(), term);
        if (exactKey) score += 4;
        if (exactDescription) score += 3;
        if (exactContent) score += 2;
        if (!exactKey && !exactDescription) {
          int fuzzy = bestFuzzyTokenScore(term, keyDescriptionTokens);
          if (fuzzy > 0) score += fuzzy;
        }
      }

      // Strongly prefer a page whose KEY itself matches the requested entity, even
      // when the player misspells it (e.g. acohilitico necrotido -> acolito necrotico).
      // Generic overview pages may mention the same creature in their description,
      // but should not outrank its dedicated page just because they contain "drops".
      Set<String> entityTerms = wikiEntityTerms(queryTerms);
      if (entityTerms.size() >= 2 && allTermsMatchKey(entityTerms, indexed.normalizedKey(), keyTokens)) {
        score += 18;
      }

      String key = indexed.key().toLowerCase(Locale.ROOT);
      // Intent bonuses may only re-rank a section that matched the actual TOPIC,
      // never a page that matched only an intent/property word. This applies to
      // obtain/craft/location AND drops. Otherwise a query such as "que dropea
      // Zorok?" could give every mob page free points merely for containing Drops.
      boolean subjectMatchedBeforeIntentBonuses = candidateMatchesWikiTopic(indexed, topicTerms);
      if (obtainIntent && subjectMatchedBeforeIntentBonuses) {
        if (key.startsWith("obtain-")) score += 7;
        else if (key.startsWith("ore-") || key.startsWith("spawn-")) score += 3;
      }
      if (craftIntent && subjectMatchedBeforeIntentBonuses) {
        if (key.startsWith("crafting-")) score += 7;
        else if (key.startsWith("obtain-")
            && containsAnyTerm(indexed.normalizedContent(),
                "se fabrica", "se crea", "se craftea", "se elabora")) score += 7;
      }
      if (locationIntent && key.startsWith("spawn-") && subjectMatchedBeforeIntentBonuses) score += 6;
      if (travelIntent && subjectMatchedBeforeIntentBonuses) {
        if (key.startsWith("commands-travel") || key.startsWith("lore-gamura")) score += 10;
      }
      if (functionIntent && subjectMatchedBeforeIntentBonuses) {
        if (key.startsWith("crafting-") || key.startsWith("tools-") || key.startsWith("items-")) score += 4;
      }
      if (recommendationIntent && subjectMatchedBeforeIntentBonuses) {
        if (key.startsWith("race-") || key.contains("weapons") || key.startsWith("magic-weapons")) score += 8;
      }
      if (broadCategoryIntent) {
        score += wikiBroadCategoryBoost(query, key, subjectMatchedBeforeIntentBonuses);
      }
      if (dropIntent && subjectMatchedBeforeIntentBonuses) {
        // For "qué mobs la dropean?" the authoritative obtain-* material page
        // usually summarizes all known source families. Promote it ahead of any one
        // individual mob page, then keep a concrete mob/overview page as extra detail.
        if (inverseDropSourceIntent && key.startsWith("obtain-")) score += 12;
        if (key.startsWith("mob-") || key.startsWith("mobs-")) score += 3;
        if (indexed.normalizedContent().contains("drops")) score += 3;
      }

      // Give a decisive local boost when the candidate actually contains the named
      // entity. This also supports compact wiki spellings such as ARCOBOSQUE for a
      // player query like "arco bosque" without relaxing unknown-entity safety.
      if (looksLikeConcreteNamedEntityQuery(query)
          && candidateStronglyMatchesConcreteEntity(
              query, indexed.key(), indexed.description(), indexed.content())) {
        score += 24;
      }

      if (score > 0) {
        candidates.add(new WikiCandidate(indexed.key(), indexed.description(), indexed.content(), score));
      }
    }
    candidates.sort(Comparator.comparingInt(WikiCandidate::score).reversed());
    return List.copyOf(candidates);
  }

  /**
   * Resolves the previous trusted wiki subject only for a genuinely incomplete
   * follow-up. The immediately previous scene must also have used wiki context and
   * involved the same speaker, preventing a stale subject from leaking into social chat.
   */
  private WikiSubjectAnchor resolveWikiFollowUpSubject(
      String normalizedDirectQuery,
      List<ChatMessage> currentMessages) {
    if (!looksLikeWikiFollowUp(normalizedDirectQuery)) return null;
    return resolveWikiFollowUpSubjectFlexible(normalizedDirectQuery, currentMessages);
  }

  private WikiSubjectAnchor resolveWikiFollowUpSubjectFlexible(
      String normalizedDirectQuery,
      List<ChatMessage> currentMessages) {

    if (sceneHistory.isEmpty()) return null;

    SceneMemory previous = sceneHistory.peekLast();
    if (previous == null || !previous.wikiContextUsed()) return null;

    UUID currentSpeaker = lastPlayerId(currentMessages);
    if (currentSpeaker == null) return null;

    boolean speakerWasPresent = previous.messages().stream().anyMatch(message ->
        message instanceof PlayerChatMessage playerMessage
            && currentSpeaker.equals(playerMessage.playerId));
    if (!speakerWasPresent) return null;

    WikiSubjectAnchor anchor = wikiSubjectAnchorByPlayer.get(currentSpeaker);
    if (anchor == null) return null;
    long age = System.currentTimeMillis() - anchor.timestampMs();
    if (age < 0L || age > WIKI_SUBJECT_ANCHOR_MAX_AGE_MS) {
      wikiSubjectAnchorByPlayer.remove(currentSpeaker);
      return null;
    }
    return anchor;
  }

  private void rememberWikiSubject(
      UUID playerId, String subjectQuery, String verificationQuery, WikiIntentKind intentKind) {
    if (playerId == null || subjectQuery == null || subjectQuery.isBlank()) return;
    String verification = verificationQuery == null || verificationQuery.isBlank()
        ? subjectQuery
        : verificationQuery.trim();
    wikiSubjectAnchorByPlayer.put(playerId, new WikiSubjectAnchor(
        subjectQuery.trim(), verification,
        intentKind == null ? WikiIntentKind.GENERAL : intentKind, System.currentTimeMillis()));
  }

  private static UUID lastPlayerId(List<ChatMessage> messages) {
    if (messages == null) return null;
    for (int i = messages.size() - 1; i >= 0; i--) {
      if (messages.get(i) instanceof PlayerChatMessage playerMessage) {
        return playerMessage.playerId;
      }
    }
    return null;
  }

  private String fullWikiContext() {
    if (wikiIndex.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (WikiIndexEntry indexed : wikiIndex) {
      String content = indexed.content();
      if (content.isBlank()) continue;
      if (!out.isEmpty()) out.append('\n');
      out.append('[').append(indexed.key()).append("] ").append(content.trim());
    }
    return out.toString();
  }

  /**
   * Normalizes the entire wiki once instead of repeating three normalization passes
   * for every section on every scene. A /sva reload constructs a new manager/index,
   * so edits remain immediately reloadable without stale data.
   */
  private List<WikiIndexEntry> buildWikiIndex() {
    ConfigurationSection wiki = wikiRoot();
    if (wiki == null) return List.of();

    List<WikiIndexEntry> entries = new ArrayList<>();
    for (String key : wiki.getKeys(false)) {
      ConfigurationSection section = wiki.getConfigurationSection(key);
      if (section == null) continue;
      String description = section.getString("description", "");
      String content = section.getString("content", "");
      description = description == null ? "" : description;
      content = content == null ? "" : content;
      entries.add(new WikiIndexEntry(
          key,
          description,
          content,
          normalizeForSearch(key.replace('-', ' ').replace('_', ' ')),
          normalizeForSearch(description),
          normalizeForSearch(content)));
    }
    return List.copyOf(entries);
  }

  private ConfigurationSection wikiRoot() {
    return plugin.getWikiConfig().getConfigurationSection("wiki");
  }

  private record WikiQuerySelection(PlayerChatMessage message, String rawQuery) { }

  private record WikiRankedQuery(
      WikiQuerySelection selection,
      String directQuery,
      String query,
      List<WikiCandidate> candidates,
      boolean contextExpanded) { }

  private record WikiSubjectAnchor(
      String subjectQuery,
      String verificationQuery,
      WikiIntentKind intentKind,
      long timestampMs) { }

  private record WikiCandidate(String key, String description, String content, int score) { }
}
