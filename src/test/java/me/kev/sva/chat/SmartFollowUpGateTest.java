package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class SmartFollowUpGateTest {

  @Test
  void naturalRepliesRemainStrongSmartContinuations() {
    int chatty = ConversationManager.smartFollowUpAffinity(
        "bien gracias, que haces?",
        "iso como estas?",
        "bien gracias y tu?",
        Set.of("Aminowana"),
        1200L);
    int activity = ConversationManager.smartFollowUpAffinity(
        "voy a minar un rato",
        "iso como estas?",
        "bien, y tu que haces?",
        Set.of("Aminowana"),
        1800L);
    int opinion = ConversationManager.smartFollowUpAffinity(
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
    assertTrue(ConversationManager.looksLikeBroadSmartSideRequest("alguien tiene piedra?"));
    assertTrue(ConversationManager.looksLikeBroadSmartSideRequest("quien vende hierro"));
    assertFalse(ConversationManager.looksLikeBroadSmartSideRequest("quien es Tablos?"));
    assertFalse(ConversationManager.looksLikeBroadSmartSideRequest("alguien me dijo que estas loca"));
  }

  @Test
  void explicitOtherPlayerAddressRequiresRecipientShape() {
    Set<String> others = Set.of("Wachi", "Pedrox", "Aminowana");
    assertTrue(ConversationManager.looksLikeExplicitOtherPlayerAddress(
        "Wachi ven al spawn", others));
    assertTrue(ConversationManager.looksLikeExplicitOtherPlayerAddress(
        "oye Pedrox dame piedra", others));
    assertFalse(ConversationManager.looksLikeExplicitOtherPlayerAddress(
        "que opinas de Wachi?", others));
    assertFalse(ConversationManager.looksLikeExplicitOtherPlayerAddress(
        "Wachi es bastante raro", others));
  }

  @Test
  void trivialAcknowledgementsCanStayLocal() {
    assertTrue(ConversationManager.isTrivialSmartAcknowledgement("xd"));
    assertTrue(ConversationManager.isTrivialSmartAcknowledgement("jajaja"));
    assertFalse(ConversationManager.isTrivialSmartAcknowledgement("bien gracias"));
  }

  @Test
  void ellipticalSideAnswerFitsRecentPublicQuestionBetterThanIsoldaAnchor() {
    int isolda = ConversationManager.smartFollowUpAffinity(
        "yo tengo",
        "iso como estas?",
        "bien gracias y tu?",
        Set.of("Aminowana"),
        700L);
    int side = ConversationManager.localLateralAffinity(
        "yo tengo",
        "oye alguien tiene piedra?",
        "Pedrox",
        700L);

    assertTrue(side >= 36);
    assertTrue(side > isolda + 3);
  }
}
