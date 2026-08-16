package me.kev.sva.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.kev.sva.chat.assistant.RelationshipUpdate;

final class RelationshipSignalDetectorTest {

  @Test
  void dateProposalCreatesRecentMemoryWithoutStartingRomance() {
    RelationshipUpdate update = RelationshipSignalDetector.detect(
        "En3Minutos", "isolda, que te parece si tenemos una primera cita para conocernos mejor?");

    assertNotNull(update);
    assertEquals(1, update.delta());
    assertEquals("r", update.memoryKind());
    assertEquals(3, update.importance());
    assertEquals("", update.romanceAction());
  }

  @Test
  void strongAffectionIsRecorded() {
    RelationshipUpdate update = RelationshipSignalDetector.detect(
        "Aminowana", "iso me agradas mucho y siento una profunda confianza en vos");

    assertNotNull(update);
    assertEquals(1, update.delta());
    assertEquals("r", update.memoryKind());
  }

  @Test
  void directHostilityLowersRelationship() {
    RelationshipUpdate update = RelationshipSignalDetector.detect(
        "Aminowana", "iso eres una puta");

    assertNotNull(update);
    assertEquals(-1, update.delta());
  }

  @Test
  void greetingsAndRelationshipMetaQuestionsAreBookkeepingNeutral() {
    assertTrue(RelationshipSignalDetector.isBookkeepingNeutral("holaa iso"));
    assertTrue(RelationshipSignalDetector.isBookkeepingNeutral("iso pq me tratas asi?"));
    assertTrue(RelationshipSignalDetector.isBookkeepingNeutral("queee :c"));
    assertTrue(RelationshipSignalDetector.isBookkeepingNeutral("queee :3"));
    assertTrue(RelationshipSignalDetector.isBookkeepingNeutral("xd :c"));
  }

  @Test
  void realComplimentIsNotNeutralAndGetsLocalSignal() {
    assertNotNull(RelationshipSignalDetector.detect(
        "En3Minutos", "iso, como esta la chica mas linda del server?"));
  }

  @Test
  void genericTierTextIsNotAcceptedAsMemorySummary() {
    assertTrue(RelationshipSignalDetector.isGenericMemorySummary("Confianza y afecto profundos"));
  }

  @Test
  void neutralMessageDoesNotCreateFallbackUpdate() {
    assertNull(RelationshipSignalDetector.detect("Kroattan", "iso estas ahi?"));
  }
}
