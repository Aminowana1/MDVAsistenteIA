package me.kev.sva.chat.tools.all;

import org.bukkit.configuration.ConfigurationSection;

import me.kev.sva.ServerAssistantPlugin;

public class WikiTool extends Tool {

  public WikiTool(ServerAssistantPlugin plugin) {
    super(plugin, "wiki");
  }

  public String getIndex() {
    ConfigurationSection wiki = plugin.getConfig()
        .getConfigurationSection("advanced-context.wiki");

    if (wiki == null) {
      return "";
    }

    StringBuilder result = new StringBuilder();

    for (String key : wiki.getKeys(false)) {
      ConfigurationSection section = wiki.getConfigurationSection(key);
      if (section == null) {
        continue;
      }

      String description = section.getString("description", "");
      description = description == null ? "" : description.replaceAll("\\s+", " ").trim();

      result.append(key);
      if (!description.isBlank()) {
        result.append(": ").append(description);
      }
      result.append('\n');
    }

    return result.toString().trim();
  }

  public String getWiki(String key) {
    ConfigurationSection wiki = plugin.getConfig()
        .getConfigurationSection("advanced-context.wiki");

    if (wiki == null) {
      return "No wiki sections are configured.";
    }

    ConfigurationSection section = wiki.getConfigurationSection(key);

    if (section == null) {
      return "Unknown wiki key: " + key;
    }

    // MessageSender.Success(section.getString("content", ""));
    return section.getString("content", "");
  }
  @Override
  public String execute(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return "Missing required wiki key.";
    }
    return getWiki(arguments.trim());
  }

}
