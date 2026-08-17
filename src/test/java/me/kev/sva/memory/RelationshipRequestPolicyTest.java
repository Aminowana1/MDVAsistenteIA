package me.kev.sva.memory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

final class RelationshipRequestPolicyTest {
  private static final UUID PLAYER = UUID.fromString("2cda16ba-11d9-3367-9926-36163a18ab14");

  @Test
  void detectsKnowledgeSocialAndActionRequestsButNotOrdinaryStatements() {
    assertTrue(RelationshipRequestPolicy.looksLikeRequest("iso revisa en la wiki como se hace mango resinoso"));
    assertTrue(RelationshipRequestPolicy.looksLikeRequest("Iso, saluda al nuevo"));
    assertTrue(RelationshipRequestPolicy.looksLikeRequest("Iso tirame un rayo"));
    assertTrue(RelationshipRequestPolicy.looksLikeRequest("qué opinas de mí?"));

    assertFalse(RelationshipRequestPolicy.looksLikeRequest("Infinite es un ladron y un mezquino"));
    assertFalse(RelationshipRequestPolicy.looksLikeRequest("jajaja tremendo fact iso"));
  }

  @Test
  void probabilityEdgesAreHardGuarantees() {
    assertFalse(RelationshipRequestPolicy.shouldRefuse(0.0, 77L, PLAYER, "iso ayudame"));
    assertTrue(RelationshipRequestPolicy.shouldRefuse(1.0, 77L, PLAYER, "iso ayudame"));
    assertFalse(RelationshipRequestPolicy.shouldRefuse(1.0, 77L, PLAYER, "hola iso"));
  }

  @Test
  void sameSceneDecisionIsStable() {
    boolean first = RelationshipRequestPolicy.shouldRefuse(0.90, 123L, PLAYER, "iso como consigo mango resinoso?");
    boolean second = RelationshipRequestPolicy.shouldRefuse(0.90, 123L, PLAYER, "iso como consigo mango resinoso?");
    assertTrue(first == second);
  }
}
