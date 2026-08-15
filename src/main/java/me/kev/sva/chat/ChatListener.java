package me.kev.sva.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Bukkit;
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

/** Feeds the one global public-conversation log. Events are context only. */
public final class ChatListener implements Listener {
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
    plugin.getServer().getScheduler().runTask(plugin, () -> {
      Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        conversationManager.handlePlayerMessage(player, message);
      }
    });
  }

  private boolean captureEvent(String eventName) {
    if (plugin.getConfig().isSet("global-conversation.events.enabled")) {
      if (!plugin.getConfig().getBoolean("global-conversation.events.enabled", true)) {
        return false;
      }
      return plugin.getConfig().getBoolean("global-conversation.events." + eventName, false);
    }

    // 1.4.x compatibility: old `global-events.enabled` meant "make an AI request".
    // In 1.5 events never request AI by themselves, so preserve only the per-event
    // capture choices and intentionally ignore the old master trigger switch.
    String oldPath = "request-triggers.global-events.events." + eventName;
    if (plugin.getConfig().isSet(oldPath)) {
      return plugin.getConfig().getBoolean(oldPath, false);
    }
    return "player-death".equals(eventName);
  }

  private String plain(Component component) {
    return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
  }

  @EventHandler
  public void onPlayerDeath(PlayerDeathEvent event) {
    if (!captureEvent("player-death")) return;
    Player victim = event.getEntity();
    Player killer = victim.getKiller();
    List<String> actors = new ArrayList<>();
    actors.add(victim.getName());
    if (killer != null) actors.add(killer.getName());

    String text = plain(event.deathMessage());
    if (text.isBlank()) {
      text = victim.getName() + " died";
    }
    if (victim.getLastDamageCause() != null) {
      text += " [cause=" + victim.getLastDamageCause().getCause().name() + "]";
    }
    conversationManager.recordServerEvent("player-death", text, actors);
  }

  @EventHandler
  public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
    if (!captureEvent("player-advancement")) return;
    String text = plain(event.message());
    if (!text.isBlank()) {
      conversationManager.recordServerEvent(
          "player-advancement", text, List.of(event.getPlayer().getName()));
    }
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    if (!captureEvent("player-join")) return;
    String text = plain(event.joinMessage());
    if (text.isBlank()) text = event.getPlayer().getName() + " joined";
    conversationManager.recordServerEvent(
        "player-join", text, List.of(event.getPlayer().getName()));
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    conversationManager.handlePlayerDisconnect(event.getPlayer().getUniqueId());
    if (!captureEvent("player-quit")) return;
    String text = plain(event.quitMessage());
    if (text.isBlank()) text = event.getPlayer().getName() + " left";
    conversationManager.recordServerEvent(
        "player-quit", text, List.of(event.getPlayer().getName()));
  }

  @EventHandler
  public void onPlayerKick(PlayerKickEvent event) {
    conversationManager.handlePlayerDisconnect(event.getPlayer().getUniqueId());
    if (!captureEvent("player-kick")) return;
    String text = plain(event.leaveMessage());
    if (text.isBlank()) text = event.getPlayer().getName() + " was kicked";
    conversationManager.recordServerEvent(
        "player-kick", text, List.of(event.getPlayer().getName()));
  }
}
