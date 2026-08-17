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
import me.kev.sva.chat.tools.ContextTargetResolver;
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
  /** Pre-normalized wiki snapshot rebuilt on plugin load/reload. */
  private final List<WikiIndexEntry> wikiIndex;

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

  /** Direct addressers collected while the current scene window is open. */
  private final Set<UUID> activeAddressers = new LinkedHashSet<>();

  /** Identity snapshots are collected only for players inside an active scene. */
  private final Map<String, String> activeIdentitySnapshots = new LinkedHashMap<>();

  public ConversationManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    this.assistantManager = new AssistantManager(plugin);
    this.providerThrottle = plugin.getProviderThrottleRegistry();
    this.wikiIndex = buildWikiIndex();
    plugin.getLogger().info("Local wiki index cached in RAM: " + wikiIndex.size() + " sections.");
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
    activeIdentitySnapshots.clear();
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
    if (plugin.getActivityJournal() != null) plugin.getActivityJournal().recordChat(player, content);
    if (plugin.getRelationshipManager() != null) plugin.getRelationshipManager().observePlayer(player);
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

    // Hostile relationship tiers may skip a trivial direct message before any API
    // request is created. This is both more natural and cheaper than asking the model
    // to decide whether to ignore it.
    if (activeCapture == null && directMention && plugin.getRelationshipManager() != null
        && plugin.getRelationshipManager().shouldIgnoreDirectMessage(player, content)) {
      return;
    }

    boolean directedAtAssistant = directMention || existingSmartFollowUp
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
      if (directMention || existingSmartFollowUp) {
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

    SceneRequest scene = buildScene(capture, addressers, identitySnapshots);
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

  private SceneRequest buildScene(
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

    GroupThreadSelection groupThread = selectGroupThreadCandidates(
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
        .filter(chat -> chat.playerId().equals(capture.triggerPlayerId()) || containsAssistantMention(chat.content()))
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
    String wiki = journalIntent || nothingToFulfill ? "" : retrieveLocalWiki(fulfillableRequestMessages);
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
    String assistantMentions = configuredAssistantMentions().stream()
        .map(value -> value.startsWith("@") ? value.substring(1) : value)
        .distinct()
        .limit(4)
        .collect(java.util.stream.Collectors.joining("|"));
    String meta = "window_ms=" + Math.max(0L, capture.endsAt() - capture.triggerAt())
        + ", assistant_trigger_mode=" + configuredTriggerMode()
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
        0);
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

  private GroupThreadSelection selectGroupThreadCandidates(
      List<PublicChatRecord> selectedChats,
      List<PublicChatRecord> allCandidates,
      ActiveCapture capture,
      Set<UUID> eligibleAddressers) {
    if (!plugin.getConfig().getBoolean("global-conversation.scene.group-threading.enabled", true)
        || allCandidates == null || allCandidates.isEmpty()) {
      return GroupThreadSelection.empty();
    }

    String base = "global-conversation.scene.group-threading.";
    long preLookback = Math.max(plugin.getConfig().getLong(
        base + "pre-candidate-lookback-ms", 6000L), 0L);
    int maxPre = Math.max(plugin.getConfig().getInt(
        base + "max-pre-candidate-lines", 2), 0);
    int joinThreshold = clampInt(plugin.getConfig().getInt(
        base + "affinity.join-threshold", 48), 0, 100);
    int minMargin = clampInt(plugin.getConfig().getInt(
        base + "affinity.min-margin-over-side-thread", 12), 0, 100);
    int autoThreshold = clampInt(plugin.getConfig().getInt(
        base + "affinity.auto-follow-up-threshold", 68), joinThreshold, 100);
    long sideLookback = Math.max(plugin.getConfig().getLong(
        base + "affinity.side-thread-lookback-ms", 8000L), 0L);
    int maxSideLines = Math.max(plugin.getConfig().getInt(
        base + "affinity.max-side-lines", 5), 1);
    boolean debugAffinity = plugin.getConfig().getBoolean(
        base + "affinity.debug-log", false);

    LinkedHashSet<UUID> roots = new LinkedHashSet<>();
    roots.add(capture.triggerPlayerId());
    if (eligibleAddressers != null) roots.addAll(eligibleAddressers);

    LinkedHashSet<String> participantNames = new LinkedHashSet<>();
    StringBuilder threadText = new StringBuilder();
    String previousAssistantReply = "";
    if (!sceneHistory.isEmpty()) {
      SceneMemory previous = sceneHistory.peekLast();
      if (previous != null && previous.assistantReply() != null) {
        previousAssistantReply = previous.assistantReply();
        threadText.append(previousAssistantReply).append(' ');
      }
    }
    for (PublicChatRecord chat : allCandidates) {
      if (roots.contains(chat.playerId())) {
        participantNames.add(chat.playerName());
        if (chat.timestampMs() >= capture.triggerAt()) {
          threadText.append(chat.content()).append(' ');
        }
      }
    }

    List<PublicChatRecord> timeline = allCandidates.stream()
        .filter(chat -> chat.timestampMs() >= capture.triggerAt() - preLookback)
        .filter(chat -> chat.timestampMs() <= capture.endsAt())
        .sorted(Comparator.comparingLong(PublicChatRecord::timestampMs))
        .toList();

    LinkedHashSet<PublicChatRecord> candidateRecords = new LinkedHashSet<>();
    LinkedHashSet<UUID> candidateIds = new LinkedHashSet<>();
    LinkedHashSet<UUID> autoParticipants = new LinkedHashSet<>();
    LinkedHashMap<String, UUID> candidateByName = new LinkedHashMap<>();
    Deque<PublicChatRecord> sideLines = new ArrayDeque<>();
    PublicChatRecord previousThreadLine = null;

    for (PublicChatRecord chat : timeline) {
      if (roots.contains(chat.playerId())) {
        previousThreadLine = chat;
        continue;
      }

      boolean beforeTrigger = chat.timestampMs() < capture.triggerAt();
      boolean assistantMention = containsAssistantMention(chat.content());
      boolean alreadySmart = hasActiveSmartFollowUp(chat.playerId(), capture.triggerAt());
      boolean explicitBridge = looksLikeExplicitGroupBridge(chat.content());

      long threadAge = previousThreadLine == null
          ? Math.abs(capture.triggerAt() - chat.timestampMs())
          : Math.max(0L, chat.timestampMs() - previousThreadLine.timestampMs());
      String previousThreadText = previousThreadLine == null
          ? previousAssistantReply
          : previousThreadLine.content();

      int isoldaAffinity;
      if (assistantMention) {
        isoldaAffinity = 100;
      } else if (alreadySmart) {
        isoldaAffinity = 96;
      } else {
        isoldaAffinity = localThreadAffinity(
            chat.content(), threadText.toString(), previousThreadText,
            participantNames, threadAge, beforeTrigger);
        if (explicitBridge) isoldaAffinity = Math.min(100, isoldaAffinity + 50);
      }

      int bestSideAffinity = 0;
      PublicChatRecord bestSideLine = null;
      for (PublicChatRecord side : sideLines) {
        long age = Math.max(0L, chat.timestampMs() - side.timestampMs());
        if (age > sideLookback) continue;
        int score = localLateralAffinity(
            chat.content(), side.content(), side.playerName(), age);
        if (score > bestSideAffinity) {
          bestSideAffinity = score;
          bestSideLine = side;
        }
      }

      int requiredMargin = explicitBridge ? Math.min(minMargin, 6) : minMargin;
      boolean strongAuthority = assistantMention || alreadySmart;
      boolean candidate = strongAuthority
          || (isoldaAffinity >= joinThreshold
              && isoldaAffinity - bestSideAffinity >= requiredMargin);

      if (debugAffinity) {
        plugin.getLogger().info("Group thread affinity scene=" + capture.sceneId()
            + " player=" + chat.playerName()
            + " isolda=" + isoldaAffinity
            + " side=" + bestSideAffinity
            + (bestSideLine == null ? "" : " side_speaker=" + bestSideLine.playerName())
            + " before_trigger=" + beforeTrigger
            + " bridge=" + explicitBridge
            + " decision=" + (candidate ? "JOIN" : "SIDE"));
      }

      if (!candidate) {
        sideLines.addLast(chat);
        while (sideLines.size() > maxSideLines) sideLines.removeFirst();
        while (!sideLines.isEmpty()
            && chat.timestampMs() - sideLines.peekFirst().timestampMs() > sideLookback) {
          sideLines.removeFirst();
        }
        continue;
      }

      candidateRecords.add(chat);
      candidateIds.add(chat.playerId());
      candidateByName.putIfAbsent(chat.playerName().toLowerCase(Locale.ROOT), chat.playerId());
      participantNames.add(chat.playerName());
      threadText.append(chat.content()).append(' ');
      previousThreadLine = chat;

      // Strong local evidence grants continuity without spending completion tokens on f.
      // Borderline-but-plausible lines remain candidates and let the SAME normal model
      // response confirm them through the tiny f field.
      if (assistantMention || alreadySmart || explicitBridge
          || (isoldaAffinity >= autoThreshold
              && isoldaAffinity - bestSideAffinity >= Math.max(requiredMargin, 16))) {
        autoParticipants.add(chat.playerId());
      }
    }

    List<PublicChatRecord> pre = candidateRecords.stream()
        .filter(chat -> chat.timestampMs() < capture.triggerAt())
        .sorted(Comparator.comparingLong(PublicChatRecord::timestampMs))
        .toList();
    if (pre.size() > maxPre) pre = pre.subList(pre.size() - maxPre, pre.size());
    if (maxPre == 0) pre = List.of();

    return new GroupThreadSelection(
        List.copyOf(pre), Set.copyOf(candidateRecords), Set.copyOf(candidateIds),
        Set.copyOf(autoParticipants), Map.copyOf(candidateByName));
  }

  /**
   * Zero-token local affinity between one public line and Isolda's active thread.
   * This intentionally combines several weak signals instead of relying on a phrase
   * whitelist: participant references, lexical topic overlap, reply shape, deictic
   * language, question/answer compatibility and recency all contribute.
   */
  static int localThreadAffinity(
      String raw,
      String threadText,
      String previousThreadRaw,
      Set<String> participantNames,
      long ageMs,
      boolean beforeTrigger) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return 0;

    int score = 0;
    boolean referencesParticipant = referencesAnyParticipantAlias(raw, participantNames);
    int topic = topicOverlapScore(raw, threadText, participantNames);

    if (referencesParticipant) score += 28;
    score += topic;
    if (looksLikeSocialThreadInterjection(raw)) score += 18;
    boolean replyShape = looksLikeConversationalReplyShape(raw);
    boolean deictic = containsDeicticReference(raw);
    if (replyShape) score += 8;
    if (deictic) score += 12;
    // A deictic/continuation-shaped sentence arriving immediately after the active
    // exchange is often a reply even when it repeats no nouns ("eso no tiene sentido").
    // This is intentionally generic, not a phrase whitelist; side-thread competition
    // below still wins when the same short reply fits another nearby conversation better.
    if (replyShape || deictic) {
      if (ageMs <= 1500L) score += 14;
      else if (ageMs <= 3000L) score += 8;
      else if (ageMs <= 5000L) score += 4;
    }
    score += questionAnswerCompatibility(raw, previousThreadRaw);
    score += recencyAffinity(ageMs);

    if (looksLikeBroadPublicRequest(raw) && !referencesParticipant) score -= 22;
    if (isVeryShortElliptical(raw) && !referencesParticipant && topic == 0
        && questionAnswerCompatibility(raw, previousThreadRaw) == 0) {
      score -= 8;
    }
    if (beforeTrigger) score -= 8;
    return clampInt(score, 0, 100);
  }

  /**
   * Competing affinity with one recent side-chat line. A candidate joins Isolda only
   * when her thread beats this lateral score by a configurable margin.
   */
  static int localLateralAffinity(
      String raw,
      String priorRaw,
      String priorSpeaker,
      long ageMs) {
    String text = normalizeForSearch(raw);
    String prior = normalizeForSearch(priorRaw);
    if (text.isBlank() || prior.isBlank()) return 0;

    int score = 0;
    if (priorSpeaker != null && !priorSpeaker.isBlank()
        && referencesAnyParticipantAlias(raw, Set.of(priorSpeaker))) {
      score += 34;
    }
    score += Math.min(30, topicOverlapScore(raw, priorRaw, Set.of()));
    score += questionAnswerCompatibility(raw, priorRaw);
    if (looksLikeConversationalReplyShape(raw)) score += 8;
    if (containsDeicticReference(raw)) score += 6;
    if (isVeryShortElliptical(raw)) score += 5;
    score += lateralRecencyAffinity(ageMs);
    return clampInt(score, 0, 100);
  }

  static int topicOverlapScore(String a, String b, Set<String> participantNames) {
    Set<String> left = new LinkedHashSet<>(meaningfulTerms(normalizeForSearch(a)));
    Set<String> right = new LinkedHashSet<>(meaningfulTerms(normalizeForSearch(b)));
    if (participantNames != null) {
      for (String name : participantNames) {
        String normalizedName = normalizeForSearch(name);
        if (normalizedName.isBlank()) continue;
        left.remove(normalizedName);
        right.remove(normalizedName);
      }
    }
    left.remove("iso"); left.remove("isolda");
    right.remove("iso"); right.remove("isolda");

    int score = 0;
    for (String l : left) {
      for (String r : right) {
        if (l.equals(r)) {
          score += 12;
          break;
        }
        if (l.length() >= 5 && r.length() >= 5
            && commonPrefixLength(l, r) >= Math.min(5, Math.min(l.length(), r.length()))) {
          score += 7;
          break;
        }
      }
      if (score >= 30) return 30;
    }
    return Math.min(score, 30);
  }

  static boolean looksLikeConversationalReplyShape(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return text.matches("^(?:si|no|nah|pero|porque|pues|igual|tambien|entonces|exacto|claro|obvio|literal|eso|esa|ese|yo|tu|vos|el|ella|ellos|ellas|ustedes)(?:\\s+.*)?$");
  }

  static boolean containsDeicticReference(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return containsAnyWholeWord(text,
        "eso", "esa", "ese", "esto", "aquello", "ahi", "alla", "tambien",
        "igual", "entonces", "el", "ella", "ellos", "ellas", "ustedes");
  }

  static int questionAnswerCompatibility(String replyRaw, String priorRaw) {
    String reply = normalizeForSearch(replyRaw);
    String prior = normalizeForSearch(priorRaw);
    if (reply.isBlank() || prior.isBlank()) return 0;

    boolean priorQuestion = (priorRaw != null && priorRaw.contains("?"))
        || prior.matches("^(?:oye|che|ey|eh)?\\s*(?:que|como|donde|cuando|quien|cual|por que|tienes|tenes|hay|puedes|podes)\\b.*")
        || prior.matches("^(?:oye|che|ey|eh)?\\s*(?:alguien|alguno|alguna|quien)\\b.*\\b(?:tiene|tienen|tenga|hay)\\b.*");
    if (!priorQuestion) return 0;

    int score = 0;
    if (reply.matches("^(?:si|no|nah|claro|obvio|exacto|yo|yo si|yo no)(?:\\s+.*)?$")) score += 18;
    if (prior.matches(".*\\b(?:alguien|quien|alguno|alguna)\\b.*\\b(?:tiene|tienen|tenga|hay)\\b.*")
        && reply.matches("^(?:yo|yo tengo|tengo|aca|aqui|yo si)(?:\\s+.*)?$")) {
      score += 22;
    }
    int topic = topicOverlapScore(replyRaw, priorRaw, Set.of());
    score += Math.min(12, topic);
    return Math.min(score, 34);
  }

  static boolean looksLikeBroadPublicRequest(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return text.matches("^(?:oye|che|ey|eh)?\\s*(?:alguien|alguno|alguna|quien)\\b.*")
        || text.matches("^(?:alguien|alguno|alguna|quien)\\b.*");
  }

  static boolean isVeryShortElliptical(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return true;
    return text.split("\\s+").length <= 3;
  }

  private static int recencyAffinity(long ageMs) {
    if (ageMs <= 1200L) return 14;
    if (ageMs <= 3000L) return 11;
    if (ageMs <= 5000L) return 7;
    if (ageMs <= 8000L) return 3;
    return 0;
  }

  private static int lateralRecencyAffinity(long ageMs) {
    if (ageMs <= 1200L) return 15;
    if (ageMs <= 3000L) return 12;
    if (ageMs <= 5000L) return 8;
    if (ageMs <= 8000L) return 4;
    return 0;
  }

  private static int commonPrefixLength(String a, String b) {
    int max = Math.min(a.length(), b.length());
    int i = 0;
    while (i < max && a.charAt(i) == b.charAt(i)) i++;
    return i;
  }

  private static int clampInt(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private static boolean containsAnyWholeWord(String text, String... words) {
    for (String word : words) {
      if (containsWholeWordIgnoreCase(text, word)) return true;
    }
    return false;
  }

  static boolean looksLikeGroupContinuation(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return looksLikeConversationalReplyShape(raw)
        && !text.matches("^(?:yo tengo|tengo|yo|aca|aqui)$");
  }

  static boolean looksLikeSocialThreadInterjection(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return containsAnyTerm(text,
        "mientes", "mentira", "no es cierto", "dejate", "deja de", "exagera",
        "calmate", "tranquilo", "te creo", "no te creo", "tiene razon",
        "no tiene razon", "estoy de acuerdo", "no estoy de acuerdo", "eso es verdad",
        "que dices", "haces que estas bien", "hacerte el que esta bien");
  }

  static boolean referencesAnyParticipantAlias(String raw, Set<String> participantNames) {
    if (raw == null || raw.isBlank() || participantNames == null || participantNames.isEmpty()) return false;
    String normalized = normalizeForSearch(raw);
    for (String name : participantNames) {
      if (name == null || name.isBlank()) continue;
      if (ContextTargetResolver.mentionsName(normalized, name)) return true;
      String canonical = normalizeForSearch(name).replace(" ", "");
      if (canonical.length() < 6) continue;
      for (String token : normalized.split("\\s+")) {
        // Human group-chat nicknames such as Aminowana -> "Amino" are safe here
        // because the candidate set is already limited to the tiny active thread.
        if (token.length() >= 5 && canonical.startsWith(token)) return true;
      }
    }
    return false;
  }

  static boolean sharesConversationTopic(String a, String b, Set<String> participantNames) {
    return topicOverlapScore(a, b, participantNames) > 0;
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

  private void rememberActiveIdentity(Player player) {
    if (player == null || plugin.getIntegrationManager() == null) return;
    String key = player.getName().toLowerCase(Locale.ROOT);
    if (activeIdentitySnapshots.containsKey(key)) return;
    String identity = plugin.getIntegrationManager().buildAmbientIdentity(player);
    if (!identity.isBlank()) activeIdentitySnapshots.put(key, identity);
  }

  private Map<String, String> snapshotOnlineIdentities(List<String> names) {
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
    Map<String, String> identities = snapshotOnlineIdentities(names);
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
   * A tiny event memory lets questions such as "Iso quién llegó?" work even when
   * the join happened before the normal 8-10 second chat lookback. It is selected
   * only when the current scene semantically refers to a recent event.
   */
  /**
   * Optional relationship-driven autonomous reaction. Disabled by default because
   * every accepted event intentionally spends one normal model request.
   */
  public void maybeQueueRelationshipReaction(String type, String text, List<Player> actors) {
    if (shutdown || plugin.getRelationshipManager() == null || actors == null || actors.isEmpty()) return;
    // If players are already talking to Isolda, the trusted event can be folded into
    // that scene instead of paying for a second reaction.
    if (activeCapture != null || requestInFlight || requestRateRetryTask != null || !sceneQueue.isEmpty()) return;
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
        0);
    sceneQueue.addLast(scene);
    processNextRequest();
  }

  /** Removes the player's rolling scene/log traces from this runtime. */
  /** Clears tiny conversational carry-over after an admin force-changes relationship state. */
  public void clearRelationshipRuntimeContext(UUID playerId) {
    if (playerId != null) {
      smartFollowUpUntilByPlayer.remove(playerId);
      activeAddressers.remove(playerId);
    }
    // Assistant replies can mention the player without a structural UUID, so the
    // safest tiny operation is clearing the 1-2 scene history entries entirely.
    sceneHistory.clear();
  }

  public void purgePlayerFromRuntime(UUID playerId, String playerName) {
    if (playerId == null) return;
    String lower = playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    publicChatLog.removeIf(chat -> playerId.equals(chat.playerId())
        || (!lower.isBlank() && containsWholeWordIgnoreCase(chat.content(), playerName)));
    serverEventLog.removeIf(event -> event.involvedPlayers().stream().anyMatch(name ->
        name != null && (!lower.isBlank() && name.equalsIgnoreCase(playerName))));
    triggerTimes.remove(playerId);
    smartFollowUpUntilByPlayer.remove(playerId);
    activeAddressers.remove(playerId);
    sceneQueue.removeIf(scene -> scene.involvedPlayerIds().contains(playerId)
        || scene.involvedPlayerNames().stream().anyMatch(name ->
            name != null && !lower.isBlank() && name.equalsIgnoreCase(playerName)));
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
      String expandedRawQuery = expandWikiFollowUpQuery(
          rawQuery, directQuery, List.<ChatMessage>of(selection.message()));
      String query = expandWikiAliases(normalizeForSearch(expandedRawQuery));
      Set<String> queryTerms = meaningfulTerms(query);
      if (queryTerms.isEmpty()) {
        if (plugin.getWikiConfig().getBoolean("local-retrieval.debug-log", false)) {
          plugin.getLogger().info("Wiki retrieval skipped=no-meaningful-terms speaker='"
              + selection.message().playerName + "' query='" + directQuery + "'");
        }
        continue;
      }
      List<WikiCandidate> candidates = rankWikiCandidates(query, queryTerms);
      rankedQueries.add(new WikiRankedQuery(selection, directQuery, query, candidates));
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
        String expansion = ranked.query().equals(ranked.directQuery())
            ? "" : " expanded_from='" + ranked.directQuery() + "'";
        plugin.getLogger().info("Wiki retrieval speaker='" + ranked.selection().message().playerName
            + "' query='" + ranked.query() + "'" + expansion + " selected=" + selectedKeys);
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
      if (looksLikeWikiFollowUp(normalized) || looksLikeLikelyWikiKnowledgeRequest(normalized)) {
        newestBySpeaker.put(playerMessage.playerId, new WikiQuerySelection(playerMessage, raw));
        if (newestBySpeaker.size() >= Math.max(1, maxQueries)) break;
      }
    }
    List<WikiQuerySelection> selected = new ArrayList<>(newestBySpeaker.values());
    java.util.Collections.reverse(selected);
    return List.copyOf(selected);
  }

  static boolean looksLikeLikelyWikiKnowledgeRequest(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;

    // Direct observation belongs to local context tools, not the static wiki.
    if (containsAnyTerm(normalized,
        "inventario", "que tengo equipado", "que tiene equipado", "que tiene en la mano",
        "donde esta el jugador", "donde esta aminowana", "donde esta kroattan")) {
      return false;
    }

    return containsAnyTerm(normalized,
        "que es ", "q es ", "que son ", "q son ", "que significa ",
        "para que sirve", "para que se usa", "como funciona",
        "como se craftea", "como crafteo", "como craftear", "crafteo", "craftear",
        "como se hace", "receta", "recetas",
        "como consigo", "como se consigue", "como obtengo", "como se obtiene",
        "de donde consigo", "de donde sale", "donde consigo", "donde se consigue",
        "donde se obtiene", "como obtener", "como conseguir", "como encontrar",
        "donde encuentro", "donde encontrar", "puedo encontrar", "encontrar un ",
        "en que coordenadas", "coordenadas puedo", "coordenadas de",
        "como hago para", "que comandos", "q comandos", "comandos hay",
        "que minerales", "q minerales", "que armas", "que armaduras", "que sets",
        "que raza tiene", "que profesion", "drops", "drop ", "dropea", "droppea",
        "que da el ", "q da el ", "que suelta", "que puede soltar",
        "cuanto cuesta", "cuanto vale");
  }

  private List<WikiCandidate> rankWikiCandidates(String query, Set<String> queryTerms) {
    String meaningfulPhrase = String.join(" ", queryTerms);
    boolean obtainIntent = isWikiObtainIntent(query);
    boolean craftIntent = containsAnyTerm(query, "craftea", "crafteo", "craftear", "receta", "como se hace");
    boolean dropIntent = containsAnyTerm(query, "drops", "drop ", "dropea", "droppea", "que da ", "q da ", "suelta");
    boolean locationIntent = containsAnyTerm(query, "donde", "coordenadas", "encontrar", "encuentro", "aparece", "spawn");

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
      if (obtainIntent) {
        if (key.startsWith("obtain-")) score += 7;
        else if (key.startsWith("ore-") || key.startsWith("spawn-")) score += 3;
      }
      if (craftIntent && key.startsWith("crafting-")) score += 7;
      // Never let a completely unrelated spawn page win only because the question
      // contained "donde/coordenadas". It must already share a real subject term.
      if (locationIntent && key.startsWith("spawn-") && score > 0) score += 6;
      if (dropIntent) {
        if (key.startsWith("mob-") || key.startsWith("mobs-")) score += 3;
        if (indexed.normalizedContent().contains("drops")) score += 3;
      }

      if (score > 0) {
        candidates.add(new WikiCandidate(indexed.key(), indexed.description(), indexed.content(), score));
      }
    }
    candidates.sort(Comparator.comparingInt(WikiCandidate::score).reversed());
    return List.copyOf(candidates);
  }

  private static Set<String> wikiEntityTerms(Set<String> queryTerms) {
    LinkedHashSet<String> entity = new LinkedHashSet<>();
    if (queryTerms == null) return entity;
    Set<String> intentNoise = Set.of(
        "drops", "drop", "dropea", "droppea", "suelta", "coordenadas", "coordenada",
        "puedo", "puede", "pueden", "miniboss", "boss", "jefe", "raros", "raro");
    for (String term : queryTerms) {
      if (!intentNoise.contains(term)) entity.add(term);
    }
    return entity;
  }

  static boolean allTermsMatchKey(Set<String> terms, String normalizedKey, Set<String> keyTokens) {
    if (terms == null || terms.isEmpty()) return false;
    for (String term : terms) {
      if (containsWholeWordIgnoreCase(normalizedKey, term)) continue;
      if (bestFuzzyTokenScore(term, keyTokens) > 0) continue;
      return false;
    }
    return true;
  }

  private static boolean isWikiObtainIntent(String query) {
    return containsAnyTerm(query,
        "como consigo", "como se consigue", "como obtengo", "como se obtiene",
        "de donde consigo", "de donde sale", "donde consigo", "donde se consigue",
        "donde se obtiene", "como obtener", "como conseguir", "como encontrar");
  }

  private static String expandWikiAliases(String query) {
    if (query == null || query.isBlank()) return "";
    return query
        .replace("mini jefe", "miniboss")
        .replace("mini-jefe", "miniboss")
        .replace("mini boss", "miniboss")
        .replace("necrodito", "necrotido");
  }

  private static Set<String> tokenSet(String text) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    if (text == null) return tokens;
    for (String token : text.split("\\s+")) {
      if (token.length() >= 3) tokens.add(token);
    }
    return tokens;
  }

  static int bestFuzzyTokenScore(String queryTerm, Set<String> candidateTokens) {
    if (queryTerm == null || queryTerm.length() < 6 || candidateTokens == null) return 0;
    double best = 0.0;
    for (String token : candidateTokens) {
      if (token == null || token.length() < 5) continue;
      if (queryTerm.charAt(0) != token.charAt(0)) continue;
      if (commonPrefixLength(queryTerm, token) < 2) continue;
      if (Math.abs(queryTerm.length() - token.length()) > 4) continue;
      best = Math.max(best, lcsSimilarity(queryTerm, token));
    }
    if (best >= 0.86) return 5;
    if (best >= 0.74) return 4;
    return 0;
  }

  private static double lcsSimilarity(String a, String b) {
    if (a.isEmpty() || b.isEmpty()) return 0.0;
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int i = 1; i <= a.length(); i++) {
      java.util.Arrays.fill(current, 0);
      for (int j = 1; j <= b.length(); j++) {
        if (a.charAt(i - 1) == b.charAt(j - 1)) current[j] = previous[j - 1] + 1;
        else current[j] = Math.max(previous[j], current[j - 1]);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return (2.0 * previous[b.length()]) / (a.length() + b.length());
  }

  private String expandWikiFollowUpQuery(
      String rawQuery,
      String normalizedDirectQuery,
      List<ChatMessage> currentMessages) {

    if (!looksLikeWikiFollowUp(normalizedDirectQuery) || sceneHistory.isEmpty()) {
      return rawQuery;
    }

    SceneMemory previous = sceneHistory.peekLast();
    if (previous == null || !previous.wikiContextUsed()) {
      return rawQuery;
    }

    UUID currentSpeaker = lastPlayerId(currentMessages);
    if (currentSpeaker == null) return rawQuery;

    boolean speakerWasPresent = previous.messages().stream().anyMatch(message ->
        message instanceof PlayerChatMessage playerMessage
            && currentSpeaker.equals(playerMessage.playerId));
    if (!speakerWasPresent) return rawQuery;

    StringBuilder expanded = new StringBuilder(rawQuery == null ? "" : rawQuery.trim());
    Set<UUID> previousSpeakers = new LinkedHashSet<>();
    for (ChatMessage message : previous.messages()) {
      if (message instanceof PlayerChatMessage playerMessage) {
        previousSpeakers.add(playerMessage.playerId);
        if (currentSpeaker.equals(playerMessage.playerId)) {
          expanded.append(' ').append(playerMessage.content);
        }
      }
    }

    // The visible assistant reply is a useful referent seed only when that old scene
    // had one speaker. In a multi-speaker scene the joined reply could contain another
    // player's answer and would contaminate this speaker's follow-up retrieval.
    String previousReply = previous.assistantReply();
    if (previousSpeakers.size() == 1 && previousReply != null && !previousReply.isBlank()) {
      String compactReply = previousReply.length() > 420
          ? previousReply.substring(0, 420)
          : previousReply;
      expanded.append(' ').append(compactReply);
    }
    return expanded.toString().trim();
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

  private static boolean looksLikeWikiFollowUp(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    Set<String> terms = meaningfulTerms(normalized);
    if (terms.size() > 2) return false;

    return normalized.contains(" eso")
        || normalized.contains(" esa")
        || normalized.contains(" ese")
        || normalized.contains(" esto")
        || normalized.startsWith("y ")
        || normalized.contains(" como se craftea")
        || normalized.startsWith("como se craftea")
        || normalized.contains(" como lo crafteo")
        || normalized.contains(" de donde consigo")
        || normalized.startsWith("de donde consigo")
        || normalized.contains(" como consigo")
        || normalized.startsWith("como consigo")
        || normalized.contains(" donde lo consigo")
        || normalized.contains(" donde se consigue")
        || normalized.contains(" como se hace")
        || normalized.startsWith("como se hace");
  }

  private static boolean isObviousSocialSmallTalk(String normalized) {
    if (normalized == null || normalized.isBlank()) return true;
    String q = " " + normalized + " ";
    return q.contains(" como estas ")
        || q.contains(" como andas ")
        || q.contains(" como te va ")
        || q.contains(" todo bien ")
        || q.contains(" que tal iso ")
        || q.contains(" que tal isolda ")
        || q.contains(" que opinas de mi ")
        || q.contains(" que opinas de ")
        || q.contains(" que es ") && q.contains(" de ti ")
        || q.contains(" como te cae ")
        || q.contains(" como te llevas con ")
        || q.contains(" que sientes por ")
        || q.contains(" quien es tu pareja ")
        || q.contains(" tienes pareja ")
        || q.contains(" por que me tratas ")
        || q.contains(" porque me tratas ")
        || q.contains(" pq me tratas ")
        || q.contains(" por que eres mala conmigo ")
        || q.contains(" pq eres mala conmigo ")
        || q.contains(" eres mala conmigo ")
        || q.contains(" eres malo conmigo ")
        || q.contains(" nuestro amor ")
        || q.contains(" tu amor ")
        || q.contains(" me quieres ")
        || q.contains(" te quiero ")
        || q.contains(" me gustas ")
        || q.contains(" no me hagas llorar ")
        || q.contains(" mi ex me trataba ")
        || q.contains(" buena charla ")
        || q.contains(" hola iso ")
        || q.contains(" hola isolda ")
        || q.contains(" holaa iso ")
        || q.contains(" holaa isolda ")
        || q.trim().equals("hola")
        || q.trim().equals("holi")
        || q.trim().equals("holaa")
        || q.trim().equals("buenas");
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

  static boolean looksLikeExplicitGroupBridge(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return text.matches("^(?:contale|cuentale|decile|dile|preguntale|respond(?:e|ele)|avisale|explicale|mostrale|muestrale)(?:\\s+.*)?$")
        || text.matches("^(?:si|no|exacto|tal cual|eso mismo)\\s+(?:iso|isolda)(?:\\s+.*)?$");
  }

  static String normalizeForSearch(String input) {
    String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}_-]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
    return normalized;
  }

  private static boolean containsAllTerms(String haystack, Set<String> terms) {
    if (haystack == null || haystack.isBlank() || terms == null || terms.isEmpty()) return false;
    for (String term : terms) {
      if (!containsWholeWordIgnoreCase(haystack, term)) return false;
    }
    return true;
  }

  private static Set<String> meaningfulTerms(String text) {
    Set<String> stop = Set.of(
        "que", "como", "donde", "cuando", "quien", "para", "por", "con", "una", "uno", "unos",
        "unas", "del", "las", "los", "eso", "esta", "este", "esto", "soy", "eres", "hay", "iso",
        "isolda", "se", "un", "de", "en", "mi", "mis", "su", "sus", "es", "son", "quiero",
        "craftea", "craftear", "crafteo", "craft", "consigo", "consigue", "conseguir",
        "obtengo", "obtiene", "obtener", "encuentro", "encontrar", "sirve", "sirven", "the", "and", "what", "where", "how", "when",
        "you", "your");
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

    // A direct mention must never disappear because a small model returned m=[].
    // Recover locally from already-selected trusted wiki context when possible; otherwise
    // emit one honest generic acknowledgement. This costs ZERO additional API requests.
    if (response.historyText().isBlank() && response.getToolCalls().isEmpty() && isDirectMentionScene(scene)) {
      List<String> guaranteed = buildGuaranteedDirectReplies(scene);
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
    List<String> factualCoverage = buildMissingIndependentKnowledgeReplies(scene, response.getMessages());
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

    // Direct/smart addressers are known locally. For a true group exchange the same
    // single model response may additionally mark CURRENT related speakers in `f`.
    // This tiny metadata field avoids a second classifier/API request while preventing
    // unrelated public chatter from inheriting a SMART follow-up window.
    Set<UUID> conversationParticipants = resolveConversationParticipants(scene, response);

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
    if (!reply.isBlank() && isDuplicateSmartFollowUpReply(scene, reply)) {
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
    long baseFollowUpMs = Math.max(plugin.getConfig().getLong(
        "global-conversation.smart-follow-up-ms", 12_000L), 0L);
    long now = System.currentTimeMillis();
    pruneSmartFollowUps(now);
    if (!reply.isBlank()) {
      for (UUID playerId : conversationParticipants) {
        long followUpMs = plugin.getRelationshipManager() == null
            ? baseFollowUpMs
            : plugin.getRelationshipManager().followUpMs(playerId, baseFollowUpMs);
        if (followUpMs > 0L) smartFollowUpUntilByPlayer.put(playerId, now + followUpMs);
        else smartFollowUpUntilByPlayer.remove(playerId);
      }
    }

    processNextRequest();
  }

  private Set<UUID> resolveConversationParticipants(SceneRequest scene, AssistantResponse response) {
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

  private boolean isDirectMentionScene(SceneRequest scene) {
    return scene != null && scene.context() != null && scene.context().sceneMeta() != null
        && scene.context().sceneMeta().contains("trigger=direct_mention");
  }

  private List<String> buildMissingIndependentKnowledgeReplies(
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
      if (block.isBlank()) continue;
      // CORE requires independent factual replies in multi-speaker scenes to prefix
      // the exact speaker name. A shared/group reply intentionally has no such
      // requirement and must not trigger fake per-player coverage.
      if (responseCoversSpeaker(modelReplies, speaker.playerName)) continue;

      String answer = extractWikiFallbackAnswer(speaker.content, block);
      if (answer.isBlank()) {
        answer = block.toLowerCase(Locale.ROOT).contains("result=no_match")
            ? "no tengo ese dato concreto y no voy a inventarlo"
            : "no tengo una respuesta clara para ese dato";
      }
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

  private List<String> buildGuaranteedDirectReplies(SceneRequest scene) {
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
      String answer = extractWikiFallbackAnswer(speaker.content, block);
      if (answer.isBlank()) {
        answer = block.toLowerCase(Locale.ROOT).contains("result=no_match")
            ? "no tengo ese dato concreto y no voy a inventarlo"
            : "no tengo una respuesta clara para ese dato";
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
    String query = expandWikiAliases(normalizeForSearch(rawQuestion));
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

  private boolean isDuplicateSmartFollowUpReply(SceneRequest scene, String reply) {
    if (scene == null || reply == null || reply.isBlank() || sceneHistory.isEmpty()) return false;
    String meta = scene.context() == null ? "" : scene.context().sceneMeta();
    if (meta == null || !meta.contains("trigger=smart_followup")) return false;
    SceneMemory previous = sceneHistory.peekLast();
    if (previous == null || previous.assistantReply() == null || previous.assistantReply().isBlank()) return false;
    String current = me.kev.sva.chat.tools.ToolManager.normalize(reply);
    String last = me.kev.sva.chat.tools.ToolManager.normalize(previous.assistantReply());
    return !current.isBlank() && current.equals(last);
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
      List<String> involvedPlayers,
      Map<String, String> playerIdentities) { }

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

  private record SceneMemory(
      List<ChatMessage> messages,
      String assistantReply,
      boolean wikiContextUsed) { }

  private record WikiQuerySelection(PlayerChatMessage message, String rawQuery) { }

  private record WikiRankedQuery(
      WikiQuerySelection selection,
      String directQuery,
      String query,
      List<WikiCandidate> candidates) { }

  private record WikiCandidate(String key, String description, String content, int score) { }
  private record WikiIndexEntry(
      String key,
      String description,
      String content,
      String normalizedKey,
      String normalizedDescription,
      String normalizedContent) { }

  private record GroupThreadSelection(
      List<PublicChatRecord> preCandidates,
      Set<PublicChatRecord> candidateRecords,
      Set<UUID> candidateIds,
      Set<UUID> autoParticipantIds,
      Map<String, UUID> candidateByName) {
    static GroupThreadSelection empty() {
      return new GroupThreadSelection(List.of(), Set.of(), Set.of(), Set.of(), Map.of());
    }
  }

  private record SceneRequest(
      long sceneId,
      List<ChatMessage> messages,
      List<ChatMessage> currentSceneMessages,
      Set<UUID> involvedPlayerIds,
      Set<String> involvedPlayerNames,
      Set<UUID> followUpEligiblePlayerIds,
      Set<UUID> groupParticipantCandidateIds,
      Set<UUID> autoGroupParticipantIds,
      Map<String, UUID> groupParticipantCandidatesByName,
      AssistantRequestContext context,
      String currentActionText,
      int providerIndex,
      int retryCount) {

    SceneRequest withProvider(int newProvider) {
      return new SceneRequest(
          sceneId, messages, currentSceneMessages, involvedPlayerIds, involvedPlayerNames,
          followUpEligiblePlayerIds, groupParticipantCandidateIds, autoGroupParticipantIds,
          groupParticipantCandidatesByName, context, currentActionText, newProvider, 0);
    }

    SceneRequest withRetry(int newRetryCount) {
      return new SceneRequest(
          sceneId, messages, currentSceneMessages, involvedPlayerIds, involvedPlayerNames,
          followUpEligiblePlayerIds, groupParticipantCandidateIds, autoGroupParticipantIds,
          groupParticipantCandidatesByName, context, currentActionText, providerIndex, newRetryCount);
    }
  }
}
