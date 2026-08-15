package me.kev.sva.utils;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import net.md_5.bungee.api.ChatColor;

public abstract class MessageSender {
  public static final String HEADER = "[ServerAssistant]";

  public static void Success(String message) {
    send(Bukkit.getConsoleSender(), ChatColor.BLUE, message);
  }

  public static void Success(CommandSender sender, String message) {
    send(sender, ChatColor.BLUE, message);
  }

  public static void Error(String message) {
    send(Bukkit.getConsoleSender(), ChatColor.RED, message);
  }

  public static void Error(CommandSender sender, String message) {
    send(sender, ChatColor.RED, message);
  }

  public static void Dev(String message) {
    send(Bukkit.getConsoleSender(), ChatColor.GREEN, message);
  }

  public static void Dev(CommandSender sender, String message) {
    send(sender, ChatColor.GREEN, message);
  }

  private static void send(CommandSender sender, ChatColor color, String message) {
    CommandSender target = sender == null ? Bukkit.getConsoleSender() : sender;
    String safe = message == null ? "" : ChatColor.translateAlternateColorCodes('&', message);
    target.sendMessage(color + HEADER + " " + safe);
  }
}
