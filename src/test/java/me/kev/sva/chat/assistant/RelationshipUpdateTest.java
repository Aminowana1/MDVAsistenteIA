package me.kev.sva.chat.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class RelationshipUpdateTest {

  @Test
  void parsesCompactUpdateWithoutExtraModelRoundTrip() {
    RelationshipUpdate update = RelationshipUpdate.parseCompact(
        "Kroattan|1|r|2|Tuvieron una buena charla|-");

    assertNotNull(update);
    assertEquals("Kroattan", update.playerName());
    assertEquals(1, update.delta());
    assertEquals("r", update.memoryKind());
    assertEquals(2, update.importance());
    assertEquals("Tuvieron una buena charla", update.memory());
    assertEquals("", update.romanceAction());
    assertEquals("Kroattan|1|r|2|Tuvieron una buena charla|-", update.toCompact());
  }

  @Test
  void rejectsMalformedCompactUpdate() {
    assertNull(RelationshipUpdate.parseCompact("Kroattan|1|r"));
    assertNull(RelationshipUpdate.parseCompact("Kroattan|not-a-number|r|2|x|-"));
  }
}
