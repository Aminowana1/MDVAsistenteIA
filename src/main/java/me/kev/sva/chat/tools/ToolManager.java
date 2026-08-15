package me.kev.sva.chat.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.all.Tool;
import me.kev.sva.chat.tools.all.WikiTool;

/**
 * Explicit allow-list of tools. Unknown names are rejected and plain chat can
 * never execute a tool. Future 2.0 actions should be registered here with their
 * own permission/state validation instead of exposing arbitrary console commands.
 */
public final class ToolManager {
  private final Map<String, Tool> tools = new LinkedHashMap<>();

  public ToolManager(ServerAssistantPlugin plugin) {
    register(new WikiTool(plugin));
  }

  public void register(Tool tool) {
    if (tool == null || tool.name == null || tool.name.isBlank()) {
      return;
    }
    tools.put(tool.name.toLowerCase(Locale.ROOT), tool);
  }

  public String executeCalls(List<String> toolCalls, int maxToolCalls) {
    if (toolCalls == null || toolCalls.isEmpty()) {
      return "";
    }

    StringBuilder results = new StringBuilder();
    int processed = 0;
    for (String rawCall : toolCalls) {
      if (maxToolCalls > 0 && processed >= maxToolCalls) {
        results.append("Tool call limit reached.\n");
        break;
      }
      if (rawCall == null || rawCall.isBlank()) {
        continue;
      }
      processed++;

      String[] parts = rawCall.trim().split("\\s+", 2);
      String toolName = parts[0].toLowerCase(Locale.ROOT);
      String arguments = parts.length > 1 ? parts[1].trim() : "";
      Tool tool = tools.get(toolName);
      if (tool == null) {
        results.append("Unknown tool: ").append(toolName).append('\n');
        continue;
      }

      try {
        results.append(toolName).append(": ")
            .append(tool.execute(arguments))
            .append('\n');
      } catch (Exception ex) {
        results.append(toolName).append(": tool failed safely.\n");
      }
    }
    return results.toString().trim();
  }
}
