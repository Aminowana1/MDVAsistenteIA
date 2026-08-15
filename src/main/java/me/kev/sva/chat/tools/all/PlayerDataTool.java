package me.kev.sva.chat.tools.all;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.tools.ToolKind;

/** Local one-call player status context inspired by the upstream player-data tool. */
public final class PlayerDataTool extends Tool {
  private static final List<String> INTENT_TERMS = List.of(
      "donde", "ubicacion", "coordenad", "coords", "position", "location",
      "vida", "health", "hambre", "food", "nivel", "level", "xp", "experiencia",
      "estado", "status", "gamemode", "modo de juego", "volando", "flying",
      "sprint", "nadando", "swimming", "invisible");

  public PlayerDataTool(ServerAssistantPlugin plugin) {
    super(plugin, "player-data");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.CONTEXT;
  }

  @Override
  public String usage() {
    return "Local trusted data about an online player's location/status.";
  }

  @Override
  public boolean shouldPrefetch(String normalizedSceneText, List<ChatMessage> currentSceneMessages) {
    for (String term : INTENT_TERMS) {
      if (normalizedSceneText.contains(term)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String buildLocalContext(List<String> involvedPlayerNames) {
    int maxPlayers = Math.max(plugin.getConfig().getInt("tools.player-data.max-players", 2), 1);
    boolean includeLocation = plugin.getConfig().getBoolean("tools.player-data.include-location", true);
    boolean includeStatus = plugin.getConfig().getBoolean("tools.player-data.include-status", true);

    List<String> rows = new ArrayList<>();
    for (String name : involvedPlayerNames) {
      if (rows.size() >= maxPlayers) break;
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) continue;
      rows.add(compact(player, includeLocation, includeStatus));
    }
    return String.join("\n", rows);
  }

  @Override
  public String execute(String arguments) {
    String playerName = arguments == null ? "" : arguments.trim();
    if (playerName.isBlank() || playerName.contains(" ")) {
      return "Usage: player-data <player>";
    }
    Player player = Bukkit.getPlayerExact(playerName);
    if (player == null) {
      return "Player '" + playerName + "' is not online.";
    }

    var location = player.getLocation();
    return "Player=" + player.getName()
        + ", world=" + player.getWorld().getName()
        + ", xyz=" + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", location.getX(), location.getY(), location.getZ())
        + ", gamemode=" + player.getGameMode().name()
        + ", health=" + String.format(Locale.ROOT, "%.1f", player.getHealth())
        + ", food=" + player.getFoodLevel()
        + ", saturation=" + String.format(Locale.ROOT, "%.1f", player.getSaturation())
        + ", level=" + player.getLevel()
        + ", exp=" + String.format(Locale.ROOT, "%.2f", player.getExp())
        + ", flying=" + player.isFlying()
        + ", sneaking=" + player.isSneaking()
        + ", sprinting=" + player.isSprinting()
        + ", swimming=" + player.isSwimming()
        + ", gliding=" + player.isGliding()
        + ", invisible=" + player.isInvisible();
  }

  private String compact(Player player, boolean includeLocation, boolean includeStatus) {
    StringBuilder out = new StringBuilder("PLAYER_DATA ").append(player.getName());
    if (includeLocation) {
      var loc = player.getLocation();
      out.append(" world=").append(player.getWorld().getName())
          .append(" xyz=").append(loc.getBlockX()).append(',').append(loc.getBlockY()).append(',').append(loc.getBlockZ());
    }
    if (includeStatus) {
      out.append(" mode=").append(player.getGameMode().name())
          .append(" hp=").append(String.format(Locale.ROOT, "%.1f", player.getHealth()))
          .append(" food=").append(player.getFoodLevel())
          .append(" lvl=").append(player.getLevel())
          .append(" flying=").append(player.isFlying())
          .append(" sneaking=").append(player.isSneaking())
          .append(" sprinting=").append(player.isSprinting());
    }
    return out.toString();
  }
}
