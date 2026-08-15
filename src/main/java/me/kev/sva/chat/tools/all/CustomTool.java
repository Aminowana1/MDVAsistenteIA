package me.kev.sva.chat.tools.all;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.ToolKind;

/** Placeholder for future explicitly configured ACTION tools. Not registered by default. */
public class CustomTool extends Tool {

  public CustomTool(ServerAssistantPlugin plugin, String name) {
    super(plugin, name);
  }

  @Override
  public ToolKind kind() {
    return ToolKind.ACTION;
  }

  @Override
  public String usage() {
    return name + " — custom tool placeholder.";
  }

  @Override
  public String execute(String arguments) {
    return "Custom tool is not implemented.";
  }
}
