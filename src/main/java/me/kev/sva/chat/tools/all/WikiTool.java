package me.kev.sva.chat.tools.all;

import org.bukkit.configuration.ConfigurationSection;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.ToolKind;

/** Wiki remains a local pre-request knowledge source; this class also makes it testable from /sva tools run. */
public final class WikiTool extends Tool {
  public WikiTool(ServerAssistantPlugin plugin) {
    super(plugin, "wiki");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.CONTEXT;
  }

  @Override
  public String usage() {
    return "Server wiki. Retrieved locally before the model call when relevant.";
  }

  @Override
  public String execute(String arguments) {
    String key = arguments == null ? "" : arguments.trim();
    if (key.isBlank() || key.contains(" ")) return "Usage: wiki <key>";
    ConfigurationSection wiki = wikiRoot();
    if (wiki == null) return "No wiki sections are configured.";
    ConfigurationSection section = wiki.getConfigurationSection(key);
    if (section == null) return "Unknown wiki key: " + key;
    String content = section.getString("content", "");
    return content == null || content.isBlank() ? "Wiki section is empty." : content;
  }

  public ConfigurationSection wikiRoot() {
    return plugin.getWikiConfig().getConfigurationSection("wiki");
  }
}
