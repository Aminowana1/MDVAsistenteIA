package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;
import static me.kev.sva.chat.ThreadAffinity.*;

import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.tools.ToolManager;

/**
 * Per-player SMART continuity: who may keep talking to Isolda without naming her,
 * and whether a given line is really a continuation (zero-token gate).
 */
final class SmartFollowUpGate {
  private final ServerAssistantPlugin plugin;
  private final Deque<PublicChatRecord> publicChatLog;
  private final Deque<SceneMemory> sceneHistory;

  /**
   * Smart-mode continuity is per player, not a global chat latch. Only players who
   * actually addressed Isolda in the last answered scene receive a short follow-up
   * window. Merely appearing as context never grants trigger rights.
   */
  private final Map<UUID, Long> smartFollowUpUntilByPlayer = new HashMap<>();

  /**
   * Tiny zero-token anchor for semantic SMART gating. It remembers only the last
   * answered exchange for each player while their normal SMART timer is alive.
   * This lets Java distinguish a real follow-up from a message aimed at public
   * chat or another player before an API request is created.
   */
  private final Map<UUID, SmartFollowUpAnchor> smartFollowUpAnchorByPlayer = new HashMap<>();

  SmartFollowUpGate(ServerAssistantPlugin plugin, ConversationLog log) {
    this.plugin = plugin;
    this.publicChatLog = log.publicChat();
    this.sceneHistory = log.sceneHistory();
  }

  /** Lets this player keep talking to Isolda without naming her until {@code untilMs}. */
  void grant(UUID playerId, long untilMs, SceneRequest scene, String assistantReply, long answeredAtMs) {
    smartFollowUpUntilByPlayer.put(playerId, untilMs);
    smartFollowUpAnchorByPlayer.put(
        playerId, buildSmartFollowUpAnchor(scene, playerId, assistantReply, answeredAtMs));
  }

  void revoke(UUID playerId) {
    smartFollowUpUntilByPlayer.remove(playerId);
    smartFollowUpAnchorByPlayer.remove(playerId);
  }

  void clear() {
    smartFollowUpUntilByPlayer.clear();
    smartFollowUpAnchorByPlayer.clear();
  }

  /**
   * Zero-token SMART trigger gate. A live timer no longer means every next line
   * automatically spends an API request. The gate is intentionally conservative:
   * clear public/side-chat is blocked, clear continuations are allowed, and ambiguous
   * human chat is allowed by default so normal conversation does not feel brittle.
   */
  boolean isSemanticSmartFollowUp(
      UUID playerId,
      String playerName,
      String raw,
      long now,
      boolean logDecision) {
    if (!plugin.getConfig().getBoolean(
        "global-conversation.smart-trigger-gate.enabled", true)) {
      return true;
    }
    SmartFollowUpAnchor anchor = smartFollowUpAnchorByPlayer.get(playerId);
    if (anchor == null) {
      // Backward/reload safety: an already-live timer without an anchor remains valid
      // until the next answered scene creates one.
      return true;
    }

    String base = "global-conversation.smart-trigger-gate.";
    int continueThreshold = clampInt(plugin.getConfig().getInt(
        base + "continuation-threshold", 28), 0, 100);
    int sideMin = clampInt(plugin.getConfig().getInt(
        base + "side-thread-min-affinity", 36), 0, 100);
    int sideMargin = clampInt(plugin.getConfig().getInt(
        base + "side-thread-margin", 8), 0, 100);
    long sideLookback = Math.max(plugin.getConfig().getLong(
        base + "side-thread-lookback-ms", 8000L), 0L);
    boolean blockBroad = plugin.getConfig().getBoolean(
        base + "block-broad-public-requests", true);
    boolean blockOther = plugin.getConfig().getBoolean(
        base + "block-explicit-other-player-address", true);
    boolean blockTrivial = plugin.getConfig().getBoolean(
        base + "block-trivial-acknowledgements", true);
    boolean allowAmbiguous = plugin.getConfig().getBoolean(
        base + "allow-ambiguous", true);
    boolean debug = logDecision && plugin.getConfig().getBoolean(base + "debug-log", false);

    long age = Math.max(0L, now - anchor.answeredAtMs());
    int isoldaAffinity = smartFollowUpAffinity(
        raw,
        anchor.lastPlayerLine(),
        anchor.assistantReply(),
        anchor.participantNames(),
        age);

    int bestSideAffinity = 0;
    PublicChatRecord bestSideLine = null;
    long oldest = Math.max(anchor.answeredAtMs(), now - sideLookback);
    for (PublicChatRecord prior : publicChatLog) {
      if (prior.timestampMs() < oldest || prior.timestampMs() >= now) continue;
      if (prior.playerId().equals(playerId)) continue;
      long sideAge = Math.max(0L, now - prior.timestampMs());
      int side = localLateralAffinity(raw, prior.content(), prior.playerName(), sideAge);
      if (side > bestSideAffinity) {
        bestSideAffinity = side;
        bestSideLine = prior;
      }
    }

    LinkedHashSet<String> otherNames = new LinkedHashSet<>(anchor.participantNames());
    for (Player online : Bukkit.getOnlinePlayers()) {
      if (!online.getUniqueId().equals(playerId)) otherNames.add(online.getName());
    }
    if (playerName != null) otherNames.removeIf(name -> name.equalsIgnoreCase(playerName));

    boolean broadPublic = blockBroad && looksLikeBroadSmartSideRequest(raw);
    boolean explicitOther = blockOther && looksLikeExplicitOtherPlayerAddress(raw, otherNames);
    boolean trivial = blockTrivial && isTrivialSmartAcknowledgement(raw);

    boolean sideWins = bestSideAffinity >= sideMin
        && bestSideAffinity - isoldaAffinity >= sideMargin;
    // Very short elliptical answers ("yo tengo", "si", "no") should follow a
    // nearby side question even when the scores are close. This is the exact class
    // of accidental SMART request that used to waste calls in busy public chat.
    if (!sideWins && isVeryShortElliptical(raw)
        && bestSideAffinity >= sideMin
        && bestSideAffinity > isoldaAffinity + 3) {
      sideWins = true;
    }

    boolean allow;
    String reason;
    if (explicitOther) {
      allow = false;
      reason = "other_player";
    } else if (broadPublic) {
      allow = false;
      reason = "public_request";
    } else if (trivial && isoldaAffinity < Math.max(continueThreshold + 18, 52)) {
      allow = false;
      reason = "trivial_ack";
    } else if (sideWins) {
      allow = false;
      reason = "side_thread";
    } else if (isoldaAffinity >= continueThreshold) {
      allow = true;
      reason = "continuation";
    } else {
      allow = allowAmbiguous;
      reason = allow ? "ambiguous_allow" : "ambiguous_block";
    }

    if (debug) {
      plugin.getLogger().info("SMART trigger gate player=" + playerName
          + " isolda=" + isoldaAffinity
          + " side=" + bestSideAffinity
          + (bestSideLine == null ? "" : " side_speaker=" + bestSideLine.playerName())
          + " age_ms=" + age
          + " broad=" + broadPublic
          + " other_player=" + explicitOther
          + " trivial=" + trivial
          + " decision=" + (allow ? "CONTINUE" : "LOCAL_ONLY")
          + " reason=" + reason);
    }
    return allow;
  }

  private SmartFollowUpAnchor buildSmartFollowUpAnchor(
      SceneRequest scene,
      UUID playerId,
      String assistantReply,
      long answeredAtMs) {
    String latestPlayerLine = "";
    LinkedHashSet<String> participants = new LinkedHashSet<>();
    if (scene != null && scene.currentSceneMessages() != null) {
      for (ChatMessage message : scene.currentSceneMessages()) {
        if (!(message instanceof PlayerChatMessage playerMessage)) continue;
        participants.add(playerMessage.playerName);
        if (playerId.equals(playerMessage.playerId)
            && playerMessage.content != null && !playerMessage.content.isBlank()) {
          latestPlayerLine = playerMessage.content;
        }
      }
    }
    return new SmartFollowUpAnchor(
        answeredAtMs,
        limitSmartAnchorText(latestPlayerLine, 260),
        limitSmartAnchorText(assistantReply, 360),
        Set.copyOf(participants));
  }

  private static String limitSmartAnchorText(String raw, int maxChars) {
    if (raw == null) return "";
    String text = raw.replaceAll("\\s+", " ").trim();
    if (text.length() <= maxChars) return text;
    return text.substring(0, Math.max(0, maxChars - 3)).trim() + "...";
  }

  boolean hasActiveSmartFollowUp(UUID playerId, long now) {
    if (playerId == null) return false;
    Long until = smartFollowUpUntilByPlayer.get(playerId);
    if (until == null) return false;
    if (until < now) {
      smartFollowUpUntilByPlayer.remove(playerId);
      smartFollowUpAnchorByPlayer.remove(playerId);
      return false;
    }
    return true;
  }

  void pruneSmartFollowUps(long now) {
    Set<UUID> expired = new HashSet<>();
    smartFollowUpUntilByPlayer.entrySet().removeIf(entry -> {
      boolean remove = entry.getValue() < now;
      if (remove) expired.add(entry.getKey());
      return remove;
    });
    for (UUID playerId : expired) smartFollowUpAnchorByPlayer.remove(playerId);
    smartFollowUpAnchorByPlayer.keySet().removeIf(id -> !smartFollowUpUntilByPlayer.containsKey(id));
  }

  int activeSmartFollowUps() {
    pruneSmartFollowUps(System.currentTimeMillis());
    return smartFollowUpUntilByPlayer.size();
  }

  boolean isDuplicateSmartFollowUpReply(SceneRequest scene, String reply) {
    if (scene == null || reply == null || reply.isBlank() || sceneHistory.isEmpty()) return false;
    String meta = scene.context() == null ? "" : scene.context().sceneMeta();
    if (meta == null || !meta.contains("trigger=smart_followup")) return false;
    SceneMemory previous = sceneHistory.peekLast();
    if (previous == null || previous.assistantReply() == null || previous.assistantReply().isBlank()) return false;
    String current = ToolManager.normalize(reply);
    String last = ToolManager.normalize(previous.assistantReply());
    return !current.isBlank() && current.equals(last);
  }

  private record SmartFollowUpAnchor(
      long answeredAtMs,
      String lastPlayerLine,
      String assistantReply,
      Set<String> participantNames) { }
}
