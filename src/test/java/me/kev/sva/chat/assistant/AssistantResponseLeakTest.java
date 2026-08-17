package me.kev.sva.chat.assistant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class AssistantResponseLeakTest {

  @Test
  void internalWikiNoMatchInstructionIsNeverPublicDialogue() {
    assertTrue(AssistantResponse.looksLikeInternalContextLeak(
        "Aminowana, No trusted wiki section matched this server-knowledge question; do not guess a server-specific fact."));
    assertTrue(AssistantResponse.looksLikeInternalContextLeak(
        "[WIKI REQUEST speaker=Aminowana query='fake' result=no_match]"));
    assertFalse(AssistantResponse.looksLikeInternalContextLeak(
        "no me suena esa espada, no tengo informacion fiable sobre ella"));
  }
}
