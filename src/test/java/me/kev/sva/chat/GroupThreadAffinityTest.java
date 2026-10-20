package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class GroupThreadAffinityTest {

  @Test
  void shortEchoQuestionsCanJoinFromLastIsoldaReply() {
    assertTrue(ThreadAffinity.activeThreadLineAffinity(
        "que cosas importantes?",
        "El amor no lo es todo. Hay cosas mas importantes en una relacion.",
        Set.of("En3Minutos"),
        1800L) >= 34);

    assertTrue(ThreadAffinity.activeThreadLineAffinity(
        "como es eso de tension?",
        "No me alejas tu, En3Minutos, es la tension entre nosotros.",
        Set.of("En3Minutos"),
        1500L) >= 34);

    assertTrue(ThreadAffinity.activeThreadLineAffinity(
        "que si sientes?",
        "En serio? Porque yo si la siento.",
        Set.of("En3Minutos"),
        1200L) >= 34);
  }

  @Test
  void participantInterjectionCanJoinWithoutSayingIsolda() {
    assertTrue(ThreadAffinity.activeThreadLineAffinity(
        "no propongas nada En3Minutos",
        "y como lo propones, En3Minutos?",
        Set.of("En3Minutos"),
        900L) >= 34);
  }

  @Test
  void unrelatedPublicRequestDoesNotLookLikeActiveAnchor() {
    int score = ThreadAffinity.activeThreadLineAffinity(
        "alguien tiene piedra en el mercado?",
        "Me gustaria que pudieramos comunicarnos mejor, sin malentendidos.",
        Set.of("En3Minutos"),
        800L);
    assertTrue(score < 34);
  }
  @Test
  void anchorCanJoinBelowBroadThresholdButSideChatStillVetoes() {
    assertTrue(ThreadAffinity.shouldJoinGroupCandidate(
        false, false, false,
        40, 0, 44,
        40, 34, 10));

    assertTrue(!ThreadAffinity.shouldJoinGroupCandidate(
        false, false, true,
        60, 0, 44,
        60, 34, 10));

    assertTrue(!ThreadAffinity.shouldJoinGroupCandidate(
        false, false, false,
        55, 50, 44,
        55, 34, 10));
  }

}
