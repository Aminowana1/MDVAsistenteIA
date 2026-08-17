package me.kev.sva.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RelationshipMemoryQualityTest {

  @Test
  void vagueProposalCanBeGroundedFromActualPlayerLine() {
    String memory = RelationshipManager.buildGroundedMemoryFallback(
        "En3Minutos", "Isolda, aceptas ser mi novia?", "");
    assertTrue(memory.contains("En3Minutos"));
    assertTrue(memory.contains("formalizar"));
    assertTrue(memory.contains("aceptas ser mi novia"));
  }

  @Test
  void standaloneStoredMemoryGetsActorName() {
    String memory = RelationshipManager.ensureMemoryNamesActor(
        "Aminowana", "Me dijo que me ama");
    assertTrue(memory.startsWith("Aminowana:"));
  }
}
