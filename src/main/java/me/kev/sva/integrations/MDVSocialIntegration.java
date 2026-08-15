package me.kev.sva.integrations;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.kev.sva.ServerAssistantPlugin;

/** Read-only optional MDVSocial integration for the currently equipped title. */
public final class MDVSocialIntegration implements PlayerContextIntegration {
  private final ServerAssistantPlugin plugin;

  public MDVSocialIntegration(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String id() {
    return "mdvsocial";
  }

  @Override
  public boolean enabled() {
    return plugin.getIntegrationsConfig().getBoolean("enabled", true)
        && plugin.getIntegrationsConfig().getBoolean("mdvsocial.enabled", true);
  }

  @Override
  public boolean available() {
    Plugin social = Bukkit.getPluginManager().getPlugin("MDVSocial");
    return social != null && social.isEnabled();
  }

  @Override
  public String status() {
    return "mdvsocial=" + (enabled() ? "enabled" : "disabled")
        + ",available=" + available();
  }

  @Override
  public String build(Player player, ProfileQuery query) {
    if (player == null || !enabled() || !available()) return "";
    if (!(query.title() || query.general())) return "";

    List<String> fields = new ArrayList<>();
    if (plugin.getIntegrationsConfig().getBoolean("mdvsocial.title.include-display", true)) {
      String display = getApiValue("getEquippedTitlePlain", player.getUniqueId());
      if (display.isBlank()) display = resolvePlaceholder(player, "%mdvsocial_title%");
      fields.add("title=" + (display.isBlank() ? "none" : compact(display)));
    }
    if (plugin.getIntegrationsConfig().getBoolean("mdvsocial.title.include-id", false)) {
      String id = getApiValue("getEquippedTitleId", player.getUniqueId());
      if (id.isBlank()) id = resolvePlaceholder(player, "%mdvsocial_title_id%");
      if (!id.isBlank()) fields.add("title_id=" + compact(id));
    }
    if (plugin.getIntegrationsConfig().getBoolean("mdvsocial.title.include-unlocked-count", false)) {
      String count = resolvePlaceholder(player, "%mdvsocial_unlocked_titles%");
      if (!count.isBlank()) fields.add("unlocked_titles=" + compact(count));
    }
    return fields.isEmpty() ? "" : "MDVSOCIAL " + String.join(" ", fields);
  }

  private String getApiValue(String methodName, UUID uuid) {
    try {
      Plugin social = Bukkit.getPluginManager().getPlugin("MDVSocial");
      if (social == null) return "";
      Class<?> api = Class.forName(
          "com.mdvcraft.mdvsocial.MDVSocialAPI", true, social.getClass().getClassLoader());
      Method method = api.getMethod(methodName, UUID.class);
      return clean(String.valueOf(method.invoke(null, uuid)));
    } catch (Throwable ignored) {
      return "";
    }
  }

  private String resolvePlaceholder(Player player, String placeholder) {
    if (!plugin.getIntegrationsConfig().getBoolean("mdvsocial.placeholder-fallback", true)) return "";
    try {
      Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
      if (papi == null || !papi.isEnabled()) return "";
      Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
      Method method = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
      String resolved = clean(String.valueOf(method.invoke(null, player, placeholder)));
      if (resolved.equalsIgnoreCase(placeholder) || resolved.contains("%mdvsocial_")) return "";
      return resolved;
    } catch (Throwable ignored) {
      return "";
    }
  }

  private static String clean(String value) {
    if (value == null || value.equals("null")) return "";
    return value
        .replaceAll("(?i)§[0-9A-FK-ORX]", "")
        .replaceAll("(?i)&[0-9A-FK-ORX]", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private static String compact(String value) {
    String cleaned = clean(value);
    if (cleaned.indexOf(' ') >= 0 || cleaned.indexOf(',') >= 0 || cleaned.indexOf('|') >= 0) {
      return '"' + cleaned.replace("\"", "'") + '"';
    }
    return cleaned;
  }
}
