package me.kev.sva.integrations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;

/**
 * Registry for optional external-plugin integrations.
 *
 * <p>All integrations are read-only context sources. They never create extra AI
 * requests and can be disabled independently in integrations.yml.</p>
 */
public final class IntegrationManager {
  private final ServerAssistantPlugin plugin;
  private final List<PlayerContextIntegration> integrations = new ArrayList<>();

  public IntegrationManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    integrations.add(new MMOCoreIntegration(plugin));
    integrations.add(new MDVSocialIntegration(plugin));
  }

  public String buildProfileContext(List<String> involvedNames, String normalizedSceneText, boolean allFields) {
    if (!plugin.getIntegrationsConfig().getBoolean("enabled", true)) return "";
    if (!plugin.getIntegrationsConfig().getBoolean("profile-context.enabled", true)) return "";

    ProfileQuery query = allFields ? ProfileQuery.all() : ProfileQuery.from(normalizedSceneText);
    if (!query.any()) return "";

    int maxPlayers = Math.max(plugin.getIntegrationsConfig().getInt("profile-context.max-players", 2), 1);
    List<String> names = involvedNames == null
        ? List.of()
        : new ArrayList<>(new LinkedHashSet<>(involvedNames));

    List<String> rows = new ArrayList<>();
    for (String name : names) {
      if (rows.size() >= maxPlayers) break;
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) continue;
      String row = buildPlayerRow(player, query);
      if (!row.isBlank()) rows.add(row);
    }
    return String.join("\n", rows);
  }

  public String buildFullProfile(Player player) {
    if (player == null) return "Player is not online.";
    String row = buildPlayerRow(player, ProfileQuery.all());
    return row.isBlank() ? "No enabled integration data is available for " + player.getName() + "." : row;
  }

  private String buildPlayerRow(Player player, ProfileQuery query) {
    List<String> pieces = new ArrayList<>();
    for (PlayerContextIntegration integration : integrations) {
      if (!integration.enabled() || !integration.available()) continue;
      try {
        String value = integration.build(player, query);
        if (value != null && !value.isBlank()) pieces.add(value.trim());
      } catch (Exception ex) {
        plugin.getLogger().warning("Integration '" + integration.id() + "' failed safely: "
            + ex.getClass().getSimpleName() + ": " + ex.getMessage());
      }
    }
    if (pieces.isEmpty()) return "";
    return "PLAYER_PROFILE " + player.getName() + " " + String.join(" | ", pieces);
  }

  public List<String> statusLines() {
    List<String> rows = new ArrayList<>();
    rows.add("integrations=" + (plugin.getIntegrationsConfig().getBoolean("enabled", true) ? "enabled" : "disabled"));
    rows.add("profile-context="
        + (plugin.getIntegrationsConfig().getBoolean("profile-context.enabled", true) ? "enabled" : "disabled"));
    for (PlayerContextIntegration integration : integrations) rows.add(integration.status());
    return rows;
  }

  public boolean setEnabled(String id, boolean enabled) {
    if (id == null || id.isBlank()) return false;
    String key = id.trim().toLowerCase();
    if (key.equals("all")) {
      plugin.getIntegrationsConfig().set("enabled", enabled);
      plugin.saveIntegrationsConfig();
      return true;
    }
    for (PlayerContextIntegration integration : integrations) {
      if (integration.id().equalsIgnoreCase(key)) {
        plugin.getIntegrationsConfig().set(integration.id() + ".enabled", enabled);
        plugin.saveIntegrationsConfig();
        return true;
      }
    }
    return false;
  }

  public List<String> integrationIds() {
    List<String> ids = new ArrayList<>();
    for (PlayerContextIntegration integration : integrations) ids.add(integration.id());
    ids.add("all");
    return ids;
  }
}
