package me.kev.sva.chat.assistant;

/** Compact relationship mutation requested by the model in the same normal reply. */
public record RelationshipUpdate(
    String playerName,
    int delta,
    String memoryKind,
    int importance,
    String memory,
    String romanceAction) {

  public static RelationshipUpdate parseCompact(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String[] parts = raw.split("\\|", -1);
    if (parts.length < 6) return null;

    String player = parts[0].trim();
    if (player.isBlank()) return null;

    int delta;
    int importance;
    try {
      delta = Integer.parseInt(parts[1].trim());
      importance = Integer.parseInt(parts[3].trim());
    } catch (NumberFormatException ex) {
      return null;
    }

    String kind = parts[2].trim().toLowerCase();
    if (!kind.equals("n") && !kind.equals("r") && !kind.equals("p")) kind = "n";

    String memory = parts[4].trim();
    if (memory.equals("-") || memory.equalsIgnoreCase("none")) memory = "";

    String romance = parts[5].trim().toLowerCase();
    if (!romance.equals("start") && !romance.equals("end")) romance = "";

    return new RelationshipUpdate(player, delta, kind, importance, memory, romance);
  }

  public String toCompact() {
    String memoryValue = memory == null || memory.isBlank() ? "-" : memory.replace('|', '/');
    String romanceValue = romanceAction == null || romanceAction.isBlank() ? "-" : romanceAction;
    return playerName + "|" + delta + "|" + memoryKind + "|" + importance + "|" + memoryValue + "|" + romanceValue;
  }
}
