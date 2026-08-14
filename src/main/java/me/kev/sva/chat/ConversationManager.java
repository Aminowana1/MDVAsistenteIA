package me.kev.sva.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantContextualizer;
import me.kev.sva.chat.assistant.AssistantManager;
import me.kev.sva.chat.assistant.AssistantRequestContext;
import me.kev.sva.chat.assistant.AssistantResponse;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.BroadcastChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;
import me.kev.sva.chat.tools.all.WikiTool;
import net.kyori.adventure.text.Component;
import net.md_5.bungee.api.ChatColor;

/**
 * Owns all conversation state.
 *
 * <p>Version 1.2 changes the routing model from "one slot per player" to
 * "one slot per logical conversation". A conversation may contain multiple
 * players. All Bukkit/session state is still mutated only on the Bukkit main
 * thread; the OpenAI network request is the only off-thread operation.</p>
 */
public class ConversationManager {
  private static final String GLOBAL_SESSION_KEY = "__GLOBAL__";

  private final ServerAssistantPlugin plugin;
  private final AssistantManager assistantManager;

  /** Active/recent logical player conversations, keyed by internal id. */
  private final Map<Long, ConversationSession> conversations = new LinkedHashMap<>();

  /** A player may actively belong to at most one SVA conversation. */
  private final Map<UUID, ConversationSession> playerConversationIndex = new HashMap<>();

  private final ConversationSession globalSession;
  private final Deque<RequestJob> requestQueue = new ArrayDeque<>();
  private final Deque<Long> aiRequestTimes = new ArrayDeque<>();
  private final Deque<String> recentServerEvents = new ArrayDeque<>();
  private final Map<UUID, Long> lastBusyNotice = new HashMap<>();
  private final Map<UUID, Long> lastRateNotice = new HashMap<>();

  private boolean requestInFlight = false;
  private boolean shutdown = false;
  private long lastGlobalRequestAt = 0;
  private long nextConversationId = 1;
  private BukkitTask requestRateRetryTask;
  private BukkitTask idleTask;

  public ConversationManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    this.assistantManager = new AssistantManager(plugin);
    this.globalSession = createGlobalSession();
  }

  public AssistantManager getAssistantManager() {
    return assistantManager;
  }

  public void shutdown() {
    shutdown = true;

    cancelTask(requestRateRetryTask);
    cancelTask(idleTask);
    requestRateRetryTask = null;
    idleTask = null;

    for (ConversationSession session : conversations.values()) {
      cancelSessionTasks(session);
    }
    cancelSessionTasks(globalSession);

    requestQueue.clear();
    conversations.clear();
    playerConversationIndex.clear();
    recentServerEvents.clear();
    assistantManager.shutdown();
  }

  // ---------------------------------------------------------------------------
  // PLAYER CHAT ROUTING
  // ---------------------------------------------------------------------------

  /** Must be invoked on the Bukkit main thread. */
  public void handlePlayerMessage(Player player, String content) {
    if (shutdown || player == null || content == null || content.isBlank()) {
      return;
    }

    if (!Bukkit.isPrimaryThread()) {
      UUID playerId = player.getUniqueId();
      String safeContent = content;
      plugin.getServer().getScheduler().runTask(plugin, () -> {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
          handlePlayerMessage(online, safeContent);
        }
      });
      return;
    }

    observePlayerActivity();
    cleanupExpiredParticipantsAndSessions();
    markParticipantsAddressedByExternalHuman(player, content);

    String mode = plugin.getConfig().getString(
        "request-triggers.player-messages.mode",
        "smart");
    mode = mode == null ? "smart" : mode.toLowerCase(Locale.ROOT);

    if (mode.equals("disabled")) {
      return;
    }

    if (!mode.equals("always") && !mode.equals("mention") && !mode.equals("smart")) {
      plugin.getLogger().warning("Unknown player message trigger mode: " + mode);
      return;
    }

    boolean directMention = containsAssistantMention(content);
    UUID playerId = player.getUniqueId();
    ConversationSession session = playerConversationIndex.get(playerId);
    ParticipantState participant = session == null ? null : session.participants.get(playerId);

    if (mode.equals("mention") && !directMention) {
      return;
    }

    // Existing group participant: smart follow-ups belong to that same logical
    // conversation until deterministic human-chat/timeout rules release them.
    if (mode.equals("smart") && !directMention) {
      if (session == null || participant == null || !participant.active || isExpired(participant)) {
        removeParticipant(session, playerId, false);
        return;
      }

      if (participant.humanConversationSignal) {
        removeParticipant(session, playerId, false);
        return;
      }

      if (plugin.getConfig().getBoolean(
          "conversation-control.human-conversation-detection.release-when-user-addresses-other-player",
          true)
          && isClearlyAddressedToPlayerOutsideConversation(player, content, session)) {
        removeParticipant(session, playerId, false);
        return;
      }

      int maxFollowUps = Math.max(
          plugin.getConfig().getInt(
              "conversation-control.max-follow-up-messages-without-mention",
              4),
          0);

      if (maxFollowUps > 0 && participant.followUpsWithoutMention >= maxFollowUps) {
        removeParticipant(session, playerId, false);
        return;
      }
    }

    // A direct mention from someone who is not already participating may join a
    // recent group conversation if the relationship is unambiguous. Otherwise it
    // opens a new logical conversation and therefore consumes a slot.
    if (directMention && (session == null || participant == null || !participant.active)) {
      ConversationSession joinCandidate = findGroupJoinCandidate(player, content);
      if (joinCandidate != null) {
        session = joinCandidate;
        participant = addOrRefreshParticipant(session, player);
      } else {
        if (!hasFreeConversationSlot()) {
          sendBusyNotice(player);
          return;
        }
        session = createPlayerConversation();
        participant = addOrRefreshParticipant(session, player);
        conversations.put(session.id, session);
      }
    }

    // always mode may open a conversation without a mention. Smart/mention only
    // reach here without a session when a direct mention opened one above.
    if (session == null || participant == null) {
      if (!mode.equals("always")) {
        return;
      }
      if (!hasFreeConversationSlot()) {
        sendBusyNotice(player);
        return;
      }
      session = createPlayerConversation();
      participant = addOrRefreshParticipant(session, player);
      conversations.put(session.id, session);
    }

    refreshParticipantSnapshot(participant, player);
    session.suppressResponse = false;

    if (!allowPlayerMessage(participant)) {
      sendRateNotice(player);
      return;
    }

    boolean closingMessage = isConfiguredConversationCloser(content);

    if (directMention) {
      participant.followUpsWithoutMention = 0;
      participant.closeAfterCurrent = false;
      participant.humanConversationSignal = false;
    } else if (mode.equals("smart")) {
      participant.followUpsWithoutMention++;
      // A fresh non-closing follow-up means the participant changed their mind
      // about ending the exchange (for example: "gracias" followed by a new question).
      if (!closingMessage) {
        participant.closeAfterCurrent = false;
      }
    }

    if (mode.equals("mention")) {
      participant.closeAfterCurrent = true;
    }

    if (closingMessage) {
      // In a group, only this participant leaves after SVA has had a chance to
      // answer naturally. The rest of the group conversation stays alive.
      participant.closeAfterCurrent = true;
    }

    int maxPendingPerPlayer = Math.max(
        plugin.getConfig().getInt("conversation-control.max-pending-messages-per-player", 8),
        1);
    if (countPendingMessagesFrom(session, playerId) >= maxPendingPerPlayer) {
      sendRateNotice(player);
      return;
    }

    session.pending.add(new PlayerChatMessage(plugin, player, content));
    session.lastSpeakerId = playerId;
    session.lastInteractionAt = System.currentTimeMillis();
    refreshParticipantExpiry(session, participant);
    scheduleBatch(session);
  }

  /** Removes this player from their SVA group without necessarily ending the group. */
  public void handlePlayerDisconnect(UUID playerId) {
    if (playerId == null) {
      return;
    }
    ConversationSession session = playerConversationIndex.get(playerId);
    if (session == null) {
      return;
    }

    removePendingMessagesFrom(session, playerId);
    removeParticipant(session, playerId, false);

    if (session.participants.isEmpty() && session.processing) {
      session.suppressResponse = true;
    }
  }

  private boolean hasFreeConversationSlot() {
    int maxActive = plugin.getConfig().contains("conversation-control.max-active-conversations")
        ? plugin.getConfig().getInt("conversation-control.max-active-conversations", 2)
        : plugin.getConfig().getInt("conversation-control.max-active-player-conversations", 2);
    maxActive = Math.max(maxActive, 0);
    return maxActive == 0 || countActivePlayerConversations() < maxActive;
  }

  private ConversationSession findGroupJoinCandidate(Player player, String message) {
    if (!plugin.getConfig().getBoolean("conversation-control.group-conversations.enabled", true)) {
      return null;
    }

    int maxParticipants = Math.max(
        plugin.getConfig().getInt("conversation-control.group-conversations.max-participants", 6),
        1);
    long joinWindow = Math.max(
        plugin.getConfig().getLong("conversation-control.group-conversations.join-window-ms", 15000),
        0);
    long now = System.currentTimeMillis();

    List<ConversationSession> eligible = new ArrayList<>();
    for (ConversationSession session : new ArrayList<>(conversations.values())) {
      cleanupExpiredParticipants(session);
      if (session.participants.isEmpty()) {
        continue;
      }
      if (session.participants.size() >= maxParticipants) {
        continue;
      }
      if (joinWindow > 0 && now - session.lastInteractionAt > joinWindow) {
        continue;
      }
      eligible.add(session);
    }

    if (eligible.isEmpty()) {
      return null;
    }

    // Strongest signal: the newcomer explicitly references a participant from
    // exactly one active SVA conversation.
    List<ConversationSession> referenced = new ArrayList<>();
    for (ConversationSession session : eligible) {
      if (messageReferencesAnyParticipant(message, session, player.getUniqueId())) {
        referenced.add(session);
      }
    }
    if (referenced.size() == 1) {
      return referenced.get(0);
    }

    // With more than one active conversation we refuse to guess. This is what
    // prevents an unrelated SVA question from being inserted into the wrong group.
    if (eligible.size() != 1) {
      return null;
    }

    ConversationSession only = eligible.get(0);
    if (!plugin.getConfig().getBoolean(
        "conversation-control.group-conversations.join-on-contextual-follow-up",
        true)) {
      return null;
    }

    return looksLikeContextualFollowUp(message) ? only : null;
  }

  private boolean looksLikeContextualFollowUp(String message) {
    String normalized = stripAssistantMentions(message)
        .toLowerCase(Locale.ROOT)
        .trim()
        .replaceFirst("^[¿¡!?.,:;\\-]+", "")
        .trim();

    if (normalized.isEmpty()) {
      return false;
    }

    List<String> prefixes = plugin.getConfig().getStringList(
        "conversation-control.group-conversations.contextual-follow-up-prefixes");
    if (prefixes.isEmpty()) {
      prefixes = List.of("y", "eso", "esa", "ese", "entonces", "pero");
    }

    for (String cue : prefixes) {
      if (cue == null || cue.isBlank()) {
        continue;
      }
      String normalizedCue = cue.toLowerCase(Locale.ROOT).trim();
      if (normalized.equals(normalizedCue)
          || normalized.startsWith(normalizedCue + " ")
          || normalized.startsWith(normalizedCue + "?")
          || normalized.startsWith(normalizedCue + ",")) {
        return true;
      }
    }

    List<String> containsCues = plugin.getConfig().getStringList(
        "conversation-control.group-conversations.contextual-follow-up-contains");
    if (containsCues.isEmpty()) {
      containsCues = List.of(
          "tambien", "también", "lo mismo", "sobre eso", "de eso", "lo que dijo", "lo que dice");
    }
    for (String cue : containsCues) {
      if (cue != null && !cue.isBlank()
          && containsWholeWordPhraseIgnoreCase(normalized, cue.trim())) {
        return true;
      }
    }
    return false;
  }

  private boolean messageReferencesAnyParticipant(
      String message,
      ConversationSession session,
      UUID senderId) {
    String lower = message.toLowerCase(Locale.ROOT);
    for (ParticipantState participant : session.participants.values()) {
      if (participant.playerId.equals(senderId)) {
        continue;
      }
      String name = participant.playerName.toLowerCase(Locale.ROOT);
      if (lower.contains("@" + name)
          || containsWholeWordIgnoreCase(message, participant.playerName)) {
        return true;
      }
    }
    return false;
  }

  private ParticipantState addOrRefreshParticipant(ConversationSession session, Player player) {
    ParticipantState participant = session.participants.get(player.getUniqueId());
    if (participant == null) {
      participant = new ParticipantState(player.getUniqueId());
      session.participants.put(player.getUniqueId(), participant);
    }

    participant.active = true;
    participant.humanConversationSignal = false;
    participant.closeAfterCurrent = false;
    participant.followUpsWithoutMention = 0;
    refreshParticipantSnapshot(participant, player);
    playerConversationIndex.put(player.getUniqueId(), session);
    refreshParticipantExpiry(session, participant);
    session.lastInteractionAt = System.currentTimeMillis();
    return participant;
  }

  private boolean allowPlayerMessage(ParticipantState participant) {
    int maxPerMinute = Math.max(
        plugin.getConfig().getInt("rate-limits.max-player-messages-per-minute", 12),
        0);

    if (maxPerMinute == 0) {
      return true;
    }

    long now = System.currentTimeMillis();
    pruneOlderThan(participant.playerMessageTimes, now - 60000L);
    if (participant.playerMessageTimes.size() >= maxPerMinute) {
      return false;
    }
    participant.playerMessageTimes.addLast(now);
    return true;
  }

  private int countPendingMessagesFrom(ConversationSession session, UUID playerId) {
    int count = 0;
    for (ChatMessage message : session.pending) {
      if (message instanceof PlayerChatMessage playerMessage
          && playerMessage.playerId.equals(playerId)) {
        count++;
      }
    }
    return count;
  }

  private void removePendingMessagesFrom(ConversationSession session, UUID playerId) {
    session.pending.removeIf(message -> message instanceof PlayerChatMessage playerMessage
        && playerMessage.playerId.equals(playerId));
  }

  private boolean containsAssistantMention(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }

    List<String> mentions = configuredAssistantMentions();
    String lowerMessage = message.toLowerCase(Locale.ROOT);
    for (String mention : mentions) {
      if (mention == null || mention.isBlank()) {
        continue;
      }
      String lowerMention = mention.toLowerCase(Locale.ROOT).trim();
      if (lowerMention.startsWith("@")) {
        if (lowerMessage.contains(lowerMention)) {
          return true;
        }
        continue;
      }

      Pattern pattern = Pattern.compile(
          "(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(mention.trim()) + "(?![\\p{L}\\p{N}_])");
      if (pattern.matcher(message).find()) {
        return true;
      }
    }
    return false;
  }

  private List<String> configuredAssistantMentions() {
    List<String> mentions = new ArrayList<>(plugin.getConfig().getStringList(
        "request-triggers.player-messages.mentions"));

    if (plugin.getConfig().getBoolean(
        "request-triggers.player-messages.include-assistant-name-as-mention",
        true)) {
      String assistantName = plugin.getConfig().getString("assistant-name", "ServerAssistant");
      if (assistantName != null && !assistantName.isBlank()) {
        mentions.add(assistantName);
      }
    }
    return mentions;
  }

  private String stripAssistantMentions(String message) {
    String result = message == null ? "" : message;
    for (String mention : configuredAssistantMentions()) {
      if (mention == null || mention.isBlank()) {
        continue;
      }
      result = result.replaceAll("(?iu)" + Pattern.quote(mention.trim()), " ");
    }
    return result.replaceAll("\\s+", " ").trim();
  }

  private void markParticipantsAddressedByExternalHuman(Player sender, String message) {
    // A message that explicitly calls SVA is not evidence that the sender is
    // pulling a participant into a separate human-only conversation.
    if (containsAssistantMention(message)) {
      return;
    }

    if (!plugin.getConfig().getBoolean(
        "conversation-control.human-conversation-detection.release-when-other-player-addresses-user",
        true)) {
      return;
    }

    ConversationSession senderSession = playerConversationIndex.get(sender.getUniqueId());
    String lower = message.trim().toLowerCase(Locale.ROOT);

    for (ConversationSession session : conversations.values()) {
      if (senderSession == session) {
        // Participants in the same SVA group are allowed to address one another;
        // that does not mean they have left the shared conversation.
        continue;
      }

      for (ParticipantState participant : session.participants.values()) {
        if (!participant.active || participant.playerId.equals(sender.getUniqueId())) {
          continue;
        }

        String name = participant.playerName.toLowerCase(Locale.ROOT);
        if (lower.contains("@" + name)
            || lower.equals(name)
            || lower.startsWith(name + " ")
            || lower.startsWith(name + ",")
            || lower.startsWith(name + ":")) {
          participant.humanConversationSignal = true;
        }
      }
    }
  }

  private boolean isClearlyAddressedToPlayerOutsideConversation(
      Player sender,
      String message,
      ConversationSession currentSession) {
    if (containsAssistantMention(message)) {
      return false;
    }

    String lower = message.trim().toLowerCase(Locale.ROOT);

    for (Player other : Bukkit.getOnlinePlayers()) {
      if (other.getUniqueId().equals(sender.getUniqueId())) {
        continue;
      }

      // Addressing a co-participant is part of the group context, not a signal to
      // leave SVA. Only people outside this logical conversation release the sender.
      if (currentSession != null && currentSession.participants.containsKey(other.getUniqueId())) {
        continue;
      }

      String name = other.getName().toLowerCase(Locale.ROOT);
      if (lower.contains("@" + name)
          || lower.equals(name)
          || lower.startsWith(name + " ")
          || lower.startsWith(name + ",")
          || lower.startsWith(name + ":")
          || lower.startsWith("@" + name + " ")
          || lower.startsWith("@" + name + ",")
          || lower.startsWith("@" + name + ":")) {
        return true;
      }
    }
    return false;
  }

  private boolean isConfiguredConversationCloser(String message) {
    if (!plugin.getConfig().getBoolean("conversation-control.closing-phrases.enabled", true)) {
      return false;
    }

    String normalized = stripAssistantMentions(message)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .trim()
        .replaceAll("\\s+", " ");

    if (normalized.isEmpty()) {
      return false;
    }

    for (String phrase : plugin.getConfig().getStringList(
        "conversation-control.closing-phrases.phrases")) {
      if (phrase == null) {
        continue;
      }
      String normalizedPhrase = phrase.toLowerCase(Locale.ROOT)
          .replaceAll("[^\\p{L}\\p{N}]+", " ")
          .trim()
          .replaceAll("\\s+", " ");
      if (!normalizedPhrase.isEmpty() && normalized.equals(normalizedPhrase)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsWholeWordIgnoreCase(String text, String word) {
    if (text == null || word == null || word.isBlank()) {
      return false;
    }
    Pattern pattern = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}_])");
    return pattern.matcher(text).find();
  }

  private static boolean containsWholeWordPhraseIgnoreCase(String text, String phrase) {
    if (text == null || phrase == null || phrase.isBlank()) {
      return false;
    }
    Pattern pattern = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(phrase) + "(?![\\p{L}\\p{N}_])");
    return pattern.matcher(text).find();
  }

  // ---------------------------------------------------------------------------
  // GLOBAL EVENTS / OPTIONAL AMBIENT SCHEDULING
  // ---------------------------------------------------------------------------

  public void queueGlobalEvent(String message) {
    if (shutdown || message == null || message.isBlank()) {
      return;
    }

    rememberServerEvent(message);

    int maxPendingEvents = Math.max(
        plugin.getConfig().getInt("request-triggers.global-events.max-pending-events", 10),
        1);
    while (globalSession.pending.size() >= maxPendingEvents) {
      globalSession.pending.remove(0);
    }

    globalSession.pending.add(new BroadcastChatMessage(plugin, message));
    scheduleBatch(globalSession);
  }

  private void rememberServerEvent(String message) {
    int limit = Math.max(
        plugin.getConfig().getInt("conversation-control.recent-server-events-limit", 8),
        0);
    if (limit == 0) {
      return;
    }

    recentServerEvents.addLast(message);
    while (recentServerEvents.size() > limit) {
      recentServerEvents.removeFirst();
    }
  }

  private void observePlayerActivity() {
    cancelTask(idleTask);
    idleTask = null;

    if (!plugin.getConfig().getBoolean("request-triggers.scheduling.enabled", false)) {
      return;
    }

    long min = Math.max(
        plugin.getConfig().getLong("request-triggers.scheduling.min-delay", 30000),
        1000);
    long max = Math.max(
        plugin.getConfig().getLong("request-triggers.scheduling.max-delay", 120000),
        min);
    long delayMs = ThreadLocalRandom.current().nextLong(min, max + 1);
    long ticks = Math.max(1, (delayMs + 49) / 50);

    idleTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      idleTask = null;
      if (shutdown || Bukkit.getOnlinePlayers().isEmpty() || countActivePlayerConversations() > 0) {
        return;
      }

      globalSession.pending.add(new SystemContextMessage(
          plugin,
          "[IDLE MOMENT] ",
          "The public chat has been quiet for a while. Decide whether a single short ambient message would feel natural and useful. Usually remain silent."));
      scheduleBatch(globalSession);
    }, ticks);
  }

  // ---------------------------------------------------------------------------
  // BATCHING
  // ---------------------------------------------------------------------------

  private void scheduleBatch(ConversationSession session) {
    if (shutdown || session.processing || session.pending.isEmpty()) {
      return;
    }

    long now = System.currentTimeMillis();
    if (session.batchStartTime == 0) {
      session.batchStartTime = now;
      scheduleMaxBatchWait(session);
    }

    int maxMessages = Math.max(plugin.getConfig().getInt("message-batching.max-size", 10), 0);
    if (maxMessages > 0 && session.pending.size() >= maxMessages) {
      processSessionBatch(session);
      return;
    }

    cancelTask(session.batchTask);

    long delayMs = Math.max(plugin.getConfig().getLong("message-batching.wait-time", 1500), 0);
    long maxWaitMs = Math.max(plugin.getConfig().getLong("message-batching.max-wait-time", 10000), 0);
    long elapsed = now - session.batchStartTime;
    long remainingMaxWait = maxWaitMs <= 0 ? delayMs : Math.max(0, maxWaitMs - elapsed);
    long actualDelay = maxWaitMs <= 0 ? delayMs : Math.min(delayMs, remainingMaxWait);
    long delayTicks = Math.max(1, (actualDelay + 49) / 50);

    session.batchTask = plugin.getServer().getScheduler().runTaskLater(
        plugin,
        () -> processSessionBatch(session),
        delayTicks);
  }

  private void scheduleMaxBatchWait(ConversationSession session) {
    long maxWaitMs = Math.max(plugin.getConfig().getLong("message-batching.max-wait-time", 10000), 0);
    if (maxWaitMs <= 0) {
      return;
    }

    cancelTask(session.maxBatchTask);
    long delayTicks = Math.max(1, (maxWaitMs + 49) / 50);
    session.maxBatchTask = plugin.getServer().getScheduler().runTaskLater(
        plugin,
        () -> processSessionBatch(session),
        delayTicks);
  }

  private void processSessionBatch(ConversationSession session) {
    if (shutdown || session.processing || session.pending.isEmpty()) {
      return;
    }

    if (session.global) {
      if (plugin.getConfig().getBoolean(
          "request-triggers.global-events.defer-while-player-conversations-active",
          true)
          && countActivePlayerConversations() > 0) {
        cancelTask(session.batchTask);
        session.batchTask = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> processSessionBatch(session),
            40L);
        return;
      }

      long minInterval = Math.max(
          plugin.getConfig().getLong(
              "request-triggers.global-events.minimum-request-interval-ms",
              30000),
          0);
      long remaining = minInterval - (System.currentTimeMillis() - lastGlobalRequestAt);
      if (lastGlobalRequestAt > 0 && remaining > 0) {
        cancelTask(session.batchTask);
        long ticks = Math.max(1, (remaining + 49) / 50);
        session.batchTask = plugin.getServer().getScheduler().runTaskLater(
            plugin,
            () -> processSessionBatch(session),
            ticks);
        return;
      }
    }

    cancelTask(session.batchTask);
    cancelTask(session.maxBatchTask);
    session.batchTask = null;
    session.maxBatchTask = null;
    session.batchStartTime = 0;

    session.history.addAll(session.pending);
    session.pending.clear();
    trimStoredHistory(session);

    session.processing = true;
    if (!session.queued) {
      session.queued = true;
      requestQueue.addLast(new RequestJob(session, 0));
    }
    processNextRequest();
  }

  // ---------------------------------------------------------------------------
  // SERIALIZED AI REQUEST PIPELINE
  // ---------------------------------------------------------------------------

  private void processNextRequest() {
    if (shutdown || requestInFlight || requestRateRetryTask != null) {
      return;
    }

    RequestJob job = pollNextJobPrioritizingPlayers();
    if (job == null) {
      return;
    }

    if (job.depth == 0) {
      job.session.queued = false;
    }

    long rateDelay = getGlobalRequestRateDelay();
    if (rateDelay > 0) {
      requestQueue.addFirst(job);
      long ticks = Math.max(1, (rateDelay + 49) / 50);
      requestRateRetryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
        requestRateRetryTask = null;
        processNextRequest();
      }, ticks);
      return;
    }

    startRequest(job);
  }

  private RequestJob pollNextJobPrioritizingPlayers() {
    if (requestQueue.isEmpty()) {
      return null;
    }

    Iterator<RequestJob> iterator = requestQueue.iterator();
    while (iterator.hasNext()) {
      RequestJob job = iterator.next();
      if (!job.session.global) {
        iterator.remove();
        return job;
      }
    }
    return requestQueue.pollFirst();
  }

  private void startRequest(RequestJob job) {
    ConversationSession session = job.session;

    if (!session.global) {
      cleanupExpiredParticipants(session);
      if (session.participants.isEmpty()) {
        finishSessionRequest(session, false, false);
        processNextRequest();
        return;
      }
    }

    if (session.global && Bukkit.getOnlinePlayers().isEmpty()) {
      finishSessionRequest(session, false, false);
      processNextRequest();
      return;
    }

    requestInFlight = true;
    long now = System.currentTimeMillis();
    aiRequestTimes.addLast(now);
    if (session.global && job.depth == 0) {
      lastGlobalRequestAt = now;
    }

    AssistantRequestContext requestContext = session.global
        ? AssistantRequestContext.global(countActivePlayerConversations(), recentEventsText())
        : AssistantRequestContext.conversation(
            session.id,
            participantSummary(session),
            session.participants.size(),
            countActivePlayerConversations(),
            recentEventsText());

    List<ChatMessage> snapshot = getConversationSnapshot(session);

    assistantManager.sendAIRequest(snapshot, requestContext, (response, error) -> {
      if (shutdown) {
        return;
      }
      handleAICompletion(job, response, error);
    });
  }

  private void handleAICompletion(RequestJob job, AssistantResponse response, Throwable error) {
    ConversationSession session = job.session;

    if (error != null) {
      plugin.getLogger().warning(
          "AI request failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
      requestInFlight = false;
      finishSessionRequest(session, false, false);
      processNextRequest();
      return;
    }

    if (response == null) {
      response = new AssistantResponse(plugin, List.of(), List.of(), false);
    }

    session.history.add(new AssistantChatMessage(plugin, response));
    trimStoredHistory(session);

    boolean hasTools = !response.getToolCalls().isEmpty();
    boolean broadcastToolProgress = plugin.getConfig().getBoolean(
        "conversation-control.broadcast-tool-progress-messages",
        false);

    if (!session.suppressResponse && (!hasTools || broadcastToolProgress)) {
      response.broadcastMessages();
    }

    int maxToolIterations = Math.max(
        plugin.getConfig().getInt("conversation-control.max-tool-iterations", 3),
        0);

    if (hasTools && job.depth < maxToolIterations) {
      String toolResults = executeTools(response.getToolCalls());
      if (!toolResults.isBlank()) {
        session.history.add(new SystemContextMessage(
            plugin,
            "[TOOL RESULTS] ",
            toolResults));
        trimStoredHistory(session);

        requestInFlight = false;
        requestQueue.addFirst(new RequestJob(session, job.depth + 1));
        processNextRequest();
        return;
      }
    } else if (hasTools) {
      plugin.getLogger().warning(
          "Tool iteration limit reached for " + session.debugName() + ". Tool chain stopped.");
    }

    requestInFlight = false;

    // Newer messages always win. An older response must never close/release people
    // while fresh player input is already waiting in the same group.
    boolean hasNewerPending = !session.pending.isEmpty();
    boolean aiRequestedClose = response.shouldCloseConversation() && !hasNewerPending;

    finishSessionRequest(session, aiRequestedClose, true);
    processNextRequest();
  }

  private String executeTools(List<String> toolCalls) {
    StringBuilder results = new StringBuilder();
    WikiTool wikiTool = new WikiTool(plugin);

    int maxToolCalls = Math.max(
        plugin.getConfig().getInt("conversation-control.max-tool-calls-per-response", 2),
        0);
    int processed = 0;

    for (String toolCall : toolCalls) {
      if (maxToolCalls > 0 && processed >= maxToolCalls) {
        results.append("Tool call limit reached for this response.\n");
        break;
      }
      processed++;

      String[] parts = toolCall.trim().split("\\s+", 2);
      if (parts.length == 0 || parts[0].isBlank()) {
        continue;
      }

      String toolName = parts[0];
      if (toolName.equalsIgnoreCase("wiki")) {
        if (parts.length < 2 || parts[1].isBlank()) {
          results.append("wiki: Missing required key.\n");
          continue;
        }
        String key = parts[1].trim();
        results.append("wiki ").append(key).append(":\n")
            .append(wikiTool.getWiki(key)).append("\n");
      } else {
        results.append("Unknown tool: ").append(toolName).append("\n");
      }
    }
    return results.toString().trim();
  }

  /**
   * Finishes one AI turn. Closing phrases release only the participant who used
   * them. An AI close request closes a single-person session, but in a group it
   * conservatively releases only the latest speaker; one player's "gracias" can
   * therefore never destroy everybody else's shared conversation.
   */
  private void finishSessionRequest(
      ConversationSession session,
      boolean aiRequestedClose,
      boolean applyParticipantClosers) {
    session.processing = false;

    if (!session.global) {
      if (applyParticipantClosers) {
        List<UUID> explicitClosers = new ArrayList<>();
        for (ParticipantState participant : session.participants.values()) {
          if (participant.closeAfterCurrent) {
            explicitClosers.add(participant.playerId);
          }
        }
        for (UUID playerId : explicitClosers) {
          removeParticipant(session, playerId, false);
        }
      }

      if (aiRequestedClose && !session.participants.isEmpty()) {
        if (session.participants.size() == 1) {
          UUID only = session.participants.keySet().iterator().next();
          removeParticipant(session, only, false);
        } else if (session.lastSpeakerId != null
            && session.participants.containsKey(session.lastSpeakerId)) {
          removeParticipant(session, session.lastSpeakerId, false);
        }
      }

      // Do not refresh every participant here. Group members expire from their
      // own last accepted message, so another member talking to SVA cannot keep a
      // silent participant attached indefinitely.
      scheduleNextExpiryCheck(session);
      maybeRemoveEmptySession(session);
    }

    if (!session.pending.isEmpty()) {
      scheduleBatch(session);
    }
  }

  private long getGlobalRequestRateDelay() {
    int maxPerMinute = Math.max(
        plugin.getConfig().getInt("rate-limits.max-ai-requests-per-minute", 20),
        0);
    if (maxPerMinute == 0) {
      return 0;
    }

    long now = System.currentTimeMillis();
    pruneOlderThan(aiRequestTimes, now - 60000L);
    if (aiRequestTimes.size() < maxPerMinute) {
      return 0;
    }

    Long oldest = aiRequestTimes.peekFirst();
    return oldest == null ? 0 : Math.max(50, 60000L - (now - oldest));
  }

  // ---------------------------------------------------------------------------
  // SESSION / PARTICIPANT LIFECYCLE
  // ---------------------------------------------------------------------------

  private ConversationSession createPlayerConversation() {
    ConversationSession session = new ConversationSession(false, nextConversationId++);
    addInitialAssistantMessage(session);
    session.lastInteractionAt = System.currentTimeMillis();
    return session;
  }

  private ConversationSession createGlobalSession() {
    ConversationSession session = new ConversationSession(true, 0);
    addInitialAssistantMessage(session);
    return session;
  }

  private void addInitialAssistantMessage(ConversationSession session) {
    AssistantResponse initial = AssistantContextualizer.getInitialResponse(plugin);
    session.history.add(new AssistantChatMessage(plugin, initial));
  }

  private void refreshParticipantSnapshot(ParticipantState participant, Player player) {
    participant.playerName = player.getName();
    participant.playerAdmin = player.isOp();
  }

  private void refreshParticipantExpiry(
      ConversationSession session,
      ParticipantState participant) {
    if (session.global || !participant.active) {
      return;
    }

    long activeTime = Math.max(
        plugin.getConfig().getLong(
            "request-triggers.player-messages.smart-active-time",
            20000),
        0);

    participant.activeUntil = System.currentTimeMillis() + activeTime;
    scheduleNextExpiryCheck(session);
  }

  private void scheduleNextExpiryCheck(ConversationSession session) {
    if (session.global) {
      return;
    }

    long now = System.currentTimeMillis();
    long earliest = Long.MAX_VALUE;
    for (ParticipantState participant : session.participants.values()) {
      if (participant.active && participant.activeUntil > 0) {
        earliest = Math.min(earliest, participant.activeUntil);
      }
    }

    cancelTask(session.expiryTask);
    session.expiryTask = null;
    if (earliest == Long.MAX_VALUE) {
      return;
    }

    long delayMs = Math.max(50, earliest - now);
    long ticks = Math.max(1, (delayMs + 49) / 50);
    session.expiryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      session.expiryTask = null;
      cleanupExpiredParticipants(session);
      maybeRemoveEmptySession(session);
      if (!session.participants.isEmpty()) {
        scheduleNextExpiryCheck(session);
      }
    }, ticks);
  }

  private boolean isExpired(ParticipantState participant) {
    return participant.active
        && participant.activeUntil > 0
        && System.currentTimeMillis() >= participant.activeUntil;
  }

  private void cleanupExpiredParticipantsAndSessions() {
    for (ConversationSession session : new ArrayList<>(conversations.values())) {
      cleanupExpiredParticipants(session);
      maybeRemoveEmptySession(session);
    }
  }

  private void cleanupExpiredParticipants(ConversationSession session) {
    if (session == null || session.global) {
      return;
    }

    List<UUID> expired = new ArrayList<>();
    for (ParticipantState participant : session.participants.values()) {
      if (isExpired(participant)) {
        expired.add(participant.playerId);
      }
    }
    for (UUID playerId : expired) {
      removeParticipant(session, playerId, false);
    }
  }

  private void removeParticipant(
      ConversationSession session,
      UUID playerId,
      boolean removePendingMessages) {
    if (session == null || playerId == null || session.global) {
      return;
    }

    ParticipantState participant = session.participants.remove(playerId);
    if (participant == null) {
      return;
    }

    participant.active = false;
    participant.activeUntil = 0;
    participant.followUpsWithoutMention = 0;
    participant.closeAfterCurrent = false;
    participant.humanConversationSignal = false;

    ConversationSession indexed = playerConversationIndex.get(playerId);
    if (indexed == session) {
      playerConversationIndex.remove(playerId);
    }

    if (removePendingMessages) {
      removePendingMessagesFrom(session, playerId);
    }

    session.lastInteractionAt = System.currentTimeMillis();
    scheduleNextExpiryCheck(session);
    maybeRemoveEmptySession(session);
  }

  private void maybeRemoveEmptySession(ConversationSession session) {
    if (session == null || session.global || !session.participants.isEmpty()) {
      return;
    }

    if (session.processing || session.queued || !session.pending.isEmpty()) {
      if (session.participants.isEmpty()) {
        session.suppressResponse = true;
      }
      return;
    }

    cancelSessionTasks(session);
    conversations.remove(session.id);
  }

  private int countActivePlayerConversations() {
    cleanupExpiredParticipantsAndSessions();
    int count = 0;
    for (ConversationSession session : conversations.values()) {
      if (!session.participants.isEmpty()
          || session.processing
          || session.queued
          || !session.pending.isEmpty()) {
        count++;
      }
    }
    return count;
  }

  private String participantSummary(ConversationSession session) {
    if (session.participants.isEmpty()) {
      return "None.";
    }
    StringBuilder builder = new StringBuilder();
    for (ParticipantState participant : session.participants.values()) {
      builder.append("- ").append(participant.playerName);
      if (participant.playerAdmin) {
        builder.append(" (ADMIN)");
      }
      builder.append('\n');
    }
    return builder.toString().trim();
  }

  private List<ChatMessage> getConversationSnapshot(ConversationSession session) {
    int configuredHistory = Math.max(plugin.getConfig().getInt("message-history-limit", 15), 0);
    int batchSize = Math.max(plugin.getConfig().getInt("message-batching.max-size", 10), 1);
    int limit = configuredHistory == 0
        ? batchSize + 4
        : Math.max(configuredHistory, batchSize + 4);

    int size = session.history.size();
    if (size <= limit) {
      return List.copyOf(session.history);
    }
    return List.copyOf(session.history.subList(size - limit, size));
  }

  private void trimStoredHistory(ConversationSession session) {
    int configuredHistory = Math.max(plugin.getConfig().getInt("message-history-limit", 15), 0);
    int keep = Math.max(configuredHistory * 3, 30);
    while (session.history.size() > keep) {
      session.history.remove(0);
    }
  }

  private String recentEventsText() {
    if (recentServerEvents.isEmpty()) {
      return "";
    }
    return String.join("\n", recentServerEvents.stream().map(event -> "- " + event).toList());
  }

  // ---------------------------------------------------------------------------
  // PRIVATE NOTICES
  // ---------------------------------------------------------------------------

  private void sendBusyNotice(Player player) {
    if (!plugin.getConfig().getBoolean("conversation-control.busy-notice.enabled", true)) {
      return;
    }
    long cooldown = Math.max(
        plugin.getConfig().getLong("conversation-control.busy-notice.cooldown-ms", 10000),
        0);
    long now = System.currentTimeMillis();
    long last = lastBusyNotice.getOrDefault(player.getUniqueId(), 0L);
    if (now - last < cooldown) {
      return;
    }
    lastBusyNotice.put(player.getUniqueId(), now);
    sendPrivateConfiguredMessage(
        player,
        "conversation-control.busy-notice.message",
        "&bSVA &7está atendiendo otras conversaciones. Inténtalo de nuevo en unos segundos.");
  }

  private void sendRateNotice(Player player) {
    long cooldown = 10000;
    long now = System.currentTimeMillis();
    long last = lastRateNotice.getOrDefault(player.getUniqueId(), 0L);
    if (now - last < cooldown) {
      return;
    }
    lastRateNotice.put(player.getUniqueId(), now);
    sendPrivateConfiguredMessage(
        player,
        "rate-limits.player-limit-message",
        "&bSVA &7necesita un momento antes de seguir con tantos mensajes.");
  }

  private void sendPrivateConfiguredMessage(Player player, String path, String fallback) {
    String text = plugin.getConfig().getString(path, fallback);
    if (text == null || text.isBlank()) {
      return;
    }
    player.sendMessage(Component.text(ChatColor.translateAlternateColorCodes('&', text)));
  }

  // ---------------------------------------------------------------------------
  // HELPERS
  // ---------------------------------------------------------------------------

  private static void pruneOlderThan(Deque<Long> deque, long threshold) {
    while (!deque.isEmpty() && deque.peekFirst() < threshold) {
      deque.removeFirst();
    }
  }

  private static void cancelTask(BukkitTask task) {
    if (task != null) {
      try {
        task.cancel();
      } catch (Exception ignored) {
      }
    }
  }

  private static void cancelSessionTasks(ConversationSession session) {
    cancelTask(session.batchTask);
    cancelTask(session.maxBatchTask);
    cancelTask(session.expiryTask);
    session.batchTask = null;
    session.maxBatchTask = null;
    session.expiryTask = null;
  }

  private record RequestJob(ConversationSession session, int depth) {
  }

  private static final class ParticipantState {
    final UUID playerId;
    String playerName = "";
    boolean playerAdmin = false;
    boolean active = true;
    boolean closeAfterCurrent = false;
    boolean humanConversationSignal = false;
    int followUpsWithoutMention = 0;
    long activeUntil = 0;
    final Deque<Long> playerMessageTimes = new ArrayDeque<>();

    ParticipantState(UUID playerId) {
      this.playerId = playerId;
    }
  }

  private static final class ConversationSession {
    final boolean global;
    final long id;
    final Map<UUID, ParticipantState> participants = new LinkedHashMap<>();

    boolean processing = false;
    boolean queued = false;
    boolean suppressResponse = false;
    UUID lastSpeakerId;
    long lastInteractionAt = 0;
    long batchStartTime = 0;

    final List<ChatMessage> history = new ArrayList<>();
    final List<ChatMessage> pending = new ArrayList<>();

    BukkitTask batchTask;
    BukkitTask maxBatchTask;
    BukkitTask expiryTask;

    ConversationSession(boolean global, long id) {
      this.global = global;
      this.id = id;
    }

    String debugName() {
      return global ? GLOBAL_SESSION_KEY : "conversation-" + id;
    }
  }
}
