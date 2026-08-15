package me.kev.sva.chat.tools;

import java.util.Locale;

public enum ToolActivation {
  SMART,
  ASK,
  NEVER;

  public static ToolActivation parse(String value) {
    if (value == null) {
      return NEVER;
    }
    return switch (value.trim().toLowerCase(Locale.ROOT)) {
      case "smart" -> SMART;
      case "ask" -> ASK;
      default -> NEVER;
    };
  }

  public String configValue() {
    return name().toLowerCase(Locale.ROOT);
  }
}
