package me.kev.sva.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.scheduler.BukkitTask;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantManager;
import me.kev.sva.chat.assistant.AssistantResponse;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.tools.ToolManager;

/**
 * The single serialized AI pipeline: one request in flight, provider throttling and
 * fallback, then post-processing and paced delivery of the reply.
 */
final class SceneRequestQueue {
  private final ServerAssistantPlugin plugin;
  private final AssistantManager assistantManager;
  private final ProviderThrottleRegistry providerThrottle;
  private final Deque<SceneMemory> sceneHistory;
  private final SmartFollowUpGate smartGate;
  private final ReplyPostProcessor replies;
  private boolean shutdown = false;

  /** Finished scenes waiting for the single serialized AI pipeline. */
  private final Deque<SceneRequest> sceneQueue = new ArrayDeque<>();

  private BukkitTask requestRateRetryTask;

  private boolean requestInFlight = false;

  /** Paced chat lines not yet shown; cancelled on reload so no line appears afterwards. */
  private final Set<BukkitTask> pendingLineTasks = new HashSet<>();

  /** Send time of the last scheduled Isolda line. Later scenes never overtake it. */
  private long lastScheduledLineAt = 0L;

  SceneRequestQueue(
      ServerAssistantPlugin plugin,
      ConversationLog log,
      SmartFollowUpGate smartGate,
      ReplyPostProcessor replies) {
    this.plugin = plugin;
    this.assistantManager = new AssistantManager(plugin);
    this.providerThrottle = plugin.getProviderThrottleRegistry();
    this.sceneHistory = log.sceneHistory();
    this.smartGate = smartGate;
    this.replies = replies;
  }

  AssistantManager assistantManager() {
    return assistantManager;
  }

  /** True while a request is in flight, waiting on a provider delay, or queued. */
  boolean isBusy() {
    return requestInFlight || requestRateRetryTask != null || !sceneQueue.isEmpty();
  }

  String state() {
    return requestInFlight ? "in_flight" : sceneQueue.isEmpty() ? "idle" : "queued";
  }

  int queuedCount() {
    return sceneQueue.size();
  }

  void enqueue(SceneRequest scene) {
    sceneQueue.addLast(scene);
    processNextRequest();
  }

  /**
   * Global chat should never say "I'm busy". Keep the newest context by dropping
   * the oldest not-yet-sent scene if the queue is abused.
   */
  void enqueueDroppingOldest(SceneRequest scene, int maxPending) {
    while (sceneQueue.size() >= maxPending) {
      sceneQueue.pollFirst();
    }
    enqueue(scene);
  }

  void removeScenesInvolving(UUID playerId, String playerName) {
    String lower = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    sceneQueue.removeIf(scene -> scene.involvedPlayerIds().contains(playerId)
        || scene.involvedPlayerNames().stream().anyMatch(name ->
            name != null && !lower.isBlank() && name.equalsIgnoreCase(playerName)));
  }

  void shutdown() {
    shutdown = true;
    ConversationManager.cancelTask(requestRateRetryTask);
    requestRateRetryTask = null;
    for (BukkitTask task : pendingLineTasks) ConversationManager.cancelTask(task);
    pendingLineTasks.clear();
    sceneQueue.clear();
    assistantManager.shutdown();
  }

  private void processNextRequest() {
    if (shutdown || requestInFlight || requestRateRetryTask != null || sceneQueue.isEmpty()) {
      return;
    }

    SceneRequest scene = sceneQueue.peekFirst();
    int providerIndex = scene.providerIndex();
    if (!assistantManager.isProviderAvailable(providerIndex)) {
      if (providerIndex == AssistantManager.PRIMARY && assistantManager.hasFallback()) {
        sceneQueue.removeFirst();
        sceneQueue.addFirst(scene.withProvider(AssistantManager.FALLBACK));
        processNextRequest();
      } else {
        plugin.getLogger().warning("AI provider is not configured; dropping global scene " + scene.sceneId() + ".");
        sceneQueue.removeFirst();
        processNextRequest();
      }
      return;
    }

    long delay = providerDelay(providerIndex);
    if (delay > 0 && providerIndex == AssistantManager.PRIMARY && assistantManager.hasFallback()) {
      long fallbackDelay = providerDelay(AssistantManager.FALLBACK);
      long maxFallbackWait = Math.max(plugin.getConfig().getLong("ai.fallback.max-wait-ms", 2500L), 0L);
      if (fallbackDelay <= maxFallbackWait) {
        sceneQueue.removeFirst();
        sceneQueue.addFirst(scene.withProvider(AssistantManager.FALLBACK));
        processNextRequest();
        return;
      }
    }

    if (delay > 0) {
      long maxQueueDelay = Math.max(plugin.getConfig().getLong("rate-limits.max-local-queue-delay-ms", 5000L), 0L);
      if (maxQueueDelay > 0 && delay > maxQueueDelay) {
        plugin.getLogger().warning("Dropping stale global scene " + scene.sceneId()
            + " because provider delay is about " + delay + "ms.");
        sceneQueue.removeFirst();
        processNextRequest();
        return;
      }
      long ticks = Math.max(1L, (delay + 49L) / 50L);
      requestRateRetryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
        requestRateRetryTask = null;
        processNextRequest();
      }, ticks);
      return;
    }

    sceneQueue.removeFirst();
    startRequest(scene);
  }

  private void startRequest(SceneRequest scene) {
    var provider = assistantManager.getProviderSettings(scene.providerIndex());
    if (provider == null) {
      processNextRequest();
      return;
    }

    requestInFlight = true;
    providerThrottle.recordAttempt(provider.throttleKey());
    assistantManager.sendAIRequest(
        scene.providerIndex(),
        scene.messages(),
        scene.context(),
        (response, error) -> handleCompletion(scene, response, error));
  }

  private void handleCompletion(SceneRequest scene, AssistantResponse response, Throwable error) {
    requestInFlight = false;
    try {
      processCompletion(scene, response, error);
    } catch (RuntimeException ex) {
      // Post-processing bugs must never stall the global queue until the next scene.
      plugin.getLogger().warning("Could not finish global scene " + scene.sceneId() + ": "
          + ex.getClass().getSimpleName() + ": " + ex.getMessage());
      processNextRequest();
    }
  }

  private void processCompletion(SceneRequest scene, AssistantResponse response, Throwable error) {
    var provider = assistantManager.getProviderSettings(scene.providerIndex());

    if (error != null) {
      if (isRateLimitError(error)) {
        long retryAfter = parseProviderRetryDelay(error, 60_000L);
        if (provider != null) {
          providerThrottle.applyCooldown(provider.throttleKey(), retryAfter);
        }
        plugin.getLogger().warning((provider == null ? "AI provider" : provider.displayName())
            + " rate limit reached. Global chat remains active; scene " + scene.sceneId() + " was not answered.");
        if (queueFallback(scene, "429 rate limit")) {
          processNextRequest();
          return;
        }
      } else if (isTransientProviderError(error)) {
        if (queueFallback(scene, "temporary provider error")) {
          processNextRequest();
          return;
        }
        if (queueTransientRetry(scene)) {
          return;
        }
        plugin.getLogger().warning("AI request failed for global scene " + scene.sceneId()
            + " (temporary provider error, no fallback/retry left): "
            + error.getClass().getSimpleName() + ": " + error.getMessage());
      } else {
        plugin.getLogger().warning("AI request failed for global scene " + scene.sceneId()
            + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
      }
      processNextRequest();
      return;
    }

    if (response == null) {
      response = new AssistantResponse(plugin, List.of(), List.of(), false);
    }

    // A direct mention must never disappear because a small model returned m=[].
    // Recover locally from already-selected trusted wiki context when possible; otherwise
    // emit one honest generic acknowledgement. This costs ZERO additional API requests.
    if (response.historyText().isBlank() && response.getToolCalls().isEmpty() && replies.isDirectMentionScene(scene)) {
      List<String> guaranteed = replies.buildGuaranteedDirectReplies(scene);
      if (!guaranteed.isEmpty()) {
        plugin.getLogger().warning("Recovered empty direct-mention response locally for scene " + scene.sceneId()
            + " (replies=" + guaranteed.size() + ").");
        response = new AssistantResponse(
            plugin, guaranteed, response.getToolCalls(), response.getRelationshipUpdates(),
            response.getFollowUpSpeakers(), response.shouldCloseConversation());
      }
    }

    // Group chat is semantic, not one-ticket-per-player. Java therefore does NOT
    // force a reply for every addressed speaker. It only protects a clearly independent
    // factual/wiki request that already had trusted context selected but was omitted by
    // the model. Shared discussions remain one natural group reply. No retry/second call.
    List<String> factualCoverage = replies.buildMissingIndependentKnowledgeReplies(scene, response.getMessages());
    if (!factualCoverage.isEmpty()) {
      List<String> merged = new ArrayList<>(response.getMessages());
      merged.addAll(factualCoverage);
      plugin.getLogger().warning("Completed omitted independent factual reply locally for scene "
          + scene.sceneId() + " (model_replies=" + response.getMessages().size()
          + ", added=" + factualCoverage.size() + ").");
      response = new AssistantResponse(
          plugin, merged, response.getToolCalls(), response.getRelationshipUpdates(),
          response.getFollowUpSpeakers(), response.shouldCloseConversation());
    }

    // Fail closed for factual queries whose named entity was not verified by the
    // local wiki. Prompt instructions are not enough here: if a small model invents
    // lore despite result=no_match, Java replaces that affected reply with an honest
    // uncertainty line before anything reaches public chat.
    List<String> groundedReplies = replies.enforceWikiNoMatchGrounding(scene, response.getMessages());
    if (!groundedReplies.equals(response.getMessages())) {
      plugin.getLogger().warning("Suppressed unsupported wiki claim for scene " + scene.sceneId()
          + " because trusted local knowledge returned result=no_match.");
      response = new AssistantResponse(
          plugin, groundedReplies, response.getToolCalls(), response.getRelationshipUpdates(),
          response.getFollowUpSpeakers(), response.shouldCloseConversation());
    }

    // One conversational subject should normally produce one public chat bubble.
    // Small models sometimes split one thought into m=[line1,line2] even for one
    // speaker. Collapse that burst locally. Multiple bubbles survive only when they
    // are explicitly targeted at different current Isolda-thread speakers.
    List<String> shapedReplies = replies.coalesceReplyBurstForScene(scene, response.getMessages());
    if (!shapedReplies.equals(response.getMessages())) {
      if (plugin.getConfig().getBoolean("provider-response.debug-reply-shaping", false)) {
        plugin.getLogger().info("Reply shaping scene=" + scene.sceneId()
            + " model_messages=" + response.getMessages().size()
            + " public_messages=" + shapedReplies.size());
      }
      response = new AssistantResponse(
          plugin, shapedReplies, response.getToolCalls(), response.getRelationshipUpdates(),
          response.getFollowUpSpeakers(), response.shouldCloseConversation());
    }

    // Direct/smart addressers are known locally. For a true group exchange the same
    // single model response may additionally mark CURRENT related speakers in `f`.
    // This tiny metadata field avoids a second classifier/API request while preventing
    // unrelated public chatter from inheriting a SMART follow-up window.
    Set<UUID> conversationParticipants = replies.resolveConversationParticipants(scene, response);

    if (plugin.getRelationshipManager() != null) {
      plugin.getRelationshipManager().applySceneUpdates(
          response.getRelationshipUpdates(),
          scene.currentSceneMessages(),
          conversationParticipants,
          response.historyText());

      // Legacy zero-token dialogue guards return one replacement string. Applying
      // them to a 2-3 message group reply would collapse the other speakers' answers.
      // The model already receives authoritative romance_global/relationship state;
      // keep these last-resort replacements only for single-line scenes.
      if (response.getMessages().size() <= 1) {
        String guardedReply = plugin.getRelationshipManager().guardRomanceReply(
            response.historyText(), scene.currentSceneMessages(), conversationParticipants).trim();
        guardedReply = plugin.getRelationshipManager().guardPartnerFactReply(
            guardedReply, scene.currentSceneMessages(), conversationParticipants).trim();
        if (!guardedReply.equals(response.historyText().trim())) {
          response = new AssistantResponse(
              plugin,
              guardedReply.isBlank() ? List.of() : List.of(guardedReply),
              response.getToolCalls(),
              response.getRelationshipUpdates(),
              response.getFollowUpSpeakers(),
              response.shouldCloseConversation());
        }
      }
    }

    String reply = response.historyText().trim();
    if (!reply.isBlank() && smartGate.isDuplicateSmartFollowUpReply(scene, reply)) {
      if (plugin.getConfig().getBoolean("provider-response.debug-empty-direct-replies", false)) {
        plugin.getLogger().info("Suppressed duplicate smart-follow-up reply for scene " + scene.sceneId());
      }
      reply = "";
    }
    if (reply.isBlank()
        && scene.context() != null
        && scene.context().sceneMeta() != null
        && scene.context().sceneMeta().contains("trigger=direct_mention")
        && plugin.getConfig().getBoolean("provider-response.debug-empty-direct-replies", false)) {
      plugin.getLogger().warning("Direct-mention scene " + scene.sceneId()
          + " returned no public chat line (tool_calls=" + response.getToolCalls().size() + ").");
    }
    ToolManager.PreparedCalls toolCalls = null;
    boolean toolCallsAccepted = true;
    if (plugin.getToolManager() != null && !response.getToolCalls().isEmpty()) {
      toolCalls = plugin.getToolManager().prepareModelCalls(
          response.getToolCalls(), scene.currentActionText());
      toolCallsAccepted = toolCalls.accepted();
    }
    if (!reply.isBlank() && !toolCallsAccepted
        && plugin.getConfig().getBoolean("tools.action-safety.suppress-reply-on-rejected-call", true)) {
      plugin.getLogger().warning("Suppressed AI chat for scene " + scene.sceneId()
          + " because it was paired with a stale/policy-blocked action call.");
      reply = "";
    }

    long now = System.currentTimeMillis();
    // When Isolda's first line actually appears in chat; actions and SMART timing follow it.
    long firstVisibleAt = now;
    if (!reply.isBlank()) {
      firstVisibleAt = deliverPaced(scene, response.getMessages(), toolCalls, now);
    } else if (toolCalls != null) {
      toolCalls.run();
    }

    rememberScene(scene, reply);
    long baseFollowUpMs = Math.max(plugin.getConfig().getLong(
        "global-conversation.smart-follow-up-ms", 30_000L), 0L);
    smartGate.pruneSmartFollowUps(now);
    if (!reply.isBlank()) {
      for (UUID playerId : conversationParticipants) {
        long followUpMs = plugin.getRelationshipManager() == null
            ? baseFollowUpMs
            : plugin.getRelationshipManager().followUpMs(playerId, baseFollowUpMs);
        if (followUpMs > 0L) {
          // The window opens now (a player may answer while Isolda is still "typing")
          // but is measured from when her reply becomes visible.
          smartGate.grant(playerId, firstVisibleAt + followUpMs, scene, reply, now);
        } else {
          smartGate.revoke(playerId);
        }
      }
    }

    processNextRequest();
  }

  /**
   * Schedules Isolda's lines with realistic typing time (see {@link ResponsePacing}).
   * Accepted ACTION calls run together with the first line. Returns when the first
   * line will be visible.
   */
  private long deliverPaced(
      SceneRequest scene, List<String> messages, ToolManager.PreparedCalls toolCalls, long now) {
    ResponsePacing pacing = new ResponsePacing(
        plugin.getConfig().getBoolean("chat.response-pacing.enabled", true),
        plugin.getConfig().getLong("chat.response-pacing.base-delay-ms", 2_500L),
        plugin.getConfig().getLong("chat.response-pacing.length-delay-ms", 3_500L),
        plugin.getConfig().getInt("chat.max-assistant-message-length", 189),
        plugin.getConfig().getLong("chat.assistant-chained-messages-delay", 750L));
    List<Long> sendTimes = pacing.schedule(messages, scene.triggerAtMs(), now, lastScheduledLineAt);
    if (sendTimes.isEmpty()) {
      if (toolCalls != null) toolCalls.run();
      return now;
    }

    for (int i = 0; i < messages.size(); i++) {
      String message = messages.get(i);
      Runnable beforeLine = i == 0 && toolCalls != null ? toolCalls::run : null;
      long ticks = Math.max(0L, (sendTimes.get(i) - now + 49L) / 50L);
      BukkitTask[] holder = new BukkitTask[1];
      holder[0] = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
        pendingLineTasks.remove(holder[0]);
        if (shutdown) return;
        if (beforeLine != null) beforeLine.run();
        plugin.getServer().broadcast(AssistantResponse.formatMessage(plugin, message));
      }, ticks);
      pendingLineTasks.add(holder[0]);
    }
    lastScheduledLineAt = sendTimes.get(sendTimes.size() - 1);
    return sendTimes.get(0);
  }

  private void rememberScene(SceneRequest scene, String assistantReply) {
    int keep = Math.max(plugin.getConfig().getInt("global-conversation.history.max-scenes", 2), 0);
    if (keep == 0) {
      sceneHistory.clear();
      return;
    }
    int maxMessages = Math.max(
        plugin.getConfig().getInt("global-conversation.history.max-messages-per-scene", 4),
        1);
    List<ChatMessage> source = scene.currentSceneMessages();
    boolean idleScene = scene.context() != null
        && scene.context().sceneMeta() != null
        && scene.context().sceneMeta().contains("trigger=idle_scheduling");
    int start = Math.max(0, source.size() - maxMessages);
    // Do not preserve the internal [IDLE] instruction as future conversation text.
    // The visible spontaneous Isolda line is still remembered through assistantReply.
    List<ChatMessage> compactMemory = idleScene
        ? List.of()
        : List.copyOf(source.subList(start, source.size()));
    boolean wikiContextUsed = scene.context() != null
        && scene.context().locallyRetrievedWiki() != null
        && !scene.context().locallyRetrievedWiki().isBlank();
    sceneHistory.addLast(new SceneMemory(
        compactMemory,
        assistantReply == null ? "" : assistantReply,
        wikiContextUsed));
    while (sceneHistory.size() > keep) {
      sceneHistory.removeFirst();
    }
  }

  private long providerDelay(int providerIndex) {
    var settings = assistantManager.getProviderSettings(providerIndex);
    if (settings == null) return 0L;
    return providerThrottle.getDelay(settings.throttleKey(), settings.maxRequestsPerMinute());
  }

  private boolean queueFallback(SceneRequest scene, String reason) {
    if (scene.providerIndex() != AssistantManager.PRIMARY || !assistantManager.hasFallback()) {
      return false;
    }
    var fallback = assistantManager.getFallbackProviderSettings();
    if (fallback == null) return false;
    long delay = providerDelay(AssistantManager.FALLBACK);
    long maxWait = Math.max(plugin.getConfig().getLong("ai.fallback.max-wait-ms", 2500L), 0L);
    if (delay > maxWait) return false;
    plugin.getLogger().warning("Using fallback " + fallback.displayName() + "/" + fallback.model()
        + " for the same global scene after " + reason + ".");
    sceneQueue.addFirst(scene.withProvider(AssistantManager.FALLBACK));
    return true;
  }

  private boolean queueTransientRetry(SceneRequest scene) {
    int maxRetries = Math.max(plugin.getConfig().getInt("provider-retry.max-503-retries", 0), 0);
    if (scene.retryCount() >= maxRetries) return false;
    long delay = Math.max(plugin.getConfig().getLong("provider-retry.initial-503-delay-ms", 1500L), 250L);
    long maxDelay = Math.max(plugin.getConfig().getLong("provider-retry.max-503-delay-ms", 5000L), delay);
    delay = Math.min(maxDelay, delay * (1L << Math.min(scene.retryCount(), 10)));
    sceneQueue.addFirst(scene.withRetry(scene.retryCount() + 1));
    long ticks = Math.max(1L, (delay + 49L) / 50L);
    requestRateRetryTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      requestRateRetryTask = null;
      processNextRequest();
    }, ticks);
    return true;
  }

  private static boolean isRateLimitError(Throwable error) {
    String type = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    return type.contains("ratelimit") || message.contains("429")
        || message.contains("resource_exhausted") || message.contains("rate limit");
  }

  private static boolean isTransientProviderError(Throwable error) {
    String type = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
    return type.contains("internalserver") || type.contains("serviceunavailable") || type.contains("timeout")
        || message.contains("500") || message.contains("502") || message.contains("503") || message.contains("504")
        || message.contains("temporarily unavailable") || message.contains("timed out") || message.contains("connection reset");
  }

  private static long parseProviderRetryDelay(Throwable error, long fallbackMs) {
    String message = String.valueOf(error.getMessage());
    java.util.regex.Matcher matcher = Pattern.compile(
        "(?:retry|try again)(?:\\s+after|\\s+in)?\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(?:s|sec|seconds?)",
        Pattern.CASE_INSENSITIVE).matcher(message);
    if (matcher.find()) {
      try {
        return Math.max(1000L, (long) Math.ceil(Double.parseDouble(matcher.group(1)) * 1000D) + 250L);
      } catch (NumberFormatException ignored) {
      }
    }
    return Math.max(1000L, fallbackMs);
  }
}
