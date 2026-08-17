package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ReplyShapingTest {

  @Test
  void oneThoughtSplitByModelBecomesOnePublicBubble() {
    List<String> shaped = ConversationManager.coalesceMessagesByExplicitTargets(
        List.of("¿Te duele algo?", "No esperaba que te lo tomes asi..."),
        Set.of("En3Minutos"));

    assertEquals(List.of("¿Te duele algo? No esperaba que te lo tomes asi..."), shaped);
  }

  @Test
  void continuationForSameNamedPlayerAlsoBecomesOneBubble() {
    List<String> shaped = ConversationManager.coalesceMessagesByExplicitTargets(
        List.of("En3Minutos, no es tan sencillo.", "Necesitamos hablarlo con calma."),
        Set.of("En3Minutos", "Aminowana"));

    assertEquals(List.of(
        "En3Minutos, no es tan sencillo. Necesitamos hablarlo con calma."), shaped);
  }

  @Test
  void explicitIndependentRepliesToDifferentPlayersStaySeparate() {
    List<String> shaped = ConversationManager.coalesceMessagesByExplicitTargets(
        List.of("Aminowana, la Viridita sale bajo tierra.",
            "En3Minutos, el Mango Resinoso usa Ramas Resinosas."),
        Set.of("Aminowana", "En3Minutos"));

    assertEquals(2, shaped.size());
    assertEquals("Aminowana, la Viridita sale bajo tierra.", shaped.get(0));
    assertEquals("En3Minutos, el Mango Resinoso usa Ramas Resinosas.", shaped.get(1));
  }
}
