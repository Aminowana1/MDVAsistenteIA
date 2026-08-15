package me.kev.sva.chat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Provider throttling state owned by the plugin instance, not by ConversationManager.
 * Therefore /sva reload cannot erase a Gemini/OpenAI cooldown and immediately hammer
 * the same external quota again.
 */
public final class ProviderThrottleRegistry {
  private final Map<String, Bucket> buckets = new HashMap<>();

  public long getDelay(String key, int maxRequestsPerMinute) {
    Bucket bucket = bucket(key);
    long now = System.currentTimeMillis();
    prune(bucket.requestTimes, now - 60_000L);

    long providerDelay = Math.max(0L, bucket.cooldownUntil - now);
    if (maxRequestsPerMinute <= 0 || bucket.requestTimes.size() < maxRequestsPerMinute) {
      return providerDelay;
    }

    Long oldest = bucket.requestTimes.peekFirst();
    long localDelay = oldest == null ? 0L : Math.max(50L, 60_000L - (now - oldest));
    return Math.max(providerDelay, localDelay);
  }

  public void recordAttempt(String key) {
    Bucket bucket = bucket(key);
    long now = System.currentTimeMillis();
    prune(bucket.requestTimes, now - 60_000L);
    bucket.requestTimes.addLast(now);
  }

  public void applyCooldown(String key, long delayMs) {
    Bucket bucket = bucket(key);
    bucket.cooldownUntil = Math.max(bucket.cooldownUntil, System.currentTimeMillis() + Math.max(0L, delayMs));
  }

  public int requestsLastMinute(String key) {
    Bucket bucket = bucket(key);
    long now = System.currentTimeMillis();
    prune(bucket.requestTimes, now - 60_000L);
    return bucket.requestTimes.size();
  }

  public long cooldownRemainingMs(String key) {
    return Math.max(0L, bucket(key).cooldownUntil - System.currentTimeMillis());
  }

  private Bucket bucket(String key) {
    return buckets.computeIfAbsent(key == null ? "default" : key, ignored -> new Bucket());
  }

  private static void prune(Deque<Long> deque, long threshold) {
    while (!deque.isEmpty() && deque.peekFirst() < threshold) {
      deque.removeFirst();
    }
  }

  private static final class Bucket {
    final Deque<Long> requestTimes = new ArrayDeque<>();
    long cooldownUntil = 0L;
  }
}
