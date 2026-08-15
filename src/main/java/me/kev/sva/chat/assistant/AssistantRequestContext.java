package me.kev.sva.chat.assistant;

/** Immutable trusted metadata/context for one global public-chat scene. */
public record AssistantRequestContext(
    long sceneId,
    String involvedPlayers,
    String sceneMeta,
    String locallyRetrievedWiki,
    String localToolContext,
    String recentEventContext) {

  public static AssistantRequestContext scene(
      long sceneId,
      String involvedPlayers,
      String sceneMeta,
      String locallyRetrievedWiki,
      String localToolContext,
      String recentEventContext) {
    return new AssistantRequestContext(
        sceneId,
        involvedPlayers == null ? "" : involvedPlayers,
        sceneMeta == null ? "" : sceneMeta,
        locallyRetrievedWiki == null ? "" : locallyRetrievedWiki,
        localToolContext == null ? "" : localToolContext,
        recentEventContext == null ? "" : recentEventContext);
  }
}
