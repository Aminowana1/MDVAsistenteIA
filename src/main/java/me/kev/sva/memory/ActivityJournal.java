package me.kev.sva.memory;

import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.tools.ContextTargetResolver;

/**
 * Small rolling public activity journal kept only in RAM.
 *
 * <p>Nothing here creates an AI request. The journal is consulted only when the
 * current player message clearly asks about recent history. This makes the normal
 * chat path effectively free: one short append plus bounded pruning.</p>
 */
public final class ActivityJournal {
  private static final Pattern DURATION_PATTERN = Pattern.compile(
      "(?i)(?:ultim(?:a|as|o|os)?\\s+)?(\\d{1,3})\\s*(h|hr|hrs|hora|horas|min|mins|minuto|minutos)\\b");
  private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

  private final ServerAssistantPlugin plugin;
  private final Deque<ActivityRecord> records = new ArrayDeque<>();
  private final Map<UUID, KnownPlayer> knownPlayers = new HashMap<>();
  private final Map<UUID, Long> lastDisconnectAt = new HashMap<>();
  private final Map<UUID, Long> lastJoinAt = new HashMap<>();

  public ActivityJournal(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  public void recordChat(Player player, String content) {
    if (!enabled() || player == null || content == null || content.isBlank()) return;
    long now = System.currentTimeMillis();
    rememberPlayer(player.getUniqueId(), player.getName());
    records.addLast(new ActivityRecord(
        now,
        RecordKind.CHAT,
        player.getUniqueId(),
        player.getName(),
        content,
        Map.of(player.getUniqueId(), player.getName())));
    prune(now);
  }

  public void recordEvent(String type, String text, List<Player> players) {
    if (!enabled() || text == null || text.isBlank()) return;
    long now = System.currentTimeMillis();
    Map<UUID, String> actors = new LinkedHashMap<>();
    if (players != null) {
      for (Player player : players) {
        if (player == null) continue;
        rememberPlayer(player.getUniqueId(), player.getName());
        actors.put(player.getUniqueId(), player.getName());
      }
    }
    records.addLast(new ActivityRecord(
        now,
        RecordKind.EVENT,
        null,
        type == null ? "event" : type,
        text,
        Map.copyOf(actors)));
    prune(now);
  }

  public void recordJoin(Player player) {
    if (!enabled() || player == null) return;
    long now = System.currentTimeMillis();
    rememberPlayer(player.getUniqueId(), player.getName());
    lastJoinAt.put(player.getUniqueId(), now);
  }

  public void recordDisconnect(Player player) {
    if (!enabled() || player == null) return;
    long now = System.currentTimeMillis();
    rememberPlayer(player.getUniqueId(), player.getName());
    lastDisconnectAt.put(player.getUniqueId(), now);
  }

  /**
   * Returns trusted history context only when the current message is recognizably a
   * history question. Empty means the normal scene pays zero journal prompt tokens.
   */
  public String buildContext(
      UUID requesterId,
      String requesterName,
      boolean requesterAdmin,
      String currentText) {

    if (!enabled() || currentText == null || currentText.isBlank()) return "";
    long now = System.currentTimeMillis();
    prune(now);

    String normalized = normalize(currentText);
    boolean generalSinceDisconnect = looksLikeSinceDisconnectQuestion(normalized);
    boolean generalTimed = !generalSinceDisconnect && looksLikeGeneralHistoryQuestion(normalized);
    KnownPlayer target = resolveTarget(normalized, requesterId);
    boolean playerHistory = target != null && looksLikePlayerHistoryQuestion(normalized);

    // A named player wins over a broad-history interpretation, e.g. "que paso con
    // Kroattan mientras no estuve" should return Kroattan's records, not everyone.
    if (playerHistory) {
      if (!hasAccess(requesterAdmin, "activity-journal.access.player-history", "admin-only")) {
        return "[ACTIVITY JOURNAL] access=denied, scope=player. Esta consulta historica esta limitada a administradores.";
      }
      long requestedWindow = parseRequestedWindowMs(normalized, defaultPlayerWindowMs());
      long from = Math.max(now - requestedWindow, retentionStart(now));
      String result = formatTargetHistory(target, from, now);
      debug("scope=player target=" + target.name() + " window_ms=" + (now - from));
      return result;
    }

    if (generalSinceDisconnect || generalTimed) {
      if (!hasAccess(requesterAdmin, "activity-journal.access.general-history", "admin-only")) {
        return "[ACTIVITY JOURNAL] access=denied, scope=general. Esta consulta historica general esta limitada a administradores.";
      }

      long from;
      String basis;
      if (generalSinceDisconnect) {
        Long disconnect = requesterId == null ? null : lastDisconnectAt.get(requesterId);
        Long join = requesterId == null ? null : lastJoinAt.get(requesterId);
        if (disconnect == null || (join != null && join < disconnect)) {
          return "[ACTIVITY JOURNAL] scope=general, result=no_disconnect_marker. No hay una desconexion reciente registrada en esta sesion del plugin.";
        }
        from = Math.max(disconnect, retentionStart(now));
        basis = "desde la ultima desconexion registrada de " + safe(requesterName);
      } else {
        long requestedWindow = parseRequestedWindowMs(normalized, defaultPlayerWindowMs());
        from = Math.max(now - requestedWindow, retentionStart(now));
        basis = "ultimos " + Math.max(1L, (now - from) / 60_000L) + " minutos";
      }
      String result = formatGeneralHistory(from, now, basis);
      debug("scope=general basis=" + basis + " window_ms=" + (now - from));
      return result;
    }

    return "";
  }

  public UUID resolveKnownPlayer(String nameOrUuid) {
    if (nameOrUuid == null || nameOrUuid.isBlank()) return null;
    try {
      UUID uuid = UUID.fromString(nameOrUuid.trim());
      return knownPlayers.containsKey(uuid) ? uuid : null;
    } catch (IllegalArgumentException ignored) {
    }
    String normalized = normalize(nameOrUuid);
    for (KnownPlayer known : knownPlayers.values()) {
      if (normalize(known.name()).equals(normalized)) return known.uuid();
    }
    return null;
  }

  public String knownName(UUID uuid) {
    KnownPlayer known = uuid == null ? null : knownPlayers.get(uuid);
    return known == null ? "" : known.name();
  }

  /** Removes all rolling/new-system traces that can be attributed to this player. */
  public void purgePlayer(UUID playerId, String lastKnownName) {
    if (playerId == null) return;
    String normalizedName = normalize(lastKnownName == null ? knownName(playerId) : lastKnownName);
    records.removeIf(record -> record.references(playerId, normalizedName));
    knownPlayers.remove(playerId);
    lastDisconnectAt.remove(playerId);
    lastJoinAt.remove(playerId);
  }

  public int size() {
    prune(System.currentTimeMillis());
    return records.size();
  }

  public String status() {
    return "activityJournal=" + (enabled() ? "enabled" : "disabled")
        + " records=" + size()
        + " retention=" + retentionMinutes() + "m"
        + " playerAccess=" + accessMode("activity-journal.access.player-history", "admin-only")
        + " generalAccess=" + accessMode("activity-journal.access.general-history", "admin-only");
  }

  private String formatTargetHistory(KnownPlayer target, long from, long now) {
    List<ActivityRecord> matched = records.stream()
        .filter(record -> record.timestampMs() >= from && record.timestampMs() <= now)
        .filter(record -> record.references(target.uuid(), normalize(target.name())))
        .toList();

    List<ActivityRecord> selected = selectRecords(matched, true);
    StringBuilder out = new StringBuilder("[ACTIVITY JOURNAL]\n")
        .append("scope=player, target=").append(target.name())
        .append(", window_minutes=").append(Math.max(1L, (now - from) / 60_000L))
        .append(", matched=").append(matched.size())
        .append(", included=").append(selected.size()).append('\n');
    appendRecords(out, selected);
    if (selected.isEmpty()) out.append("No hay actividad publica registrada para ese jugador en ese periodo.");
    return trimToConfiguredChars(out.toString());
  }

  private String formatGeneralHistory(long from, long now, String basis) {
    List<ActivityRecord> matched = records.stream()
        .filter(record -> record.timestampMs() >= from && record.timestampMs() <= now)
        .toList();
    List<ActivityRecord> selected = selectRecords(matched, true);

    StringBuilder out = new StringBuilder("[ACTIVITY JOURNAL]\n")
        .append("scope=general, basis=").append(basis)
        .append(", matched=").append(matched.size())
        .append(", included=").append(selected.size()).append('\n');
    appendRecords(out, selected);
    if (selected.isEmpty()) out.append("No hay actividad publica registrada en ese periodo.");
    return trimToConfiguredChars(out.toString());
  }

  /**
   * Balanced summaries retain important server events first, then fill remaining
   * space with chronologically distributed chat. This avoids sending a wall of chat
   * and keeps both player-specific and general history token-bounded.
   */
  private List<ActivityRecord> selectRecords(List<ActivityRecord> matched, boolean balanced) {
    if (matched == null || matched.isEmpty()) return List.of();
    int max = Math.max(plugin.getConfig().getInt("activity-journal.max-context-records", 18), 1);
    List<ActivityRecord> sorted = new ArrayList<>(matched);
    sorted.sort(Comparator.comparingLong(ActivityRecord::timestampMs));
    if (sorted.size() <= max) return List.copyOf(sorted);

    if (!balanced) {
      return List.copyOf(sorted.subList(sorted.size() - max, sorted.size()));
    }

    LinkedHashSet<ActivityRecord> chosen = new LinkedHashSet<>();
    List<ActivityRecord> events = sorted.stream().filter(r -> r.kind() == RecordKind.EVENT).toList();
    int eventBudget = Math.min(events.size(), Math.max(1, max / 2));
    int eventStart = Math.max(0, events.size() - eventBudget);
    chosen.addAll(events.subList(eventStart, events.size()));

    int remaining = max - chosen.size();
    List<ActivityRecord> chats = sorted.stream().filter(r -> r.kind() == RecordKind.CHAT).toList();
    if (remaining > 0 && !chats.isEmpty()) {
      if (chats.size() <= remaining) {
        chosen.addAll(chats);
      } else if (remaining == 1) {
        chosen.add(chats.get(chats.size() - 1));
      } else {
        // Evenly sample the whole interval and include the newest chat without
        // exceeding the hard record budget.
        for (int i = 0; i < remaining; i++) {
          int index = (int) Math.round((double) i * (chats.size() - 1) / (remaining - 1));
          chosen.add(chats.get(Math.min(chats.size() - 1, index)));
        }
      }
    }

    if (chosen.size() < max) {
      // If one category had little/no data, use the remaining budget for the newest
      // still-unselected relevant records instead of wasting prompt capacity.
      for (int i = sorted.size() - 1; i >= 0 && chosen.size() < max; i--) {
        chosen.add(sorted.get(i));
      }
    }

    List<ActivityRecord> result = new ArrayList<>(chosen);
    result.sort(Comparator.comparingLong(ActivityRecord::timestampMs));
    if (result.size() > max) result = result.subList(result.size() - max, result.size());
    return List.copyOf(result);
  }

  private void appendRecords(StringBuilder out, List<ActivityRecord> selected) {
    ZoneId zone = ZoneId.systemDefault();
    for (ActivityRecord record : selected) {
      String time = TIME.format(Instant.ofEpochMilli(record.timestampMs()).atZone(zone));
      if (record.kind() == RecordKind.CHAT) {
        out.append(time).append(" CHAT ").append(record.label()).append(": ")
            .append(compact(record.text(), 180)).append('\n');
      } else {
        out.append(time).append(" EVENT ").append(record.label()).append(": ")
            .append(compact(record.text(), 200)).append('\n');
      }
    }
  }

  private KnownPlayer resolveTarget(String normalizedText, UUID requesterId) {
    if (normalizedText == null || normalizedText.isBlank()) return null;

    KnownPlayer bestStrict = null;
    int bestLength = -1;
    for (KnownPlayer known : knownPlayers.values()) {
      if (requesterId != null && requesterId.equals(known.uuid())) continue;
      String normalizedName = normalize(known.name());
      if (normalizedName.isBlank()) continue;
      if (ContextTargetResolver.mentionsNameStrict(normalizedText, known.name())
          && normalizedName.length() > bestLength) {
        bestStrict = known;
        bestLength = normalizedName.length();
      }
    }
    if (bestStrict != null) return bestStrict;

    // Fuzzy nickname/typo matching is accepted only when exactly one known player
    // fits. This keeps "white" -> WITHE9033 useful without guessing among aliases.
    KnownPlayer fuzzy = null;
    for (KnownPlayer known : knownPlayers.values()) {
      if (requesterId != null && requesterId.equals(known.uuid())) continue;
      if (!ContextTargetResolver.mentionsName(normalizedText, known.name())) continue;
      if (fuzzy != null && !fuzzy.uuid().equals(known.uuid())) return null;
      fuzzy = known;
    }
    return fuzzy;
  }

  private static boolean looksLikePlayerHistoryQuestion(String text) {
    return containsAny(text,
        "que paso con", "que ha pasado con", "que hizo", "que ha hecho", "que estuvo haciendo",
        "que anduvo haciendo", "que hizo mientras", "que paso mientras", "historial de", "actividad de");
  }

  private static boolean looksLikeSinceDisconnectQuestion(String text) {
    return containsAny(text,
        "desde que me desconect", "mientras no estuve", "mientras no estaba", "cuando no estaba",
        "mientras estaba fuera", "desde que me fui", "desde que sali", "desde mi ultima conexion",
        "desde mi ultima desconexion", "en mi ausencia", "que me perdi");
  }

  private static boolean looksLikeGeneralHistoryQuestion(String text) {
    boolean timeHint = text.contains("ultima hora") || text.contains("ultimas horas")
        || text.contains("ultimos minutos") || DURATION_PATTERN.matcher(text).find();
    return timeHint && containsAny(text, "que paso", "que ha pasado", "que ocurrio", "que hicieron", "resumen");
  }

  private long parseRequestedWindowMs(String normalizedText, long fallback) {
    Matcher matcher = DURATION_PATTERN.matcher(normalizedText == null ? "" : normalizedText);
    if (!matcher.find()) {
      if (normalizedText != null && normalizedText.contains("ultima hora")) return Math.min(60 * 60_000L, retentionMs());
      return Math.min(fallback, retentionMs());
    }
    long amount;
    try {
      amount = Long.parseLong(matcher.group(1));
    } catch (NumberFormatException ex) {
      return Math.min(fallback, retentionMs());
    }
    String unit = matcher.group(2).toLowerCase(Locale.ROOT);
    long millis = unit.startsWith("h") ? amount * 60L * 60_000L : amount * 60_000L;
    return Math.max(60_000L, Math.min(millis, retentionMs()));
  }

  private boolean hasAccess(boolean admin, String path, String fallback) {
    String mode = accessMode(path, fallback);
    return "everyone".equals(mode) || admin;
  }

  private String accessMode(String path, String fallback) {
    String mode = plugin.getConfig().getString(path, fallback);
    mode = mode == null ? fallback : mode.trim().toLowerCase(Locale.ROOT);
    return "everyone".equals(mode) ? "everyone" : "admin-only";
  }

  private void debug(String message) {
    if (plugin.getConfig().getBoolean("activity-journal.debug-log", false)) {
      plugin.getLogger().info("Activity journal selected: " + message);
    }
  }

  private boolean enabled() {
    return plugin.getConfig().getBoolean("activity-journal.enabled", true);
  }

  private long defaultPlayerWindowMs() {
    long minutes = Math.max(plugin.getConfig().getLong("activity-journal.default-player-window-minutes", 120L), 1L);
    return Math.min(minutes * 60_000L, retentionMs());
  }

  private long retentionMs() {
    return retentionMinutes() * 60_000L;
  }

  private long retentionMinutes() {
    return Math.max(plugin.getConfig().getLong("activity-journal.retention-minutes", 120L), 1L);
  }

  private long retentionStart(long now) {
    return now - retentionMs();
  }

  private void rememberPlayer(UUID uuid, String name) {
    if (uuid == null || name == null || name.isBlank()) return;
    knownPlayers.put(uuid, new KnownPlayer(uuid, name));
  }

  private void prune(long now) {
    long cutoff = retentionStart(now);
    while (!records.isEmpty() && records.peekFirst().timestampMs() < cutoff) {
      records.removeFirst();
    }
    int max = Math.max(plugin.getConfig().getInt("activity-journal.max-records", 10_000), 100);
    while (records.size() > max) records.removeFirst();
  }

  private String trimToConfiguredChars(String text) {
    int max = Math.max(plugin.getConfig().getInt("activity-journal.max-context-chars", 3200), 500);
    if (text.length() <= max) return text.trim();
    return text.substring(0, Math.max(0, max - 20)).trim() + "\n[truncated]";
  }

  private static String normalize(String value) {
    return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9_ ]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private static boolean containsWholeWord(String text, String word) {
    if (text == null || word == null || word.isBlank()) return false;
    return (" " + text + " ").contains(" " + word + " ");
  }

  private static boolean containsAny(String text, String... terms) {
    if (text == null) return false;
    for (String term : terms) if (text.contains(term)) return true;
    return false;
  }

  private static String compact(String text, int max) {
    String cleaned = text == null ? "" : text.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
    return cleaned.length() <= max ? cleaned : cleaned.substring(0, Math.max(1, max - 1)).trim() + "…";
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "jugador" : value;
  }

  private enum RecordKind { CHAT, EVENT }

  private record KnownPlayer(UUID uuid, String name) { }

  private record ActivityRecord(
      long timestampMs,
      RecordKind kind,
      UUID authorId,
      String label,
      String text,
      Map<UUID, String> actors) {

    boolean references(UUID playerId, String normalizedName) {
      if (playerId != null && (playerId.equals(authorId) || actors.containsKey(playerId))) return true;
      if (normalizedName == null || normalizedName.isBlank()) return false;
      if (containsWholeWord(normalize(label), normalizedName)) return true;
      if (containsWholeWord(normalize(text), normalizedName)) return true;
      for (String actorName : actors.values()) {
        if (normalize(actorName).equals(normalizedName)) return true;
      }
      return false;
    }
  }
}
