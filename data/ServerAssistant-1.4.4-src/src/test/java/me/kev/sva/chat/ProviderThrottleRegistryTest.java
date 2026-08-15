package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProviderThrottleRegistryTest {

  @Test
  void enforcesLocalRequestWindow() {
    ProviderThrottleRegistry registry = new ProviderThrottleRegistry();
    registry.recordAttempt("gemini|model");
    registry.recordAttempt("gemini|model");

    assertEquals(2, registry.requestsLastMinute("gemini|model"));
    assertTrue(registry.getDelay("gemini|model", 2) > 0L);
  }

  @Test
  void providerBucketsAreIndependent() {
    ProviderThrottleRegistry registry = new ProviderThrottleRegistry();
    registry.recordAttempt("gemini|model");

    assertTrue(registry.getDelay("gemini|model", 1) > 0L);
    assertEquals(0L, registry.getDelay("openai|model", 1));
  }

  @Test
  void explicitCooldownIsRetained() {
    ProviderThrottleRegistry registry = new ProviderThrottleRegistry();
    registry.applyCooldown("gemini|model", 5_000L);

    assertTrue(registry.cooldownRemainingMs("gemini|model") > 4_000L);
    assertTrue(registry.getDelay("gemini|model", 4) > 4_000L);
  }
}
