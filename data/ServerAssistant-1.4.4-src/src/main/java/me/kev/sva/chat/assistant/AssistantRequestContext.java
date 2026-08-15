package me.kev.sva.chat.assistant;

/** Immutable metadata describing the logical conversation being answered. */
public record AssistantRequestContext(
    boolean global,
    long conversationId,
    String participants,
    int participantCount,
    int activePlayerConversations,
    String recentServerEvents) {

  public static AssistantRequestContext conversation(
      long conversationId,
      String participants,
      int participantCount,
      int activePlayerConversations,
      String recentServerEvents) {
    return new AssistantRequestContext(
        false,
        conversationId,
        participants,
        participantCount,
        activePlayerConversations,
        recentServerEvents);
  }

  public static AssistantRequestContext global(
      int activePlayerConversations,
      String recentServerEvents) {
    return new AssistantRequestContext(
        true,
        0,
        "",
        0,
        activePlayerConversations,
        recentServerEvents);
  }
}
