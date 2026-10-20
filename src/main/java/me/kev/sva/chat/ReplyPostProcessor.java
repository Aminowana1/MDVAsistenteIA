package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;
import static me.kev.sva.chat.ThreadAffinity.*;
import static me.kev.sva.chat.WikiEvidence.*;
import static me.kev.sva.chat.WikiQueryClassifier.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantResponse;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;

/**
 * Local fixes applied to the model's reply before it reaches public chat: grounding
 * against wiki no_match, rescuing empty direct replies, and merging split bubbles.
 */
final class ReplyPostProcessor {
  private final ServerAssistantPlugin plugin;

  ReplyPostProcessor(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  List<String> enforceWikiNoMatchGrounding(SceneRequest scene, List<String> modelReplies) {
    if (scene == null || scene.context() == null) {
      return modelReplies == null ? List.of() : List.copyOf(modelReplies);
    }
    String wiki = scene.context().locallyRetrievedWiki();
    if (wiki == null || wiki.isBlank() || !wiki.toLowerCase(Locale.ROOT).contains("result=no_match")) {
      return modelReplies == null ? List.of() : List.copyOf(modelReplies);
    }

    int maxReplies = Math.max(plugin.getConfig().getInt("chat.max-messages-per-response", 3), 1);
    LinkedHashMap<UUID, PlayerChatMessage> currentBySpeaker = new LinkedHashMap<>();
    for (ChatMessage message : scene.currentSceneMessages()) {
      if (message instanceof PlayerChatMessage playerMessage) {
        currentBySpeaker.put(playerMessage.playerId, playerMessage);
      }
    }
    if (currentBySpeaker.isEmpty()) return modelReplies == null ? List.of() : List.copyOf(modelReplies);

    List<PlayerChatMessage> noMatchSpeakers = new ArrayList<>();
    for (PlayerChatMessage speaker : currentBySpeaker.values()) {
      String block = wikiBlockForSpeaker(wiki, speaker.playerName);
      if (!block.isBlank() && wikiBlockIsNoMatch(block)) noMatchSpeakers.add(speaker);
    }
    if (noMatchSpeakers.isEmpty()) return modelReplies == null ? List.of() : List.copyOf(modelReplies);

    boolean multi = currentBySpeaker.size() > 1;
    if (!multi && noMatchSpeakers.size() == 1) {
      return List.of("no tengo informacion fiable sobre eso");
    }

    List<String> kept = new ArrayList<>();
    if (modelReplies != null) {
      outer:
      for (String reply : modelReplies) {
        if (reply == null || reply.isBlank()) continue;
        for (PlayerChatMessage speaker : noMatchSpeakers) {
          if (containsWholeWordIgnoreCase(reply, speaker.playerName)
              || replyMentionsConcreteNoMatchSubject(reply, speaker.content)) {
            continue outer;
          }
        }
        kept.add(reply.trim());
      }
    }

    for (PlayerChatMessage speaker : noMatchSpeakers) {
      if (kept.size() >= maxReplies) break;
      kept.add(speaker.playerName + ", no tengo informacion fiable sobre eso");
    }
    return List.copyOf(kept);
  }

  static boolean replyMentionsConcreteNoMatchSubject(String reply, String rawQuery) {
    String normalizedReply = normalizeForSearch(reply);
    String normalizedQuery = normalizeForSearch(rawQuery);
    if (normalizedReply.isBlank() || normalizedQuery.isBlank()) return false;
    LinkedHashSet<String> qualifiers = new LinkedHashSet<>(wikiIdentityTerms(normalizedQuery));
    if (qualifiers.isEmpty()) return false;
    Set<String> replyTokens = tokenSet(normalizedReply);
    for (String qualifier : qualifiers) {
      if (containsWholeWordIgnoreCase(normalizedReply, qualifier)
          || bestFuzzyTokenScore(qualifier, replyTokens) > 0) {
        return true;
      }
    }
    return false;
  }

  List<String> coalesceReplyBurstForScene(SceneRequest scene, List<String> messages) {
    if (messages == null || messages.size() <= 1 || scene == null) {
      return messages == null ? List.of() : List.copyOf(messages);
    }

    LinkedHashSet<String> allowedTargets = new LinkedHashSet<>();
    for (ChatMessage message : scene.currentSceneMessages()) {
      if (!(message instanceof PlayerChatMessage playerMessage)) continue;
      if (scene.followUpEligiblePlayerIds().contains(playerMessage.playerId)
          || scene.groupParticipantCandidateIds().contains(playerMessage.playerId)) {
        allowedTargets.add(playerMessage.playerName);
      }
    }
    return coalesceMessagesByExplicitTargets(messages, allowedTargets);
  }

  /**
   * Pure reply-shaping helper used by tests too. A model may use multiple m[] entries
   * only to address different current speakers. Generic continuations are appended to
   * the current bubble instead of becoming extra spammy chat messages.
   */
  static List<String> coalesceMessagesByExplicitTargets(
      List<String> messages,
      Set<String> allowedTargets) {
    if (messages == null || messages.isEmpty()) return List.of();
    if (messages.size() == 1) return List.copyOf(messages);

    LinkedHashMap<String, StringBuilder> targeted = new LinkedHashMap<>();
    StringBuilder shared = new StringBuilder();
    String currentTarget = null;

    for (String raw : messages) {
      String message = raw == null ? "" : raw.trim();
      if (message.isBlank()) continue;

      String explicitTarget = null;
      if (allowedTargets != null) {
        for (String name : allowedTargets) {
          if (name != null && !name.isBlank() && containsWholeWordIgnoreCase(message, name)) {
            explicitTarget = name.toLowerCase(Locale.ROOT);
            break;
          }
        }
      }

      if (explicitTarget != null) {
        currentTarget = explicitTarget;
        appendReplyFragment(targeted.computeIfAbsent(explicitTarget, ignored -> new StringBuilder()), message);
      } else if (currentTarget != null && targeted.containsKey(currentTarget)) {
        appendReplyFragment(targeted.get(currentTarget), message);
      } else {
        appendReplyFragment(shared, message);
      }
    }

    // No distinct explicit targets: this was one thought split across m[].
    if (targeted.size() <= 1) {
      StringBuilder merged = new StringBuilder();
      appendReplyFragment(merged, shared.toString());
      for (StringBuilder value : targeted.values()) appendReplyFragment(merged, value.toString());
      return merged.isEmpty() ? List.of() : List.of(merged.toString());
    }

    List<String> out = new ArrayList<>();
    if (!shared.isEmpty()) out.add(shared.toString());
    for (StringBuilder value : targeted.values()) {
      if (!value.isEmpty()) out.add(value.toString());
    }
    return List.copyOf(out);
  }

  private static void appendReplyFragment(StringBuilder out, String fragment) {
    if (out == null || fragment == null || fragment.isBlank()) return;
    if (!out.isEmpty()) out.append(' ');
    out.append(fragment.trim());
  }

  Set<UUID> resolveConversationParticipants(SceneRequest scene, AssistantResponse response) {
    if (scene == null) return Set.of();
    LinkedHashSet<UUID> participants = new LinkedHashSet<>(scene.followUpEligiblePlayerIds());
    if (response == null) return Set.copyOf(participants);

    // Strong local threading evidence (explicit bridge, reference+shared topic, etc.)
    // can grant continuity without spending completion tokens on f. Crucially, only
    // locally classified candidates are eligible; random capture-window speakers are not.
    if (!response.historyText().isBlank()) {
      participants.addAll(scene.autoGroupParticipantIds());
    }

    Map<String, UUID> allowedCandidates = scene.groupParticipantCandidatesByName();
    for (String name : response.getFollowUpSpeakers()) {
      if (name == null || name.isBlank()) continue;
      UUID id = allowedCandidates.get(name.toLowerCase(Locale.ROOT));
      if (id != null) participants.add(id);
    }

    Map<String, UUID> currentSpeakers = new LinkedHashMap<>();
    for (ChatMessage message : scene.currentSceneMessages()) {
      if (message instanceof PlayerChatMessage playerMessage) {
        currentSpeakers.putIfAbsent(playerMessage.playerName.toLowerCase(Locale.ROOT), playerMessage.playerId);
      }
    }

    // Explicit bridge fallback remains zero-token, but is now gated by Java's thread
    // candidate set. "dile la verdad" can join; "yo tengo" from a stone trade cannot.
    for (ChatMessage message : scene.currentSceneMessages()) {
      if (message instanceof PlayerChatMessage playerMessage
          && scene.groupParticipantCandidateIds().contains(playerMessage.playerId)
          && looksLikeExplicitGroupBridge(playerMessage.content)) {
        participants.add(playerMessage.playerId);
      }
    }

    // Relationship bookkeeping is additional evidence only for already-classified
    // group candidates (or direct addressers). It can no longer promote ambient chat.
    for (var update : response.getRelationshipUpdates()) {
      if (update == null || update.playerName() == null) continue;
      UUID id = currentSpeakers.get(update.playerName().toLowerCase(Locale.ROOT));
      if (id != null && (scene.groupParticipantCandidateIds().contains(id)
          || scene.followUpEligiblePlayerIds().contains(id))) {
        participants.add(id);
      }
    }
    return Set.copyOf(participants);
  }

  boolean isDirectMentionScene(SceneRequest scene) {
    return scene != null && scene.context() != null && scene.context().sceneMeta() != null
        && scene.context().sceneMeta().contains("trigger=direct_mention");
  }

  List<String> buildMissingIndependentKnowledgeReplies(
      SceneRequest scene,
      List<String> modelReplies) {
    if (scene == null) return List.of();
    int maxReplies = Math.max(plugin.getConfig().getInt("chat.max-messages-per-response", 3), 1);
    List<PlayerChatMessage> addressed = currentAddressedSpeakers(scene, maxReplies);
    if (addressed.size() <= 1) return List.of();

    String wiki = scene.context() == null ? "" : scene.context().locallyRetrievedWiki();
    String relationships = scene.context() == null ? "" : scene.context().relationshipContext();
    if (wiki.isBlank()) return List.of();

    List<String> replies = new ArrayList<>();
    int existing = modelReplies == null ? 0 : modelReplies.size();
    for (PlayerChatMessage speaker : addressed) {
      if (existing + replies.size() >= maxReplies) break;
      if (currentRequestRefusedForSpeaker(relationships, speaker.playerName)) continue;
      String block = wikiBlockForSpeaker(wiki, speaker.playerName);
      if (block.isBlank() || wikiBlockIsNoMatch(block)) continue;
      // Local coverage is allowed only when Java actually selected trusted wiki
      // content. A result=no_match block is an instruction to the model, not a fact
      // and never justifies manufacturing an extra public reply.
      // CORE requires independent factual replies in multi-speaker scenes to prefix
      // the exact speaker name. A shared/group reply intentionally has no such
      // requirement and must not trigger fake per-player coverage.
      if (responseCoversSpeaker(modelReplies, speaker.playerName)) continue;

      String answer = extractWikiFallbackAnswer(speaker.content, block);
      if (answer.isBlank()) continue;
      replies.add(speaker.playerName + ", " + answer);
    }
    return List.copyOf(replies);
  }

  static boolean responseCoversSpeaker(List<String> replies, String playerName) {
    if (replies == null || replies.isEmpty() || playerName == null || playerName.isBlank()) return false;
    for (String reply : replies) {
      if (reply != null && containsWholeWordIgnoreCase(reply, playerName)) return true;
    }
    return false;
  }

  private List<PlayerChatMessage> currentAddressedSpeakers(SceneRequest scene, int maxReplies) {
    if (scene == null || scene.currentSceneMessages() == null) return List.of();
    LinkedHashMap<UUID, PlayerChatMessage> latestBySpeaker = new LinkedHashMap<>();
    for (ChatMessage message : scene.currentSceneMessages()) {
      if (!(message instanceof PlayerChatMessage playerMessage)) continue;
      if (!scene.followUpEligiblePlayerIds().contains(playerMessage.playerId)) continue;
      // Keep insertion order by first addressed appearance, but refresh the content to
      // the newest line from that same speaker inside this capture window.
      latestBySpeaker.put(playerMessage.playerId, playerMessage);
    }
    List<PlayerChatMessage> addressed = new ArrayList<>(latestBySpeaker.values());
    if (addressed.size() > maxReplies) addressed = addressed.subList(0, maxReplies);
    return List.copyOf(addressed);
  }

  List<String> buildGuaranteedDirectReplies(SceneRequest scene) {
    if (scene == null) return List.of();
    int maxReplies = Math.max(plugin.getConfig().getInt("chat.max-messages-per-response", 3), 1);
    List<PlayerChatMessage> addressed = currentAddressedSpeakers(scene, maxReplies);
    if (addressed.isEmpty()) return List.of();

    String wiki = scene.context() == null ? "" : scene.context().locallyRetrievedWiki();
    String relationships = scene.context() == null ? "" : scene.context().relationshipContext();
    boolean multi = addressed.size() > 1;

    // First rescue only independent trusted knowledge requests. This preserves the
    // natural one-line shape of shared group discussions even when the model went empty.
    List<String> factual = new ArrayList<>();
    for (PlayerChatMessage speaker : addressed) {
      if (factual.size() >= maxReplies) break;
      if (currentRequestRefusedForSpeaker(relationships, speaker.playerName)) continue;
      String block = wikiBlockForSpeaker(wiki, speaker.playerName);
      if (block.isBlank()) continue;
      String answer;
      if (wikiBlockIsNoMatch(block)) {
        answer = "no tengo informacion fiable sobre eso y no voy a inventarla";
      } else {
        answer = extractWikiFallbackAnswer(speaker.content, block);
        if (answer.isBlank()) continue;
      }
      factual.add((multi ? speaker.playerName + ", " : "") + answer);
    }
    if (!factual.isEmpty()) return List.copyOf(factual);

    // If a hostile request was deterministically refused, preserve that decision even
    // when the provider returned m=[]. Otherwise use one single social acknowledgement,
    // never one mechanical fallback per participant.
    for (int i = addressed.size() - 1; i >= 0; i--) {
      PlayerChatMessage speaker = addressed.get(i);
      if (!currentRequestRefusedForSpeaker(relationships, speaker.playerName)) continue;
      String tier = relationshipTierForSpeaker(relationships, speaker.playerName);
      String answer = tier.equals("arch-enemy")
          ? "búscatelo tú, pedazo de mierda"
          : tier.equals("enemy")
              ? "arréglatelas tú, no me da la gana ayudarte"
              : "no me da la gana hacerte ese favor";
      return List.of((multi ? speaker.playerName + ", " : "") + answer);
    }

    PlayerChatMessage last = addressed.get(addressed.size() - 1);
    return List.of((multi ? last.playerName + ", " : "") + "te leí; no me quedo muda, pero no tengo mucho que añadir");
  }

  private static String wikiBlockForSpeaker(String wiki, String playerName) {
    if (wiki == null || wiki.isBlank() || playerName == null || playerName.isBlank()) return "";
    String lower = wiki.toLowerCase(Locale.ROOT);
    String marker = "[wiki request speaker=" + playerName.toLowerCase(Locale.ROOT) + " ";
    int start = lower.indexOf(marker);
    if (start < 0) return "";
    int next = lower.indexOf("\n[wiki request speaker=", start + marker.length());
    return next < 0 ? wiki.substring(start) : wiki.substring(start, next);
  }

  static boolean wikiBlockIsNoMatch(String wikiBlock) {
    return wikiBlock != null
        && wikiBlock.toLowerCase(Locale.ROOT).contains("result=no_match");
  }

  private static String relationshipTierForSpeaker(String context, String playerName) {
    if (context == null || context.isBlank() || playerName == null) return "";
    for (String line : context.split("\\R")) {
      String lower = line.toLowerCase(Locale.ROOT);
      if (!lower.startsWith("player=" + playerName.toLowerCase(Locale.ROOT) + " ")) continue;
      int at = lower.indexOf(" tier=");
      if (at < 0) return "";
      int from = at + 6;
      int to = lower.indexOf(' ', from);
      return (to < 0 ? lower.substring(from) : lower.substring(from, to)).trim();
    }
    return "";
  }

  private static boolean currentRequestRefusedForSpeaker(String context, String playerName) {
    if (context == null || context.isBlank() || playerName == null || playerName.isBlank()) return false;
    boolean inTarget = false;
    String target = "player=" + playerName.toLowerCase(Locale.ROOT) + " ";
    for (String rawLine : context.split("\\R")) {
      String line = rawLine.trim().toLowerCase(Locale.ROOT);
      if (line.startsWith("player=")) {
        inTarget = line.startsWith(target);
        continue;
      }
      if (inTarget && line.equals("current_request_policy=refuse")) return true;
    }
    return false;
  }

  /** Extracts a tiny factual answer from the already trusted wiki block. */
  static String extractWikiFallbackAnswer(String rawQuestion, String wikiBlock) {
    if (rawQuestion == null || wikiBlock == null || wikiBlock.isBlank()) return "";
    // result=no_match contains an INTERNAL instruction line, not trusted knowledge.
    // Never let the local extractor turn that protocol text into Isolda dialogue.
    if (wikiBlockIsNoMatch(wikiBlock)) return "";
    String query = expandWikiAliases(normalizeForSearch(rawQuestion));
    // The local extractor sees only the CURRENT wording. For a subjectless wiki
    // follow-up, guessing an entity from the selected block can cross-contaminate a
    // multi-entity section. Let the normal model answer handle it; if the model is
    // empty, the generic direct-message fallback is safer than a fabricated fact.
    if (looksLikeWikiFollowUp(query) && !wikiQueryHasIndependentSubject(query)) return "";
    Set<String> terms = new LinkedHashSet<>(meaningfulTerms(query));
    terms.removeAll(Set.of("drops", "drop", "dropea", "droppea", "coordenadas", "miniboss"));
    if (terms.isEmpty()) return "";

    String[] lines = wikiBlock.split("\\R");
    int bestIndex = -1;
    int bestHits = 0;
    for (int i = 0; i < lines.length; i++) {
      String normalized = normalizeForSearch(lines[i]);
      int hits = 0;
      for (String term : terms) {
        if (containsWholeWordIgnoreCase(normalized, term)
            || bestFuzzyTokenScore(term, tokenSet(normalized)) > 0) hits++;
      }
      if (hits > bestHits) {
        bestHits = hits;
        bestIndex = i;
      }
    }
    if (bestIndex < 0 || bestHits == 0) return "";

    boolean wantsDrops = containsAnyTerm(query, "drops", "drop ", "dropea", "droppea", "que da ", "q da ", "suelta");
    List<String> chosen = new ArrayList<>();
    if (wantsDrops) {
      int dropsAt = -1;
      for (int i = bestIndex; i < Math.min(lines.length, bestIndex + 45); i++) {
        if (normalizeForSearch(lines[i]).equals("drops")) {
          dropsAt = i;
          break;
        }
      }
      if (dropsAt >= 0) {
        for (int i = dropsAt + 1; i < Math.min(lines.length, dropsAt + 5); i++) {
          String line = cleanWikiFallbackLine(lines[i]);
          if (line.isBlank()) break;
          if (!line.startsWith("[") && !line.endsWith(":")) chosen.add(line);
        }
      }
    }

    if (chosen.isEmpty()) {
      for (int i = bestIndex; i < Math.min(lines.length, bestIndex + 4); i++) {
        String line = cleanWikiFallbackLine(lines[i]);
        if (line.isBlank()) {
          if (!chosen.isEmpty()) break;
          continue;
        }
        if (line.toLowerCase(Locale.ROOT).startsWith("[wiki request")) continue;
        chosen.add(line);
      }
    }
    String answer = String.join(" ", chosen)
        .replaceAll("\\s{2,}", " ")
        .trim();
    if (answer.length() > 185) answer = answer.substring(0, 182).trim() + "...";
    return answer;
  }

  private static String cleanWikiFallbackLine(String line) {
    if (line == null) return "";
    String cleaned = line.trim();
    cleaned = cleaned.replaceFirst("^\\[[^\\]]+\\]\\s*", "");
    cleaned = cleaned.replaceFirst("^-\\s*", "");
    return cleaned.trim();
  }
}
