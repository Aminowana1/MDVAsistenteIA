package me.kev.sva.chat.assistant;

/** Immutable metadata for one global public-chat scene. */
public record AssistantRequestContext(
    long sceneId,
    String involvedPlayers,
    String sceneMeta,
    String locallyRetrievedWiki) {

  public static AssistantRequestContext scene(
      long sceneId,
      String involvedPlayers,
      String sceneMeta,
      String locallyRetrievedWiki) {
    return new AssistantRequestContext(
        sceneId,
        involvedPlayers == null ? "" : involvedPlayers,
        sceneMeta == null ? "" : sceneMeta,
        locallyRetrievedWiki == null ? "" : locallyRetrievedWiki);
  }
}
