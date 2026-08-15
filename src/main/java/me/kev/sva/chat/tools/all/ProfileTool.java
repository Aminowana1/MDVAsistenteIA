package me.kev.sva.chat.tools.all;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.tools.ContextTargetResolver;
import me.kev.sva.chat.tools.ToolKind;
import me.kev.sva.chat.tools.ToolManager;
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

    // Resolve profile context from profile-related lines only. This preserves the
    // single group scene while preventing an unrelated last speaker from stealing
    // the race/title/profession target.
    List<ChatMessage> queryMessages = selectRelevantMessages(currentSceneMessages);
    String queryText = normalizedText(queryMessages);
    if (queryText.isBlank()) queryText = normalizedSceneText == null ? "" : normalizedSceneText;

    List<String> targets = ContextTargetResolver.resolve(
        involvedPlayerNames, queryText, queryMessages, maxPlayers);
    return plugin.getIntegrationManager().buildProfileContext(targets, queryText, false);
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

  private List<ChatMessage> selectRelevantMessages(List<ChatMessage> currentSceneMessages) {
    if (currentSceneMessages == null || currentSceneMessages.isEmpty()) return List.of();
    List<ChatMessage> relevant = new ArrayList<>();
    for (ChatMessage message : currentSceneMessages) {
      if (!(message instanceof PlayerChatMessage) || message.content == null) continue;
      if (ProfileQuery.from(message.content).any()) relevant.add(message);
    }
    return relevant.isEmpty() ? currentSceneMessages : List.copyOf(relevant);
  }

  private String normalizedText(List<ChatMessage> messages) {
    StringBuilder out = new StringBuilder();
    if (messages != null) {
      for (ChatMessage message : messages) {
        if (message != null && message.content != null) out.append(message.content).append(' ');
      }
    }
    return ToolManager.normalize(out.toString());
  }
}
