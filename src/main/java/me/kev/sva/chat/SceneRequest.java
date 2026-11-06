package me.kev.sva.chat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import me.kev.sva.chat.assistant.AssistantRequestContext;
import me.kev.sva.chat.message.ChatMessage;

/** A finished scene waiting for, or being sent as, the single serialized AI request. */
record SceneRequest(
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
    int retryCount,
    /** When the player wrote the line that opened this scene; negative when no player did. */
    long triggerAtMs) {

  SceneRequest withProvider(int newProvider) {
    return new SceneRequest(
        sceneId, messages, currentSceneMessages, involvedPlayerIds, involvedPlayerNames,
        followUpEligiblePlayerIds, groupParticipantCandidateIds, autoGroupParticipantIds,
        groupParticipantCandidatesByName, context, currentActionText, newProvider, 0, triggerAtMs);
  }

  SceneRequest withRetry(int newRetryCount) {
    return new SceneRequest(
        sceneId, messages, currentSceneMessages, involvedPlayerIds, involvedPlayerNames,
        followUpEligiblePlayerIds, groupParticipantCandidateIds, autoGroupParticipantIds,
        groupParticipantCandidatesByName, context, currentActionText, providerIndex, newRetryCount,
        triggerAtMs);
  }
}
