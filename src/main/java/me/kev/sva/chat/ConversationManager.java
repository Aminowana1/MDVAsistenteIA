package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.containsWholeWordIgnoreCase;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantManager;
import me.kev.sva.chat.assistant.AssistantRequestContext;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * ServerAssistant 1.7.19: one public global conversation with local context/tools.
 *
 * <p>There are no player/group slots. A direct call (or a short smart follow-up)
 * opens one configurable scene window. Java reads a small amount of public chat
 * and trusted events immediately before the trigger, listens for a short period
 * afterwards, filters unrelated lines locally, and sends the resulting scene in
 * one normal model request. Events never create their own AI request.</p>
 *
 * <p>This class owns the capture window and the public entry points. The work is
 * done by {@link SceneBuilder} (what goes into a scene), {@link SceneRequestQueue}
 * (the single AI pipeline), {@link SmartFollowUpGate} (who may continue without
 * naming Isolda) and {@link WikiRetriever} (local knowledge).</p>
 */
public final class ConversationManager {
  private final ServerAssistantPlugin plugin;
  private final ProviderThrottleRegistry providerThrottle;

  private final ConversationLog log = new ConversationLog();
  private final Deque<PublicChatRecord> publicChatLog = log.publicChat();
  private final Deque<ServerEventRecord> serverEventLog = log.serverEvents();
  private final Deque<SceneMemory> sceneHistory = log.sceneHistory();

  private final AssistantMentions mentions;
  private final SmartFollowUpGate smartGate;
  private final WikiRetriever wiki;
  private final SceneBuilder sceneBuilder;
  private final SceneRequestQueue requests;

  /** Per-player trigger timestamps only; ordinary public messages are never rate-limited. */
  private final Map<UUID, Deque<Long>> triggerTimes = new HashMap<>();

  private ActiveCapture activeCapture;
  private BukkitTask captureTask;
  private BukkitTask idleScheduleTask;
  private boolean shutdown = false;

  private long nextSceneId = 1L;

  /** Direct addressers collected while the current scene window is open. */
  private final Set<UUID> activeAddressers = new LinkedHashSet<>();

  /** Identity snapshots are collected only for players inside an active scene. */
  private final Map<String, String> activeIdentitySnapshots = new LinkedHashMap<>();

  public ConversationManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    this.providerThrottle = plugin.getProviderThrottleRegistry();
    this.mentions = new AssistantMentions(plugin);
    this.smartGate = new SmartFollowUpGate(plugin, log);
    this.wiki = new WikiRetriever(plugin, log);
    GroupThreadSelector groupThreads = new GroupThreadSelector(plugin, log, mentions, smartGate);
    this.sceneBuilder = new SceneBuilder(plugin, log, mentions, groupThreads, wiki);
    this.requests = new SceneRequestQueue(plugin, log, smartGate, new ReplyPostProcessor(plugin));
  }

  public AssistantManager getAssistantManager() {
    return requests.assistantManager();
  }

  public String getRuntimeStatus() {
    AssistantManager assistantManager = requests.assistantManager();
    var primary = assistantManager.getPrimaryProviderSettings();
    var fallback = assistantManager.getFallbackProviderSettings();
    String sceneState = activeCapture != null ? "collecting" : requests.state();

    return providerStatus("primary", primary)
        + ", " + (fallback == null ? "fallback=disabled" : providerStatus("fallback", fallback))
        + ", global_scene=" + sceneState
        + ", queued_scenes=" + requests.queuedCount()
        + ", chat_log=" + publicChatLog.size()
        + ", event_log=" + serverEventLog.size()
        + ", history_scenes=" + sceneHistory.size()
        + ", smart_followups=" + smartGate.activeSmartFollowUps()
        + ", journal_records=" + (plugin.getActivityJournal() == null ? 0 : plugin.getActivityJournal().size())
        + ", relationship_profiles=" + (plugin.getRelationshipManager() == null ? 0 : plugin.getRelationshipManager().profileCount())
        + ", idle_timer=" + (idleScheduleTask == null ? "off" : "scheduled")
        + ", pending_tool_approvals=" + (plugin.getToolManager() == null
            ? 0 : plugin.getToolManager().pendingApprovalSummaries().size());
  }

  private String providerStatus(String role, me.kev.sva.chat.assistant.ProviderSettings settings) {
    if (settings == null) {
      return role + "=unconfigured";
    }
    long cooldownMs = providerThrottle.cooldownRemainingMs(settings.throttleKey());
    int used = providerThrottle.requestsLastMinute(settings.throttleKey());
    int limit = settings.maxRequestsPerMinute();
    return role + "=" + settings.displayName() + "/" + settings.model()
        + "[" + used + "/" + (limit == 0 ? "unlimited" : limit)
        + ",cooldown=" + Math.max(0L, (cooldownMs + 999L) / 1000L) + "s]";
  }

  public void shutdown() {
    shutdown = true;
    cancelTask(captureTask);
    cancelTask(idleScheduleTask);
    captureTask = null;
    idleScheduleTask = null;
    activeCapture = null;
    log.clear();
    triggerTimes.clear();
    smartGate.clear();
    wiki.clear();
    activeAddressers.clear();
    activeIdentitySnapshots.clear();
    requests.shutdown();
  }

  // ---------------------------------------------------------------------------
  // PUBLIC CHAT -> GLOBAL SCENE
  // ---------------------------------------------------------------------------

  /** Must ultimately run on Bukkit's main thread. */
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

    long now = System.currentTimeMillis();
    PublicChatRecord record = snapshotPlayerMessage(player, content, now);
    rememberPublicChat(record);
    if (plugin.getActivityJournal() != null) plugin.getActivityJournal().recordChat(player, content);
    if (plugin.getRelationshipManager() != null) plugin.getRelationshipManager().observePlayer(player);
    scheduleIdleRequestAfterActivity();

    if (!plugin.getConfig().getBoolean("global-conversation.enabled", true)) {
      return;
    }

    String mode = mentions.configuredTriggerMode();
    if ("disabled".equals(mode)) {
      return;
    }

    boolean directMention = mentions.containsAssistantMention(content);
    boolean existingSmartFollowUp = "smart".equals(mode)
        && smartGate.hasActiveSmartFollowUp(player.getUniqueId(), now);
    // A live SMART timer is only permission to attempt a continuation. Before it can
    // create or join a scene, Java checks whether THIS line still belongs to Isolda's
    // thread. Clear side-chat/public-chat messages stay local and cost zero API tokens.
    boolean semanticSmartFollowUp = existingSmartFollowUp
        && !directMention
        && smartGate.isSemanticSmartFollowUp(player.getUniqueId(), player.getName(), content, now, true);

    // Hostile relationship tiers may skip a trivial direct message before any API
    // request is created. This is both more natural and cheaper than asking the model
    // to decide whether to ignore it.
    if (activeCapture == null && directMention && plugin.getRelationshipManager() != null
        && plugin.getRelationshipManager().shouldIgnoreDirectMessage(player, content)) {
      return;
    }

    boolean directedAtAssistant = directMention || semanticSmartFollowUp
        || (activeCapture != null && activeAddressers.contains(player.getUniqueId()));
    if (plugin.getToolManager() != null) {
      plugin.getToolManager().observePlayerMessage(player, content, directedAtAssistant);
    }

    // Once a scene is open, all lines are merely local candidates and never create
    // an extra request. A player who directly calls Isolda during the same window is
    // remembered as an addresser so they may continue briefly after the reply.
    if (activeCapture != null) {
      rememberActiveIdentity(player);
      // A player who already owns a valid SMART follow-up window is still directly
      // participating even if another player's line opened this shared capture first.
      // This is the key group-continuity case: both speakers can contribute to the
      // SAME request without repeating Iso and without creating another request.
      if (directMention || semanticSmartFollowUp) {
        activeAddressers.add(player.getUniqueId());
      }
      return;
    }

    boolean smartFollowUp = semanticSmartFollowUp;

    boolean shouldTrigger = switch (mode) {
      case "always" -> true;
      case "mention" -> directMention;
      case "smart" -> directMention || smartFollowUp;
      default -> directMention;
    };

    if (!shouldTrigger || !allowSceneTrigger(player.getUniqueId())) {
      return;
    }

    startCapture(record, directMention, smartFollowUp);
  }

  /** There are no player sessions to release in the global model. */
  public void handlePlayerDisconnect(UUID playerId) {
    if (playerId != null) {
      triggerTimes.remove(playerId);
      smartGate.revoke(playerId);
      wiki.forgetPlayer(playerId);
      activeAddressers.remove(playerId);
    }
  }

  /**
   * Administrative equivalent of the friend's /sva trigger command. It opens one
   * normal global scene without broadcasting a fake player message to Minecraft.
   * The synthetic admin line exists only inside the local log/model context.
   */
  public boolean forceTrigger(CommandSender sender) {
    if (shutdown || activeCapture != null) {
      return false;
    }
    long now = System.currentTimeMillis();
    UUID id;
    String name;
    String display;
    if (sender instanceof Player player) {
      id = player.getUniqueId();
      name = player.getName();
      display = PlainTextComponentSerializer.plainText().serialize(player.displayName());
    } else {
      id = new UUID(0L, 0L);
      name = "CONSOLE";
      display = "CONSOLE";
    }
    String assistantName = plugin.getConfig().getString("assistant-name", "Isolda");
    PublicChatRecord synthetic = new PublicChatRecord(
        now, id, name, display, true,
        (assistantName == null || assistantName.isBlank() ? "Isolda" : assistantName)
            + ", reacciona al chat reciente si hay algo que valga la pena.");
    rememberPublicChat(synthetic);
    startCapture(synthetic, true, false);
    return true;
  }

  /**
   * Optional compatibility/improvement over the friend's request-triggers.scheduling
   * idea. One timer is reset by real player chat. If the server then stays quiet,
   * Java may enqueue ONE idle scene. Disabled by default because it intentionally
   * spends an API request without requiring an Isolda mention.
   */
  private void scheduleIdleRequestAfterActivity() {
    cancelTask(idleScheduleTask);
    idleScheduleTask = null;

    if (shutdown
        || !plugin.getConfig().getBoolean("global-conversation.enabled", true)
        || !plugin.getConfig().getBoolean("global-conversation.idle-scheduling.enabled", false)) {
      return;
    }

    long min = Math.max(plugin.getConfig().getLong(
        "global-conversation.idle-scheduling.min-delay-ms", 30_000L), 1_000L);
    long max = Math.max(plugin.getConfig().getLong(
        "global-conversation.idle-scheduling.max-delay-ms", 120_000L), min);
    long delay = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1L);
    long ticks = Math.max(1L, (delay + 49L) / 50L);

    idleScheduleTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      idleScheduleTask = null;
      enqueueIdleScene();
    }, ticks);
  }

  private void enqueueIdleScene() {
    if (shutdown
        || !plugin.getConfig().getBoolean("global-conversation.enabled", true)
        || !plugin.getConfig().getBoolean("global-conversation.idle-scheduling.enabled", false)) {
      return;
    }
    if (plugin.getConfig().getBoolean(
        "global-conversation.idle-scheduling.require-online-players", true)
        && Bukkit.getOnlinePlayers().isEmpty()) {
      return;
    }

    // Do not stack an autonomous thought behind active player interaction.
    if (activeCapture != null || requests.isBusy()) {
      return;
    }

    long sceneId = nextSceneId++;
    List<ChatMessage> modelMessages = new ArrayList<>();
    int historyScenes = Math.max(
        plugin.getConfig().getInt("global-conversation.history.max-scenes", 2), 0);
    if (historyScenes > 0 && !sceneHistory.isEmpty()) {
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

    SystemContextMessage idle = new SystemContextMessage(
        plugin,
        "[IDLE] ",
        "El chat lleva un rato tranquilo. Puedes hacer un comentario espontaneo y natural basado solo en el contexto real disponible, o guardar silencio.");
    modelMessages.add(idle);
    List<ChatMessage> current = List.of(idle);

    SceneRequest scene = new SceneRequest(
        sceneId,
        List.copyOf(modelMessages),
        current,
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        Map.of(),
        AssistantRequestContext.scene(
            sceneId,
            "none",
            "trigger=idle_scheduling, chat_lines=0, events=0",
            "",
            "",
            "",
            "",
            "",
            "",
            ""),
        "",
        AssistantManager.PRIMARY,
        0,
        -1L);

    requests.enqueue(scene);
  }

  private boolean allowSceneTrigger(UUID playerId) {
    int maxPerMinute = Math.max(
        plugin.getConfig().getInt("rate-limits.max-scene-triggers-per-player-per-minute", 8),
        0);
    if (maxPerMinute == 0) {
      return true;
    }
    long now = System.currentTimeMillis();
    Deque<Long> times = triggerTimes.computeIfAbsent(playerId, ignored -> new ArrayDeque<>());
    pruneOlderThan(times, now - 60_000L);
    if (times.size() >= maxPerMinute) {
      return false;
    }
    times.addLast(now);
    return true;
  }

  private PublicChatRecord snapshotPlayerMessage(Player player, String content, long timestamp) {
    String displayName = PlainTextComponentSerializer.plainText().serialize(player.displayName());
    return new PublicChatRecord(
        timestamp,
        player.getUniqueId(),
        player.getName(),
        displayName,
        player.isOp() || player.hasPermission("sva.admin"),
        content);
  }

  private void rememberPublicChat(PublicChatRecord record) {
    publicChatLog.addLast(record);
    int max = Math.max(plugin.getConfig().getInt("global-conversation.logs.max-chat-records", 80), 20);
    while (publicChatLog.size() > max) {
      publicChatLog.removeFirst();
    }
  }

  private void startCapture(PublicChatRecord trigger, boolean directMention, boolean smartFollowUp) {
    long windowMs = Math.max(
        plugin.getConfig().getLong("global-conversation.scene.capture-window-ms", 1500L),
        0L);
    long now = System.currentTimeMillis();
    activeCapture = new ActiveCapture(
        nextSceneId++,
        trigger.timestampMs(),
        now + windowMs,
        trigger.playerId(),
        trigger.playerName(),
        directMention,
        smartFollowUp);

    activeAddressers.clear();
    activeIdentitySnapshots.clear();
    Player triggerPlayer = Bukkit.getPlayer(trigger.playerId());
    if (triggerPlayer != null) rememberActiveIdentity(triggerPlayer);

    // A direct mention grants continuity. A smart follow-up renews continuity for
    // the same player only after Isolda successfully completes this scene.
    if (directMention || smartFollowUp) {
      activeAddressers.add(trigger.playerId());
    }

    cancelTask(captureTask);
    long ticks = Math.max(1L, (windowMs + 49L) / 50L);
    captureTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
      captureTask = null;
      finishCapture();
    }, ticks);
  }

  private void finishCapture() {
    if (shutdown || activeCapture == null) {
      return;
    }
    ActiveCapture capture = activeCapture;
    Set<UUID> addressers = Set.copyOf(activeAddressers);
    Map<String, String> identitySnapshots = Map.copyOf(activeIdentitySnapshots);
    activeAddressers.clear();
    activeIdentitySnapshots.clear();
    activeCapture = null;

    SceneRequest scene = sceneBuilder.buildScene(capture, addressers, identitySnapshots);
    if (scene == null || scene.messages().isEmpty()) {
      return;
    }

    int maxPending = Math.max(
        plugin.getConfig().getInt("global-conversation.scene.max-pending-scenes", 4),
        1);
    requests.enqueueDroppingOldest(scene, maxPending);
  }

  private void rememberActiveIdentity(Player player) {
    if (player == null || plugin.getIntegrationManager() == null) return;
    String key = player.getName().toLowerCase(Locale.ROOT);
    if (activeIdentitySnapshots.containsKey(key)) return;
    String identity = plugin.getIntegrationManager().buildAmbientIdentity(player);
    if (!identity.isBlank()) activeIdentitySnapshots.put(key, identity);
  }

  // ---------------------------------------------------------------------------
  // TRUSTED EVENT LOGGING - NEVER TRIGGERS AI BY ITSELF
  // ---------------------------------------------------------------------------

  public void queueGlobalEvent(String message) {
    recordServerEvent("server", message, List.of());
  }

  public void recordServerEvent(String type, String message, List<String> involvedPlayers) {
    if (shutdown || message == null || message.isBlank()) {
      return;
    }
    List<String> names = involvedPlayers == null
        ? List.of()
        : involvedPlayers.stream().filter(n -> n != null && !n.isBlank()).distinct().toList();
    Map<String, String> identities = sceneBuilder.snapshotOnlineIdentities(names);
    serverEventLog.addLast(new ServerEventRecord(
        System.currentTimeMillis(),
        type == null || type.isBlank() ? "server" : type,
        message,
        names,
        Map.copyOf(identities)));
    int max = Math.max(plugin.getConfig().getInt("global-conversation.logs.max-event-records", 30), 10);
    while (serverEventLog.size() > max) {
      serverEventLog.removeFirst();
    }
  }

  /**
   * Optional relationship-driven autonomous reaction. Disabled by default because
   * every accepted event intentionally spends one normal model request.
   */
  public void maybeQueueRelationshipReaction(String type, String text, List<Player> actors) {
    if (shutdown || plugin.getRelationshipManager() == null || actors == null || actors.isEmpty()) return;
    // If players are already talking to Isolda, the trusted event can be folded into
    // that scene instead of paying for a second reaction.
    if (activeCapture != null || requests.isBusy()) return;
    if (!plugin.getRelationshipManager().shouldReactToEvent(type, actors)) return;

    long sceneId = nextSceneId++;
    List<String> names = actors.stream().filter(java.util.Objects::nonNull)
        .map(Player::getName).distinct().toList();
    String relation = plugin.getRelationshipManager().eventRelationshipContext(actors);
    SystemContextMessage event = new SystemContextMessage(
        plugin,
        "[EVENT type=" + type + " players=" + (names.isEmpty() ? "none" : String.join(",", names)) + "] ",
        text == null ? "" : text);
    List<ChatMessage> current = List.of(event);
    List<ChatMessage> modelMessages = new ArrayList<>();
    modelMessages.add(new SystemContextMessage(
        plugin,
        "[RELATIONSHIP EVENT REACTION] ",
        "This trusted event may merit one spontaneous in-character reaction because of a strong stored relationship. A reply is optional. Do not invent extra facts."));
    modelMessages.add(event);

    SceneRequest scene = new SceneRequest(
        sceneId,
        List.copyOf(modelMessages),
        current,
        Set.of(),
        Set.copyOf(names),
        Set.of(),
        Set.of(),
        Set.of(),
        Map.of(),
        AssistantRequestContext.scene(
            sceneId,
            names.isEmpty() ? "none" : String.join(",", names),
            "trigger=relationship_event, chat_lines=0, events=1",
            "", "", "", "", "", relation, ""),
        "",
        AssistantManager.PRIMARY,
        0,
        -1L);
    requests.enqueue(scene);
  }

  /** Clears tiny conversational carry-over after an admin force-changes relationship state. */
  public void clearRelationshipRuntimeContext(UUID playerId) {
    if (playerId != null) {
      smartGate.revoke(playerId);
      wiki.forgetPlayer(playerId);
      activeAddressers.remove(playerId);
    }
    // Assistant replies can mention the player without a structural UUID, so the
    // safest tiny operation is clearing the 1-2 scene history entries entirely.
    sceneHistory.clear();
  }

  /** Removes the player's rolling scene/log traces from this runtime. */
  public void purgePlayerFromRuntime(UUID playerId, String playerName) {
    if (playerId == null) return;
    String lower = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    publicChatLog.removeIf(chat -> playerId.equals(chat.playerId())
        || (!lower.isBlank() && containsWholeWordIgnoreCase(chat.content(), playerName)));
    serverEventLog.removeIf(event -> event.involvedPlayers().stream().anyMatch(name ->
        name != null && (!lower.isBlank() && name.equalsIgnoreCase(playerName))));
    triggerTimes.remove(playerId);
    smartGate.revoke(playerId);
    wiki.forgetPlayer(playerId);
    activeAddressers.remove(playerId);
    requests.removeScenesInvolving(playerId, playerName);
    if (activeCapture != null && playerId.equals(activeCapture.triggerPlayerId())) {
      if (captureTask != null) {
        captureTask.cancel();
        captureTask = null;
      }
      activeCapture = null;
    }
    // Scene history is tiny; clearing it avoids retaining an assistant line that may
    // mention the purged player even when that line cannot be attributed structurally.
    sceneHistory.clear();
  }

  private static void pruneOlderThan(Deque<Long> deque, long threshold) {
    while (!deque.isEmpty() && deque.peekFirst() < threshold) deque.removeFirst();
  }

  static void cancelTask(BukkitTask task) {
    if (task != null) {
      try { task.cancel(); } catch (Exception ignored) { }
    }
  }
}
