package me.kev.sva.chat;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantManager;
import me.kev.sva.chat.assistant.AssistantRequestContext;
import me.kev.sva.chat.assistant.AssistantResponse;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * ServerAssistant 1.6: one public global conversation with local context/tools.
 *
 * <p>There are no player/group slots. A direct call (or a short smart follow-up)
 * opens one configurable scene window. Java reads a small amount of public chat
 * and trusted events immediately before the trigger, listens for a short period
 * afterwards, filters unrelated lines locally, and sends the resulting scene in
 * one normal model request. Events never create their own AI request.</p>
 */
public final class ConversationManager {
  private final ServerAssistantPlugin plugin;
  private final AssistantManager assistantManager;
  private final ProviderThrottleRegistry providerThrottle;

  /** Rolling local logs. These cost zero AI tokens until a scene is actually built. */
  private final Deque<PublicChatRecord> publicChatLog = new ArrayDeque<>();
  private final Deque<ServerEventRecord> serverEventLog = new ArrayDeque<>();
  private final Deque<SceneMemory> sceneHistory = new ArrayDeque<>();

  /** Finished scenes waiting for the single serialized AI pipeline. */
  private final Deque<SceneRequest> sceneQueue = new ArrayDeque<>();

  /** Per-player trigger timestamps only; ordinary public messages are never rate-limited. */
  private final Map<UUID, Deque<Long>> triggerTimes = new HashMap<>();

  private ActiveCapture activeCapture;
  private BukkitTask captureTask;
  private BukkitTask requestRateRetryTask;
  private BukkitTask idleScheduleTask;
  private boolean requestInFlight = false;
  private boolean shutdown = false;
  private long nextSceneId = 1L;

  /**
   * Smart-mode continuity is per player, not a global chat latch. Only players who
   * actually addressed Isolda in the last answered scene receive a short follow-up
   * window. Merely appearing as context never grants trigger rights.
   */
  private final Map<UUID, Long> smartFollowUpUntilByPlayer = new HashMap<>();

  /** Direct addressers collected while the current 1.5s scene window is open. */
  private final Set<UUID> activeAddressers = new LinkedHashSet<>();

  public ConversationManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    this.assistantManager = new AssistantManager(plugin);
    this.providerThrottle = plugin.getProviderThrottleRegistry();
  }

  public AssistantManager getAssistantManager() {
    return assistantManager;
  }

  public String getRuntimeStatus() {
    var primary = assistantManager.getPrimaryProviderSettings();
    var fallback = assistantManager.getFallbackProviderSettings();
    String sceneState = activeCapture != null
        ? "collecting"
        : requestInFlight ? "in_flight" : sceneQueue.isEmpty() ? "idle" : "queued";

    return providerStatus("primary", primary)
        + ", " + (fallback == null ? "fallback=disabled" : providerStatus("fallback", fallback))
        + ", global_scene=" + sceneState
        + ", queued_scenes=" + sceneQueue.size()
        + ", chat_log=" + publicChatLog.size()
        + ", event_log=" + serverEventLog.size()
        + ", history_scenes=" + sceneHistory.size()
        + ", smart_followups=" + activeSmartFollowUps()
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
    cancelTask(requestRateRetryTask);
    cancelTask(idleScheduleTask);
    captureTask = null;
    requestRateRetryTask = null;
    idleScheduleTask = null;
    activeCapture = null;
    sceneQueue.clear();
    publicChatLog.clear();
    serverEventLog.clear();
    sceneHistory.clear();
    triggerTimes.clear();
    smartFollowUpUntilByPlayer.clear();
    activeAddressers.clear();
    assistantManager.shutdown();
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
    scheduleIdleRequestAfterActivity();

    if (!plugin.getConfig().getBoolean("global-conversation.enabled", true)) {
      return;
    }

    String mode = configuredTriggerMode();
    if ("disabled".equals(mode)) {
      return;
    }

    boolean directMention = containsAssistantMention(content);
    boolean existingSmartFollowUp = "smart".equals(mode)
        && hasActiveSmartFollowUp(player.getUniqueId(), now);
    boolean directedAtAssistant = directMention || existingSmartFollowUp
        || (activeCapture != null && activeAddressers.contains(player.getUniqueId()));
    if (plugin.getToolManager() != null) {
      plugin.getToolManager().observePlayerMessage(player, content, directedAtAssistant);
    }

    // Once a scene is open, all lines are merely local candidates and never create
    // an extra request. A player who directly calls Isolda during the same window is
    // remembered as an addresser so they may continue briefly after the reply.
    if (activeCapture != null) {
      if (directMention) {
        activeAddressers.add(player.getUniqueId());
      }
      return;
    }

    boolean smartFollowUp = existingSmartFollowUp;

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
      smartFollowUpUntilByPlayer.remove(playerId);
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
    if (activeCapture != null || requestInFlight || requestRateRetryTask != null || !sceneQueue.isEmpty()) {
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
        AssistantRequestContext.scene(
            sceneId,
            "none",
            "trigger=idle_scheduling, chat_lines=0, events=0",
            "",
            "",
            ""),
        "",
        AssistantManager.PRIMARY,
        0);

    sceneQueue.addLast(scene);
    processNextRequest();
  }

  private String configuredTriggerMode() {
    String mode;
    if (plugin.getConfig().isSet("global-conversation.trigger-mode")) {
      mode = plugin.getConfig().getString("global-conversation.trigger-mode", "smart");
    } else {
      // Non-destructive compatibility for users upgrading with a 1.4.x config.
      mode = plugin.getConfig().getString("request-triggers.player-messages.mode", "smart");
    }
    mode = mode == null ? "smart" : mode.toLowerCase(Locale.ROOT).trim();
    return Set.of("always", "mention", "smart", "disabled").contains(mode) ? mode : "smart";
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
    activeAddressers.clear();
    activeCapture = null;

    SceneRequest scene = buildScene(capture, addressers);
    if (scene == null || scene.messages().isEmpty()) {
      return;
    }

    int maxPending = Math.max(
        plugin.getConfig().getInt("global-conversation.scene.max-pending-scenes", 4),
        1);
    while (sceneQueue.size() >= maxPending) {
      // Global chat should never say "I'm busy". Keep the newest context by
      // dropping the oldest not-yet-sent scene if the queue is abused.
      sceneQueue.pollFirst();
    }
    sceneQueue.addLast(scene);
    processNextRequest();
  }

  // ---------------------------------------------------------------------------
  // RELEVANCE FILTERING
  // ---------------------------------------------------------------------------

  private SceneRequest buildScene(ActiveCapture capture, Set<UUID> addressers) {
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
            || containsAssistantMention(chat.content())
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

    List<PublicChatRecord> selectedChats = limitChatRecords(
        new ArrayList<>(includedChats), capture.triggerAt());
    List<ServerEventRecord> selectedEvents = limitEventRecords(
        new ArrayList<>(includedEvents), capture.triggerAt());

    // Only a player whose direct/smart line actually survived the relevance/cap
    // filter receives follow-up eligibility. Context-only players never do.
    Set<UUID> eligibleAddressers = new LinkedHashSet<>();
    if (addressers != null && !addressers.isEmpty()) {
      for (PublicChatRecord chat : selectedChats) {
        if (addressers.contains(chat.playerId())) {
          eligibleAddressers.add(chat.playerId());
        }
      }
    }

    List<SceneAtom> atoms = new ArrayList<>();
    for (PublicChatRecord chat : selectedChats) {
      atoms.add(SceneAtom.chat(chat));
    }
    for (ServerEventRecord event : selectedEvents) {
      atoms.add(SceneAtom.event(event));
    }
    atoms.sort(Comparator.comparingLong(SceneAtom::timestampMs)
        .thenComparingInt(atom -> atom.event() == null ? 0 : 1));

    List<ChatMessage> currentMessages = new ArrayList<>();
    for (SceneAtom atom : atoms) {
      if (atom.chat() != null) {
        PublicChatRecord chat = atom.chat();
        currentMessages.add(new PlayerChatMessage(
            plugin,
            chat.playerId(),
            chat.playerName(),
            chat.displayName(),
            chat.admin(),
            chat.content()));
      } else {
        ServerEventRecord event = atom.event();
        String players = event.involvedPlayers().isEmpty()
            ? "none"
            : String.join(",", event.involvedPlayers());
        currentMessages.add(new SystemContextMessage(
            plugin,
            "[EVENT type=" + event.type() + " players=" + players + "] ",
            event.text()));
      }
    }

    List<ChatMessage> modelMessages = new ArrayList<>();
    int historyScenes = Math.max(
        plugin.getConfig().getInt("global-conversation.history.max-scenes", 2),
        0);
    if (historyScenes > 0 && !sceneHistory.isEmpty()) {
      List<SceneMemory> memories = new ArrayList<>(sceneHistory);
      int start = Math.max(0, memories.size() - historyScenes);
      for (int i = start; i < memories.size(); i++) {
        SceneMemory memory = memories.get(i);
        modelMessages.addAll(memory.messages());
        if (!memory.assistantReply().isBlank()) {
          modelMessages.add(new AssistantChatMessage(plugin, memory.assistantReply()));
        }
      }
    }
    modelMessages.addAll(currentMessages);

    String wiki = retrieveLocalWiki(currentMessages);
    String localTools = plugin.getToolManager() == null
        ? ""
        : plugin.getToolManager().buildLocalContext(currentMessages, involvedNames);
    String recentEvents = retrieveRecentEventContext(currentMessages);
    String involved = involvedNames.isEmpty() ? "none" : String.join(",", involvedNames);
    String currentActionText = selectedChats.stream()
        .filter(chat -> chat.timestampMs() >= capture.triggerAt())
        .filter(chat -> chat.playerId().equals(capture.triggerPlayerId()) || containsAssistantMention(chat.content()))
        .map(PublicChatRecord::content)
        .collect(java.util.stream.Collectors.joining(" "));
    String meta = "window_ms=" + Math.max(0L, capture.endsAt() - capture.triggerAt())
        + ", chat_lines=" + selectedChats.size()
        + ", events=" + selectedEvents.size()
        + ", trigger=" + (capture.directMention() ? "direct_mention" : "smart_followup");

    return new SceneRequest(
        capture.sceneId(),
        List.copyOf(modelMessages),
        List.copyOf(currentMessages),
        Set.copyOf(involvedIds),
        Set.copyOf(involvedNames),
        Set.copyOf(eligibleAddressers),
        AssistantRequestContext.scene(capture.sceneId(), involved, meta, wiki, localTools, recentEvents),
        currentActionText,
        AssistantManager.PRIMARY,
        0);
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
    for (Map.Entry<String, UUID> entry : knownNames.entrySet()) {
      String lowercaseName = entry.getKey();
      if (!containsWholeWordIgnoreCase(message, lowercaseName)
          && !message.toLowerCase(Locale.ROOT).contains("@" + lowercaseName)) {
        continue;
      }
      UUID id = entry.getValue();
      if (involvedIds.add(id)) {
        changed = true;
      }
      String canonical = Bukkit.getPlayer(id) != null ? Bukkit.getPlayer(id).getName() : lowercaseName;
      if (involvedNames.add(canonical)) {
        changed = true;
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
      if (containsWholeWordIgnoreCase(text, name)
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
    serverEventLog.addLast(new ServerEventRecord(
        System.currentTimeMillis(),
        type == null || type.isBlank() ? "server" : type,
        message,
        names));
    int max = Math.max(plugin.getConfig().getInt("global-conversation.logs.max-event-records", 30), 10);
    while (serverEventLog.size() > max) {
      serverEventLog.removeFirst();
    }
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
    if (containsAnyTerm(query, "murio", "mori", "muerte", "mato", "matado", "kill", "killed", "morir")) {
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
      used++;
    }
    return out.toString();
  }

  private static boolean containsAnyTerm(String text, String... terms) {
    for (String term : terms) {
      if (text.contains(term)) return true;
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // LOCAL WIKI RETRIEVAL - NO SECOND MODEL CALL
  // ---------------------------------------------------------------------------

  private String retrieveLocalWiki(List<ChatMessage> currentMessages) {
    if (plugin.getToolManager() != null && !plugin.getToolManager().isToolEnabled("wiki")) {
      return "";
    }
    if (!plugin.getWikiConfig().getBoolean("lazy-mode", true)) {
      return fullWikiContext();
    }
    if (!plugin.getWikiConfig().getBoolean("local-retrieval.enabled", true)) {
      return "";
    }

    ConfigurationSection wiki = wikiRoot();
    if (wiki == null) {
      return "";
    }

    StringBuilder queryBuilder = new StringBuilder();
    for (ChatMessage message : currentMessages) {
      queryBuilder.append(message.content).append(' ');
    }
    String query = normalizeForSearch(queryBuilder.toString());
    Set<String> queryTerms = meaningfulTerms(query);
    if (queryTerms.isEmpty()) {
      return "";
    }

    List<WikiCandidate> candidates = new ArrayList<>();
    for (String key : wiki.getKeys(false)) {
      ConfigurationSection section = wiki.getConfigurationSection(key);
      if (section == null) {
        continue;
      }
      String description = section.getString("description", "");
      String content = section.getString("content", "");
      description = description == null ? "" : description;
      content = content == null ? "" : content;

      String normalizedKey = normalizeForSearch(key.replace('-', ' ').replace('_', ' '));
      String normalizedDescription = normalizeForSearch(description);
      String normalizedContent = normalizeForSearch(content);
      int score = 0;
      if (!normalizedKey.isBlank() && query.contains(normalizedKey)) {
        score += 8;
      }
      for (String term : queryTerms) {
        if (containsWholeWordIgnoreCase(normalizedKey, term)) score += 4;
        if (containsWholeWordIgnoreCase(normalizedDescription, term)) score += 2;
        if (containsWholeWordIgnoreCase(normalizedContent, term)) score += 1;
      }
      if (score > 0) {
        candidates.add(new WikiCandidate(key, description, content, score));
      }
    }

    int minScore = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.min-score", 2), 1);
    int maxSections = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.max-sections", 2), 0);
    int maxChars = Math.max(plugin.getWikiConfig().getInt(
        "local-retrieval.max-section-chars", 4500), 200);
    if (maxSections == 0) {
      return "";
    }

    candidates.sort(Comparator.comparingInt(WikiCandidate::score).reversed());
    StringBuilder out = new StringBuilder();
    int used = 0;
    for (WikiCandidate candidate : candidates) {
      if (candidate.score() < minScore || used >= maxSections) {
        break;
      }
      String content = candidate.content().trim();
      if (content.length() > maxChars) {
        content = content.substring(0, maxChars).trim();
      }
      if (content.isBlank()) {
        continue;
      }
      if (!out.isEmpty()) {
        out.append('\n');
      }
      out.append('[').append(candidate.key()).append("] ").append(content);
      used++;
    }
    return out.toString();
  }

  private String fullWikiContext() {
    ConfigurationSection wiki = wikiRoot();
    if (wiki == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    for (String key : wiki.getKeys(false)) {
      ConfigurationSection section = wiki.getConfigurationSection(key);
      if (section == null) continue;
      String content = section.getString("content", "");
      if (content == null || content.isBlank()) continue;
      if (!out.isEmpty()) out.append('\n');
      out.append('[').append(key).append("] ").append(content.trim());
    }
    return out.toString();
  }

  private ConfigurationSection wikiRoot() {
    return plugin.getWikiConfig().getConfigurationSection("wiki");
  }

  private static String normalizeForSearch(String input) {
    String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}_-]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
    return normalized;
  }

  private static Set<String> meaningfulTerms(String text) {
    Set<String> stop = Set.of(
        "que", "como", "donde", "cuando", "quien", "para", "por", "con", "una", "uno", "unos",
        "unas", "del", "las", "los", "eso", "esta", "este", "esto", "soy", "eres", "hay", "iso",
        "isolda", "the", "and", "what", "where", "how", "when", "you", "your");
    Set<String> result = new LinkedHashSet<>();
    for (String token : text.split("\\s+")) {
      if (token.length() >= 3 && !stop.contains(token)) {
        result.add(token);
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // SINGLE SERIALIZED MODEL REQUEST PER SCENE
  // ---------------------------------------------------------------------------

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

    String reply = response.historyText().trim();
    boolean toolCallsAccepted = true;
    if (plugin.getToolManager() != null && !response.getToolCalls().isEmpty()) {
      toolCallsAccepted = plugin.getToolManager().processModelCalls(
          response.getToolCalls(), scene.currentActionText());
    }
    if (!reply.isBlank() && toolCallsAccepted) {
      response.broadcastMessages();
    } else if (!reply.isBlank() && !toolCallsAccepted
        && plugin.getConfig().getBoolean("tools.action-safety.suppress-reply-on-rejected-call", true)) {
      plugin.getLogger().warning("Suppressed AI chat for scene " + scene.sceneId()
          + " because it was paired with a stale/policy-blocked action call.");
      reply = "";
    } else if (!reply.isBlank()) {
      response.broadcastMessages();
    }

    rememberScene(scene, reply);
    long followUpMs = Math.max(plugin.getConfig().getLong(
        "global-conversation.smart-follow-up-ms", 12_000L), 0L);
    long now = System.currentTimeMillis();
    pruneSmartFollowUps(now);
    if (followUpMs > 0L && !reply.isBlank()) {
      long until = now + followUpMs;
      for (UUID playerId : scene.followUpEligiblePlayerIds()) {
        smartFollowUpUntilByPlayer.put(playerId, until);
      }
    }

    processNextRequest();
  }

  private boolean hasActiveSmartFollowUp(UUID playerId, long now) {
    if (playerId == null) return false;
    Long until = smartFollowUpUntilByPlayer.get(playerId);
    if (until == null) return false;
    if (until < now) {
      smartFollowUpUntilByPlayer.remove(playerId);
      return false;
    }
    return true;
  }

  private void pruneSmartFollowUps(long now) {
    smartFollowUpUntilByPlayer.entrySet().removeIf(entry -> entry.getValue() < now);
  }

  private int activeSmartFollowUps() {
    pruneSmartFollowUps(System.currentTimeMillis());
    return smartFollowUpUntilByPlayer.size();
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
    sceneHistory.addLast(new SceneMemory(compactMemory, assistantReply == null ? "" : assistantReply));
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

  // ---------------------------------------------------------------------------
  // MENTION / TEXT HELPERS
  // ---------------------------------------------------------------------------

  private boolean containsAssistantMention(String message) {
    if (message == null || message.isBlank()) return false;
    for (String mention : configuredAssistantMentions()) {
      if (mention == null || mention.isBlank()) continue;
      String clean = mention.startsWith("@") ? mention.substring(1) : mention;
      if (clean.isBlank()) continue;
      if (containsWholeWordIgnoreCase(message, mention) || containsWholeWordIgnoreCase(message, clean)) {
        return true;
      }
      // Friendly typo/stretch support: Isoo, Isooo, Isoldaa. This still requires
      // the name at a word boundary and does not match arbitrary substrings.
      Pattern stretched = Pattern.compile(
          "(?iu)(?<![\\p{L}\\p{N}_])@?" + Pattern.quote(clean)
              + Pattern.quote(clean.substring(clean.length() - 1)) + "{0,3}(?![\\p{L}\\p{N}_])");
      if (stretched.matcher(message).find()) {
        return true;
      }
    }
    return false;
  }

  private List<String> configuredAssistantMentions() {
    List<String> mentions = new ArrayList<>(plugin.getConfig().getStringList("global-conversation.mentions"));
    if (mentions.isEmpty()) {
      mentions.addAll(plugin.getConfig().getStringList("request-triggers.player-messages.mentions"));
    }
    String assistantName = plugin.getConfig().getString("assistant-name", "Isolda");
    if (assistantName != null && !assistantName.isBlank()) {
      mentions.add(assistantName);
      mentions.add("@" + assistantName);
    }
    if (mentions.isEmpty()) {
      mentions.add("Isolda");
      mentions.add("Iso");
    }
    return mentions.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
  }

  private static boolean containsWholeWordIgnoreCase(String text, String word) {
    if (text == null || word == null || word.isBlank()) return false;
    Pattern pattern = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}_])");
    return pattern.matcher(text).find();
  }

  private static void pruneOlderThan(Deque<Long> deque, long threshold) {
    while (!deque.isEmpty() && deque.peekFirst() < threshold) deque.removeFirst();
  }

  private static void cancelTask(BukkitTask task) {
    if (task != null) {
      try { task.cancel(); } catch (Exception ignored) { }
    }
  }

  // ---------------------------------------------------------------------------
  // IMMUTABLE STATE
  // ---------------------------------------------------------------------------

  private record PublicChatRecord(
      long timestampMs,
      UUID playerId,
      String playerName,
      String displayName,
      boolean admin,
      String content) { }

  private record ServerEventRecord(
      long timestampMs,
      String type,
      String text,
      List<String> involvedPlayers) { }

  private record ActiveCapture(
      long sceneId,
      long triggerAt,
      long endsAt,
      UUID triggerPlayerId,
      String triggerPlayerName,
      boolean directMention,
      boolean smartFollowUp) { }

  private record SceneAtom(long timestampMs, PublicChatRecord chat, ServerEventRecord event) {
    static SceneAtom chat(PublicChatRecord chat) {
      return new SceneAtom(chat.timestampMs(), chat, null);
    }
    static SceneAtom event(ServerEventRecord event) {
      return new SceneAtom(event.timestampMs(), null, event);
    }
  }

  private record SceneMemory(List<ChatMessage> messages, String assistantReply) { }

  private record WikiCandidate(String key, String description, String content, int score) { }

  private record SceneRequest(
      long sceneId,
      List<ChatMessage> messages,
      List<ChatMessage> currentSceneMessages,
      Set<UUID> involvedPlayerIds,
      Set<String> involvedPlayerNames,
      Set<UUID> followUpEligiblePlayerIds,
      AssistantRequestContext context,
      String currentActionText,
      int providerIndex,
      int retryCount) {

    SceneRequest withProvider(int newProvider) {
      return new SceneRequest(
          sceneId, messages, currentSceneMessages, involvedPlayerIds, involvedPlayerNames,
          followUpEligiblePlayerIds, context, currentActionText, newProvider, 0);
    }

    SceneRequest withRetry(int newRetryCount) {
      return new SceneRequest(
          sceneId, messages, currentSceneMessages, involvedPlayerIds, involvedPlayerNames,
          followUpEligiblePlayerIds, context, currentActionText, providerIndex, newRetryCount);
    }
  }
}
