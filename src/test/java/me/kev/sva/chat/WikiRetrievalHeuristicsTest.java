package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class WikiRetrievalHeuristicsTest {

  @Test
  void naturalObtainAndDropWordingTriggersWiki() {
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch("iso como se consigue mango resinoso?")));
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch("iso que da el acolito necrotico")));
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch("iso en que coordenadas puedo encontrar un mini jefe")));
  }

  @Test
  void smallTalkStillDoesNotBecomeKnowledgeIntent() {
    assertFalse(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch("iso como estas?")));
  }

  @Test
  void commonMobTyposReceiveFuzzyCredit() {
    assertTrue(ConversationManager.bestFuzzyTokenScore(
        "acohilitico", Set.of("acolito", "necrotico")) > 0);
    assertTrue(ConversationManager.bestFuzzyTokenScore(
        "necrotido", Set.of("acolito", "necrotico")) > 0);
  }

  @Test
  void fuzzyEntityTermsPreferDedicatedWikiKey() {
    String key = ConversationManager.normalizeForSearch("mob-hueste-acolito-necrotico".replace('-', ' '));
    assertTrue(ConversationManager.allTermsMatchKey(
        Set.of("acohilitico", "necrotido"), key, Set.of("mob", "hueste", "acolito", "necrotico")));
    assertFalse(ConversationManager.allTermsMatchKey(
        Set.of("acohilitico", "necrotido"),
        ConversationManager.normalizeForSearch("mobs-hueste-overview".replace('-', ' ')),
        Set.of("mobs", "hueste", "overview")));
  }

  @Test
  void wikiFallbackCanExtractCraftAndDrops() {
    String craftBlock = """
        [WIKI REQUEST speaker=Aminowana query='iso como se consigue mango resinoso']
        [obtain-wood-crafted-materials] Mango Resinoso:
        Se fabrica con:
        9 Ramas Resinosas → 1 Mango Resinoso.
        """;
    String craft = ConversationManager.extractWikiFallbackAnswer(
        "iso como se consigue mango resinoso?", craftBlock);
    assertTrue(craft.contains("9 Ramas Resinosas"));

    String dropsBlock = """
        [WIKI REQUEST speaker=InfiniteVoid2026 query='que da el acolito necrotico']
        [mob-hueste-acolito-necrotico] Acólito Necrótico:
        Hechicero de la Hueste.
        Habilidades conocidas:
        - Orbe Devorador.
        Drops:
        - 1-4 Huesos Profanos: 99%.
        - 1-3 Esencias de la Muerte: 99%.
        """;
    String drops = ConversationManager.extractWikiFallbackAnswer(
        "iso que da el acolito necrotico", dropsBlock);
    assertTrue(drops.contains("1-4 Huesos Profanos"));
    assertTrue(drops.contains("1-3 Esencias de la Muerte"));
  }
  @Test
  void multiSpeakerCoverageRequiresExplicitNameForIndependentFacts() {
    assertTrue(ConversationManager.responseCoversSpeaker(
        List.of("Aminowana, se fabrica con 9 Ramas Resinosas"), "Aminowana"));
    assertFalse(ConversationManager.responseCoversSpeaker(
        List.of("calmense los dos, parecen viejas de pueblo xd"), "Aminowana"));
  }

  @Test
  void explicitGroupBridgeCanInheritSmartContinuityWithoutBroadChatterGuessing() {
    assertTrue(ConversationManager.looksLikeExplicitGroupBridge("contale q estas bien"));
    assertTrue(ConversationManager.looksLikeExplicitGroupBridge("decile que no exagere"));
    assertFalse(ConversationManager.looksLikeExplicitGroupBridge("viste infinit"));
    assertFalse(ConversationManager.looksLikeExplicitGroupBridge("ando minando hierro"));
  }

  @Test
  void groupThreadHeuristicsSeparateSideChatFromIsoldasExchange() {
    assertTrue(ConversationManager.looksLikeGroupContinuation("si, es un ladron y un mezquino"));
    assertFalse(ConversationManager.looksLikeGroupContinuation("yo tengo"));
    assertTrue(ConversationManager.looksLikeSocialThreadInterjection(
        "Amino dejate de hacerte el que esta bien"));
    assertTrue(ConversationManager.referencesAnyParticipantAlias(
        "Amino dejate de hacerte el que esta bien", Set.of("Aminowana")));
    assertTrue(ConversationManager.sharesConversationTopic(
        "Amino dejate de hacerte el que esta bien",
        "bien gracias, que haces?",
        Set.of("Aminowana")));
    assertFalse(ConversationManager.sharesConversationTopic(
        "Amino, alguien tiene piedra?",
        "bien gracias, que haces?",
        Set.of("Aminowana")));
  }

  @Test
  void affinityRouterPrefersActiveIsoldaThreadForLinkedSocialReply() {
    int isolda = ConversationManager.localThreadAffinity(
        "Amino dejate de hacerte el que esta bien",
        "bien gracias, que haces?",
        "bien gracias, que haces?",
        Set.of("Aminowana"),
        900L,
        false);
    int side = ConversationManager.localLateralAffinity(
        "Amino dejate de hacerte el que esta bien",
        "dile la verdad",
        "Pedrox",
        900L);
    assertTrue(isolda >= 68);
    assertTrue(isolda - side >= 12);
  }

  @Test
  void affinityRouterKeepsEllipticalAnswerInCompetingStoneSideThread() {
    int isolda = ConversationManager.localThreadAffinity(
        "yo tengo",
        "bien gracias, que haces?",
        "bien gracias, que haces?",
        Set.of("Aminowana"),
        700L,
        false);
    int side = ConversationManager.localLateralAffinity(
        "yo tengo",
        "oye alguien tiene piedra?",
        "Pedrox",
        700L);
    assertTrue(side > isolda);
    assertTrue(isolda - side < 12);
  }

  @Test
  void affinityRouterCanUnderstandUnlistedContextualContradiction() {
    int isolda = ConversationManager.localThreadAffinity(
        "eso no tiene ningun sentido",
        "Aminowana dice que esta perfectamente bien",
        "Aminowana dice que esta perfectamente bien",
        Set.of("Aminowana"),
        800L,
        false);
    assertTrue(isolda >= 48);
  }

}
