package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;
import static me.kev.sva.chat.ThreadAffinity.*;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.kev.sva.ServerAssistantPlugin;

/** Picks which nearby speakers join Isolda's thread for one scene, using {@link ThreadAffinity}. */
final class GroupThreadSelector {
  private final ServerAssistantPlugin plugin;
  private final Deque<SceneMemory> sceneHistory;
  private final AssistantMentions mentions;
  private final SmartFollowUpGate smartGate;

  GroupThreadSelector(
      ServerAssistantPlugin plugin,
      ConversationLog log,
      AssistantMentions mentions,
      SmartFollowUpGate smartGate) {
    this.plugin = plugin;
    this.sceneHistory = log.sceneHistory();
    this.mentions = mentions;
    this.smartGate = smartGate;
  }

  GroupThreadSelection selectGroupThreadCandidates(
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
        base + "pre-candidate-lookback-ms", 12000L), 0L);
    int maxPre = Math.max(plugin.getConfig().getInt(
        base + "max-pre-candidate-lines", 2), 0);
    int joinThreshold = clampInt(plugin.getConfig().getInt(
        base + "affinity.join-threshold", 44), 0, 100);
    int minMargin = clampInt(plugin.getConfig().getInt(
        base + "affinity.min-margin-over-side-thread", 10), 0, 100);
    int anchorJoinThreshold = clampInt(plugin.getConfig().getInt(
        base + "affinity.active-anchor-join-threshold", 34), 0, 100);
    int autoThreshold = clampInt(plugin.getConfig().getInt(
        base + "affinity.auto-follow-up-threshold", 64), joinThreshold, 100);
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
    List<PublicChatRecord> rootLines = timeline.stream()
        .filter(chat -> roots.contains(chat.playerId()))
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
      boolean assistantMention = mentions.containsAssistantMention(chat.content());
      boolean alreadySmart = smartGate.hasActiveSmartFollowUp(chat.playerId(), chat.timestampMs())
          && smartGate.isSemanticSmartFollowUp(
              chat.playerId(), chat.playerName(), chat.content(), chat.timestampMs(), false);
      boolean explicitBridge = looksLikeExplicitGroupBridge(chat.content());

      long threadAge = previousThreadLine == null
          ? Math.abs(capture.triggerAt() - chat.timestampMs())
          : Math.max(0L, chat.timestampMs() - previousThreadLine.timestampMs());
      String previousThreadText = previousThreadLine == null
          ? previousAssistantReply
          : previousThreadLine.content();

      int baseThreadAffinity;
      int assistantAnchorAffinity = activeThreadLineAffinity(
          chat.content(), previousAssistantReply, participantNames,
          Math.abs(chat.timestampMs() - capture.triggerAt()));
      int rootAnchorAffinity = 0;
      PublicChatRecord bestRootAnchor = null;
      for (PublicChatRecord rootLine : rootLines) {
        long anchorAge = Math.abs(chat.timestampMs() - rootLine.timestampMs());
        int anchorScore = activeThreadLineAffinity(
            chat.content(), rootLine.content(), participantNames, anchorAge);
        if (anchorScore > rootAnchorAffinity) {
          rootAnchorAffinity = anchorScore;
          bestRootAnchor = rootLine;
        }
      }

      if (assistantMention) {
        baseThreadAffinity = 100;
      } else if (alreadySmart) {
        baseThreadAffinity = 96;
      } else {
        baseThreadAffinity = localThreadAffinity(
            chat.content(), threadText.toString(), previousThreadText,
            participantNames, threadAge, beforeTrigger);
        if (explicitBridge) baseThreadAffinity = Math.min(100, baseThreadAffinity + 50);
      }
      int isoldaAffinity = Math.max(
          baseThreadAffinity, Math.max(assistantAnchorAffinity, rootAnchorAffinity));

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
      boolean anchorMatch = Math.max(assistantAnchorAffinity, rootAnchorAffinity) >= anchorJoinThreshold;
      boolean explicitParticipantSideAddress = looksLikeExplicitOtherPlayerAddress(
          chat.content(), participantNames)
          && topicOverlapScore(chat.content(), threadText.toString(), participantNames) == 0
          && topicOverlapScore(chat.content(), previousAssistantReply, participantNames) == 0
          && (bestRootAnchor == null
              || topicOverlapScore(chat.content(), bestRootAnchor.content(), participantNames) == 0)
          && !explicitBridge
          && !looksLikeSocialThreadInterjection(chat.content());
      boolean broadPublicSide = looksLikeBroadPublicRequest(chat.content())
          && !referencesAnyParticipantAlias(chat.content(), participantNames)
          && !explicitBridge;

      boolean candidate = shouldJoinGroupCandidate(
          strongAuthority, explicitParticipantSideAddress, broadPublicSide,
          isoldaAffinity, bestSideAffinity, joinThreshold,
          Math.max(assistantAnchorAffinity, rootAnchorAffinity), anchorJoinThreshold,
          requiredMargin);

      if (debugAffinity) {
        plugin.getLogger().info("Group thread affinity scene=" + capture.sceneId()
            + " player=" + chat.playerName()
            + " base=" + baseThreadAffinity
            + " assistant_anchor=" + assistantAnchorAffinity
            + " root_anchor=" + rootAnchorAffinity
            + (bestRootAnchor == null ? "" : " root_speaker=" + bestRootAnchor.playerName())
            + " isolda=" + isoldaAffinity
            + " side=" + bestSideAffinity
            + (bestSideLine == null ? "" : " side_speaker=" + bestSideLine.playerName())
            + " before_trigger=" + beforeTrigger
            + " bridge=" + explicitBridge
            + " direct_side=" + explicitParticipantSideAddress
            + " broad_public=" + broadPublicSide
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
              && isoldaAffinity - bestSideAffinity >= Math.max(requiredMargin, 16))
          || (Math.max(assistantAnchorAffinity, rootAnchorAffinity) >= anchorJoinThreshold + 14
              && isoldaAffinity - bestSideAffinity >= Math.max(requiredMargin, 12))) {
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
}
