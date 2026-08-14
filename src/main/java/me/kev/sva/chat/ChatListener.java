package me.kev.sva.chat;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.kev.sva.ServerAssistantPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public class ChatListener implements Listener {

  private final ConversationManager conversationManager;
  private final ServerAssistantPlugin plugin;

  public ChatListener(ServerAssistantPlugin plugin, ConversationManager conversationManager) {
    this.plugin = plugin;
    this.conversationManager = conversationManager;
  }

  @EventHandler(ignoreCancelled = true)
  public void onChat(AsyncChatEvent event) {
    UUID playerId = event.getPlayer().getUniqueId();
    String message = PlainTextComponentSerializer.plainText().serialize(event.message());

    // AsyncChatEvent is asynchronous. Capture only immutable data here and move all
    // Bukkit/player/session work to the main thread.
    plugin.getServer().getScheduler().runTask(plugin, () -> {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        conversationManager.handlePlayerMessage(player, message);
      }
    });
  }

  // ------------------------------------------------------------
  // GLOBAL EVENTS
  // ------------------------------------------------------------

  private boolean shouldProcessGlobalEvent(String eventName) {
    FileConfiguration config = plugin.getConfig();
    if (!config.getBoolean("request-triggers.global-events.enabled", true)) {
      return false;
    }
    return config.getBoolean("request-triggers.global-events.events." + eventName, false);
  }

  private void queueGlobalEvent(String message) {
    if (message != null && !message.isBlank()) {
      conversationManager.queueGlobalEvent(message);
    }
  }

  private String plain(Component component) {
    return PlainTextComponentSerializer.plainText().serialize(component);
  }

  @EventHandler
  public void onPlayerDeath(PlayerDeathEvent event) {
    if (!shouldProcessGlobalEvent("player-death")) {
      return;
    }
    Component message = event.deathMessage();
    if (message != null) {
      queueGlobalEvent(plain(message));
    }
  }

  @EventHandler
  public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
    if (!shouldProcessGlobalEvent("player-advancement")) {
      return;
    }
    Component message = event.message();
    if (message != null) {
      queueGlobalEvent(plain(message));
    }
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (!shouldProcessGlobalEvent("player-join")) {
      return;
    }
    Component message = event.joinMessage();
    if (message != null) {
      queueGlobalEvent(plain(message));
    }
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    conversationManager.handlePlayerDisconnect(event.getPlayer().getUniqueId());

    if (!shouldProcessGlobalEvent("player-quit")) {
      return;
    }
    Component message = event.quitMessage();
    if (message != null) {
      queueGlobalEvent(plain(message));
    }
  }

  @EventHandler
  public void onPlayerKick(PlayerKickEvent event) {
    conversationManager.handlePlayerDisconnect(event.getPlayer().getUniqueId());

    if (!shouldProcessGlobalEvent("player-kick")) {
      return;
    }
    Component message = event.leaveMessage();
    if (message != null) {
      queueGlobalEvent(plain(message));
    }
  }
}
