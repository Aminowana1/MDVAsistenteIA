package me.kev.sva.chat.message;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantResponse;

public class AssistantChatMessage extends ChatMessage {
  public final AssistantResponse response;

  /**
   * Builds a history-only assistant turn. The text is already normalized visible chat,
   * so parsing it again as model protocol would be both wasteful and could emit
   * misleading relationship debug lines.
   */
  public AssistantChatMessage(ServerAssistantPlugin plugin, String content) {
    super(plugin, content == null ? "" : content.trim());
    this.response = null;
  }

  public AssistantChatMessage(ServerAssistantPlugin plugin, AssistantResponse response) {
    super(plugin, response.historyText());
    this.response = response;
  }
}
