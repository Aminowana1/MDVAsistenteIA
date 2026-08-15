package me.kev.sva.chat.tools.all;

import java.util.List;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.tools.ToolActivation;
import me.kev.sva.chat.tools.ToolKind;

/**
 * Explicitly registered assistant capability.
 *
 * <p>CONTEXT tools are executed locally before the one model request when Java
 * detects that their information is relevant. ACTION tools may be emitted by
 * the model in the same response and are then executed (or queued for real
 * admin approval when activation=ask). No arbitrary console command is ever
 * exposed to the model.</p>
 */
public abstract class Tool {
  protected final ServerAssistantPlugin plugin;
  public final String name;

  protected Tool(ServerAssistantPlugin plugin, String name) {
    this.plugin = plugin;
    this.name = name;
  }

  public abstract ToolKind kind();

  /** Short instruction shown to the model for ACTION tools. */
  public abstract String usage();

  /** Executes an explicit tool call. Must be safe to run on Bukkit's main thread. */
  public abstract String execute(String arguments);

  /** Whether a CONTEXT tool should enrich this scene. */
  public boolean shouldPrefetch(String normalizedSceneText, List<ChatMessage> currentSceneMessages) {
    return false;
  }

  /** Compact trusted data for one scene. CONTEXT tools override this. */
  public String buildLocalContext(List<String> involvedPlayerNames) {
    return "";
  }

  /**
   * Scene-aware variant used by context tools that need the exact current intent.
   * Existing tools automatically fall back to the simpler overload.
   */
  public String buildLocalContext(
      List<String> involvedPlayerNames,
      String normalizedSceneText,
      List<ChatMessage> currentSceneMessages) {
    return buildLocalContext(involvedPlayerNames);
  }

  /** Optional cleanup hook, for example cancelling scheduled tasks on reload. */
  public void shutdown() {
  }

  public ToolActivation activation() {
    return ToolActivation.parse(plugin.getConfig().getString("tools." + name + ".activation", "never"));
  }

  public boolean globallyEnabled() {
    return plugin.getConfig().getBoolean("tools.enabled", true);
  }

  public boolean enabled() {
    return globallyEnabled() && activation() != ToolActivation.NEVER;
  }
}
