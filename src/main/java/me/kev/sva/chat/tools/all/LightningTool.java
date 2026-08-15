package me.kev.sva.chat.tools.all;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.ToolKind;

public final class LightningTool extends Tool {
  public LightningTool(ServerAssistantPlugin plugin) {
    super(plugin, "lightning");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.ACTION;
  }

  @Override
  public String usage() {
    return "lightning <player> — harmless visual/audio lightning at an ONLINE player's location; no damage or fire.";
  }

  @Override
  public String execute(String arguments) {
    String playerName = arguments == null ? "" : arguments.trim();
    if (playerName.isBlank() || playerName.contains(" ")) return "Usage: lightning <player>";
    Player player = Bukkit.getPlayerExact(playerName);
    if (player == null) return "Player '" + playerName + "' is not online.";
    player.getWorld().strikeLightningEffect(player.getLocation());
    return "Created harmless lightning at " + player.getName() + ".";
  }
}
