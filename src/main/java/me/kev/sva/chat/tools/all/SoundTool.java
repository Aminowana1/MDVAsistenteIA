package me.kev.sva.chat.tools.all;

import java.util.Locale;

import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.ToolKind;

public final class SoundTool extends Tool {
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
    String available = sounds == null ? "none" : String.join(",", sounds.getKeys(false));
    return "sound <name> — plays one curated sound to all online players. names=" + available;
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
