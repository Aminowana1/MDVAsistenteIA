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
  public final String header;

  public PlayerChatMessage(ServerAssistantPlugin plugin, Player player, String content) {
    super(plugin, content);
    this.playerId = player.getUniqueId();
    this.playerName = player.getName();
    this.displayName = PlainTextComponentSerializer.plainText().serialize(player.displayName());
    this.admin = player.isOp() || player.hasPermission("sva.admin");
    this.header = "[PLAYER name=" + playerName + " admin=" + admin + "] " + displayName + " > ";
  }

  public PlayerChatMessage(
      ServerAssistantPlugin plugin,
      UUID playerId,
      String playerName,
      String displayName,
      boolean admin,
      String content) {
    super(plugin, content);
    this.playerId = playerId;
    this.playerName = playerName == null ? "unknown" : playerName;
    this.displayName = displayName == null || displayName.isBlank() ? this.playerName : displayName;
    this.admin = admin;
    this.header = "[PLAYER name=" + this.playerName + " admin=" + admin + "] " + this.displayName + " > ";
  }
}
