package me.kev.sva.chat.tools.all;

import me.kev.sva.ServerAssistantPlugin;

/** Placeholder for future explicitly configured tools. Not registered by default. */
public class CustomTool extends Tool {

  public CustomTool(ServerAssistantPlugin plugin, String name) {
    super(plugin, name);
  }

  @Override
  public String execute(String arguments) {
    return "Custom tool is not implemented.";
  }
}
