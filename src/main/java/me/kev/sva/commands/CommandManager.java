package me.kev.sva.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.util.StringUtil;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.utils.MessageSender;
import net.kyori.adventure.text.Component;

/**
 * 1.6 command surface: keeps the user's commands and adds the friend's listener
 * tree plus tool/approval controls. `listen` and `listener` are both accepted.
 */
public final class CommandManager implements TabExecutor {
  private static final List<String> MODES = List.of("always", "mention", "smart", "disabled");
  private static final List<String> EVENT_NAMES = List.of(
      "death", "advancement", "join", "quit", "kick", "joinquit", "all");
  private static final List<String> ENABLE_OPTIONS = List.of("enabled", "disabled");
  private static final List<String> TOOL_ACTIVATIONS = List.of("smart", "ask", "never");

  private final ServerAssistantPlugin plugin;

  public CommandManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!command.getName().equalsIgnoreCase("sva")) return false;
    if (!sender.hasPermission("sva.admin")) {
      MessageSender.Error(sender, "You don't have permission to do that.");
      return true;
    }

    if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
      sendHelp(sender);
      return true;
    }

    String root = args[0].toLowerCase(Locale.ROOT);
    switch (root) {
      case "reload" -> handleReload(sender, args);
      case "status" -> handleStatus(sender, args);
      case "trigger" -> handleTrigger(sender, args);
      case "playerchatmode" -> handlePlayerChatMode(sender, args);
      case "listener", "listen" -> handleListener(sender, args);
      case "tools", "tool" -> handleTools(sender, args);
      case "integrations", "integration" -> handleIntegrations(sender, args);
      case "approve" -> handleApprove(sender, args);
      case "deny" -> handleDeny(sender, args);
      default -> sendHelp(sender);
    }
    return true;
  }

  private void handleReload(CommandSender sender, String[] args) {
    if (args.length != 1) {
      MessageSender.Error(sender, "Usage: /sva reload");
      return;
    }
    try {
      plugin.reloadPlugin();
      MessageSender.Success(sender, "Plugin reloaded!");
    } catch (Exception ex) {
      plugin.getLogger().severe("Config reload failed: " + ex.getClass().getName() + ": " + ex.getMessage());
      ex.printStackTrace();
      MessageSender.Error(sender, "Config reload failed. Check console.");
    }
  }

  private void handleStatus(CommandSender sender, String[] args) {
    if (args.length != 1) {
      MessageSender.Error(sender, "Usage: /sva status");
      return;
    }
    if (plugin.getConversationManager() == null) {
      sender.sendMessage(Component.text("ServerAssistant is not initialized."));
      return;
    }
    sender.sendMessage(Component.text("ServerAssistant: " + plugin.getConversationManager().getRuntimeStatus()));
  }

  private void handleTrigger(CommandSender sender, String[] args) {
    if (args.length != 1) {
      MessageSender.Error(sender, "Usage: /sva trigger");
      return;
    }
    boolean started = plugin.getConversationManager() != null
        && plugin.getConversationManager().forceTrigger(sender);
    if (started) {
      MessageSender.Success(sender, "Manual global scene triggered.");
    } else {
      MessageSender.Error(sender, "Could not trigger now (a scene may already be collecting).");
    }
  }

  private void handlePlayerChatMode(CommandSender sender, String[] args) {
    if (args.length != 2) {
      MessageSender.Error(sender, "Usage: /sva playerchatmode <always|mention|smart|disabled>");
      return;
    }
    setChatMode(sender, args[1]);
  }

  private void handleListener(CommandSender sender, String[] args) {
    if (args.length < 2) {
      MessageSender.Error(sender, "Usage: /sva listener playerchat <mode> | events <event> <enabled|disabled> | idle <enabled|disabled>");
      return;
    }
    String category = args[1].toLowerCase(Locale.ROOT);
    if (category.equals("playerchat")) {
      if (args.length != 3) {
        MessageSender.Error(sender, "Usage: /sva listener playerchat <always|mention|smart|disabled>");
        return;
      }
      setChatMode(sender, args[2]);
      return;
    }
    if (category.equals("idle")) {
      if (args.length != 3 || !ENABLE_OPTIONS.contains(args[2].toLowerCase(Locale.ROOT))) {
        MessageSender.Error(sender, "Usage: /sva listener idle <enabled|disabled>");
        return;
      }
      boolean enabled = args[2].equalsIgnoreCase("enabled");
      plugin.getConfig().set("global-conversation.idle-scheduling.enabled", enabled);
      plugin.saveConfig();
      MessageSender.Success(sender, "Idle scheduling set to " + (enabled ? "enabled" : "disabled")
          + ". It may spend one API request after chat inactivity.");
      return;
    }
    if (category.equals("events")) {
      if (args.length < 3 || args.length > 4) {
        MessageSender.Error(sender, "Usage: /sva listener events <death|advancement|join|quit|kick|joinquit|all> [enabled|disabled]");
        return;
      }
      String event = args[2].toLowerCase(Locale.ROOT);
      if (!EVENT_NAMES.contains(event)) {
        MessageSender.Error(sender, "Unknown event. Use: " + String.join(", ", EVENT_NAMES));
        return;
      }
      String option = args.length == 4 ? args[3].toLowerCase(Locale.ROOT) : "enabled";
      if (!ENABLE_OPTIONS.contains(option)) {
        MessageSender.Error(sender, "State must be enabled or disabled.");
        return;
      }
      setEvent(sender, event, option.equals("enabled"));
      return;
    }
    MessageSender.Error(sender, "Unknown listener category. Use playerchat, events or idle.");
  }

  private void setChatMode(CommandSender sender, String rawMode) {
    String mode = rawMode.toLowerCase(Locale.ROOT);
    if (!MODES.contains(mode)) {
      MessageSender.Error(sender, "Invalid mode. Use: " + String.join(", ", MODES));
      return;
    }
    plugin.getConfig().set("global-conversation.trigger-mode", mode);
    plugin.saveConfig();
    MessageSender.Success(sender, "Global conversation trigger mode set to: " + mode);
  }

  private void setEvent(CommandSender sender, String event, boolean enabled) {
    List<String> paths = switch (event) {
      case "death" -> List.of("player-death");
      case "advancement" -> List.of("player-advancement");
      case "join" -> List.of("player-join");
      case "quit" -> List.of("player-quit");
      case "kick" -> List.of("player-kick");
      case "joinquit" -> List.of("player-join", "player-quit");
      case "all" -> List.of("player-death", "player-advancement", "player-join", "player-quit", "player-kick");
      default -> List.of();
    };
    for (String path : paths) {
      plugin.getConfig().set("global-conversation.events." + path, enabled);
    }
    plugin.saveConfig();
    MessageSender.Success(sender, "Event listener '" + event + "' set to " + (enabled ? "enabled" : "disabled") + ".");
  }

  private void handleTools(CommandSender sender, String[] args) {
    if (plugin.getToolManager() == null) {
      MessageSender.Error(sender, "Tool manager is not initialized.");
      return;
    }
    if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("list"))) {
      MessageSender.Success(sender, "Tools: " + String.join(", ", plugin.getToolManager().describeTools()));
      return;
    }
    String action = args[1].toLowerCase(Locale.ROOT);
    if (action.equals("pending")) {
      List<String> pending = plugin.getToolManager().pendingApprovalSummaries();
      MessageSender.Success(sender, pending.isEmpty() ? "No pending tool approvals." : String.join(" | ", pending));
      return;
    }
    if (action.equals("moderation")) {
      List<String> rows = plugin.getToolManager().moderationSummaries();
      MessageSender.Success(sender, rows.isEmpty() ? "No current moderation strikes." : String.join(" | ", rows));
      return;
    }
    if (action.equals("set")) {
      if (args.length != 4) {
        MessageSender.Error(sender, "Usage: /sva tools set <tool> <smart|ask|never>");
        return;
      }
      String activation = args[3].toLowerCase(Locale.ROOT);
      if (!TOOL_ACTIVATIONS.contains(activation)
          || !plugin.getToolManager().setActivation(args[2], activation)) {
        MessageSender.Error(sender, "Invalid tool or activation.");
        return;
      }
      MessageSender.Success(sender, "Tool '" + args[2] + "' activation set to " + activation + ".");
      return;
    }
    if (action.equals("run")) {
      if (args.length < 3) {
        MessageSender.Error(sender, "Usage: /sva tools run <tool> [arguments...]");
        return;
      }
      String call = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
      MessageSender.Success(sender, plugin.getToolManager().executeAdminTool(call, sender));
      return;
    }
    MessageSender.Error(sender, "Usage: /sva tools [list|pending|moderation|set|run]");
  }


  private void handleIntegrations(CommandSender sender, String[] args) {
    if (plugin.getIntegrationManager() == null) {
      MessageSender.Error(sender, "Integration manager is not initialized.");
      return;
    }
    if (args.length == 1 || (args.length == 2 && args[1].equalsIgnoreCase("list"))) {
      MessageSender.Success(sender, "Integrations: " + String.join(" | ", plugin.getIntegrationManager().statusLines()));
      return;
    }
    if (args.length == 4 && args[1].equalsIgnoreCase("set")) {
      String state = args[3].toLowerCase(Locale.ROOT);
      if (!ENABLE_OPTIONS.contains(state)) {
        MessageSender.Error(sender, "State must be enabled or disabled.");
        return;
      }
      boolean enabled = state.equals("enabled");
      if (!plugin.getIntegrationManager().setEnabled(args[2], enabled)) {
        MessageSender.Error(sender, "Unknown integration. Use: "
            + String.join(", ", plugin.getIntegrationManager().integrationIds()));
        return;
      }
      MessageSender.Success(sender, "Integration '" + args[2] + "' set to " + state + ".");
      return;
    }
    MessageSender.Error(sender, "Usage: /sva integrations [list|set <mmocore|mdvsocial|all> <enabled|disabled>]");
  }

  private void handleApprove(CommandSender sender, String[] args) {
    if (args.length != 2) {
      MessageSender.Error(sender, "Usage: /sva approve <id>");
      return;
    }
    try {
      long id = Long.parseLong(args[1]);
      MessageSender.Success(sender, plugin.getToolManager().approve(id, sender));
    } catch (NumberFormatException ex) {
      MessageSender.Error(sender, "Approval id must be a number.");
    }
  }

  private void handleDeny(CommandSender sender, String[] args) {
    if (args.length != 2) {
      MessageSender.Error(sender, "Usage: /sva deny <id>");
      return;
    }
    try {
      long id = Long.parseLong(args[1]);
      MessageSender.Success(sender, plugin.getToolManager().deny(id));
    } catch (NumberFormatException ex) {
      MessageSender.Error(sender, "Approval id must be a number.");
    }
  }

  private void sendHelp(CommandSender sender) {
    sender.sendMessage(Component.text(
        "SVA: /sva reload | status | trigger | listener playerchat <mode> | listener events <event> <enabled|disabled> | listener idle <enabled|disabled> | tools [list|pending|moderation|set|run] | integrations [list|set] | approve <id> | deny <id>"));
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (!command.getName().equalsIgnoreCase("sva") || !sender.hasPermission("sva.admin")) {
      return Collections.emptyList();
    }
    if (args.length == 1) {
      return complete(args[0], List.of(
          "reload", "status", "trigger", "listener", "listen", "playerchatmode", "tools", "integrations", "approve", "deny"));
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("playerchatmode")) {
      return complete(args[1], MODES);
    }
    if (args.length == 2 && isListener(args[0])) {
      return complete(args[1], List.of("playerchat", "events", "idle"));
    }
    if (args.length == 3 && isListener(args[0]) && args[1].equalsIgnoreCase("playerchat")) {
      return complete(args[2], MODES);
    }
    if (args.length == 3 && isListener(args[0]) && args[1].equalsIgnoreCase("events")) {
      return complete(args[2], EVENT_NAMES);
    }
    if (args.length == 3 && isListener(args[0]) && args[1].equalsIgnoreCase("idle")) {
      return complete(args[2], ENABLE_OPTIONS);
    }
    if (args.length == 4 && isListener(args[0]) && args[1].equalsIgnoreCase("events")) {
      return complete(args[3], ENABLE_OPTIONS);
    }
    if (args.length == 2 && (args[0].equalsIgnoreCase("integrations") || args[0].equalsIgnoreCase("integration"))) {
      return complete(args[1], List.of("list", "set"));
    }
    if (args.length == 3 && (args[0].equalsIgnoreCase("integrations") || args[0].equalsIgnoreCase("integration"))
        && args[1].equalsIgnoreCase("set") && plugin.getIntegrationManager() != null) {
      return complete(args[2], plugin.getIntegrationManager().integrationIds());
    }
    if (args.length == 4 && (args[0].equalsIgnoreCase("integrations") || args[0].equalsIgnoreCase("integration"))
        && args[1].equalsIgnoreCase("set")) {
      return complete(args[3], ENABLE_OPTIONS);
    }
    if (args.length == 2 && (args[0].equalsIgnoreCase("tools") || args[0].equalsIgnoreCase("tool"))) {
      return complete(args[1], List.of("list", "pending", "moderation", "set", "run"));
    }
    if (args.length == 3 && (args[0].equalsIgnoreCase("tools") || args[0].equalsIgnoreCase("tool"))
        && (args[1].equalsIgnoreCase("set") || args[1].equalsIgnoreCase("run"))) {
      return complete(args[2], new ArrayList<>(plugin.getToolManager().getToolNames()));
    }
    if (args.length == 4 && (args[0].equalsIgnoreCase("tools") || args[0].equalsIgnoreCase("tool"))
        && args[1].equalsIgnoreCase("set")) {
      return complete(args[3], TOOL_ACTIVATIONS);
    }
    return Collections.emptyList();
  }

  private static boolean isListener(String value) {
    return value.equalsIgnoreCase("listener") || value.equalsIgnoreCase("listen");
  }

  private static List<String> complete(String partial, List<String> options) {
    List<String> matches = new ArrayList<>();
    StringUtil.copyPartialMatches(partial, options, matches);
    Collections.sort(matches);
    return matches;
  }
}
