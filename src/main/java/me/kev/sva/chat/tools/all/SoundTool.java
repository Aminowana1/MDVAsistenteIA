package me.kev.sva.chat.tools.all;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.ToolKind;

public final class SoundTool extends Tool {
  private static final Map<String, String> SEMANTIC_HINTS = new LinkedHashMap<>();

  static {
    SEMANTIC_HINTS.put("creeper", "scare / creeper hiss");
    SEMANTIC_HINTS.put("explosion", "boom / explosion");
    SEMANTIC_HINTS.put("anvil", "metallic impact");
    SEMANTIC_HINTS.put("level-up", "celebration / success");
    SEMANTIC_HINTS.put("villager", "villager ambient");
    SEMANTIC_HINTS.put("enderman", "eerie / scary scream");
    SEMANTIC_HINTS.put("ghast", "scary ghast scream");
    SEMANTIC_HINTS.put("lightning", "thunder");
    SEMANTIC_HINTS.put("goat", "goat scream");
    SEMANTIC_HINTS.put("raid-horn", "dramatic horn / announcement");
  }
  public SoundTool(ServerAssistantPlugin plugin) {
    super(plugin, "sound");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.ACTION;
  }

  @Override
  public String usage() {
    ConfigurationSection sounds = plugin.getConfig().getConfigurationSection("tools.sound.sounds");
    if (sounds == null) {
      return "sound <name> — plays one curated sound to all online players. names=none";
    }
    StringBuilder available = new StringBuilder();
    for (String name : sounds.getKeys(false)) {
      if (!available.isEmpty()) available.append(", ");
      available.append(name);
      String hint = SEMANTIC_HINTS.get(name.toLowerCase(Locale.ROOT));
      if (hint != null && !hint.isBlank()) available.append('(').append(hint).append(')');
    }
    return "sound <name> — plays one curated sound to all online players. Choose by meaning when requested. names="
        + available;
  }

  @Override
  public String execute(String arguments) {
    String name = arguments == null ? "" : arguments.trim();
    if (name.isBlank() || name.contains(" ")) return "Usage: sound <name>";
    ConfigurationSection sounds = plugin.getConfig().getConfigurationSection("tools.sound.sounds");
    if (sounds == null) return "No sounds are configured.";
    String soundName = sounds.getString(name, "");
    if (soundName == null || soundName.isBlank()) return "Unknown sound: " + name;

    Sound sound;
    try {
      sound = Sound.valueOf(soundName.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      plugin.getLogger().warning("Invalid Bukkit sound configured for '" + name + "': " + soundName);
      return "Configured sound '" + name + "' is invalid.";
    }

    for (Player player : plugin.getServer().getOnlinePlayers()) {
      player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }
    return "Played sound '" + name + "' to all online players.";
  }
}
