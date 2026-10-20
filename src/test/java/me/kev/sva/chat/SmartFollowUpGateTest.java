package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class SmartFollowUpGateTest {

  @Test
  void naturalRepliesRemainStrongSmartContinuations() {
    int chatty = ThreadAffinity.smartFollowUpAffinity(
        "bien gracias, que haces?",
        "iso como estas?",
        "bien gracias y tu?",
        Set.of("Aminowana"),
        1200L);
    int activity = ThreadAffinity.smartFollowUpAffinity(
        "voy a minar un rato",
        "iso como estas?",
        "bien, y tu que haces?",
        Set.of("Aminowana"),
        1800L);
    int opinion = ThreadAffinity.smartFollowUpAffinity(
        "y tu que opinas?",
        "estabamos hablando de Tablos",
        "mmm, puede ser",
        Set.of("Aminowana"),
        2300L);

    assertTrue(chatty >= 28);
    assertTrue(activity >= 28);
    assertTrue(opinion >= 28);
  }

  @Test
  void broadPublicRequestsDoNotConfuseNormalWhoQuestions() {
    assertTrue(ThreadAffinity.looksLikeBroadSmartSideRequest("alguien tiene piedra?"));
    assertTrue(ThreadAffinity.looksLikeBroadSmartSideRequest("quien vende hierro"));
    assertFalse(ThreadAffinity.looksLikeBroadSmartSideRequest("quien es Tablos?"));
    assertFalse(ThreadAffinity.looksLikeBroadSmartSideRequest("alguien me dijo que estas loca"));
  }

  @Test
  void explicitOtherPlayerAddressRequiresRecipientShape() {
    Set<String> others = Set.of("Wachi", "Pedrox", "Aminowana");
    assertTrue(ThreadAffinity.looksLikeExplicitOtherPlayerAddress(
        "Wachi ven al spawn", others));
    assertTrue(ThreadAffinity.looksLikeExplicitOtherPlayerAddress(
        "oye Pedrox dame piedra", others));
    assertFalse(ThreadAffinity.looksLikeExplicitOtherPlayerAddress(
        "que opinas de Wachi?", others));
    assertFalse(ThreadAffinity.looksLikeExplicitOtherPlayerAddress(
        "Wachi es bastante raro", others));
  }

  @Test
  void trivialAcknowledgementsCanStayLocal() {
    assertTrue(ThreadAffinity.isTrivialSmartAcknowledgement("xd"));
    assertTrue(ThreadAffinity.isTrivialSmartAcknowledgement("jajaja"));
    assertFalse(ThreadAffinity.isTrivialSmartAcknowledgement("bien gracias"));
  }

  @Test
  void ellipticalSideAnswerFitsRecentPublicQuestionBetterThanIsoldaAnchor() {
    int isolda = ThreadAffinity.smartFollowUpAffinity(
        "yo tengo",
        "iso como estas?",
        "bien gracias y tu?",
        Set.of("Aminowana"),
        700L);
    int side = ThreadAffinity.localLateralAffinity(
        "yo tengo",
        "oye alguien tiene piedra?",
        "Pedrox",
        700L);

    assertTrue(side >= 36);
    assertTrue(side > isolda + 3);
  }
}
