package me.kev.sva.chat.tools.all;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.ToolKind;

public final class MuteTool extends Tool {
  public MuteTool(ServerAssistantPlugin plugin) {
    super(plugin, "mute");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.ACTION;
  }

  @Override
  public String usage() {
    String duration = plugin.getConfig().getString("tools.mute.duration", "5m");
    return "mute <player> — temporarily mute an ONLINE player for " + duration
        + ". This is a moderation action and is normally mode=ask.";
  }

  @Override
  public String execute(String arguments) {
    String playerName = arguments == null ? "" : arguments.trim();
    if (playerName.isBlank() || playerName.contains(" ")) return "Usage: mute <player>";
    Player player = Bukkit.getPlayerExact(playerName);
    if (player == null) return "Player '" + playerName + "' is not online.";

    boolean allowAdminTargets = plugin.getConfig().getBoolean("tools.mute.allow-admin-targets", false);
    if (!allowAdminTargets && (player.isOp() || player.hasPermission("sva.admin"))) {
      return "Refused to mute an administrator.";
    }

    String duration = plugin.getConfig().getString("tools.mute.duration", "5m");
    if (duration == null || !duration.matches("(?i)[0-9]{1,4}[smhd]")) duration = "5m";
    String template = plugin.getConfig().getString("tools.mute.command", "mute %player% %duration%");
    if (template == null || template.isBlank()) return "Mute command is not configured.";
    String command = template
        .replace("%player%", player.getName())
        .replace("%duration%", duration);

    boolean success = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    return success
        ? "Muted " + player.getName() + " for " + duration + "."
        : "Mute command failed for " + player.getName() + ".";
  }
}
