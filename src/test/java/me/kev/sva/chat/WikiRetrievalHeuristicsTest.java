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
  void deicticOpinionQuestionsStaySocialInsteadOfWikiNoMatch() {
    assertTrue(ConversationManager.looksLikeDeicticOpinionSmallTalk(
        ConversationManager.normalizeForSearch("iso q opinas de eso?")));
    assertTrue(ConversationManager.looksLikeDeicticOpinionSmallTalk(
        ConversationManager.normalizeForSearch("que piensas de eso iso")));
    assertFalse(ConversationManager.looksLikeImplicitServerEntityQuestion(
        "iso q opinas de eso?", ConversationManager.normalizeForSearch("iso q opinas de eso?")));
  }

  @Test
  void shortUnknownServerEntityQuestionStillConsultsLocalWiki() {
    String raw = "epicardo y la espada ultracita? iso";
    assertTrue(ConversationManager.looksLikeImplicitServerEntityQuestion(
        raw, ConversationManager.normalizeForSearch(raw)));
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch("iso quien es epicardo?")));
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

  @Test
  void noMatchControlTextCanNeverBecomeLocalWikiAnswer() {
    String noMatch = """
        [WIKI REQUEST speaker=Aminowana query='epicardo y la espada ultracita' result=no_match]
        No trusted wiki section matched this server-knowledge question; do not guess a server-specific fact.
        """;
    assertTrue(ConversationManager.wikiBlockIsNoMatch(noMatch));
    assertTrue(ConversationManager.extractWikiFallbackAnswer(
        "epicardo y la espada ultracita? iso", noMatch).isBlank());
  }

  @Test
  void concreteNamedEntityGateRejectsGenericRelatedPages() {
    assertTrue(ConversationManager.looksLikeConcreteNamedEntityQuery(
        "iso donde consigo la espada de artera"));
    assertFalse(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso donde consigo la espada de artera",
        "spawn-saurios",
        "Dónde aparecen los Saurios, junglas y pantanos",
        "Los Saurios aparecen en Jungla y Pantano."));

    assertFalse(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso que sabes del arco de la jungla",
        "spawn-saurios",
        "Dónde aparecen los Saurios, junglas y pantanos",
        "Biomas: Jungla, Pantano."));

    assertFalse(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso donde esta la espada de nagamuta",
        "weapons-swords-common",
        "Espadas comunes, Espada Hoja, Espada Orco y Filo Escarchado",
        "Espada Hoja y Espada Orco son armas comunes."));
  }

  @Test
  void concreteNamedEntityGateKeepsRealNamedItemsAndCompactIds() {
    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso donde consigo espada hoja",
        "weapons-swords-common",
        "Espadas comunes y Espada Hoja",
        "Espada Hoja: espada fabricada con materiales del bosque."));

    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso como hago el arco bosque",
        "crafting-ranged-t1",
        "Crafteos de arcos y ballestas",
        "ARCOBOSQUE: 2 Nudos Vivos + 2 Mangos Resinosos."));

    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso donde consigo la espada del duelista necrotido",
        "weapon-necrotic-duelist-sword",
        "Espada del Duelista Necrótido",
        "Espada del Duelista Necrótido [Especial / Tier 2]."));
  }

  @Test
  void factualContinuationKeepsPreviousWikiSubject() {
    assertTrue(ConversationManager.looksLikeWikiFollowUp(
        ConversationManager.normalizeForSearch("sisi decime porfa")));
    assertTrue(ConversationManager.looksLikeWikiFollowUp(
        ConversationManager.normalizeForSearch("dale decime mas")));
  }

  @Test
  void noMatchReplySubjectDetectorCatchesInventedEntityClaims() {
    assertTrue(ConversationManager.replyMentionsConcreteNoMatchSubject(
        "La espada de Nagamuta está en las Ruinas de Zorok.",
        "iso donde esta la espada de nagamuta?"));
    assertTrue(ConversationManager.replyMentionsConcreteNoMatchSubject(
        "El arco de la jungla se fabrica con madera y cuerdas.",
        "iso que sabes del arco de la jungla?"));
    assertFalse(ConversationManager.replyMentionsConcreteNoMatchSubject(
        "no tengo información fiable sobre eso",
        "iso donde esta la espada de nagamuta?"));
  }

  @Test
  void completeNewKnowledgeQuestionDoesNotInheritPreviousWikiTopic() {
    String essence = ConversationManager.normalizeForSearch(
        "iso como consigo esencia del bosque?");
    String mango = ConversationManager.normalizeForSearch(
        "iso como se craftea el mango resinoso?");

    assertTrue(ConversationManager.wikiQueryHasIndependentSubject(essence));
    assertTrue(ConversationManager.wikiQueryHasIndependentSubject(mango));
    assertFalse(ConversationManager.looksLikeWikiFollowUp(essence));
    assertFalse(ConversationManager.looksLikeWikiFollowUp(mango));
    assertTrue(ConversationManager.buildWikiSubjectAnchor(essence).contains("esencia"));
    assertTrue(ConversationManager.buildWikiSubjectAnchor(essence).contains("bosque"));
    assertFalse(ConversationManager.buildWikiSubjectAnchor(essence).contains("mango"));
    assertTrue(ConversationManager.buildWikiSubjectAnchor(mango).contains("mango"));
    assertTrue(ConversationManager.buildWikiSubjectAnchor(mango).contains("resinoso"));
  }

  @Test
  void onlySubjectlessKnowledgeContinuationInheritsWikiTopic() {
    String where = ConversationManager.normalizeForSearch("y donde sale?");
    String obtain = ConversationManager.normalizeForSearch("y como se consigue?");
    String drops = ConversationManager.normalizeForSearch("y que mobs la dropean?");

    assertFalse(ConversationManager.wikiQueryHasIndependentSubject(where));
    assertFalse(ConversationManager.wikiQueryHasIndependentSubject(obtain));
    assertFalse(ConversationManager.wikiQueryHasIndependentSubject(drops));
    assertTrue(ConversationManager.looksLikeWikiFollowUp(where));
    assertTrue(ConversationManager.looksLikeWikiFollowUp(obtain));
    assertTrue(ConversationManager.looksLikeWikiFollowUp(drops));
  }

  @Test
  void explicitSubjectAfterAndStillStaysDirect() {
    String essence = ConversationManager.normalizeForSearch("y la esencia del bosque?");
    String sword = ConversationManager.normalizeForSearch("y la espada hoja donde sale?");
    String more = ConversationManager.normalizeForSearch("decime mas sobre viridita");

    assertTrue(ConversationManager.wikiQueryHasIndependentSubject(essence));
    assertTrue(ConversationManager.wikiQueryHasIndependentSubject(sword));
    assertTrue(ConversationManager.wikiQueryHasIndependentSubject(more));
    assertFalse(ConversationManager.looksLikeWikiFollowUp(essence));
    assertFalse(ConversationManager.looksLikeWikiFollowUp(sword));
    assertFalse(ConversationManager.looksLikeWikiFollowUp(more));
  }

  @Test
  void craftedNamedMaterialAlsoUsesStrictExistenceGrounding() {
    assertTrue(ConversationManager.looksLikeConcreteNamedEntityQuery(
        "iso como se craftea el mango resinoso"));
    assertTrue(ConversationManager.looksLikeConcreteNamedEntityQuery(
        "iso como se craftea el mango ultracita"));
    assertFalse(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso como se craftea el mango ultracita",
        "obtain-wood-crafted-materials",
        "Mango Resinoso y Mango Arcano",
        "Mango Resinoso: 9 Ramas Resinosas. Mango Arcano: 9 Ramas Arcanas."));
  }

  @Test
  void propertyOnlyWikiQuestionsRemainFollowUps() {
    for (String raw : List.of(
        "y que porcentaje tiene?",
        "y que probabilidad tiene?",
        "y cuanta vida tiene?",
        "y que rareza es?",
        "y que habilidades tiene?")) {
      String normalized = ConversationManager.normalizeForSearch(raw);
      assertFalse(ConversationManager.wikiQueryHasIndependentSubject(normalized), raw);
      assertTrue(ConversationManager.looksLikeWikiFollowUp(normalized), raw);
    }
  }

  @Test
  void propertyOnlyWikiQuestionsRemainFollowUps() {
    for (String raw : List.of(
        "y que porcentaje tiene?",
        "y que probabilidad tiene?",
        "y cuanta vida tiene?",
        "y que rareza es?",
        "y que habilidades tiene?")) {
      String normalized = ConversationManager.normalizeForSearch(raw);
      assertFalse(ConversationManager.wikiQueryHasIndependentSubject(normalized), raw);
      assertTrue(ConversationManager.looksLikeWikiFollowUp(normalized), raw);
    }
  }

}
