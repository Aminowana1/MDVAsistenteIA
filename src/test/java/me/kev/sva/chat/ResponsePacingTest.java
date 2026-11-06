package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class ResponsePacingTest {
  private static final ResponsePacing PACING = new ResponsePacing(true, 2_500L, 3_500L, 189, 750L);

  @Test
  void typingTimeScalesWithLengthAndCapsAtN() {
    assertEquals(0L, PACING.typingMs(""));
    assertEquals(1_741L, PACING.typingMs("x".repeat(94))); // 94/189 * 3500
    assertEquals(3_500L, PACING.typingMs("x".repeat(189)));
    assertEquals(3_500L, PACING.typingMs("x".repeat(400)));
  }

  @Test
  void latencyIsPartOfTheBudgetNotAddedOnTop() {
    String max = "x".repeat(189);
    // Trigger at 0, AI answered at 1500: due at 2500 + 3500 = 6000.
    assertEquals(List.of(6_000L), PACING.schedule(List.of(max), 0L, 1_500L, 0L));
    // AI already took longer than the whole budget: send right away.
    assertEquals(List.of(9_000L), PACING.schedule(List.of(max), 0L, 9_000L, 0L));
  }

  @Test
  void followingLinesWaitForTheirOwnTypingTime() {
    List<Long> times = PACING.schedule(List.of("x".repeat(189), "ok", "x".repeat(189)), 0L, 1_000L, 0L);
    // second line is short, so the old 750 ms chained minimum applies
    assertEquals(List.of(6_000L, 6_750L, 10_250L), times);
  }

  @Test
  void neverOvertakesThePreviousScene() {
    // Previous scene's last line goes out at 8000; this short reply would be due at 2500.
    assertEquals(List.of(8_750L), PACING.schedule(List.of("si"), 0L, 1_000L, 8_000L));
  }

  @Test
  void idleScenesOnlyUseTypingTime() {
    assertEquals(List.of(1_000L + 3_500L), PACING.schedule(List.of("x".repeat(189)), -1L, 1_000L, 0L));
  }

  @Test
  void disabledKeepsTheOldFixedChainedDelay() {
    ResponsePacing off = new ResponsePacing(false, 2_500L, 3_500L, 189, 750L);
    assertEquals(List.of(1_000L, 1_750L, 2_500L),
        off.schedule(List.of("x".repeat(189), "b", "c"), 0L, 1_000L, 0L));
  }
}
