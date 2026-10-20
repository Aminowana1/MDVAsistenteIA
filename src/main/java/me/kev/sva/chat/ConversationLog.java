package me.kev.sva.chat;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling local logs shared by the conversation components. They live only in RAM
 * and cost zero AI tokens until a scene actually selects something from them.
 * Everything here is touched on the Bukkit main thread only.
 */
final class ConversationLog {
  private final Deque<PublicChatRecord> publicChat = new ArrayDeque<>();
  private final Deque<ServerEventRecord> serverEvents = new ArrayDeque<>();
  /** The last answered scenes, replayed as short conversational history. */
  private final Deque<SceneMemory> sceneHistory = new ArrayDeque<>();

  Deque<PublicChatRecord> publicChat() {
    return publicChat;
  }

  Deque<ServerEventRecord> serverEvents() {
    return serverEvents;
  }

  Deque<SceneMemory> sceneHistory() {
    return sceneHistory;
  }

  void clear() {
    publicChat.clear();
    serverEvents.clear();
    sceneHistory.clear();
  }
}
