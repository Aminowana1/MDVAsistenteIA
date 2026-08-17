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
    String cleaned = raw.trim();
    String[] parts = cleaned.split("\\|", -1);
    if (parts.length < 2) return null;

    // Compatibility salvage for small-model labelled compact output such as:
    //   Name|DELTA|-1|KIND|recent|IMPORTANCE|2|MEMORY|...|ROMANCE|-
    // and the shorter malformed variant seen in live logs:
    //   Name|DELTA|-1|KIND|IMPORTANCE|No me gusta ...
    // Java still performs the semantic/current-speaker validation later; this only
    // prevents harmless formatting drift from throwing away an otherwise usable r.
    if (parts.length >= 3 && "delta".equalsIgnoreCase(parts[1].trim())) {
      return parseLabelledCompact(parts);
    }

    // Some models wrap the first labelled token as {name==Player|DELTA|...}.
    if (looksLikeLabelledName(parts[0]) || containsLabel(parts, "DELTA")) {
      RelationshipUpdate labelled = parseLabelledCompact(parts);
      if (labelled != null) return labelled;
    }

    // Small models sometimes omit the optional trailing ROMANCE field (or both
    // optional MEMORY/ROMANCE fields). The first four fields are enough to validate
    // the bookkeeping safely; missing optional values default to none.
    if (parts.length < 4) return null;

    String player = cleanPlayerToken(parts[0]);
    if (player.isBlank()) return null;

    Integer delta = parseInt(parts[1]);
    Integer importance = parseInt(parts[3]);
    if (delta == null || importance == null) return null;

    return normalized(
        player,
        delta,
        parts[2],
        importance,
        parts.length >= 5 ? parts[4] : "",
        parts.length >= 6 ? parts[5] : "");
  }

  private static RelationshipUpdate parseLabelledCompact(String[] parts) {
    if (parts == null || parts.length < 3) return null;
    String player = cleanPlayerToken(parts[0]);
    if (player.isBlank()) return null;

    Integer delta = null;
    String kind = "n";
    int importance = 1;
    String memory = "";
    String romance = "";

    for (int i = 1; i < parts.length; i++) {
      String token = stripWrapper(parts[i]);
      String label = token.toUpperCase(Locale.ROOT);
      if (label.equals("DELTA") && i + 1 < parts.length) {
        Integer parsed = parseInt(stripWrapper(parts[++i]));
        if (parsed != null) delta = parsed;
        continue;
      }
      if (label.equals("KIND") && i + 1 < parts.length) {
        String candidate = stripWrapper(parts[i + 1]);
        if (isMemoryKindToken(candidate)) {
          kind = candidate;
          i++;
        }
        continue;
      }
      if (label.equals("IMPORTANCE") && i + 1 < parts.length) {
        Integer parsed = parseInt(stripWrapper(parts[i + 1]));
        if (parsed != null) {
          importance = parsed;
          i++;
        }
        continue;
      }
      if (label.equals("MEMORY") && i + 1 < parts.length) {
        memory = stripWrapper(parts[++i]);
        continue;
      }
      if (label.equals("ROMANCE") && i + 1 < parts.length) {
        romance = stripWrapper(parts[++i]);
        continue;
      }

      // When a model emitted KIND|IMPORTANCE|free text, do not reinterpret the
      // free text as an enum/number. If there is no explicit MEMORY label, keeping
      // it as a non-persistent note is safe; normalized kind=n means it cannot be
      // stored as a relationship memory by this parse alone.
      if (memory.isBlank() && !token.isBlank() && !isKnownLabel(label)) {
        memory = token;
      }
    }

    if (delta == null) return null;
    return normalized(player, delta, kind, importance, memory, romance);
  }

  private static boolean containsLabel(String[] parts, String wanted) {
    if (parts == null) return false;
    for (String part : parts) {
      if (wanted.equalsIgnoreCase(stripWrapper(part))) return true;
    }
    return false;
  }

  private static boolean looksLikeLabelledName(String raw) {
    if (raw == null) return false;
    String lower = raw.toLowerCase(Locale.ROOT);
    return lower.contains("name=") || lower.contains("player=");
  }

  private static String cleanPlayerToken(String raw) {
    String token = stripWrapper(raw);
    token = token.replaceFirst("(?i)^(?:name|player|playername|player_name)\\s*={1,2}\\s*", "");
    return stripWrapper(token).trim();
  }

  private static String stripWrapper(String raw) {
    if (raw == null) return "";
    String value = raw.trim();
    while (!value.isEmpty() && "{[(\"'".indexOf(value.charAt(0)) >= 0) {
      value = value.substring(1).trim();
    }
    while (!value.isEmpty() && "}])\"',.".indexOf(value.charAt(value.length() - 1)) >= 0) {
      value = value.substring(0, value.length() - 1).trim();
    }
    return value;
  }

  private static boolean isKnownLabel(String upper) {
    return upper.equals("DELTA") || upper.equals("KIND") || upper.equals("IMPORTANCE")
        || upper.equals("MEMORY") || upper.equals("ROMANCE");
  }

  private static boolean isMemoryKindToken(String raw) {
    if (raw == null) return false;
    String value = stripWrapper(raw).toLowerCase(Locale.ROOT);
    return value.equals("n") || value.equals("r") || value.equals("p")
        || value.equals("none") || value.equals("recent") || value.equals("persistent")
        || value.equals("-") || value.equals("null");
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
    // Small models often append punctuation (`partner.`, `-.`) even inside compact r.
    romance = romance.replaceAll("[^\\p{L}+-]+", "");
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
