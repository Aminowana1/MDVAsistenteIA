package me.kev.sva.memory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.RelationshipUpdate;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;

/**
 * Persistent per-player relationship state with a RAM cache and asynchronous writes.
 *
 * <p>Normal reads never touch SQLite. The model may propose a compact update in the
 * same response it already produces; Java validates/clamps it before changing the
 * cache and queues the durable write on one daemon thread.</p>
 */
public final class RelationshipManager {
  private final ServerAssistantPlugin plugin;
  private final File databaseFile;
  private final Map<UUID, Profile> profiles = new HashMap<>();
  private final Map<UUID, List<Memory>> memories = new HashMap<>();
  private final Map<String, UUID> nameIndex = new HashMap<>();
  private final Map<UUID, Deque<DeltaStamp>> positiveRecent = new HashMap<>();
  private final Map<UUID, Deque<DeltaStamp>> negativeRecent = new HashMap<>();
  // Blocks stale in-flight scene results from recreating data after an admin purge.
  // The marker is cleared only by a genuinely new player observation (join/chat).
  private final Set<UUID> purgedUntilNextObservation = new HashSet<>();
  private final ExecutorService databaseExecutor;
  private volatile boolean shutdown;
  private long lastReactionAt;
  private final Deque<Long> reactionTimes = new ArrayDeque<>();

  public RelationshipManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    this.databaseFile = new File(plugin.getDataFolder(), databaseFileName());

    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "ServerAssistant-RelationshipDB");
      thread.setDaemon(true);
      return thread;
    };
    this.databaseExecutor = Executors.newSingleThreadExecutor(factory);

    try {
      Class.forName("org.sqlite.JDBC");
      initializeDatabase();
      loadCache();
      enforceRomanceCapacity();
      plugin.getLogger().info("Relationship cache loaded: " + profiles.size() + " players, "
          + memories.values().stream().mapToInt(List::size).sum() + " memories.");
    } catch (Exception ex) {
      throw new IllegalStateException("Could not initialize relationship database", ex);
    }
  }

  public void reloadSettings() {
    pruneExpiredMemories(System.currentTimeMillis(), true);
    enforceRomanceCapacity();
  }

  public void observePlayer(Player player) {
    if (!enabled() || player == null) return;
    purgedUntilNextObservation.remove(player.getUniqueId());
    Profile profile = profile(player.getUniqueId(), player.getName());
    if (!profile.lastName.equals(player.getName())) {
      nameIndex.remove(profile.lastName.toLowerCase(Locale.ROOT));
      profile.lastName = player.getName();
      nameIndex.put(profile.lastName.toLowerCase(Locale.ROOT), profile.uuid);
      profile.updatedAt = System.currentTimeMillis();
      persistProfile(profile);
    }
  }

  /** Builds only the small relevant subset used by the current scene. */
  public String buildContext(List<ChatMessage> currentMessages, Set<String> involvedNames) {
    if (!enabled()) return "";

    LinkedHashMap<UUID, String> relevant = new LinkedHashMap<>();
    if (currentMessages != null) {
      for (ChatMessage message : currentMessages) {
        if (message instanceof PlayerChatMessage playerMessage) {
          relevant.putIfAbsent(playerMessage.playerId, playerMessage.playerName);
        }
      }
    }

    if (involvedNames != null) {
      for (String name : involvedNames) {
        UUID uuid = resolveByName(name);
        if (uuid != null) relevant.putIfAbsent(uuid, name);
      }
    }

    int maxPlayers = Math.max(config().getInt("context.max-players", 2), 1);
    int persistentLimit = Math.max(config().getInt("context.persistent-memories-per-player", 2), 0);
    int recentLimit = Math.max(config().getInt("context.recent-memories-per-player", 2), 0);
    int maxChars = Math.max(config().getInt("context.max-chars", 1800), 400);
    long now = System.currentTimeMillis();

    StringBuilder out = new StringBuilder();
    int count = 0;
    for (Map.Entry<UUID, String> entry : relevant.entrySet()) {
      if (count++ >= maxPlayers) break;
      Profile profile = profile(entry.getKey(), entry.getValue());
      Tier tier = tierFor(profile.score);
      RomanceAvailability romance = romanceAvailability(profile);

      if (!out.isEmpty()) out.append('\n');
      out.append("player=").append(profile.lastName)
          .append(" score=").append(profile.score)
          .append(" tier=").append(tier.id)
          .append(" romance=").append(profile.romantic ? "partner" : "none")
          .append(" can_start_romance=").append(romance.allowed)
          .append(" romance_reason=").append(romance.reason)
          .append(" partners=").append(romance.currentPartners).append('/').append(romance.maxPartners)
          .append('\n');
      if (!tier.behavior.isBlank()) {
        out.append("behavior=").append(compact(tier.behavior, 220)).append('\n');
      }
      if (profile.romantic) {
        String partnerBehavior = config().getString("romance.partner-behavior", "");
        if (partnerBehavior != null && !partnerBehavior.isBlank()) {
          out.append("romance_behavior=").append(compact(partnerBehavior, 260)).append('\n');
        }
      } else if ("capacity-full".equals(romance.reason)) {
        String fullBehavior = config().getString("romance.capacity-full-behavior", "");
        if (fullBehavior != null && !fullBehavior.isBlank()) {
          out.append("romance_rule=").append(compact(fullBehavior, 240)).append('\n');
        }
      } else if ("score-too-low".equals(romance.reason)) {
        String lowScoreBehavior = config().getString("romance.below-threshold-behavior", "");
        if (lowScoreBehavior != null && !lowScoreBehavior.isBlank()) {
          out.append("romance_rule=").append(compact(lowScoreBehavior, 240)).append('\n');
        }
      } else if ("disabled".equals(romance.reason)) {
        String disabledBehavior = config().getString("romance.disabled-behavior", "");
        if (disabledBehavior != null && !disabledBehavior.isBlank()) {
          out.append("romance_rule=").append(compact(disabledBehavior, 220)).append('\n');
        }
      }

      pruneExpiredMemoriesFor(profile.uuid, now, true);
      List<Memory> playerMemories = memories.getOrDefault(profile.uuid, List.of());
      appendMemorySubset(out, playerMemories, MemoryKind.PERSISTENT, persistentLimit);
      appendMemorySubset(out, playerMemories, MemoryKind.RECENT, recentLimit);
      if (out.length() >= maxChars) break;
    }

    if (out.length() > maxChars) {
      return out.substring(0, Math.max(0, maxChars - 15)).trim() + "\n[truncated]";
    }
    return out.toString().trim();
  }

  /**
   * Applies model-proposed updates and, if GPT omitted them, one conservative local
   * fallback signal. This preserves the one-call architecture: the fallback is pure
   * Java and spends zero additional model tokens.
   */
  public void applySceneUpdates(
      List<RelationshipUpdate> updates,
      List<ChatMessage> currentMessages,
      Set<UUID> relationshipEligiblePlayerIds,
      String assistantReply) {

    if (!enabled() || !config().getBoolean("updates.enabled", true)) return;

    Map<String, PlayerChatMessage> speakers = new LinkedHashMap<>();
    if (currentMessages != null) {
      for (ChatMessage message : currentMessages) {
        if (message instanceof PlayerChatMessage playerMessage) {
          speakers.put(playerMessage.playerName.toLowerCase(Locale.ROOT), playerMessage);
        }
      }
    }
    if (speakers.isEmpty()) return;

    Set<UUID> explicitPartnershipProposals = new HashSet<>();
    if (currentMessages != null) {
      for (ChatMessage message : currentMessages) {
        if (message instanceof PlayerChatMessage playerMessage
            && RelationshipSignalDetector.isExplicitPartnershipProposal(playerMessage.content)) {
          explicitPartnershipProposals.add(playerMessage.playerId);
        }
      }
    }
    boolean visiblePartnershipAcceptance = RelationshipSignalDetector.replyClearlyAcceptsPartnership(assistantReply);

    Map<UUID, RelationshipUpdate> localSignals = new LinkedHashMap<>();
    if (config().getBoolean("updates.local-fallback.enabled", true) && currentMessages != null) {
      List<ChatMessage> newestFirst = new ArrayList<>(currentMessages);
      java.util.Collections.reverse(newestFirst);
      for (ChatMessage message : newestFirst) {
        if (!(message instanceof PlayerChatMessage playerMessage)) continue;
        if (relationshipEligiblePlayerIds == null || !relationshipEligiblePlayerIds.contains(playerMessage.playerId)) continue;
        if (localSignals.containsKey(playerMessage.playerId)) continue;
        RelationshipUpdate signal = RelationshipSignalDetector.detect(playerMessage.playerName, playerMessage.content);
        if (signal != null) localSignals.put(playerMessage.playerId, signal);
      }
    }

    boolean logRejected = config().getBoolean("debug.log-rejected-updates", false);
    int maxUpdates = Math.max(config().getInt("updates.max-per-response", 1), 0);
    if (maxUpdates == 0) return;

    int applied = 0;
    Set<UUID> modelHandled = new HashSet<>();
    if (updates != null) {
      for (RelationshipUpdate requested : updates) {
        if (requested == null || applied >= maxUpdates) break;
        PlayerChatMessage speaker = speakers.get(requested.playerName().toLowerCase(Locale.ROOT));
        if (speaker == null) {
          if (logRejected) plugin.getLogger().warning(
              "Rejected relationship update for non-current speaker: " + requested.playerName());
          continue;
        }
        if (relationshipEligiblePlayerIds == null || relationshipEligiblePlayerIds.isEmpty()
            || !relationshipEligiblePlayerIds.contains(speaker.playerId)) {
          if (logRejected) plugin.getLogger().warning(
              "Rejected relationship update for context-only player: " + speaker.playerName);
          continue;
        }
        if (purgedUntilNextObservation.contains(speaker.playerId)) {
          if (logRejected) plugin.getLogger().warning(
              "Rejected stale relationship update after purge for " + speaker.playerName);
          continue;
        }
        modelHandled.add(speaker.playerId);
        RelationshipUpdate effective = mergeWithLocalSignal(requested, localSignals.get(speaker.playerId));
        if ((effective.romanceAction() == null || effective.romanceAction().isBlank())
            && explicitPartnershipProposals.contains(speaker.playerId)
            && visiblePartnershipAcceptance) {
          effective = new RelationshipUpdate(
              effective.playerName(), effective.delta(), effective.memoryKind(), effective.importance(),
              effective.memory(), "start");
        }
        if (applyOne(effective, speaker, "model",
            explicitPartnershipProposals.contains(speaker.playerId), visiblePartnershipAcceptance)) applied++;
      }
    }

    if (applied >= maxUpdates || !config().getBoolean("updates.local-fallback.enabled", true)) return;

    // Scan newest-to-oldest so a trigger such as a bare "isolda?" can still pick up
    // the meaningful line just before it (for example "quieres tener una cita?").
    List<ChatMessage> reversed = currentMessages == null ? List.of() : new ArrayList<>(currentMessages);
    java.util.Collections.reverse(reversed);
    Set<UUID> fallbackTried = new HashSet<>();
    for (ChatMessage message : reversed) {
      if (applied >= maxUpdates) break;
      if (!(message instanceof PlayerChatMessage speaker)) continue;
      if (relationshipEligiblePlayerIds == null || relationshipEligiblePlayerIds.isEmpty()
          || !relationshipEligiblePlayerIds.contains(speaker.playerId)) continue;
      if (modelHandled.contains(speaker.playerId) || !fallbackTried.add(speaker.playerId)) continue;
      if (purgedUntilNextObservation.contains(speaker.playerId)) continue;

      RelationshipUpdate fallback = localSignals.get(speaker.playerId);
      if (fallback == null) {
        fallbackTried.remove(speaker.playerId);
        continue;
      }
      if (explicitPartnershipProposals.contains(speaker.playerId)
          && visiblePartnershipAcceptance) {
        fallback = new RelationshipUpdate(
            fallback.playerName(), fallback.delta(), "p", Math.max(4, fallback.importance()),
            "Acordaron iniciar una relacion romantica", "start");
      }
      if (applyOne(fallback, speaker, "local-fallback",
          explicitPartnershipProposals.contains(speaker.playerId), visiblePartnershipAcceptance)) applied++;
    }
  }

  /** Backward-compatible entry point used by older code/tests. */
  public void applyUpdates(List<RelationshipUpdate> updates, List<ChatMessage> currentMessages) {
    Set<UUID> eligible = new HashSet<>();
    if (currentMessages != null) {
      for (ChatMessage message : currentMessages) {
        if (message instanceof PlayerChatMessage playerMessage) eligible.add(playerMessage.playerId);
      }
    }
    applySceneUpdates(updates, currentMessages, eligible, "");
  }

  private static RelationshipUpdate mergeWithLocalSignal(
      RelationshipUpdate model,
      RelationshipUpdate local) {
    if (model == null) return local;
    if (local == null) return model;

    int delta = model.delta() == 0 ? local.delta() : model.delta();
    String kind = model.memoryKind();
    String memory = model.memory();
    int importance = model.importance();
    if ((memory == null || memory.isBlank()) && local.memory() != null && !local.memory().isBlank()) {
      kind = local.memoryKind();
      memory = local.memory();
      importance = Math.max(importance, local.importance());
    }
    return new RelationshipUpdate(
        model.playerName(), delta, kind, importance, memory, model.romanceAction());
  }

  private boolean applyOne(
      RelationshipUpdate requested,
      PlayerChatMessage speaker,
      String source,
      boolean explicitPartnershipProposal,
      boolean visiblePartnershipAcceptance) {
    Profile profile = profile(speaker.playerId, speaker.playerName);
    long now = System.currentTimeMillis();
    boolean logRejected = config().getBoolean("debug.log-rejected-updates", false);
    int maxAbs = Math.max(config().getInt("updates.max-absolute-delta", 5), 0);
    int delta = Math.max(-maxAbs, Math.min(maxAbs, requested.delta()));

    // Repetitive praise/insults should not allow instant farming. Positive and
    // negative directions have independent cooldowns and rolling hourly caps.
    if (delta > 0 && !allowedDirectionalChange(profile.uuid, delta, true, now)) {
      if (logRejected) plugin.getLogger().info(
          "Relationship positive delta blocked by cooldown/hour cap for " + profile.lastName);
      delta = 0;
    }
    if (delta < 0 && !allowedDirectionalChange(profile.uuid, -delta, false, now)) {
      if (logRejected) plugin.getLogger().info(
          "Relationship negative delta blocked by cooldown/hour cap for " + profile.lastName);
      delta = 0;
    }

    int oldScore = profile.score;
    if (delta != 0) {
      profile.score = clampScore(profile.score + delta);
      profile.updatedAt = now;
      if (delta > 0) profile.lastPositiveAt = now;
      if (delta < 0) profile.lastNegativeAt = now;
      rememberDelta(profile.uuid, Math.abs(delta), delta > 0, now);
    }

    String memoryText = sanitizeMemory(requested.memory());
    MemoryKind memoryKind = parseMemoryKind(requested.memoryKind());
    int importance = Math.max(1, Math.min(5, requested.importance()));
    boolean memoryAdded = false;
    if (!memoryText.isBlank() && memoryKind != null) {
      if (memoryKind == MemoryKind.PERSISTENT
          && importance < Math.max(config().getInt("memories.persistent-min-importance", 4), 1)) {
        memoryKind = MemoryKind.RECENT;
      }
      memoryAdded = addMemory(profile, memoryKind, memoryText, importance, now);
    }

    boolean romanceChanged = applyRomanceAction(
        profile, requested.romanceAction(), speaker, now,
        explicitPartnershipProposal, visiblePartnershipAcceptance);
    if (delta != 0 || romanceChanged) persistProfile(profile);

    if (config().getBoolean("debug.log-updates", false) && (delta != 0 || memoryAdded || romanceChanged)) {
      plugin.getLogger().info("Relationship update[" + source + "] " + profile.lastName + ": " + oldScore + " -> "
          + profile.score + ", memory=" + (memoryAdded ? memoryKind : "none") + ", romance=" + profile.romantic);
    }
    return delta != 0 || memoryAdded || romanceChanged;
  }

  /**
   * Last-resort zero-token dialogue guard. Java already rejects an illegal romance
   * state change; this prevents a small model from verbally saying "yes" anyway when
   * romance is disabled, trust is too low, or the configured partner capacity is full.
   */
  public String guardRomanceReply(
      String reply,
      List<ChatMessage> currentMessages,
      Set<UUID> relationshipEligiblePlayerIds) {
    if (!enabled() || reply == null || reply.isBlank()
        || !RelationshipSignalDetector.replyClearlyAcceptsPartnership(reply)
        || currentMessages == null || relationshipEligiblePlayerIds == null
        || relationshipEligiblePlayerIds.isEmpty()) {
      return reply == null ? "" : reply;
    }

    List<ChatMessage> newestFirst = new ArrayList<>(currentMessages);
    java.util.Collections.reverse(newestFirst);
    for (ChatMessage message : newestFirst) {
      if (!(message instanceof PlayerChatMessage speaker)) continue;
      if (!relationshipEligiblePlayerIds.contains(speaker.playerId)) continue;
      if (!RelationshipSignalDetector.isExplicitPartnershipProposal(speaker.content)) continue;

      Profile profile = profile(speaker.playerId, speaker.playerName);
      if (profile.romantic) return reply; // accepted legally in this scene or already partner.
      RomanceAvailability availability = romanceAvailability(profile);
      String path = switch (availability.reason) {
        case "capacity-full" -> "romance.guard-replies.capacity-full";
        case "disabled" -> "romance.guard-replies.disabled";
        case "score-too-low" -> "romance.guard-replies.score-too-low";
        default -> "";
      };
      if (path.isBlank()) return reply;
      String guarded = config().getString(path, "");
      if (guarded == null || guarded.isBlank()) return reply;
      if (config().getBoolean("debug.log-rejected-updates", false)) {
        plugin.getLogger().info("Replaced illegal romance acceptance for " + profile.lastName
            + " reason=" + availability.reason);
      }
      return guarded.trim();
    }
    return reply;
  }

  public boolean shouldIgnoreDirectMessage(Player player, String content) {
    if (!enabled() || player == null || !config().getBoolean("behavior.ignore.enabled", true)) return false;
    Profile profile = profile(player.getUniqueId(), player.getName());
    if (profile.score >= 0) return false;

    if (config().getBoolean("behavior.ignore.only-trivial-messages", true) && !looksTrivial(content)) {
      return false;
    }

    double chance = ignoreChance(profile.score);
    return chance > 0.0 && ThreadLocalRandom.current().nextDouble() < chance;
  }

  public long followUpMs(UUID playerId, long baseMs) {
    if (!enabled() || playerId == null || !config().getBoolean("behavior.dynamic-follow-up.enabled", true)) {
      return baseMs;
    }
    Profile profile = profiles.get(playerId);
    if (profile == null) return baseMs;
    Tier tier = tierFor(profile.score);
    long configured = config().getLong("behavior.dynamic-follow-up.by-tier." + tier.id, Long.MIN_VALUE);
    return configured == Long.MIN_VALUE ? baseMs : Math.max(0L, configured);
  }

  /** Decide locally whether an event is worth spending one spontaneous AI request. */
  public boolean shouldReactToEvent(String type, List<Player> actors) {
    if (!enabled() || !config().getBoolean("event-reactions.enabled", false)) return false;
    if (actors == null || actors.isEmpty()) return false;
    List<String> allowedTypes = config().getStringList("event-reactions.event-types");
    if (!allowedTypes.isEmpty() && (type == null || allowedTypes.stream().noneMatch(type::equalsIgnoreCase))) return false;

    int strongest = 0;
    for (Player actor : actors) {
      if (actor == null) continue;
      Profile profile = profile(actor.getUniqueId(), actor.getName());
      if (Math.abs(profile.score) > Math.abs(strongest)) strongest = profile.score;
    }

    int threshold = Math.max(config().getInt("event-reactions.minimum-absolute-score", 70), 0);
    if (Math.abs(strongest) < threshold) return false;

    long now = System.currentTimeMillis();
    long cooldownMs = Math.max(config().getLong("event-reactions.cooldown-seconds", 180L), 0L) * 1000L;
    if (now - lastReactionAt < cooldownMs) return false;

    long hourAgo = now - 3_600_000L;
    while (!reactionTimes.isEmpty() && reactionTimes.peekFirst() < hourAgo) reactionTimes.removeFirst();
    int maxPerHour = Math.max(config().getInt("event-reactions.max-per-hour", 3), 0);
    if (maxPerHour == 0 || reactionTimes.size() >= maxPerHour) return false;

    double chance = Math.max(0.0, Math.min(1.0, config().getDouble("event-reactions.chance", 0.25)));
    if (ThreadLocalRandom.current().nextDouble() >= chance) return false;

    lastReactionAt = now;
    reactionTimes.addLast(now);
    return true;
  }

  public String eventRelationshipContext(List<Player> actors) {
    if (actors == null || actors.isEmpty()) return "";
    LinkedHashMap<String, UUID> names = new LinkedHashMap<>();
    for (Player actor : actors) if (actor != null) names.put(actor.getName(), actor.getUniqueId());
    StringBuilder out = new StringBuilder();
    int max = Math.max(config().getInt("context.max-players", 2), 1);
    int count = 0;
    for (Map.Entry<String, UUID> entry : names.entrySet()) {
      if (count++ >= max) break;
      Profile profile = profile(entry.getValue(), entry.getKey());
      Tier tier = tierFor(profile.score);
      if (!out.isEmpty()) out.append('\n');
      out.append("player=").append(profile.lastName).append(" score=").append(profile.score)
          .append(" tier=").append(tier.id).append(" romance=")
          .append(profile.romantic ? "partner" : "none");
      if (!tier.behavior.isBlank()) out.append(" behavior=").append(compact(tier.behavior, 180));
    }
    return out.toString();
  }

  public String info(String nameOrUuid) {
    UUID uuid = resolve(nameOrUuid);
    if (uuid == null) return "Player not found in relationship/activity cache.";
    Profile profile = profiles.get(uuid);
    if (profile == null) return "No relationship data for that player.";
    List<Memory> list = memories.getOrDefault(uuid, List.of());
    long persistent = list.stream().filter(m -> m.kind == MemoryKind.PERSISTENT).count();
    long recent = list.stream().filter(m -> m.kind == MemoryKind.RECENT && !m.expired(System.currentTimeMillis())).count();
    RomanceAvailability romance = romanceAvailability(profile);
    return profile.lastName + " score=" + profile.score + " tier=" + tierFor(profile.score).id
        + " romance=" + profile.romantic + " romance_status=" + romance.reason
        + " partners=" + romance.currentPartners + "/" + romance.maxPartners
        + " memories=" + persistent + "P/" + recent + "R";
  }

  public List<String> memorySummaries(String nameOrUuid) {
    UUID uuid = resolve(nameOrUuid);
    if (uuid == null) return List.of();
    long now = System.currentTimeMillis();
    pruneExpiredMemoriesFor(uuid, now, true);
    return memories.getOrDefault(uuid, List.of()).stream()
        .filter(m -> !m.expired(now))
        .sorted(Comparator.comparingLong((Memory m) -> m.createdAt).reversed())
        .map(m -> (m.kind == MemoryKind.PERSISTENT ? "P" : "R") + m.importance + ": " + m.summary)
        .toList();
  }

  public boolean setScore(String nameOrUuid, int score) {
    UUID uuid = resolve(nameOrUuid);
    if (uuid == null) return false;
    // An explicit admin set intentionally recreates fresh relationship state after purge.
    purgedUntilNextObservation.remove(uuid);
    Profile profile = profiles.get(uuid);
    if (profile == null) {
      Player online = Bukkit.getPlayer(uuid);
      if (online == null) return false;
      profile = profile(uuid, online.getName());
    }
    profile.score = clampScore(score);
    profile.updatedAt = System.currentTimeMillis();
    persistProfile(profile);
    if (plugin.getConversationManager() != null) {
      plugin.getConversationManager().clearRelationshipRuntimeContext(uuid);
    }
    return true;
  }

  /** Admin testing helper. Enabling romance obeys the exact same capacity/score rules as the model. */
  public String setRomance(String nameOrUuid, boolean romantic) {
    UUID uuid = resolve(nameOrUuid);
    if (uuid == null) return "Player not found in current relationship data/online players.";
    purgedUntilNextObservation.remove(uuid);
    Profile profile = profiles.get(uuid);
    if (profile == null) {
      Player online = Bukkit.getPlayer(uuid);
      if (online == null) return "Player is not available.";
      profile = profile(uuid, online.getName());
    }

    if (romantic) {
      if (profile.romantic) return profile.lastName + " is already a romantic partner.";
      RomanceAvailability availability = romanceAvailability(profile);
      if (!availability.allowed) {
        return "Cannot enable romance for " + profile.lastName + ": " + availability.reason
            + " (partners " + availability.currentPartners + "/" + availability.maxPartners + ").";
      }
      profile.romantic = true;
    } else {
      if (!profile.romantic) return profile.lastName + " is not a romantic partner.";
      profile.romantic = false;
    }

    profile.updatedAt = System.currentTimeMillis();
    persistProfile(profile);
    if (plugin.getConversationManager() != null) {
      plugin.getConversationManager().clearRelationshipRuntimeContext(uuid);
    }
    return "Romance updated: " + info(profile.lastName);
  }

  public UUID resolve(String nameOrUuid) {
    if (nameOrUuid == null || nameOrUuid.isBlank()) return null;
    try {
      UUID uuid = UUID.fromString(nameOrUuid.trim());
      if (profiles.containsKey(uuid)) return uuid;
      Player online = Bukkit.getPlayer(uuid);
      return online == null ? null : uuid;
    } catch (IllegalArgumentException ignored) {
    }
    UUID indexed = resolveByName(nameOrUuid);
    if (indexed != null) return indexed;
    Player online = Bukkit.getPlayerExact(nameOrUuid);
    return online == null ? null : online.getUniqueId();
  }

  public String lastKnownName(UUID uuid) {
    Profile profile = uuid == null ? null : profiles.get(uuid);
    if (profile != null) return profile.lastName;
    Player online = uuid == null ? null : Bukkit.getPlayer(uuid);
    return online == null ? "" : online.getName();
  }

  /**
   * Completely removes this player's relationship data. The SQL delete is queued
   * behind all older writes and awaited, so an already queued save cannot recreate
   * the record after the purge. Stale in-flight AI results are ignored until the
   * player performs a new observed action.
   */
  public boolean purge(UUID uuid) {
    if (uuid == null) return false;
    Profile removed = profiles.remove(uuid);
    List<Memory> removedMemories = memories.remove(uuid);
    positiveRecent.remove(uuid);
    negativeRecent.remove(uuid);
    purgedUntilNextObservation.add(uuid);
    if (removed != null) nameIndex.remove(removed.lastName.toLowerCase(Locale.ROOT));

    if (shutdown) return removed != null || (removedMemories != null && !removedMemories.isEmpty());

    try {
      Future<Boolean> deletion = databaseExecutor.submit(() -> {
        try (Connection connection = openConnection()) {
          connection.setAutoCommit(false);
          try (PreparedStatement deleteMemories = connection.prepareStatement(
              "DELETE FROM relationship_memories WHERE player_uuid=?");
               PreparedStatement deleteProfile = connection.prepareStatement(
              "DELETE FROM player_relationships WHERE uuid=?")) {
            deleteMemories.setString(1, uuid.toString());
            int memoryRows = deleteMemories.executeUpdate();
            deleteProfile.setString(1, uuid.toString());
            int profileRows = deleteProfile.executeUpdate();
            connection.commit();
            return profileRows > 0 || memoryRows > 0;
          } catch (SQLException ex) {
            connection.rollback();
            throw ex;
          }
        }
      });
      boolean existedOnDisk = deletion.get(5, TimeUnit.SECONDS);
      return removed != null || (removedMemories != null && !removedMemories.isEmpty()) || existedOnDisk;
    } catch (Exception ex) {
      plugin.getLogger().severe("Could not purge relationship data for " + uuid + ": " + ex.getMessage());
      return false;
    }
  }

  public int profileCount() {
    return profiles.size();
  }

  public void shutdown() {
    shutdown = true;
    databaseExecutor.shutdown();
    try {
      if (!databaseExecutor.awaitTermination(3, TimeUnit.SECONDS)) databaseExecutor.shutdownNow();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      databaseExecutor.shutdownNow();
    }
  }

  private void initializeDatabase() throws SQLException {
    plugin.getDataFolder().mkdirs();
    try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
      String journalMode = config().getString("storage.journal-mode", "DELETE");
      journalMode = journalMode == null ? "DELETE" : journalMode.trim().toUpperCase(Locale.ROOT);
      if (!Set.of("DELETE", "WAL", "TRUNCATE", "PERSIST").contains(journalMode)) journalMode = "DELETE";
      // DELETE is the bundled default so a FileZilla copy of relationships.db reflects
      // committed writes without also needing the sidecar relationships.db-wal file.
      // Writes are already serialized on one background executor, so the tiny social
      // database does not need WAL for throughput.
      statement.execute("PRAGMA journal_mode=" + journalMode);
      statement.execute("PRAGMA synchronous=NORMAL");
      statement.execute("CREATE TABLE IF NOT EXISTS player_relationships ("
          + "uuid TEXT PRIMARY KEY, last_name TEXT NOT NULL, score INTEGER NOT NULL DEFAULT 0, "
          + "romantic INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0, "
          + "last_positive_at INTEGER NOT NULL DEFAULT 0, last_negative_at INTEGER NOT NULL DEFAULT 0)");
      statement.execute("CREATE TABLE IF NOT EXISTS relationship_memories ("
          + "id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, kind TEXT NOT NULL, "
          + "summary TEXT NOT NULL, importance INTEGER NOT NULL DEFAULT 1, created_at INTEGER NOT NULL, "
          + "expires_at INTEGER NOT NULL DEFAULT 0)");
      statement.execute("CREATE INDEX IF NOT EXISTS idx_relationship_memories_player "
          + "ON relationship_memories(player_uuid, kind, created_at)");
    }
  }

  private void loadCache() throws SQLException {
    long now = System.currentTimeMillis();
    try (Connection connection = openConnection()) {
      try (PreparedStatement deleteExpired = connection.prepareStatement(
          "DELETE FROM relationship_memories WHERE expires_at > 0 AND expires_at <= ?")) {
        deleteExpired.setLong(1, now);
        deleteExpired.executeUpdate();
      }

      try (Statement statement = connection.createStatement();
           ResultSet rs = statement.executeQuery("SELECT uuid,last_name,score,romantic,updated_at,last_positive_at,last_negative_at FROM player_relationships")) {
        while (rs.next()) {
          UUID uuid;
          try {
            uuid = UUID.fromString(rs.getString("uuid"));
          } catch (IllegalArgumentException ex) {
            continue;
          }
          Profile profile = new Profile(
              uuid,
              rs.getString("last_name"),
              clampScore(rs.getInt("score")),
              rs.getInt("romantic") != 0,
              rs.getLong("updated_at"),
              rs.getLong("last_positive_at"),
              rs.getLong("last_negative_at"));
          profiles.put(uuid, profile);
          nameIndex.put(profile.lastName.toLowerCase(Locale.ROOT), uuid);
        }
      }

      try (Statement statement = connection.createStatement();
           ResultSet rs = statement.executeQuery(
               "SELECT id,player_uuid,kind,summary,importance,created_at,expires_at FROM relationship_memories ORDER BY created_at ASC")) {
        while (rs.next()) {
          UUID uuid;
          try {
            uuid = UUID.fromString(rs.getString("player_uuid"));
          } catch (IllegalArgumentException ex) {
            continue;
          }
          MemoryKind kind = "PERSISTENT".equalsIgnoreCase(rs.getString("kind"))
              ? MemoryKind.PERSISTENT : MemoryKind.RECENT;
          Memory memory = new Memory(
              rs.getLong("id"), uuid, kind, rs.getString("summary"), rs.getInt("importance"),
              rs.getLong("created_at"), rs.getLong("expires_at"));
          memories.computeIfAbsent(uuid, ignored -> new ArrayList<>()).add(memory);
        }
      }
    }
  }

  private Profile profile(UUID uuid, String name) {
    Profile existing = profiles.get(uuid);
    if (existing != null) {
      if (name != null && !name.isBlank() && !existing.lastName.equals(name)) {
        nameIndex.remove(existing.lastName.toLowerCase(Locale.ROOT));
        existing.lastName = name;
        nameIndex.put(name.toLowerCase(Locale.ROOT), uuid);
      }
      return existing;
    }
    Profile created = new Profile(uuid, safeName(name), initialScore(), false,
        System.currentTimeMillis(), 0L, 0L);
    profiles.put(uuid, created);
    nameIndex.put(created.lastName.toLowerCase(Locale.ROOT), uuid);
    persistProfile(created);
    return created;
  }

  private UUID resolveByName(String name) {
    if (name == null || name.isBlank()) return null;
    return nameIndex.get(name.trim().toLowerCase(Locale.ROOT));
  }

  private boolean applyRomanceAction(
      Profile profile,
      String action,
      PlayerChatMessage speaker,
      long now,
      boolean explicitPartnershipProposal,
      boolean visiblePartnershipAcceptance) {
    if (action == null || action.isBlank()) return false;
    boolean logRejected = config().getBoolean("debug.log-rejected-updates", false);

    if ("end".equalsIgnoreCase(action)) {
      if (!profile.romantic) {
        if (logRejected) plugin.getLogger().info(
            "Ignored romance=end for non-partner " + profile.lastName);
        return false;
      }
      profile.romantic = false;
      profile.updatedAt = now;
      return true;
    }

    if (!"start".equalsIgnoreCase(action)) return false;
    if (profile.romantic) return false;
    if (config().getBoolean("romance.require-explicit-proposal", true) && !explicitPartnershipProposal) {
      if (logRejected) plugin.getLogger().info(
          "Rejected romance=start for " + profile.lastName + " reason=no-explicit-current-proposal");
      return false;
    }
    if (config().getBoolean("romance.require-visible-acceptance", true) && !visiblePartnershipAcceptance) {
      if (logRejected) plugin.getLogger().info(
          "Rejected romance=start for " + profile.lastName + " reason=visible-reply-did-not-accept");
      return false;
    }

    RomanceAvailability availability = romanceAvailability(profile);
    if (!availability.allowed) {
      if (logRejected) plugin.getLogger().info(
          "Rejected romance=start for " + profile.lastName + " reason=" + availability.reason
              + " partners=" + availability.currentPartners + "/" + availability.maxPartners);
      return false;
    }

    profile.romantic = true;
    profile.updatedAt = now;
    return true;
  }

  private boolean canStartRomance(Profile profile) {
    return romanceAvailability(profile).allowed;
  }

  private RomanceAvailability romanceAvailability(Profile profile) {
    int maxPartners = Math.max(config().getInt("romance.max-partners", 0), 0);
    int currentPartners = (int) profiles.values().stream().filter(p -> p.romantic).count();
    if (profile == null) return new RomanceAvailability(false, "unknown-player", currentPartners, maxPartners);
    if (profile.romantic) return new RomanceAvailability(false, "already-partner", currentPartners, maxPartners);
    if (maxPartners <= 0) return new RomanceAvailability(false, "disabled", currentPartners, maxPartners);
    // Capacity wins over score for non-partners: if Isolda is already at her global
    // limit, she should never sound available to another player.
    if (currentPartners >= maxPartners) return new RomanceAvailability(false, "capacity-full", currentPartners, maxPartners);
    int minimum = config().getInt("romance.minimum-score", 90);
    if (profile.score < minimum) return new RomanceAvailability(false, "score-too-low", currentPartners, maxPartners);
    return new RomanceAvailability(true, "eligible", currentPartners, maxPartners);
  }

  /** Keeps the persisted romance state inside the configured global capacity. */
  private void enforceRomanceCapacity() {
    int maxPartners = Math.max(config().getInt("romance.max-partners", 0), 0);
    List<Profile> romantic = profiles.values().stream()
        .filter(profile -> profile.romantic)
        .sorted(Comparator.comparingInt((Profile profile) -> profile.score).reversed()
            .thenComparing(Comparator.comparingLong((Profile profile) -> profile.updatedAt).reversed()))
        .toList();
    if (romantic.size() <= maxPartners) return;

    long now = System.currentTimeMillis();
    for (int i = maxPartners; i < romantic.size(); i++) {
      Profile profile = romantic.get(i);
      profile.romantic = false;
      profile.updatedAt = now;
      persistProfile(profile);
    }
  }

  private boolean allowedDirectionalChange(UUID uuid, int amount, boolean positive, long now) {
    Profile profile = profiles.get(uuid);
    long cooldown = Math.max(config().getLong(
        positive ? "updates.positive-cooldown-seconds" : "updates.negative-cooldown-seconds",
        positive ? 90L : 30L), 0L) * 1000L;
    long last = profile == null ? 0L : (positive ? profile.lastPositiveAt : profile.lastNegativeAt);
    if (cooldown > 0 && now - last < cooldown) return false;

    int hourlyCap = Math.max(config().getInt(
        positive ? "updates.max-positive-points-per-hour" : "updates.max-negative-points-per-hour",
        positive ? 6 : 12), 0);
    if (hourlyCap == 0) return false;
    Deque<DeltaStamp> deque = (positive ? positiveRecent : negativeRecent)
        .computeIfAbsent(uuid, ignored -> new ArrayDeque<>());
    long cutoff = now - 3_600_000L;
    while (!deque.isEmpty() && deque.peekFirst().timestamp < cutoff) deque.removeFirst();
    int used = deque.stream().mapToInt(DeltaStamp::amount).sum();
    return used + amount <= hourlyCap;
  }

  private void rememberDelta(UUID uuid, int amount, boolean positive, long now) {
    (positive ? positiveRecent : negativeRecent)
        .computeIfAbsent(uuid, ignored -> new ArrayDeque<>())
        .addLast(new DeltaStamp(now, amount));
  }

  private boolean addMemory(Profile profile, MemoryKind kind, String summary, int importance, long now) {
    List<Memory> list = memories.computeIfAbsent(profile.uuid, ignored -> new ArrayList<>());
    if (isDuplicateMemory(list, summary, now)) return false;

    long expiresAt = 0L;
    if (kind == MemoryKind.RECENT) {
      long days = Math.max(config().getLong("memories.recent-expire-days", 7L), 1L);
      expiresAt = now + TimeUnit.DAYS.toMillis(days);
    }

    Memory memory = new Memory(0L, profile.uuid, kind, summary, importance, now, expiresAt);
    list.add(memory);
    enforceMemoryLimits(profile.uuid, list);
    if (!list.contains(memory)) return false;
    persistNewMemory(memory);
    return true;
  }

  private boolean isDuplicateMemory(List<Memory> list, String summary, long now) {
    String normalized = summary.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    long window = Math.max(config().getLong("memories.duplicate-window-minutes", 180L), 1L) * 60_000L;
    for (Memory memory : list) {
      if (now - memory.createdAt <= window
          && memory.summary.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim().equals(normalized)) {
        return true;
      }
    }
    return false;
  }

  private void enforceMemoryLimits(UUID uuid, List<Memory> list) {
    int persistentMax = Math.max(config().getInt("memories.max-persistent", 8), 0);
    int recentMax = Math.max(config().getInt("memories.max-recent", 12), 0);
    trimKind(uuid, list, MemoryKind.PERSISTENT, persistentMax);
    trimKind(uuid, list, MemoryKind.RECENT, recentMax);
  }

  private void trimKind(UUID uuid, List<Memory> list, MemoryKind kind, int max) {
    List<Memory> same = list.stream().filter(m -> m.kind == kind)
        .sorted(Comparator.comparingInt((Memory m) -> m.importance)
            .thenComparingLong(m -> m.createdAt))
        .toList();
    int removeCount = Math.max(0, same.size() - max);
    for (int i = 0; i < removeCount; i++) {
      Memory remove = same.get(i);
      list.remove(remove);
      if (remove.id > 0) deleteMemory(remove.id);
      else deleteMemoryByFingerprint(uuid, remove);
    }
  }

  private void appendMemorySubset(StringBuilder out, List<Memory> source, MemoryKind kind, int limit) {
    if (limit <= 0 || source == null || source.isEmpty()) return;
    long now = System.currentTimeMillis();
    List<Memory> selected = source.stream()
        .filter(m -> m.kind == kind && !m.expired(now))
        .sorted(Comparator.comparingInt((Memory m) -> m.importance).reversed()
            .thenComparing(Comparator.comparingLong((Memory m) -> m.createdAt).reversed()))
        .limit(limit)
        .toList();
    if (selected.isEmpty()) return;
    out.append(kind == MemoryKind.PERSISTENT ? "persistent=" : "recent=");
    for (int i = 0; i < selected.size(); i++) {
      if (i > 0) out.append(" | ");
      out.append(compact(selected.get(i).summary, 110));
    }
    out.append('\n');
  }

  private void pruneExpiredMemories(long now, boolean persist) {
    for (UUID uuid : List.copyOf(memories.keySet())) {
      pruneExpiredMemoriesFor(uuid, now, persist);
    }
  }

  private void pruneExpiredMemoriesFor(UUID uuid, long now, boolean persist) {
    List<Memory> list = memories.get(uuid);
    if (list == null || list.isEmpty()) return;
    List<Memory> expired = new ArrayList<>();
    list.removeIf(memory -> {
      boolean remove = memory.expired(now);
      if (remove) expired.add(memory);
      return remove;
    });
    if (list.isEmpty()) memories.remove(uuid);
    if (!persist) return;
    for (Memory memory : expired) {
      if (memory.id > 0) deleteMemory(memory.id);
      else deleteMemoryByFingerprint(uuid, memory);
    }
  }

  private Tier tierFor(int score) {
    FileConfiguration cfg = config();
    ConfigurationSection tiers = cfg.getConfigurationSection("tiers");
    if (tiers == null) return new Tier("neutral", 0, 0, "Trato neutral y natural.");

    Tier best = null;
    for (String id : tiers.getKeys(false)) {
      ConfigurationSection section = tiers.getConfigurationSection(id);
      if (section == null) continue;
      int min = section.getInt("min", -100);
      int max = section.getInt("max", 100);
      if (score < min || score > max) continue;
      Tier candidate = new Tier(id, min, max, section.getString("behavior", ""));
      if (best == null || (candidate.max - candidate.min) < (best.max - best.min)) best = candidate;
    }
    return best == null ? new Tier("neutral", -24, 24, "Trato neutral y natural.") : best;
  }

  private double ignoreChance(int score) {
    ConfigurationSection section = config().getConfigurationSection("behavior.ignore.thresholds");
    if (section == null) return 0.0;
    double selected = 0.0;
    int selectedThreshold = Integer.MAX_VALUE;
    for (String key : section.getKeys(false)) {
      int threshold;
      try {
        threshold = Integer.parseInt(key);
      } catch (NumberFormatException ex) {
        continue;
      }
      if (score <= threshold && threshold < selectedThreshold) {
        selectedThreshold = threshold;
        selected = section.getDouble(key, 0.0);
      }
    }
    return Math.max(0.0, Math.min(1.0, selected));
  }

  private static boolean looksTrivial(String content) {
    if (content == null || content.isBlank()) return true;
    String text = Normalizer.normalize(content, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT);
    if (content.length() > 100) return false;
    if (text.contains("?") || text.matches(".*\\b(que|como|donde|cuando|quien|cual|cuanto|ayuda|comando|receta|nivel|raza|objeto|inventario|historial)\\b.*")) {
      return false;
    }
    return true;
  }

  private void persistProfile(Profile profile) {
    if (shutdown || profile == null) return;
    Profile snapshot = profile.copy();
    databaseExecutor.submit(() -> {
      try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO player_relationships(uuid,last_name,score,romantic,updated_at,last_positive_at,last_negative_at) "
              + "VALUES(?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name,score=excluded.score,"
              + "romantic=excluded.romantic,updated_at=excluded.updated_at,last_positive_at=excluded.last_positive_at,"
              + "last_negative_at=excluded.last_negative_at")) {
        statement.setString(1, snapshot.uuid.toString());
        statement.setString(2, snapshot.lastName);
        statement.setInt(3, snapshot.score);
        statement.setInt(4, snapshot.romantic ? 1 : 0);
        statement.setLong(5, snapshot.updatedAt);
        statement.setLong(6, snapshot.lastPositiveAt);
        statement.setLong(7, snapshot.lastNegativeAt);
        statement.executeUpdate();
      } catch (SQLException ex) {
        plugin.getLogger().warning("Could not persist relationship profile " + snapshot.lastName + ": " + ex.getMessage());
      }
    });
  }

  private void persistNewMemory(Memory memory) {
    if (shutdown || memory == null) return;
    databaseExecutor.submit(() -> {
      try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO relationship_memories(player_uuid,kind,summary,importance,created_at,expires_at) VALUES(?,?,?,?,?,?)")) {
        statement.setString(1, memory.playerUuid.toString());
        statement.setString(2, memory.kind.name());
        statement.setString(3, memory.summary);
        statement.setInt(4, memory.importance);
        statement.setLong(5, memory.createdAt);
        statement.setLong(6, memory.expiresAt);
        statement.executeUpdate();
      } catch (SQLException ex) {
        plugin.getLogger().warning("Could not persist relationship memory: " + ex.getMessage());
      }
    });
  }

  private void deleteMemory(long id) {
    if (id <= 0 || shutdown) return;
    databaseExecutor.submit(() -> {
      try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM relationship_memories WHERE id=?")) {
        statement.setLong(1, id);
        statement.executeUpdate();
      } catch (SQLException ex) {
        plugin.getLogger().warning("Could not delete relationship memory " + id + ": " + ex.getMessage());
      }
    });
  }

  private void deleteMemoryByFingerprint(UUID uuid, Memory memory) {
    if (shutdown || uuid == null || memory == null) return;
    databaseExecutor.submit(() -> {
      try (Connection connection = openConnection(); PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM relationship_memories WHERE player_uuid=? AND kind=? AND summary=? AND created_at=?")) {
        statement.setString(1, uuid.toString());
        statement.setString(2, memory.kind.name());
        statement.setString(3, memory.summary);
        statement.setLong(4, memory.createdAt);
        statement.executeUpdate();
      } catch (SQLException ex) {
        plugin.getLogger().warning("Could not delete overflow relationship memory: " + ex.getMessage());
      }
    });
  }

  private Connection openConnection() throws SQLException {
    return DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
  }

  private String databaseFileName() {
    String configured = config().getString("storage.database-file", "relationships.db");
    if (configured == null || configured.isBlank()) return "relationships.db";
    String safe = configured.replace('\\', '/');
    if (safe.contains("..") || safe.contains("/")) return "relationships.db";
    return safe;
  }

  private FileConfiguration config() {
    FileConfiguration config = plugin.getRelationshipsConfig();
    if (config == null) throw new IllegalStateException("relationships.yml is not loaded");
    return config;
  }

  private boolean enabled() {
    return config().getBoolean("enabled", true);
  }

  private int initialScore() {
    return clampScore(config().getInt("score.initial", 0));
  }

  private int clampScore(int score) {
    int min = config().getInt("score.minimum", -100);
    int max = config().getInt("score.maximum", 100);
    if (min > max) {
      int swap = min;
      min = max;
      max = swap;
    }
    return Math.max(min, Math.min(max, score));
  }

  private String sanitizeMemory(String value) {
    if (value == null || value.isBlank() || value.equals("-")) return "";
    String cleaned = value.replace('|', '/').replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
    int max = Math.max(config().getInt("memories.max-summary-chars", 90), 20);
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, max).trim();
  }

  private static MemoryKind parseMemoryKind(String raw) {
    if (raw == null) return null;
    return switch (raw.toLowerCase(Locale.ROOT)) {
      case "p", "persistent" -> MemoryKind.PERSISTENT;
      case "r", "recent" -> MemoryKind.RECENT;
      default -> null;
    };
  }

  private static String compact(String text, int max) {
    String value = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
    return value.length() <= max ? value : value.substring(0, Math.max(1, max - 1)).trim() + "…";
  }

  private static String safeName(String name) {
    return name == null || name.isBlank() ? "unknown" : name;
  }

  private record RomanceAvailability(boolean allowed, String reason, int currentPartners, int maxPartners) { }

  private enum MemoryKind { RECENT, PERSISTENT }

  private static final class Profile {
    final UUID uuid;
    String lastName;
    int score;
    boolean romantic;
    long updatedAt;
    long lastPositiveAt;
    long lastNegativeAt;

    Profile(UUID uuid, String lastName, int score, boolean romantic, long updatedAt,
        long lastPositiveAt, long lastNegativeAt) {
      this.uuid = uuid;
      this.lastName = safeName(lastName);
      this.score = score;
      this.romantic = romantic;
      this.updatedAt = updatedAt;
      this.lastPositiveAt = lastPositiveAt;
      this.lastNegativeAt = lastNegativeAt;
    }

    Profile copy() {
      return new Profile(uuid, lastName, score, romantic, updatedAt, lastPositiveAt, lastNegativeAt);
    }
  }

  private record Memory(long id, UUID playerUuid, MemoryKind kind, String summary,
      int importance, long createdAt, long expiresAt) {
    boolean expired(long now) {
      return expiresAt > 0 && expiresAt <= now;
    }
  }

  private record Tier(String id, int min, int max, String behavior) { }
  private record DeltaStamp(long timestamp, int amount) { }
}
