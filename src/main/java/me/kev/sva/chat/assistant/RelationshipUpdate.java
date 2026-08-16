package me.kev.sva.chat.assistant;

import java.util.Locale;
import java.util.Map;

/**
 * Compact relationship mutation requested by the model in the same normal reply.
 *
 * <p>1.7.1 accepts both the original compact string and a small object/map. Some
 * compatible models naturally return JSON objects even when instructed to use a
 * compact scalar; accepting both avoids silently losing relationship updates.</p>
 */
public record RelationshipUpdate(
    String playerName,
    int delta,
    String memoryKind,
    int importance,
    String memory,
    String romanceAction) {

  public static RelationshipUpdate parse(Object raw) {
    if (raw instanceof String text) return parseCompact(text);
    if (raw instanceof Map<?, ?> map) return parseMap(map);
    return null;
  }

  public static RelationshipUpdate parseCompact(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String[] parts = raw.split("\\|", -1);
    if (parts.length < 6) return null;

    String player = parts[0].trim();
    if (player.isBlank()) return null;

    Integer delta = parseInt(parts[1]);
    Integer importance = parseInt(parts[3]);
    if (delta == null || importance == null) return null;

    return normalized(
        player,
        delta,
        parts[2],
        importance,
        parts[4],
        parts[5]);
  }

  public static RelationshipUpdate parseMap(Map<?, ?> map) {
    if (map == null || map.isEmpty()) return null;

    String player = firstString(map, "player", "playerName", "player_name", "name", "p");
    Integer delta = firstInt(map, "delta", "change", "scoreDelta", "score_delta", "d");
    String kind = firstString(map, "kind", "memoryKind", "memory_kind", "type", "k");
    Integer importance = firstInt(map, "importance", "priority", "i");
    String memory = firstString(map, "memory", "summary", "event", "m");
    String romance = firstString(map, "romance", "romanceAction", "romance_action", "relationship", "x");

    if (player.isBlank() || delta == null) return null;
    if (kind.isBlank()) kind = "n";
    if (importance == null) importance = 1;
    if (memory.isBlank()) memory = "-";
    if (romance.isBlank()) romance = "-";

    return normalized(player, delta, kind, importance, memory, romance);
  }

  private static RelationshipUpdate normalized(
      String player,
      int delta,
      String rawKind,
      int importance,
      String rawMemory,
      String rawRomance) {

    String kind = rawKind == null ? "n" : rawKind.trim().toLowerCase(Locale.ROOT);
    if (kind.equals("none") || kind.equals("no") || kind.equals("-") || kind.equals("null")) kind = "n";
    if (kind.equals("recent")) kind = "r";
    if (kind.equals("persistent")) kind = "p";
    if (!kind.equals("n") && !kind.equals("r") && !kind.equals("p")) kind = "n";

    String memory = rawMemory == null ? "" : rawMemory.trim();
    if (memory.equals("-") || memory.equalsIgnoreCase("none") || memory.equalsIgnoreCase("null")) memory = "";

    String romance = rawRomance == null ? "" : rawRomance.trim().toLowerCase(Locale.ROOT);
    if (romance.equals("begin") || romance.equals("accept") || romance.equals("partner")) romance = "start";
    if (romance.equals("breakup") || romance.equals("break-up") || romance.equals("stop")) romance = "end";
    if (!romance.equals("start") && !romance.equals("end")) romance = "";

    return new RelationshipUpdate(player.trim(), delta, kind, importance, memory, romance);
  }

  private static Integer parseInt(Object value) {
    if (value instanceof Number number) return number.intValue();
    if (value == null) return null;
    try {
      return Integer.parseInt(String.valueOf(value).trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private static String firstString(Map<?, ?> map, String... keys) {
    for (String key : keys) {
      Object value = getIgnoreCase(map, key);
      if (value != null) {
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) return text;
      }
    }
    return "";
  }

  private static Integer firstInt(Map<?, ?> map, String... keys) {
    for (String key : keys) {
      Object value = getIgnoreCase(map, key);
      Integer parsed = parseInt(value);
      if (parsed != null) return parsed;
    }
    return null;
  }

  private static Object getIgnoreCase(Map<?, ?> map, String wanted) {
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() != null && wanted.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
        return entry.getValue();
      }
    }
    return null;
  }

  public String toCompact() {
    String memoryValue = memory == null || memory.isBlank() ? "-" : memory.replace('|', '/');
    String romanceValue = romanceAction == null || romanceAction.isBlank() ? "-" : romanceAction;
    return playerName + "|" + delta + "|" + memoryKind + "|" + importance + "|" + memoryValue + "|" + romanceValue;
  }
}
