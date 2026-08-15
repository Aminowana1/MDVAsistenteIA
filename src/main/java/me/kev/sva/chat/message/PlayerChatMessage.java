package me.kev.sva.chat.message;

import java.util.UUID;

import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Immutable snapshot of a player chat message.
 *
 * Instances are intentionally created on the Bukkit main thread so the AI
 * worker never needs to read live Player state.
 */
public class PlayerChatMessage extends ChatMessage {
  public final UUID playerId;
  public final String playerName;
  public final String displayName;
  public final boolean admin;
  public final String identitySummary;
  public final String header;

  public PlayerChatMessage(ServerAssistantPlugin plugin, Player player, String content) {
    super(plugin, content);
    this.playerId = player.getUniqueId();
    this.playerName = player.getName();
    this.displayName = PlainTextComponentSerializer.plainText().serialize(player.displayName());
    this.admin = player.isOp() || player.hasPermission("sva.admin");
    this.identitySummary = plugin.getIntegrationManager() == null
        ? ""
        : plugin.getIntegrationManager().buildAmbientIdentity(player);
    this.header = buildHeader(this.playerName, this.displayName, this.admin, this.identitySummary);
  }

  public PlayerChatMessage(
      ServerAssistantPlugin plugin,
      UUID playerId,
      String playerName,
      String displayName,
      boolean admin,
      String content) {
    this(plugin, playerId, playerName, displayName, admin, "", content);
  }

  public PlayerChatMessage(
      ServerAssistantPlugin plugin,
      UUID playerId,
      String playerName,
      String displayName,
      boolean admin,
      String identitySummary,
      String content) {
    super(plugin, content);
    this.playerId = playerId;
    this.playerName = playerName == null ? "unknown" : playerName;
    this.displayName = displayName == null || displayName.isBlank() ? this.playerName : displayName;
    this.admin = admin;
    this.identitySummary = identitySummary == null ? "" : identitySummary.trim();
    this.header = buildHeader(this.playerName, this.displayName, this.admin, this.identitySummary);
  }

  private static String buildHeader(String playerName, String displayName, boolean admin, String identitySummary) {
    String identity = identitySummary == null || identitySummary.isBlank()
        ? ""
        : " " + identitySummary.trim();
    return "[PLAYER name=" + playerName + identity + " admin=" + admin + "] " + displayName + " > ";
  }
}
