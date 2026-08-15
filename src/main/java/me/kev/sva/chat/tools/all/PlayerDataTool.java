package me.kev.sva.chat.tools.all;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.tools.ContextTargetResolver;
import me.kev.sva.chat.tools.ToolKind;
import me.kev.sva.chat.tools.ToolManager;

/** Local trusted online-player location/status context. */
public final class PlayerDataTool extends Tool {
  private static final List<String> LOCATION_TERMS = List.of(
      "donde", "ubicacion", "coordenad", "coords", "position", "location",
      "zona", "region", "bioma", "biome");
  private static final List<String> STATUS_TERMS = List.of(
      "vida", "health", "hambre", "food",
      "nivel vanilla", "level vanilla", "xp vanilla", "experiencia vanilla",
      "estado", "status", "gamemode", "modo de juego", "volando", "flying",
      "sprint", "nadando", "swimming", "invisible");
  private static final List<String> PERSONAL_TERMS = List.of(
      "yo", "mi", "mis", "me", "mio", "mia", "tengo", "tenemos", "llevo",
      "estoy", "estamos", "soy", "somos");
  private static final List<String> LOCATION_DEICTIC_TERMS = List.of(
      "aqui", "aca", "esta zona", "este bioma", "esta region", "este lugar");

  public PlayerDataTool(ServerAssistantPlugin plugin) {
    super(plugin, "player-data");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.CONTEXT;
  }

  @Override
  public String usage() {
    return "Local trusted data about an online player's location/status.";
  }

  @Override
  public boolean shouldPrefetch(String normalizedSceneText, List<ChatMessage> currentSceneMessages) {
    if (currentSceneMessages != null) {
      for (ChatMessage message : currentSceneMessages) {
        if (!(message instanceof PlayerChatMessage playerMessage) || message.content == null) continue;
        String text = ToolManager.normalize(message.content);
        if (matchesLocationIntent(text, playerMessage.playerName, currentSceneMessages)
            || matchesStatusIntent(text, playerMessage.playerName, currentSceneMessages)) {
          return true;
        }
      }
    }
    // Fallback for synthetic/debug calls without PlayerChatMessage metadata.
    String text = normalizedSceneText == null ? "" : normalizedSceneText;
    return matchesPersonalReference(text, "", currentSceneMessages)
        && (containsAny(text, LOCATION_TERMS) || containsAny(text, STATUS_TERMS));
  }

  @Override
  public String buildLocalContext(
      List<String> involvedPlayerNames,
      String normalizedSceneText,
      List<ChatMessage> currentSceneMessages) {

    int maxPlayers = Math.max(plugin.getConfig().getInt("tools.player-data.max-players", 2), 1);

    // Resolve location/status against only the lines that asked about location/status.
    // In a grouped scene, an unrelated later inventory question no longer steals
    // this tool's target from the player who asked "donde estoy?".
    List<ChatMessage> queryMessages = selectRelevantMessages(currentSceneMessages);
    String queryText = normalizedText(queryMessages);
    if (queryText.isBlank()) queryText = normalizedSceneText == null ? "" : normalizedSceneText;

    boolean queryLocation = matchesLocationIntent(queryText, latestSpeaker(queryMessages), queryMessages);
    boolean queryStatus = matchesStatusIntent(queryText, latestSpeaker(queryMessages), queryMessages);
    if (!queryLocation && !queryStatus) {
      queryLocation = plugin.getConfig().getBoolean("tools.player-data.include-location", true);
      queryStatus = plugin.getConfig().getBoolean("tools.player-data.include-status", true);
    }

    List<String> targets = ContextTargetResolver.resolve(
        involvedPlayerNames, queryText, queryMessages, maxPlayers);
    List<String> rows = new ArrayList<>();
    for (String name : targets) {
      if (rows.size() >= maxPlayers) break;
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) continue;
      rows.add(compact(player, queryLocation, queryStatus));
    }
    return String.join("\n", rows);
  }

  @Override
  public String buildLocalContext(List<String> involvedPlayerNames) {
    int maxPlayers = Math.max(plugin.getConfig().getInt("tools.player-data.max-players", 2), 1);
    boolean includeLocation = plugin.getConfig().getBoolean("tools.player-data.include-location", true);
    boolean includeStatus = plugin.getConfig().getBoolean("tools.player-data.include-status", true);

    List<String> rows = new ArrayList<>();
    for (String name : involvedPlayerNames) {
      if (rows.size() >= maxPlayers) break;
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) continue;
      rows.add(compact(player, includeLocation, includeStatus));
    }
    return String.join("\n", rows);
  }

  @Override
  public String execute(String arguments) {
    String playerName = arguments == null ? "" : arguments.trim();
    if (playerName.isBlank() || playerName.contains(" ")) {
      return "Usage: player-data <player>";
    }
    Player player = Bukkit.getPlayerExact(playerName);
    if (player == null) {
      return "Player '" + playerName + "' is not online.";
    }

    var location = player.getLocation();
    return "Player=" + player.getName()
        + ", world=" + player.getWorld().getName()
        + ", biome=" + biomeName(player)
        + ", xyz=" + String.format(Locale.ROOT, "%.2f,%.2f,%.2f", location.getX(), location.getY(), location.getZ())
        + ", gamemode=" + player.getGameMode().name()
        + ", health=" + String.format(Locale.ROOT, "%.1f", player.getHealth())
        + ", food=" + player.getFoodLevel()
        + ", saturation=" + String.format(Locale.ROOT, "%.1f", player.getSaturation())
        + ", vanilla_level=" + player.getLevel()
        + ", vanilla_exp=" + String.format(Locale.ROOT, "%.2f", player.getExp())
        + ", flying=" + player.isFlying()
        + ", sneaking=" + player.isSneaking()
        + ", sprinting=" + player.isSprinting()
        + ", swimming=" + player.isSwimming()
        + ", gliding=" + player.isGliding()
        + ", invisible=" + player.isInvisible();
  }

  private List<ChatMessage> selectRelevantMessages(List<ChatMessage> currentSceneMessages) {
    if (currentSceneMessages == null || currentSceneMessages.isEmpty()) return List.of();
    List<ChatMessage> relevant = new ArrayList<>();
    for (ChatMessage message : currentSceneMessages) {
      if (!(message instanceof PlayerChatMessage) || message.content == null) continue;
      String normalized = ToolManager.normalize(message.content);
      PlayerChatMessage playerMessage = (PlayerChatMessage) message;
      if (matchesLocationIntent(normalized, playerMessage.playerName, currentSceneMessages)
          || matchesStatusIntent(normalized, playerMessage.playerName, currentSceneMessages)) {
        relevant.add(message);
      }
    }
    return relevant.isEmpty() ? currentSceneMessages : List.copyOf(relevant);
  }


  private boolean matchesLocationIntent(String text, String speakerName, List<ChatMessage> sceneMessages) {
    if (!containsAny(text, LOCATION_TERMS)) return false;
    // "coords?" is a common shorthand for the speaker's current coordinates.
    if (containsAny(text, List.of("coords", "mis coords", "mis coordenadas"))) return true;
    return matchesPersonalReference(text, speakerName, sceneMessages)
        || containsAny(text, LOCATION_DEICTIC_TERMS);
  }

  private boolean matchesStatusIntent(String text, String speakerName, List<ChatMessage> sceneMessages) {
    if (!containsAny(text, STATUS_TERMS)) return false;
    return matchesPersonalReference(text, speakerName, sceneMessages);
  }

  private boolean matchesPersonalReference(String text, String speakerName, List<ChatMessage> sceneMessages) {
    if (text == null || text.isBlank()) return false;
    for (String term : PERSONAL_TERMS) {
      if (ContextTargetResolver.containsWholeToken(text, ToolManager.normalize(term))) return true;
    }
    if (speakerName != null && !speakerName.isBlank()
        && ContextTargetResolver.containsWholeToken(text, ToolManager.normalize(speakerName))) {
      return true;
    }
    if (sceneMessages != null) {
      for (ChatMessage message : sceneMessages) {
        if (message instanceof PlayerChatMessage playerMessage
            && ContextTargetResolver.containsWholeToken(text, ToolManager.normalize(playerMessage.playerName))) {
          return true;
        }
      }
    }
    return false;
  }

  private String latestSpeaker(List<ChatMessage> messages) {
    if (messages == null) return "";
    for (int i = messages.size() - 1; i >= 0; i--) {
      ChatMessage message = messages.get(i);
      if (message instanceof PlayerChatMessage playerMessage) return playerMessage.playerName;
    }
    return "";
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

  private String compact(Player player, boolean includeLocation, boolean includeStatus) {
    StringBuilder out = new StringBuilder("PLAYER_DATA player=").append(player.getName());
    if (includeLocation) {
      var loc = player.getLocation();
      out.append(" | world=").append(player.getWorld().getName())
          .append(" | biome=").append(biomeName(player))
          .append(" | xyz=").append(loc.getBlockX()).append(',').append(loc.getBlockY()).append(',').append(loc.getBlockZ());
    }
    if (includeStatus) {
      out.append(" | mode=").append(player.getGameMode().name())
          .append(" | hp=").append(String.format(Locale.ROOT, "%.1f", player.getHealth()))
          .append(" | food=").append(player.getFoodLevel())
          .append(" | vanilla_level=").append(player.getLevel())
          .append(" | flying=").append(player.isFlying())
          .append(" | sneaking=").append(player.isSneaking())
          .append(" | sprinting=").append(player.isSprinting());
    }
    return out.toString();
  }

  private String biomeName(Player player) {
    try {
      return player.getLocation().getBlock().getBiome().getKey().getKey();
    } catch (Throwable ignored) {
      return player.getLocation().getBlock().getBiome().name().toLowerCase(Locale.ROOT);
    }
  }

  private static boolean containsAny(String text, List<String> terms) {
    if (text == null || text.isBlank()) return false;
    for (String term : terms) {
      if (text.contains(term)) return true;
    }
    return false;
  }
}
