package me.kev.sva.chat.tools.all;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
    return "lightning <player> — harmless visual/audio lightning at an ONLINE player's location; no damage or fire. "
        + "Use an exact online name when possible; a unique name prefix is accepted too.";
  }

  @Override
  public String execute(String arguments) {
    String requestedName = arguments == null ? "" : arguments.trim();
    if (requestedName.isBlank() || requestedName.contains(" ")) return "Usage: lightning <player>";

    ResolveResult resolved = resolveOnlinePlayer(requestedName);
    if (resolved.player() == null) {
      return resolved.ambiguous()
          ? "Player prefix '" + requestedName + "' is ambiguous; use the exact online name."
          : "Player '" + requestedName + "' is not online.";
    }

    Player player = resolved.player();
    player.getWorld().strikeLightningEffect(player.getLocation());
    return "Created harmless lightning at " + player.getName() + ".";
  }

  private ResolveResult resolveOnlinePlayer(String requestedName) {
    Player exact = Bukkit.getPlayerExact(requestedName);
    if (exact != null) return new ResolveResult(exact, false);

    String needle = requestedName.toLowerCase(Locale.ROOT);
    for (Player online : Bukkit.getOnlinePlayers()) {
      if (online.getName().equalsIgnoreCase(requestedName)) {
        return new ResolveResult(online, false);
      }
    }

    if (needle.length() < 3) return new ResolveResult(null, false);
    List<Player> prefixMatches = new ArrayList<>();
    for (Player online : Bukkit.getOnlinePlayers()) {
      if (online.getName().toLowerCase(Locale.ROOT).startsWith(needle)) {
        prefixMatches.add(online);
      }
    }
    if (prefixMatches.size() == 1) return new ResolveResult(prefixMatches.get(0), false);
    if (prefixMatches.size() > 1) return new ResolveResult(null, true);
    return new ResolveResult(null, false);
  }

  private record ResolveResult(Player player, boolean ambiguous) {}
}
