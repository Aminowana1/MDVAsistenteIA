package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class GroupThreadAffinityTest {

  @Test
  void shortEchoQuestionsCanJoinFromLastIsoldaReply() {
    assertTrue(ConversationManager.activeThreadLineAffinity(
        "que cosas importantes?",
        "El amor no lo es todo. Hay cosas mas importantes en una relacion.",
        Set.of("En3Minutos"),
        1800L) >= 34);

    assertTrue(ConversationManager.activeThreadLineAffinity(
        "como es eso de tension?",
        "No me alejas tu, En3Minutos, es la tension entre nosotros.",
        Set.of("En3Minutos"),
        1500L) >= 34);

    assertTrue(ConversationManager.activeThreadLineAffinity(
        "que si sientes?",
        "En serio? Porque yo si la siento.",
        Set.of("En3Minutos"),
        1200L) >= 34);
  }

  @Test
  void participantInterjectionCanJoinWithoutSayingIsolda() {
    assertTrue(ConversationManager.activeThreadLineAffinity(
        "no propongas nada En3Minutos",
        "y como lo propones, En3Minutos?",
        Set.of("En3Minutos"),
        900L) >= 34);
  }

  @Test
  void unrelatedPublicRequestDoesNotLookLikeActiveAnchor() {
    int score = ConversationManager.activeThreadLineAffinity(
        "alguien tiene piedra en el mercado?",
        "Me gustaria que pudieramos comunicarnos mejor, sin malentendidos.",
        Set.of("En3Minutos"),
        800L);
    assertTrue(score < 34);
  }
  @Test
  void anchorCanJoinBelowBroadThresholdButSideChatStillVetoes() {
    assertTrue(ConversationManager.shouldJoinGroupCandidate(
        false, false, false,
        40, 0, 44,
        40, 34, 10));

    assertTrue(!ConversationManager.shouldJoinGroupCandidate(
        false, false, true,
        60, 0, 44,
        60, 34, 10));

    assertTrue(!ConversationManager.shouldJoinGroupCandidate(
        false, false, false,
        55, 50, 44,
        55, 34, 10));
  }

}
