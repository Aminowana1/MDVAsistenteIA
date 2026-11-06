package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantManager;
import me.kev.sva.chat.assistant.AssistantRequestContext;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;
import me.kev.sva.chat.tools.ContextTargetResolver;

/**
 * Turns a closed capture window into one {@link SceneRequest}: selects related chat
 * and events, resolves the addressed request and gathers all local context.
 */
final class SceneBuilder {
  private final ServerAssistantPlugin plugin;
  private final Deque<PublicChatRecord> publicChatLog;
  private final Deque<ServerEventRecord> serverEventLog;
  private final Deque<SceneMemory> sceneHistory;
  private final AssistantMentions mentions;
  private final GroupThreadSelector groupThreads;
  private final WikiRetriever wikiRetriever;

  SceneBuilder(
      ServerAssistantPlugin plugin,
      ConversationLog log,
      AssistantMentions mentions,
      GroupThreadSelector groupThreads,
      WikiRetriever wiki) {
    this.plugin = plugin;
    this.publicChatLog = log.publicChat();
    this.serverEventLog = log.serverEvents();
    this.sceneHistory = log.sceneHistory();
    this.mentions = mentions;
    this.groupThreads = groupThreads;
    this.wikiRetriever = wiki;
  }

  SceneRequest buildScene(
      ActiveCapture capture,
      Set<UUID> addressers,
      Map<String, String> activeIdentities) {
    long lookbackMs = Math.max(
        plugin.getConfig().getLong("global-conversation.scene.pre-lookback-ms", 10_000L),
        0L);
    long from = capture.triggerAt() - lookbackMs;
    long to = capture.endsAt();

    List<PublicChatRecord> chatCandidates = publicChatLog.stream()
        .filter(m -> m.timestampMs() >= from && m.timestampMs() <= to)
        .sorted(Comparator.comparingLong(PublicChatRecord::timestampMs))
        .toList();
    List<ServerEventRecord> eventCandidates = serverEventLog.stream()
        .filter(e -> e.timestampMs() >= from && e.timestampMs() <= to)
        .sorted(Comparator.comparingLong(ServerEventRecord::timestampMs))
        .toList();

    Map<String, UUID> knownNames = new LinkedHashMap<>();
    for (PublicChatRecord chat : chatCandidates) {
      knownNames.put(chat.playerName().toLowerCase(Locale.ROOT), chat.playerId());
    }
    for (Player online : Bukkit.getOnlinePlayers()) {
      knownNames.putIfAbsent(online.getName().toLowerCase(Locale.ROOT), online.getUniqueId());
    }

    Set<UUID> involvedIds = new LinkedHashSet<>();
    Set<String> involvedNames = new LinkedHashSet<>();
    involvedIds.add(capture.triggerPlayerId());
    involvedNames.add(capture.triggerPlayerName());

    // Names explicitly referenced by the triggering message are involved too.
    PublicChatRecord triggerRecord = chatCandidates.stream()
        .filter(m -> m.timestampMs() == capture.triggerAt()
            && m.playerId().equals(capture.triggerPlayerId()))
        .findFirst().orElse(null);
    if (triggerRecord != null) {
      addReferencedNames(triggerRecord.content(), knownNames, involvedIds, involvedNames);
    }

    Set<PublicChatRecord> includedChats = new LinkedHashSet<>();
    Set<ServerEventRecord> includedEvents = new LinkedHashSet<>();

    // Expand the relation graph. Example: A calls Iso; B mentions A; C threatens B;
    // then an event says C killed B. All become involved without sending unrelated chat.
    for (int pass = 0; pass < 6; pass++) {
      boolean changed = false;

      for (PublicChatRecord chat : chatCandidates) {
        boolean isTrigger = chat.timestampMs() == capture.triggerAt()
            && chat.playerId().equals(capture.triggerPlayerId());
        boolean related = isTrigger
            || mentions.containsAssistantMention(chat.content())
            || involvedIds.contains(chat.playerId())
            || referencesAnyName(chat.content(), involvedNames);
        if (!related) {
          continue;
        }
        if (includedChats.add(chat)) {
          changed = true;
        }
        if (involvedIds.add(chat.playerId())) {
          changed = true;
        }
        if (involvedNames.add(chat.playerName())) {
          changed = true;
        }
        if (addReferencedNames(chat.content(), knownNames, involvedIds, involvedNames)) {
          changed = true;
        }
      }

      for (ServerEventRecord event : eventCandidates) {
        boolean recentPreEvent = capture.directMention()
            && event.timestampMs() < capture.triggerAt()
            && capture.triggerAt() - event.timestampMs() <= Math.max(
                plugin.getConfig().getLong(
                    "global-conversation.scene.recent-pre-event-window-ms", 4000L),
                0L);
        if (!recentPreEvent && !intersectsNames(event.involvedPlayers(), involvedNames)) {
          continue;
        }
        if (includedEvents.add(event)) {
          changed = true;
        }
        for (String name : event.involvedPlayers()) {
          if (name != null && !name.isBlank() && involvedNames.add(name)) {
            changed = true;
          }
          UUID id = knownNames.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
          if (id != null && involvedIds.add(id)) {
            changed = true;
          }
        }
      }

      if (!changed) {
        break;
      }
    }

    // Keep CURRENT capture-window chat locally so Java can thread the conversation,
    // but do NOT immediately mark every speaker as involved. 1.7.8 promoted all
    // capture-window chatter and a side conversation such as "alguien tiene piedra?"
    // / "yo tengo" could accidentally inherit SMART continuity. 1.7.10 routes
    // nearby lines by local thread affinity versus competing side-chat, with zero AI calls.
    for (PublicChatRecord chat : chatCandidates) {
      if (chat.timestampMs() < capture.triggerAt()) continue;
      includedChats.add(chat);
    }

    List<PublicChatRecord> selectedChats = limitChatRecords(
        new ArrayList<>(includedChats), capture.triggerAt());
    List<ServerEventRecord> selectedEvents = limitEventRecords(
        new ArrayList<>(includedEvents), capture.triggerAt());

    // Only a player whose direct/smart CURRENT line actually survived the
    // relevance/cap filter receives follow-up eligibility. Pre-lookback lines are
    // context only and must never renew a conversation.
    Set<UUID> eligibleAddressers = new LinkedHashSet<>();
    if (addressers != null && !addressers.isEmpty()) {
      for (PublicChatRecord chat : selectedChats) {
        if (chat.timestampMs() >= capture.triggerAt() && addressers.contains(chat.playerId())) {
          eligibleAddressers.add(chat.playerId());
        }
      }
    }

    GroupThreadSelection groupThread = groupThreads.selectGroupThreadCandidates(
        selectedChats, chatCandidates, capture, eligibleAddressers);
    for (Map.Entry<String, UUID> entry : groupThread.candidateByName().entrySet()) {
      involvedIds.add(entry.getValue());
      Player online = Bukkit.getPlayer(entry.getValue());
      involvedNames.add(online == null ? entry.getKey() : online.getName());
    }

    Map<String, String> sceneIdentities = resolveSceneIdentities(
        involvedNames, selectedEvents, activeIdentities);
    String identityContext = formatIdentityContext(involvedNames, sceneIdentities);

    List<SceneAtom> atoms = new ArrayList<>();
    for (PublicChatRecord chat : selectedChats) {
      atoms.add(SceneAtom.chat(chat));
    }
    for (ServerEventRecord event : selectedEvents) {
      atoms.add(SceneAtom.event(event));
    }
    atoms.sort(Comparator.comparingLong(SceneAtom::timestampMs)
        .thenComparingInt(atom -> atom.event() == null ? 0 : 1));

    // CRITICAL 1.7.2 isolation: the pre-lookback exists only so Isolda can understand
    // references such as "eso" / "viste lo de antes?". It is not the CURRENT request.
    // Feeding it through currentMessages made a smart follow-up like "xd" re-answer
    // an older question and also polluted wiki/inventory target detection.
    List<ChatMessage> preContextMessages = new ArrayList<>();
    List<ChatMessage> promotedPreGroupMessages = new ArrayList<>();
    List<ChatMessage> currentMessages = new ArrayList<>();
    for (SceneAtom atom : atoms) {
      ChatMessage rendered;
      if (atom.chat() != null) {
        PublicChatRecord chat = atom.chat();
        rendered = new PlayerChatMessage(
            plugin,
            chat.playerId(),
            chat.playerName(),
            chat.displayName(),
            chat.admin(),
            sceneIdentities.getOrDefault(chat.playerName().toLowerCase(Locale.ROOT), ""),
            chat.content());
      } else {
        ServerEventRecord event = atom.event();
        String players = event.involvedPlayers().isEmpty()
            ? "none"
            : formatEventPlayers(event.involvedPlayers(), sceneIdentities, event.playerIdentities());
        rendered = new SystemContextMessage(
            plugin,
            "[EVENT type=" + event.type() + " players=" + players + "] ",
            event.text());
      }

      if (atom.timestampMs() < capture.triggerAt()) {
        if (atom.chat() != null && groupThread.candidateRecords().contains(atom.chat())) {
          promotedPreGroupMessages.add(rendered);
        } else {
          preContextMessages.add(rendered);
        }
      } else {
        currentMessages.add(rendered);
      }
    }

    // A very recent bridge may have happened before the line that opened this capture
    // and therefore may not be part of the normal relation graph. Promote at most the
    // configured tiny number of such lines into social context without granting tools.
    for (PublicChatRecord chat : groupThread.preCandidates()) {
      if (selectedChats.contains(chat)) continue;
      promotedPreGroupMessages.add(new PlayerChatMessage(
          plugin, chat.playerId(), chat.playerName(), chat.displayName(), chat.admin(),
          sceneIdentities.getOrDefault(chat.playerName().toLowerCase(Locale.ROOT), ""),
          chat.content()));
    }

    // Resolve the actual addressed request BEFORE building the model transcript.
    // Non-addressed current chatter remains visible as context, but is placed before
    // the request marker so a later unrelated line cannot become the accidental task.
    List<ChatMessage> resolvedRequestMessages = selectRequestMessages(currentMessages, eligibleAddressers);
    if (resolvedRequestMessages.isEmpty()) {
      resolvedRequestMessages = currentMessages.stream()
          .filter(message -> message instanceof PlayerChatMessage)
          .toList();
    }
    final List<ChatMessage> requestMessages = resolvedRequestMessages.isEmpty()
        ? List.copyOf(currentMessages)
        : List.copyOf(resolvedRequestMessages);
    List<ChatMessage> related = new ArrayList<>();
    int ignoredAmbientCurrentLines = 0;
    for (ChatMessage message : currentMessages) {
      if (requestMessages.contains(message)) continue;
      if (message instanceof PlayerChatMessage playerMessage) {
        if (groupThread.candidateIds().contains(playerMessage.playerId)) related.add(message);
        else ignoredAmbientCurrentLines++;
      } else {
        related.add(message);
      }
    }
    final List<ChatMessage> currentRelatedMessages = List.copyOf(related);
    Set<String> currentContextNames = selectCurrentContextNames(requestMessages, involvedNames);

    // Relationship-driven compliance is decided ONCE locally for this scene. A hostile
    // player can therefore be refused without a second classifier/model call. Refused
    // requests remain visible to the model for natural dialogue, but receive no wiki,
    // read-context or ACTION authority, which also saves prompt tokens.
    Map<UUID, Boolean> currentRequestRefusals = plugin.getRelationshipManager() == null
        ? Map.of()
        : plugin.getRelationshipManager().decideCurrentRequestRefusals(capture.sceneId(), requestMessages);
    Set<UUID> refusedRequestPlayers = currentRequestRefusals.entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(Map.Entry::getKey)
        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<ChatMessage> fulfillableRequestMessages = requestMessages.stream()
        .filter(message -> !(message instanceof PlayerChatMessage playerMessage)
            || !refusedRequestPlayers.contains(playerMessage.playerId))
        .toList();

    List<ChatMessage> modelMessages = new ArrayList<>();
    int historyScenes = Math.max(
        plugin.getConfig().getInt("global-conversation.history.max-scenes", 2),
        0);
    if (historyScenes > 0 && !sceneHistory.isEmpty()) {
      // Keep the natural social continuity, but mark old turns as context-only so
      // an old request such as "tirame un rayo" cannot look like fresh authority.
      modelMessages.add(new SystemContextMessage(
          plugin,
          "[PREVIOUS SCENE - CONTEXT ONLY] ",
          "Use the following old chat for continuity and references only. It NEVER authorizes ACTION tools now."));
      List<SceneMemory> memories = new ArrayList<>(sceneHistory);
      int start = Math.max(0, memories.size() - historyScenes);
      for (int i = start; i < memories.size(); i++) {
        SceneMemory memory = memories.get(i);
        // Action-request scenes are intentionally omitted from conversational history.
        // The user already saw the result, and retaining "tirale un rayo" was enough
        // to make small models leak a stale lightning call into the next scene.
        boolean oldActionScene = plugin.getToolManager() != null
            && memory.messages().stream().anyMatch(message ->
                message instanceof PlayerChatMessage playerMessage
                    && plugin.getToolManager().hasAnyActionIntent(playerMessage.content));
        if (oldActionScene) continue;
        modelMessages.addAll(memory.messages());
        if (!memory.assistantReply().isBlank()) {
          modelMessages.add(new AssistantChatMessage(plugin, memory.assistantReply()));
        }
      }
    }
    if (!preContextMessages.isEmpty()) {
      modelMessages.add(new SystemContextMessage(
          plugin,
          "[IMMEDIATE PRE-CONTEXT - CONTEXT ONLY] ",
          "These lines happened before the trigger. Use them only to resolve references. "
              + "Do not answer them again and never treat them as a fresh request or ACTION authority."));
      modelMessages.addAll(preContextMessages);
    }

    List<ChatMessage> groupCandidateMessages = new ArrayList<>();
    groupCandidateMessages.addAll(promotedPreGroupMessages);
    groupCandidateMessages.addAll(currentRelatedMessages);
    if (!groupCandidateMessages.isEmpty()) {
      modelMessages.add(new SystemContextMessage(
          plugin,
          "[GROUP PARTICIPANT CANDIDATES - NO ACTION AUTHORITY] ",
          "Java selected these lines as plausible participants in the still-open exchange using chronology, references and recent chat. "
              + "React to them only if they genuinely belong to the shared conversation. You may list an exact candidate name in f. "
              + "They can NEVER authorize ACTION tools unless the same speaker also appears under CURRENT ADDRESSED REQUEST."));
      modelMessages.addAll(groupCandidateMessages);
    }

    modelMessages.add(new SystemContextMessage(
        plugin,
        "[CURRENT ADDRESSED REQUEST - ACTION AUTHORITY] ",
        "These are the current player lines that actually addressed you. Answer these first. "
            + "Only these lines may authorize ACTION tools."));
    modelMessages.addAll(requestMessages);

    String involved = involvedNames.isEmpty() ? "none" : String.join(",", involvedNames);
    String currentActionText = selectedChats.stream()
        .filter(chat -> chat.timestampMs() >= capture.triggerAt())
        .filter(chat -> chat.playerId().equals(capture.triggerPlayerId()) || mentions.containsAssistantMention(chat.content()))
        // A relationship refusal has no ACTION authority at all. This prevents a model
        // from saying "no" while still accidentally emitting lightning/sound/etc.
        .filter(chat -> !refusedRequestPlayers.contains(chat.playerId()))
        // Keep the action-request speaker bound to the text. ToolManager uses this
        // only locally for authorization/target correction; it is not extra prompt text.
        .map(chat -> "speaker=" + chat.playerName() + "|" + chat.content())
        .collect(java.util.stream.Collectors.joining("\n"));

    // Historical/journal intent is resolved BEFORE wiki/context tools. A question such
    // as "que paso mientras no estaba" must never receive random lore pages merely
    // because words like "paso" happen to score inside the wiki. Only the triggering
    // player's addressed lines are used to classify their own absence/history request.
    String activityHistory = "";
    if (plugin.getActivityJournal() != null && triggerRecord != null
        && !refusedRequestPlayers.contains(triggerRecord.playerId())) {
      String journalQueryText = requestTextForPlayer(fulfillableRequestMessages, triggerRecord.playerId());
      activityHistory = plugin.getActivityJournal().buildContext(
          triggerRecord.playerId(), triggerRecord.playerName(), triggerRecord.admin(), journalQueryText);
    }

    // CONTEXT tools and wiki retrieval are driven only by requests Isolda decided to
    // entertain this scene. A refused arch-enemy request gets no expensive wiki chunk
    // or player-context payload; the SAME model call only receives the compact refusal
    // policy and can answer with personality/hostility.
    boolean journalIntent = activityHistory != null && !activityHistory.isBlank();
    boolean nothingToFulfill = fulfillableRequestMessages.isEmpty();
    String wiki = journalIntent || nothingToFulfill ? "" : wikiRetriever.retrieveLocalWiki(fulfillableRequestMessages);
    String localTools = journalIntent || nothingToFulfill || plugin.getToolManager() == null
        ? ""
        : plugin.getToolManager().buildLocalContext(fulfillableRequestMessages, currentContextNames);
    String recentEvents = journalIntent || nothingToFulfill ? "" : retrieveRecentEventContext(fulfillableRequestMessages);
    // Relationship context prioritizes actual addressed speakers/targets first, then
    // adds the other CURRENT group speakers. This keeps the existing max-player/token
    // cap while still giving the model enough state for group loyalty decisions.
    List<ChatMessage> relationshipMessages = new ArrayList<>(requestMessages);
    for (ChatMessage message : groupCandidateMessages) {
      if (message instanceof PlayerChatMessage && !relationshipMessages.contains(message)) {
        relationshipMessages.add(message);
      }
    }
    String relationshipContext = plugin.getRelationshipManager() == null
        ? ""
        : plugin.getRelationshipManager().buildContext(
            relationshipMessages, currentContextNames, currentRequestRefusals);

    String addressedSpeakers = requestMessages.stream()
        .filter(message -> message instanceof PlayerChatMessage)
        .map(message -> ((PlayerChatMessage) message).playerName)
        .distinct()
        .collect(java.util.stream.Collectors.joining(","));
    String assistantMentions = mentions.configuredAssistantMentions().stream()
        .map(value -> value.startsWith("@") ? value.substring(1) : value)
        .distinct()
        .limit(4)
        .collect(java.util.stream.Collectors.joining("|"));
    String meta = "window_ms=" + Math.max(0L, capture.endsAt() - capture.triggerAt())
        + ", assistant_trigger_mode=" + mentions.configuredTriggerMode()
        + ", assistant_mentions=" + assistantMentions
        + ", current_chat_lines=" + currentMessages.stream().filter(m -> m instanceof PlayerChatMessage).count()
        + ", request_lines=" + requestMessages.stream().filter(m -> m instanceof PlayerChatMessage).count()
        + ", addressed_speakers=" + (addressedSpeakers.isBlank() ? "none" : addressedSpeakers)
        + ", group_candidate_lines=" + groupCandidateMessages.stream().filter(m -> m instanceof PlayerChatMessage).count()
        + ", ambient_ignored_lines=" + ignoredAmbientCurrentLines
        + ", pre_context_lines=" + preContextMessages.size()
        + ", events=" + selectedEvents.size()
        + ", trigger=" + (capture.directMention() ? "direct_mention" : "smart_followup");

    List<ChatMessage> currentSocialMessages = new ArrayList<>(promotedPreGroupMessages);
    for (ChatMessage message : currentMessages) {
      if (!(message instanceof PlayerChatMessage playerMessage)
          || requestMessages.contains(message)
          || groupThread.candidateIds().contains(playerMessage.playerId)) {
        currentSocialMessages.add(message);
      }
    }
    return new SceneRequest(
        capture.sceneId(),
        List.copyOf(modelMessages),
        List.copyOf(currentSocialMessages),
        Set.copyOf(involvedIds),
        Set.copyOf(involvedNames),
        Set.copyOf(eligibleAddressers),
        Set.copyOf(groupThread.candidateIds()),
        Set.copyOf(groupThread.autoParticipantIds()),
        Map.copyOf(groupThread.candidateByName()),
        AssistantRequestContext.scene(
            capture.sceneId(), involved, meta, identityContext, wiki, localTools, recentEvents,
            activityHistory, relationshipContext, currentActionText),
        currentActionText,
        AssistantManager.PRIMARY,
        0,
        capture.triggerAt());
  }

  private static String requestTextForPlayer(List<ChatMessage> messages, UUID playerId) {
    if (messages == null || playerId == null) return "";
    StringBuilder out = new StringBuilder();
    for (ChatMessage message : messages) {
      if (message instanceof PlayerChatMessage playerMessage
          && playerId.equals(playerMessage.playerId)
          && playerMessage.content != null && !playerMessage.content.isBlank()) {
        if (!out.isEmpty()) out.append(' ');
        out.append(playerMessage.content);
      }
    }
    return out.toString().trim();
  }

  private List<ChatMessage> selectRequestMessages(
      List<ChatMessage> currentMessages,
      Set<UUID> eligibleAddressers) {
    if (currentMessages == null || currentMessages.isEmpty()) return List.of();
    if (eligibleAddressers == null || eligibleAddressers.isEmpty()) return List.copyOf(currentMessages);

    List<ChatMessage> selected = new ArrayList<>();
    for (ChatMessage message : currentMessages) {
      if (message instanceof PlayerChatMessage playerMessage
          && eligibleAddressers.contains(playerMessage.playerId)) {
        selected.add(message);
      }
    }
    return List.copyOf(selected);
  }

  /**
   * Candidate players for local observation/relationship context must come from the
   * CURRENT addressed request, not from names that happened to appear in pre-lookback.
   * This keeps old group chatter from making inventory/profile inspect the wrong player.
   */
  private Set<String> selectCurrentContextNames(
      List<ChatMessage> requestMessages,
      Set<String> involvedNames) {
    LinkedHashSet<String> selected = new LinkedHashSet<>();
    StringBuilder requestText = new StringBuilder();
    if (requestMessages != null) {
      for (ChatMessage message : requestMessages) {
        if (message instanceof PlayerChatMessage playerMessage) {
          selected.add(playerMessage.playerName);
          if (playerMessage.content != null) requestText.append(playerMessage.content).append(' ');
        }
      }
    }

    String text = requestText.toString();
    if (involvedNames != null && !text.isBlank()) {
      for (String name : involvedNames) {
        if (name == null || name.isBlank()) continue;
        if (ContextTargetResolver.mentionsName(text, name)) selected.add(name);
      }
    }
    return new LinkedHashSet<>(selected);
  }

  private List<PublicChatRecord> limitChatRecords(List<PublicChatRecord> records, long triggerAt) {
    records.sort(Comparator.comparingLong(PublicChatRecord::timestampMs));
    int maxTotal = Math.max(plugin.getConfig().getInt(
        "global-conversation.scene.max-chat-messages", 10), 1);
    int maxPre = Math.max(plugin.getConfig().getInt(
        "global-conversation.scene.max-pre-chat-messages", 5), 0);
    maxPre = Math.min(maxPre, maxTotal - 1);

    List<PublicChatRecord> pre = records.stream().filter(r -> r.timestampMs() < triggerAt).toList();
    List<PublicChatRecord> post = records.stream().filter(r -> r.timestampMs() >= triggerAt).toList();

    List<PublicChatRecord> out = new ArrayList<>();
    int preStart = Math.max(0, pre.size() - maxPre);
    out.addAll(pre.subList(preStart, pre.size()));
    int remaining = Math.max(0, maxTotal - out.size());
    out.addAll(post.subList(0, Math.min(remaining, post.size())));
    out.sort(Comparator.comparingLong(PublicChatRecord::timestampMs));
    return List.copyOf(out);
  }

  private List<ServerEventRecord> limitEventRecords(List<ServerEventRecord> records, long triggerAt) {
    records.sort(Comparator.comparingLong(ServerEventRecord::timestampMs));
    int maxTotal = Math.max(plugin.getConfig().getInt(
        "global-conversation.scene.max-events", 2), 0);
    if (maxTotal == 0) {
      return List.of();
    }
    int maxPre = Math.max(plugin.getConfig().getInt(
        "global-conversation.scene.max-pre-events", 1), 0);
    maxPre = Math.min(maxPre, maxTotal);

    List<ServerEventRecord> pre = records.stream().filter(r -> r.timestampMs() < triggerAt).toList();
    List<ServerEventRecord> post = records.stream().filter(r -> r.timestampMs() >= triggerAt).toList();
    List<ServerEventRecord> out = new ArrayList<>();
    int preStart = Math.max(0, pre.size() - maxPre);
    out.addAll(pre.subList(preStart, pre.size()));
    int remaining = Math.max(0, maxTotal - out.size());
    out.addAll(post.subList(0, Math.min(remaining, post.size())));
    out.sort(Comparator.comparingLong(ServerEventRecord::timestampMs));
    return List.copyOf(out);
  }

  private boolean addReferencedNames(
      String message,
      Map<String, UUID> knownNames,
      Set<UUID> involvedIds,
      Set<String> involvedNames) {
    boolean changed = false;
    boolean strictMatched = false;
    for (Map.Entry<String, UUID> entry : knownNames.entrySet()) {
      String lowercaseName = entry.getKey();
      if (!ContextTargetResolver.mentionsNameStrict(message, lowercaseName)
          && !message.toLowerCase(Locale.ROOT).contains("@" + lowercaseName)) {
        continue;
      }
      strictMatched = true;
      UUID id = entry.getValue();
      if (involvedIds.add(id)) changed = true;
      Player online = Bukkit.getPlayer(id);
      String canonical = online != null ? online.getName() : lowercaseName;
      if (involvedNames.add(canonical)) changed = true;
    }

    // If no exact/compact name matched, accept ONE unambiguous fuzzy alias only for
    // digit-bearing Minecraft names. This safely handles e.g. WITHE9033 -> "white"
    // without fuzzily scanning ordinary alphabetic player names against normal prose.
    if (!strictMatched) {
      Map.Entry<String, UUID> fuzzy = null;
      for (Map.Entry<String, UUID> entry : knownNames.entrySet()) {
        String candidate = entry.getKey();
        if (!candidate.matches(".*\\d.*")) continue;
        if (!ContextTargetResolver.mentionsName(message, candidate)) continue;
        if (fuzzy != null && !fuzzy.getValue().equals(entry.getValue())) {
          fuzzy = null;
          break;
        }
        fuzzy = entry;
      }
      if (fuzzy != null) {
        UUID id = fuzzy.getValue();
        if (involvedIds.add(id)) changed = true;
        Player online = Bukkit.getPlayer(id);
        String canonical = online != null ? online.getName() : fuzzy.getKey();
        if (involvedNames.add(canonical)) changed = true;
      }
    }
    return changed;
  }

  private static boolean referencesAnyName(String text, Set<String> names) {
    if (text == null || text.isBlank() || names.isEmpty()) {
      return false;
    }
    for (String name : names) {
      if (name == null || name.isBlank()) {
        continue;
      }
      if (ContextTargetResolver.mentionsName(text, name)
          || text.toLowerCase(Locale.ROOT).contains("@" + name.toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private static boolean intersectsNames(List<String> eventNames, Set<String> involvedNames) {
    for (String eventName : eventNames) {
      for (String involved : involvedNames) {
        if (eventName != null && involved != null && eventName.equalsIgnoreCase(involved)) {
          return true;
        }
      }
    }
    return false;
  }

  Map<String, String> snapshotOnlineIdentities(List<String> names) {
    Map<String, String> out = new LinkedHashMap<>();
    if (names == null || names.isEmpty() || plugin.getIntegrationManager() == null) return out;
    if (!plugin.getIntegrationsConfig().getBoolean("identity-context.enabled", true)) return out;
    int max = Math.max(plugin.getIntegrationsConfig().getInt("identity-context.max-players", 12), 1);
    for (String name : names) {
      if (out.size() >= max) break;
      if (name == null || name.isBlank()) continue;
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) continue;
      String identity = plugin.getIntegrationManager().buildAmbientIdentity(player);
      if (!identity.isBlank()) out.put(player.getName(), identity);
    }
    return out;
  }

  private Map<String, String> resolveSceneIdentities(
      Set<String> involvedNames,
      List<ServerEventRecord> selectedEvents,
      Map<String, String> activeIdentities) {
    Map<String, String> out = new LinkedHashMap<>();
    if (plugin.getIntegrationManager() == null
        || !plugin.getIntegrationsConfig().getBoolean("identity-context.enabled", true)) {
      return out;
    }

    int max = Math.max(plugin.getIntegrationsConfig().getInt("identity-context.max-players", 12), 1);
    if (activeIdentities != null) out.putAll(activeIdentities);
    if (selectedEvents != null) {
      for (ServerEventRecord event : selectedEvents) {
        for (Map.Entry<String, String> entry : event.playerIdentities().entrySet()) {
          out.putIfAbsent(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
      }
    }

    Map<String, String> bounded = new LinkedHashMap<>();
    if (involvedNames == null) return bounded;
    for (String name : involvedNames) {
      if (bounded.size() >= max) break;
      if (name == null || name.isBlank() || "CONSOLE".equalsIgnoreCase(name)) continue;
      String key = name.toLowerCase(Locale.ROOT);
      String identity = out.getOrDefault(key, "");
      if (identity.isBlank()) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
          identity = plugin.getIntegrationManager().buildAmbientIdentity(online);
        }
      }
      if (!identity.isBlank()) bounded.put(key, identity);
    }
    return bounded;
  }

  private String formatIdentityContext(Set<String> names, Map<String, String> identities) {
    if (names == null || names.isEmpty() || identities == null || identities.isEmpty()) return "";
    List<String> rows = new ArrayList<>();
    for (String name : names) {
      if (name == null || name.isBlank()) continue;
      String identity = identities.get(name.toLowerCase(Locale.ROOT));
      if (identity == null || identity.isBlank()) continue;
      rows.add("name=" + name + " " + identity);
    }
    return String.join("\n", rows);
  }

  private String formatEventPlayers(
      List<String> names,
      Map<String, String> liveIdentities,
      Map<String, String> storedIdentities) {
    List<String> rows = new ArrayList<>();
    for (String name : names) {
      if (name == null || name.isBlank()) continue;
      String key = name.toLowerCase(Locale.ROOT);
      String identity = liveIdentities == null ? "" : liveIdentities.getOrDefault(key, "");
      if (identity.isBlank() && storedIdentities != null) {
        identity = lookupIdentity(storedIdentities, name);
      }
      rows.add(identity.isBlank() ? name : name + "{" + identity + "}");
    }
    return rows.isEmpty() ? "none" : String.join(";", rows);
  }

  private String lookupIdentity(Map<String, String> identities, String playerName) {
    if (identities == null || identities.isEmpty() || playerName == null) return "";
    for (Map.Entry<String, String> entry : identities.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(playerName)) {
        return entry.getValue() == null ? "" : entry.getValue();
      }
    }
    return "";
  }

  private String formatStoredIdentityMap(Map<String, String> identities) {
    List<String> rows = new ArrayList<>();
    for (Map.Entry<String, String> entry : identities.entrySet()) {
      rows.add(entry.getKey() + "{" + entry.getValue() + "}");
    }
    return String.join(";", rows);
  }

  /**
   * A tiny event memory lets questions such as "Iso quién llegó?" work even when
   * the join happened before the normal 8-10 second chat lookback. It is selected
   * only when the current scene semantically refers to a recent event.
   */
  private String retrieveRecentEventContext(List<ChatMessage> currentMessages) {
    if (!plugin.getConfig().getBoolean("global-conversation.events.enabled", true)) {
      return "";
    }

    StringBuilder raw = new StringBuilder();
    for (ChatMessage message : currentMessages) {
      if (message != null && message.content != null) raw.append(message.content).append(' ');
    }
    String query = normalizeForSearch(raw.toString());
    if (query.isBlank()) return "";

    Set<String> wanted = new LinkedHashSet<>();
    if (containsAnyTerm(query, "llego", "llegado", "entro", "entrado", "conecto", "join", "online nuevo")) {
      wanted.add("player-join");
    }
    if (containsAnyTerm(query, "se fue", "salio", "desconecto", "desconectado", "quit", "left")) {
      wanted.add("player-quit");
      wanted.add("player-kick");
    }
    if (containsAnyTerm(query, "murio", "mori", "muerte", "mato", "mataron", "matado",
        "asesinaron", "abatieron", "kill", "killed", "morir")) {
      wanted.add("player-death");
    }
    if (containsAnyTerm(query, "logro", "avance", "advancement", "achievement")) {
      wanted.add("player-advancement");
    }
    boolean genericRecentReference = containsAnyTerm(
        query, "que paso", "q paso", "viste eso", "viste lo", "que ocurrio", "eso que fue");
    if (wanted.isEmpty() && !genericRecentReference) return "";

    int limit = Math.max(plugin.getConfig().getInt(
        "global-conversation.events.recent-context-limit", 2), 0);
    long maxAge = Math.max(plugin.getConfig().getLong(
        "global-conversation.events.recent-context-max-age-ms", 300_000L), 0L);
    if (limit == 0 || maxAge == 0L) return "";

    long now = System.currentTimeMillis();
    List<ServerEventRecord> candidates = new ArrayList<>(serverEventLog);
    candidates.sort(Comparator.comparingLong(ServerEventRecord::timestampMs).reversed());
    String currentText = raw.toString().toLowerCase(Locale.ROOT);
    StringBuilder out = new StringBuilder();
    int used = 0;
    for (ServerEventRecord event : candidates) {
      if (used >= limit) break;
      long age = now - event.timestampMs();
      if (age < 0L || age > maxAge) continue;
      if (!wanted.isEmpty() && !wanted.contains(event.type())) continue;
      // Do not duplicate an event already embedded as a current scene atom.
      if (!event.text().isBlank() && currentText.contains(event.text().toLowerCase(Locale.ROOT))) continue;
      if (!out.isEmpty()) out.append('\n');
      out.append(Math.max(0L, age / 1000L)).append("s ago ")
          .append(event.type()).append(": ").append(event.text());
      if (!event.playerIdentities().isEmpty()) {
        out.append(" [identities=")
            .append(formatStoredIdentityMap(event.playerIdentities()))
            .append(']');
      }
      used++;
    }
    return out.toString();
  }

  private record SceneAtom(long timestampMs, PublicChatRecord chat, ServerEventRecord event) {
    static SceneAtom chat(PublicChatRecord chat) {
      return new SceneAtom(chat.timestampMs(), chat, null);
    }
    static SceneAtom event(ServerEventRecord event) {
      return new SceneAtom(event.timestampMs(), null, event);
    }
  }
}
