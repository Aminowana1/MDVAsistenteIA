package me.kev.sva.chat.assistant;

/** Immutable trusted metadata/context for one global public-chat scene. */
public record AssistantRequestContext(
    long sceneId,
    String involvedPlayers,
    String sceneMeta,
    String playerIdentityContext,
    String locallyRetrievedWiki,
    String localToolContext,
    String recentEventContext,
    String currentActionText) {

  public static AssistantRequestContext scene(
      long sceneId,
      String involvedPlayers,
      String sceneMeta,
      String playerIdentityContext,
      String locallyRetrievedWiki,
      String localToolContext,
      String recentEventContext,
      String currentActionText) {
    return new AssistantRequestContext(
        sceneId,
        involvedPlayers == null ? "" : involvedPlayers,
        sceneMeta == null ? "" : sceneMeta,
        playerIdentityContext == null ? "" : playerIdentityContext,
        locallyRetrievedWiki == null ? "" : locallyRetrievedWiki,
        localToolContext == null ? "" : localToolContext,
        recentEventContext == null ? "" : recentEventContext,
        currentActionText == null ? "" : currentActionText);
  }
}
