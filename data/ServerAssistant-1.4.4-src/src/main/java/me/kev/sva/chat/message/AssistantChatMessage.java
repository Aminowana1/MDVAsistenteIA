package me.kev.sva.chat.message;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.assistant.AssistantResponse;

public class AssistantChatMessage extends ChatMessage {
  public final AssistantResponse response;

  public AssistantChatMessage(ServerAssistantPlugin plugin, String content) {
    this(plugin, new AssistantResponse(plugin, content));
  }

  public AssistantChatMessage(ServerAssistantPlugin plugin, AssistantResponse response) {
    super(plugin, response.historyText());
    this.response = response;
  }
}
