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
    if (plugin.getActivityJournal() != null) {
      plugin.getActivityJournal().recordEvent("player-death", text, killer == null ? List.of(victim) : List.of(victim, killer));
    }
    if (captureEvent("player-death")) {
      conversationManager.recordServerEvent("player-death", text, actors);
    }
    conversationManager.maybeQueueRelationshipReaction(
        "player-death", text, killer == null ? List.of(victim) : List.of(victim, killer));
  }

  @EventHandler
  public void onPlayerAdvancement(PlayerAdvancementDoneEvent event) {
    String text = plain(event.message());
    if (!text.isBlank()) {
      if (plugin.getActivityJournal() != null) {
        plugin.getActivityJournal().recordEvent("player-advancement", text, List.of(event.getPlayer()));
      }
      if (captureEvent("player-advancement")) {
        conversationManager.recordServerEvent(
            "player-advancement", text, List.of(event.getPlayer().getName()));
      }
      conversationManager.maybeQueueRelationshipReaction("player-advancement", text, List.of(event.getPlayer()));
    }
  }

  @EventHandler
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (plugin.getActivityJournal() != null) plugin.getActivityJournal().recordJoin(player);
    if (plugin.getRelationshipManager() != null) plugin.getRelationshipManager().observePlayer(player);
    String text = plain(event.joinMessage());
    if (text.isBlank()) text = player.getName() + " joined";
    if (plugin.getActivityJournal() != null) {
      plugin.getActivityJournal().recordEvent("player-join", text, List.of(player));
    }
    if (captureEvent("player-join")) {
      conversationManager.recordServerEvent("player-join", text, List.of(player.getName()));
    }
    conversationManager.maybeQueueRelationshipReaction("player-join", text, List.of(player));
  }

  @EventHandler
  public void onPlayerQuit(PlayerQuitEvent event) {
    Player player = event.getPlayer();
    if (plugin.getActivityJournal() != null) plugin.getActivityJournal().recordDisconnect(player);
    conversationManager.handlePlayerDisconnect(player.getUniqueId());
    String text = plain(event.quitMessage());
    if (text.isBlank()) text = player.getName() + " left";
    if (plugin.getActivityJournal() != null) {
      plugin.getActivityJournal().recordEvent("player-quit", text, List.of(player));
    }
    if (captureEvent("player-quit")) {
      conversationManager.recordServerEvent("player-quit", text, List.of(player.getName()));
    }
    conversationManager.maybeQueueRelationshipReaction("player-quit", text, List.of(player));
  }

  @EventHandler
  public void onPlayerKick(PlayerKickEvent event) {
    Player player = event.getPlayer();
    if (plugin.getActivityJournal() != null) plugin.getActivityJournal().recordDisconnect(player);
    conversationManager.handlePlayerDisconnect(player.getUniqueId());
    String text = plain(event.leaveMessage());
    if (text.isBlank()) text = player.getName() + " was kicked";
    if (plugin.getActivityJournal() != null) {
      plugin.getActivityJournal().recordEvent("player-kick", text, List.of(player));
    }
    if (captureEvent("player-kick")) {
      conversationManager.recordServerEvent("player-kick", text, List.of(player.getName()));
    }
    conversationManager.maybeQueueRelationshipReaction("player-kick", text, List.of(player));
  }
}
