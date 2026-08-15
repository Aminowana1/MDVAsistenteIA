package me.kev.sva.chat.tools.all;

import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.tools.ContextTargetResolver;
import me.kev.sva.chat.tools.ToolKind;
import me.kev.sva.integrations.ProfileQuery;

/** Local profile context assembled from optional integrations such as MMOCore/MDVSocial. */
public final class ProfileTool extends Tool {
  public ProfileTool(ServerAssistantPlugin plugin) {
    super(plugin, "profile");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.CONTEXT;
  }

  @Override
  public String usage() {
    return "Trusted optional profile data (race/class, RPG level, professions, attributes, stats and equipped title).";
  }

  @Override
  public boolean shouldPrefetch(String normalizedSceneText, List<ChatMessage> currentSceneMessages) {
    if (plugin.getIntegrationManager() == null) return false;
    if (!plugin.getIntegrationsConfig().getBoolean("enabled", true)
        || !plugin.getIntegrationsConfig().getBoolean("profile-context.enabled", true)) return false;
    return ProfileQuery.from(normalizedSceneText).any();
  }

  @Override
  public String buildLocalContext(
      List<String> involvedPlayerNames,
      String normalizedSceneText,
      List<ChatMessage> currentSceneMessages) {
    if (plugin.getIntegrationManager() == null) return "";
    int maxPlayers = Math.max(
        plugin.getIntegrationsConfig().getInt("profile-context.max-players", 2), 1);
    List<String> targets = ContextTargetResolver.resolve(
        involvedPlayerNames, normalizedSceneText, currentSceneMessages, maxPlayers);
    return plugin.getIntegrationManager().buildProfileContext(targets, normalizedSceneText, false);
  }

  @Override
  public String buildLocalContext(List<String> involvedPlayerNames) {
    if (plugin.getIntegrationManager() == null) return "";
    return plugin.getIntegrationManager().buildProfileContext(involvedPlayerNames, "perfil", true);
  }

  @Override
  public String execute(String arguments) {
    String playerName = arguments == null ? "" : arguments.trim();
    if (playerName.isBlank() || playerName.contains(" ")) return "Usage: profile <player>";
    Player player = Bukkit.getPlayerExact(playerName);
    if (player == null) return "Player '" + playerName + "' is not online.";
    if (plugin.getIntegrationManager() == null) return "Integration manager is not initialized.";
    return plugin.getIntegrationManager().buildFullProfile(player);
  }
}
