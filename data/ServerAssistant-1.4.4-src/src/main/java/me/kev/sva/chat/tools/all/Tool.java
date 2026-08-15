package me.kev.sva.chat.tools.all;

import me.kev.sva.ServerAssistantPlugin;

/** Base class for explicitly registered server tools. */
public abstract class Tool {
  protected final ServerAssistantPlugin plugin;
  public final String name;

  protected Tool(ServerAssistantPlugin plugin, String name) {
    this.name = name;
    this.plugin = plugin;
  }

  /** Executes this tool with already-parsed text arguments. Never receives raw chat implicitly. */
  public abstract String execute(String arguments);
}
