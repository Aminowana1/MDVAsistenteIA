package me.kev.sva.chat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Nearby players that {@link GroupThreadSelector} decided belong to Isolda's active thread. */
record GroupThreadSelection(
    List<PublicChatRecord> preCandidates,
    Set<PublicChatRecord> candidateRecords,
    Set<UUID> candidateIds,
    Set<UUID> autoParticipantIds,
    Map<String, UUID> candidateByName) {
  static GroupThreadSelection empty() {
    return new GroupThreadSelection(List.of(), Set.of(), Set.of(), Set.of(), Map.of());
  }
}
