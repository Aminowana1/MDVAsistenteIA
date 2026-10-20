package me.kev.sva.chat;

import java.util.ArrayList;
import java.util.List;

/**
 * Realistic "typing" timing for Isolda's public chat lines. Pure math, no Bukkit.
 *
 * <p>The first line is due at {@code trigger + base + typing(line)}: the capture
 * window and the AI's own latency are part of that budget, never added on top.
 * Every following line waits for its own typing time (never less than the old
 * fixed chained delay). Nothing may be sent before the previous scene's last line,
 * so a short later answer can never overtake a longer earlier one.</p>
 */
public record ResponsePacing(
    boolean enabled,
    long baseDelayMs,
    long lengthDelayMs,
    int maxChars,
    long minChainedDelayMs) {

  /** Used when chat.max-assistant-message-length is 0 (unlimited). */
  static final int FALLBACK_MAX_CHARS = 190;

  /** Time Isolda "types" one line: (length / max chars) * n, capped at n. */
  public long typingMs(String message) {
    if (!enabled || message == null || message.isEmpty() || lengthDelayMs <= 0L) return 0L;
    int limit = maxChars > 0 ? maxChars : FALLBACK_MAX_CHARS;
    double ratio = Math.min(1.0D, (double) message.length() / limit);
    return Math.round(ratio * lengthDelayMs);
  }

  /**
   * Absolute send time for each message.
   *
   * @param triggerAtMs   when the player wrote the line that opened the scene, or a
   *                      negative value when there is none (idle scenes)
   * @param nowMs         current time
   * @param notBeforeMs   send time of the previous scene's last line (0 when none)
   */
  public List<Long> schedule(List<String> messages, long triggerAtMs, long nowMs, long notBeforeMs) {
    List<Long> sendTimes = new ArrayList<>();
    if (messages == null || messages.isEmpty()) return sendTimes;

    long chained = Math.max(minChainedDelayMs, 0L);
    long first = nowMs;
    if (enabled) {
      long typing = typingMs(messages.get(0));
      first = triggerAtMs >= 0L
          ? Math.max(nowMs, triggerAtMs + Math.max(baseDelayMs, 0L) + typing)
          : nowMs + typing;
    }
    if (notBeforeMs > 0L) {
      first = Math.max(first, notBeforeMs + chained);
    }
    sendTimes.add(first);

    long previous = first;
    for (int i = 1; i < messages.size(); i++) {
      previous += Math.max(chained, typingMs(messages.get(i)));
      sendTimes.add(previous);
    }
    return sendTimes;
  }
}
