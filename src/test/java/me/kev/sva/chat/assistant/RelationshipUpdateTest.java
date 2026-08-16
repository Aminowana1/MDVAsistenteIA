package me.kev.sva.chat.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

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
  void parsesStructuredObjectReturnedBySmallModels() {
    RelationshipUpdate update = RelationshipUpdate.parse(Map.of(
        "player", "En3Minutos",
        "delta", 1,
        "kind", "recent",
        "importance", 3,
        "memory", "Le propuso una cita",
        "romance", "none"));

    assertNotNull(update);
    assertEquals("En3Minutos", update.playerName());
    assertEquals(1, update.delta());
    assertEquals("r", update.memoryKind());
    assertEquals(3, update.importance());
    assertEquals("Le propuso una cita", update.memory());
    assertEquals("", update.romanceAction());
  }

  @Test
  void acceptsCommonRomanceAliasesButKeepsValidationInJava() {
    RelationshipUpdate update = RelationshipUpdate.parse(Map.of(
        "name", "En3Minutos",
        "change", 0,
        "type", "persistent",
        "priority", 5,
        "summary", "Aceptaron ser pareja",
        "romanceAction", "accept"));

    assertNotNull(update);
    assertEquals("p", update.memoryKind());
    assertEquals("start", update.romanceAction());
  }

  @Test
  void acceptsCompactUpdateWhenOptionalRomanceFieldIsMissing() {
    RelationshipUpdate update = RelationshipUpdate.parseCompact(
        "En3Minutos|1|r|3|Le hizo un cumplido");

    assertNotNull(update);
    assertEquals("En3Minutos", update.playerName());
    assertEquals(1, update.delta());
    assertEquals("r", update.memoryKind());
    assertEquals("Le hizo un cumplido", update.memory());
    assertEquals("", update.romanceAction());
  }

  @Test
  void rejectsMalformedCompactUpdate() {
    assertNull(RelationshipUpdate.parseCompact("Kroattan|1|r"));
    assertNull(RelationshipUpdate.parseCompact("Kroattan|not-a-number|r|2|x|-"));
  }
}
