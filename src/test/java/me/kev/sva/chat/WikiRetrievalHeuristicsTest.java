package me.kev.sva.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

final class WikiRetrievalHeuristicsTest {

  @Test
  void naturalObtainAndDropWordingTriggersWiki() {
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch("iso como se consigue mango resinoso?")));
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch("iso que da el acolito necrotico")));
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch("iso en que coordenadas puedo encontrar un mini jefe")));
  }

  @Test
  void smallTalkStillDoesNotBecomeKnowledgeIntent() {
    assertFalse(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch("iso como estas?")));
  }

  @Test
  void deicticOpinionQuestionsStaySocialInsteadOfWikiNoMatch() {
    assertTrue(WikiQueryClassifier.looksLikeDeicticOpinionSmallTalk(
        ChatText.normalizeForSearch("iso q opinas de eso?")));
    assertTrue(WikiQueryClassifier.looksLikeDeicticOpinionSmallTalk(
        ChatText.normalizeForSearch("que piensas de eso iso")));
    assertFalse(WikiQueryClassifier.looksLikeImplicitServerEntityQuestion(
        "iso q opinas de eso?", ChatText.normalizeForSearch("iso q opinas de eso?")));
  }

  @Test
  void shortUnknownServerEntityQuestionStillConsultsLocalWiki() {
    String raw = "epicardo y la espada ultracita? iso";
    assertTrue(WikiQueryClassifier.looksLikeImplicitServerEntityQuestion(
        raw, ChatText.normalizeForSearch(raw)));
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch("iso quien es epicardo?")));
  }

  @Test
  void commonMobTyposReceiveFuzzyCredit() {
    assertTrue(WikiEvidence.bestFuzzyTokenScore(
        "acohilitico", Set.of("acolito", "necrotico")) > 0);
    assertTrue(WikiEvidence.bestFuzzyTokenScore(
        "necrotido", Set.of("acolito", "necrotico")) > 0);
  }

  @Test
  void fuzzyEntityTermsPreferDedicatedWikiKey() {
    String key = ChatText.normalizeForSearch("mob-hueste-acolito-necrotico".replace('-', ' '));
    assertTrue(WikiEvidence.allTermsMatchKey(
        Set.of("acohilitico", "necrotido"), key, Set.of("mob", "hueste", "acolito", "necrotico")));
    assertFalse(WikiEvidence.allTermsMatchKey(
        Set.of("acohilitico", "necrotido"),
        ChatText.normalizeForSearch("mobs-hueste-overview".replace('-', ' ')),
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
    String craft = ReplyPostProcessor.extractWikiFallbackAnswer(
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
    String drops = ReplyPostProcessor.extractWikiFallbackAnswer(
        "iso que da el acolito necrotico", dropsBlock);
    assertTrue(drops.contains("1-4 Huesos Profanos"));
    assertTrue(drops.contains("1-3 Esencias de la Muerte"));
  }
  @Test
  void multiSpeakerCoverageRequiresExplicitNameForIndependentFacts() {
    assertTrue(ReplyPostProcessor.responseCoversSpeaker(
        List.of("Aminowana, se fabrica con 9 Ramas Resinosas"), "Aminowana"));
    assertFalse(ReplyPostProcessor.responseCoversSpeaker(
        List.of("calmense los dos, parecen viejas de pueblo xd"), "Aminowana"));
  }

  @Test
  void explicitGroupBridgeCanInheritSmartContinuityWithoutBroadChatterGuessing() {
    assertTrue(ThreadAffinity.looksLikeExplicitGroupBridge("contale q estas bien"));
    assertTrue(ThreadAffinity.looksLikeExplicitGroupBridge("decile que no exagere"));
    assertFalse(ThreadAffinity.looksLikeExplicitGroupBridge("viste infinit"));
    assertFalse(ThreadAffinity.looksLikeExplicitGroupBridge("ando minando hierro"));
  }

  @Test
  void groupThreadHeuristicsSeparateSideChatFromIsoldasExchange() {
    assertTrue(ThreadAffinity.looksLikeGroupContinuation("si, es un ladron y un mezquino"));
    assertFalse(ThreadAffinity.looksLikeGroupContinuation("yo tengo"));
    assertTrue(ThreadAffinity.looksLikeSocialThreadInterjection(
        "Amino dejate de hacerte el que esta bien"));
    assertTrue(ThreadAffinity.referencesAnyParticipantAlias(
        "Amino dejate de hacerte el que esta bien", Set.of("Aminowana")));
    assertTrue(ThreadAffinity.sharesConversationTopic(
        "Amino dejate de hacerte el que esta bien",
        "bien gracias, que haces?",
        Set.of("Aminowana")));
    assertFalse(ThreadAffinity.sharesConversationTopic(
        "Amino, alguien tiene piedra?",
        "bien gracias, que haces?",
        Set.of("Aminowana")));
  }

  @Test
  void affinityRouterPrefersActiveIsoldaThreadForLinkedSocialReply() {
    int isolda = ThreadAffinity.localThreadAffinity(
        "Amino dejate de hacerte el que esta bien",
        "bien gracias, que haces?",
        "bien gracias, que haces?",
        Set.of("Aminowana"),
        900L,
        false);
    int side = ThreadAffinity.localLateralAffinity(
        "Amino dejate de hacerte el que esta bien",
        "dile la verdad",
        "Pedrox",
        900L);
    assertTrue(isolda >= 68);
    assertTrue(isolda - side >= 12);
  }

  @Test
  void affinityRouterKeepsEllipticalAnswerInCompetingStoneSideThread() {
    int isolda = ThreadAffinity.localThreadAffinity(
        "yo tengo",
        "bien gracias, que haces?",
        "bien gracias, que haces?",
        Set.of("Aminowana"),
        700L,
        false);
    int side = ThreadAffinity.localLateralAffinity(
        "yo tengo",
        "oye alguien tiene piedra?",
        "Pedrox",
        700L);
    assertTrue(side > isolda);
    assertTrue(isolda - side < 12);
  }

  @Test
  void affinityRouterCanUnderstandUnlistedContextualContradiction() {
    int isolda = ThreadAffinity.localThreadAffinity(
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
    assertTrue(ReplyPostProcessor.wikiBlockIsNoMatch(noMatch));
    assertTrue(ReplyPostProcessor.extractWikiFallbackAnswer(
        "epicardo y la espada ultracita? iso", noMatch).isBlank());
  }

  @Test
  void concreteNamedEntityGateRejectsGenericRelatedPages() {
    assertTrue(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery(
        "iso donde consigo la espada de artera"));
    assertFalse(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso donde consigo la espada de artera",
        "spawn-saurios",
        "Dónde aparecen los Saurios, junglas y pantanos",
        "Los Saurios aparecen en Jungla y Pantano."));

    assertFalse(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso que sabes del arco de la jungla",
        "spawn-saurios",
        "Dónde aparecen los Saurios, junglas y pantanos",
        "Biomas: Jungla, Pantano."));

    assertFalse(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso donde esta la espada de nagamuta",
        "weapons-swords-common",
        "Espadas comunes, Espada Hoja, Espada Orco y Filo Escarchado",
        "Espada Hoja y Espada Orco son armas comunes."));
  }

  @Test
  void concreteNamedEntityGateKeepsRealNamedItemsAndCompactIds() {
    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso donde consigo espada hoja",
        "weapons-swords-common",
        "Espadas comunes y Espada Hoja",
        "Espada Hoja: espada fabricada con materiales del bosque."));

    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso como hago el arco bosque",
        "crafting-ranged-t1",
        "Crafteos de arcos y ballestas",
        "ARCOBOSQUE: 2 Nudos Vivos + 2 Mangos Resinosos."));

    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso donde consigo la espada del duelista necrotido",
        "weapon-necrotic-duelist-sword",
        "Espada del Duelista Necrótido",
        "Espada del Duelista Necrótido [Especial / Tier 2]."));
  }

  @Test
  void factualContinuationKeepsPreviousWikiSubject() {
    assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(
        ChatText.normalizeForSearch("sisi decime porfa")));
    assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(
        ChatText.normalizeForSearch("dale decime mas")));
  }

  @Test
  void noMatchReplySubjectDetectorCatchesInventedEntityClaims() {
    assertTrue(ReplyPostProcessor.replyMentionsConcreteNoMatchSubject(
        "La espada de Nagamuta está en las Ruinas de Zorok.",
        "iso donde esta la espada de nagamuta?"));
    assertTrue(ReplyPostProcessor.replyMentionsConcreteNoMatchSubject(
        "El arco de la jungla se fabrica con madera y cuerdas.",
        "iso que sabes del arco de la jungla?"));
    assertFalse(ReplyPostProcessor.replyMentionsConcreteNoMatchSubject(
        "no tengo información fiable sobre eso",
        "iso donde esta la espada de nagamuta?"));
  }

  @Test
  void completeNewKnowledgeQuestionDoesNotInheritPreviousWikiTopic() {
    String essence = ChatText.normalizeForSearch(
        "iso como consigo esencia del bosque?");
    String mango = ChatText.normalizeForSearch(
        "iso como se craftea el mango resinoso?");

    assertTrue(WikiQueryClassifier.wikiQueryHasIndependentSubject(essence));
    assertTrue(WikiQueryClassifier.wikiQueryHasIndependentSubject(mango));
    assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(essence));
    assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(mango));
    assertTrue(WikiQueryClassifier.buildWikiSubjectAnchor(essence).contains("esencia"));
    assertTrue(WikiQueryClassifier.buildWikiSubjectAnchor(essence).contains("bosque"));
    assertFalse(WikiQueryClassifier.buildWikiSubjectAnchor(essence).contains("mango"));
    assertTrue(WikiQueryClassifier.buildWikiSubjectAnchor(mango).contains("mango"));
    assertTrue(WikiQueryClassifier.buildWikiSubjectAnchor(mango).contains("resinoso"));
  }

  @Test
  void onlySubjectlessKnowledgeContinuationInheritsWikiTopic() {
    String where = ChatText.normalizeForSearch("y donde sale?");
    String obtain = ChatText.normalizeForSearch("y como se consigue?");
    String drops = ChatText.normalizeForSearch("y que mobs la dropean?");

    assertFalse(WikiQueryClassifier.wikiQueryHasIndependentSubject(where));
    assertFalse(WikiQueryClassifier.wikiQueryHasIndependentSubject(obtain));
    assertFalse(WikiQueryClassifier.wikiQueryHasIndependentSubject(drops));
    assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(where));
    assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(obtain));
    assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(drops));
  }

  @Test
  void explicitSubjectAfterAndStillStaysDirect() {
    String essence = ChatText.normalizeForSearch("y la esencia del bosque?");
    String sword = ChatText.normalizeForSearch("y la espada hoja donde sale?");
    String more = ChatText.normalizeForSearch("decime mas sobre viridita");

    assertTrue(WikiQueryClassifier.wikiQueryHasIndependentSubject(essence));
    assertTrue(WikiQueryClassifier.wikiQueryHasIndependentSubject(sword));
    assertTrue(WikiQueryClassifier.wikiQueryHasIndependentSubject(more));
    assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(essence));
    assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(sword));
    assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(more));
  }

  @Test
  void craftedNamedMaterialAlsoUsesStrictExistenceGrounding() {
    assertTrue(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery(
        "iso como se craftea el mango resinoso"));
    assertTrue(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery(
        "iso como se craftea el mango ultracita"));
    assertFalse(WikiEvidence.candidateStronglyMatchesConcreteEntity(
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
      String normalized = ChatText.normalizeForSearch(raw);
      assertFalse(WikiQueryClassifier.wikiQueryHasIndependentSubject(normalized), raw);
      assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(normalized), raw);
    }
  }

  @Test
  void propertyOnlyWikiQuestionsWorkWithOrWithoutLeadingAnd() {
    for (String raw : List.of(
        "cuanta vida tiene?",
        "cuanta vida maxima tiene exactamente?",
        "que habilidades tiene?",
        "cuanto dura?",
        "donde sale?",
        "donde aparece?",
        "donde lo hallo?",
        "como lo saco?",
        "que dropea?",
        "quienes lo dropean?")) {
      String normalized = ChatText.normalizeForSearch(raw);
      assertFalse(WikiQueryClassifier.wikiQueryHasIndependentSubject(normalized), raw);
      assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(normalized), raw);
    }
  }

  @Test
  void explicitSubjectTurnsTheSamePropertyQuestionIntoDirectWikiLookup() {
    for (String raw : List.of(
        "iso cuanta vida tiene Galumrog?",
        "iso que habilidades tiene el Hierofante del Eclipse?",
        "iso donde sale Viridita?",
        "iso donde vive Galumrog?",
        "iso que dropea Zorok?",
        "iso que materiales necesita Mango Resinoso?",
        "iso que arma usa Galumrog?")) {
      String normalized = ChatText.normalizeForSearch(raw);
      assertTrue(WikiQueryClassifier.wikiQueryHasIndependentSubject(normalized), raw);
      assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(normalized), raw);
      assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(normalized), raw);
      assertTrue(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery(raw), raw);
    }
  }

  @Test
  void unknownSingleTokenEntityCannotBeProvedByGenericDropPages() {
    assertFalse(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso que dropea Zorok?",
        "mobs-goblins-common",
        "Goblins comunes, estadísticas y drops",
        "Goblin Arquero. Drops: 1 Esencia del Bosque: 20%."));
    assertFalse(WikiEvidence.candidateMatchesWikiTopic(
        "iso que dropea Zorok?",
        "mobs-goblins-common",
        "Goblins comunes, estadísticas y drops",
        "Goblin Arquero. Drops: 1 Esencia del Bosque: 20%."));

    assertTrue(WikiEvidence.candidateMatchesWikiTopic(
        "iso que dropea Galumrog?",
        "lore-galumrog",
        "Galumrog, líder de la Hueste Insepulta",
        "Galumrog es el gran líder de la Hueste Insepulta."));
  }

  @Test
  void directEssenceQueryMatchesEssenceKnowledgeNotPreviousMangoTopic() {
    assertTrue(WikiEvidence.candidateMatchesWikiTopic(
        "iso como consigo esencia del bosque?",
        "obtain-faction-materials-t1",
        "Cómo conseguir Esencia del Bosque, Corteza Viva y otros materiales",
        "Esencia del Bosque: Se obtiene principalmente derrotando criaturas del Bosque Viviente."));
    assertFalse(WikiEvidence.candidateMatchesWikiTopic(
        "iso como consigo esencia del bosque?",
        "obtain-wood-crafted-materials",
        "Cómo fabricar Mango Resinoso y Mango Arcano",
        "Mango Resinoso: 9 Ramas Resinosas -> 1 Mango Resinoso."));
  }

  @Test
  void requestedPropertyNounDoesNotReplaceTheActualNamedSubject() {
    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso que habilidades tiene el Hierofante del Eclipse?",
        "mob-acolitos-hierofante-eclipse",
        "Hierofante del Eclipse, jefe lunar, estadísticas y fases",
        "Hierofante del Eclipse: Lluvia del Eclipse y Juicio del Cénit."));

    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso que materiales necesita Mango Resinoso?",
        "obtain-wood-crafted-materials",
        "Mango Resinoso y Mango Arcano",
        "Mango Resinoso: 9 Ramas Resinosas -> 1 Mango Resinoso."));

    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "iso que arma usa Galumrog?",
        "lore-galumrog",
        "Galumrog, líder de la Hueste Insepulta",
        "Galumrog es el gran líder de la Hueste Insepulta."));
  }

  @Test
  void subjectlessNaturalFactPhrasesStayOnThePreviousWikiSubject() {
    for (String raw : List.of(
        "que probabilidad tiene?",
        "que porcentaje tiene?",
        "que mobs la dropean?",
        "que bichos la dropean?",
        "dame los crafteos",
        "para que sirve?",
        "como funciona?",
        "que hace?",
        "como lo saco?",
        "donde lo encuentro?")) {
      String normalized = ChatText.normalizeForSearch(raw);
      assertFalse(WikiQueryClassifier.wikiQueryHasIndependentSubject(normalized), raw);
      assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(normalized), raw);
    }
  }

  @Test
  void multiWordEntityNameCannotBeProvedByTwoDifferentNearbyPhrases() {
    String query = "iso cuanta vida tiene el Hierofante del Eclipse?";
    assertFalse(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        query,
        "mobs-acolitos-hierofante-summons",
        "Fragmento del Hierofante, Corona del Eclipse e invocaciones",
        "Fragmento de la Totalidad: Vida base: 20. Habilidad del Hierofante."));
    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        query,
        "mob-acolitos-hierofante-eclipse",
        "Hierofante del Eclipse, jefe lunar",
        "Hierofante del Eclipse: Vida base: 500."));
  }

  @Test
  void namedEntityExistenceDoesNotInventAMissingRequestedProperty() {
    String galumrogLore = """
        Galumrog es el gran líder de la Hueste Insepulta.
        Habita una de las regiones más oscuras y profundas de la Disformidad.
        """;

    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso que dropea Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso cuanta vida tiene Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso que arma usa Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso donde vive Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
  }

  @Test
  void craftingEvidenceMustDescribeTheRequestedOutputNotJustUseItAsIngredient() {
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso como se craftea Mango Resinoso?",
        "obtain-wood-crafted-materials",
        "Cómo conseguir Mango Resinoso",
        "Mango Resinoso:\nSe fabrica con:\n9 Ramas Resinosas → 1 Mango Resinoso."));

    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso como se craftea Mango Resinoso?",
        "crafting-melee-t1",
        "Crafteos de espadas",
        "Espada Hoja:\n2 Nudos Vivos + 1 Mango Resinoso → 1 Espada Hoja."));

    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso que materiales necesita Mango Resinoso?",
        "materials-mining-t2",
        "Materiales Tier 2, Mango Resinoso",
        "Mango Resinoso [Especial / Tier 2]: Mango endurecido con savia."));
  }

  @Test
  void requestedStatsAreScopedToTheNamedEntryInsideMultiEntityPages() {
    String multi = """
        Goblin Arquero:
        Vida base: 22.
        Drops:
        - 1 Esencia del Bosque: 20%.

        Goblin Fuerte:
        Vida base: 30.
        """;
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso cuanta vida tiene Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso que dropea Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso que habilidades tiene Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
  }

  @Test
  void followUpPropertyUsesTheRememberedEntityEntryNotNeighborStats() {
    String multi = """
        Goblin Arquero:
        Vida base: 22.
        Drops:
        - 1 Esencia del Bosque: 20%.

        Goblin Asaltante:
        Vida base: 22.
        Habilidades:
        - Esquiva Sombría.
        """;

    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "y que habilidades tiene?",
        "iso que sabes del Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "y cuanta vida tiene?",
        "iso que sabes del Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
  }

  @Test
  void reverseDropFollowUpCanUseDropListsWhereTheItemIsNotAnEntryHeading() {
    String goblins = """
        Goblin Arquero:
        Vida base: 22.
        Drops:
        - 1 Carne de Goblin: 60%.
        - 1 Esencia del Bosque: 20%.
        """;

    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "y que mobs la dropean?",
        "iso como consigo esencia del bosque?",
        "mobs-goblins-common", "Goblins comunes", goblins));

    String unrelatedDrop = """
        Esencia del Bosque aparece mencionada en la introducción.

        Goblin Arquero:
        Drops:
        - 1 Carne de Goblin: 60%.
        """;
    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "y que mobs la dropean?",
        "iso como consigo esencia del bosque?",
        "mobs-goblins-common", "Goblins comunes", unrelatedDrop));
  }

  @Test
  void localFallbackNeverGuessesAResolvedSubjectForSubjectlessWikiFollowUps() {
    String block = """
        [WIKI REQUEST speaker=Aminowana query='y cuanta vida tiene?']
        [mobs-goblins-common] Goblin Arquero:
        Vida base: 22.
        """;
    assertTrue(ReplyPostProcessor.extractWikiFallbackAnswer(
        "y cuanta vida tiene?", block).isBlank());
  }



  @Test
  void politeConversationalWrappersDoNotBecomeEntityIdentity() {
    String raw = "me dices como consigo viridita?";
    String normalized = ChatText.normalizeForSearch(raw);
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(normalized));
    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        raw,
        "ore-viridita",
        "Viridita, veta de Viridita, dónde encontrar Viridita, altura y poder de pico",
        "Viridita: Se encuentra entre Y -15 y Y 0. Requiere Poder de Pico 4."));
    assertTrue(WikiQueryClassifier.canonicalWikiRoutingQuery(raw).startsWith("como consigo viridita"));

    for (String variant : List.of(
        "iso me puedes decir como consigo viridita?",
        "podrias decirme donde sale viridita?",
        "me explicas como consigo viridita?",
        "sabes donde encuentro viridita?",
        "quisiera saber como consigo viridita?",
        "quiero saber sobre viridita",
        "podrias contarme sobre viridita?")) {
      assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
          ChatText.normalizeForSearch(variant)), variant);
    }
  }

  @Test
  void shorthandAndNaturalUseQuestionsStillGroundInWiki() {
    for (String raw : List.of(
        "iso y para que me sirve la viridita?",
        "para q me sirve un denar?",
        "de que sirve la viridita?",
        "la viridita sirve de algo?")) {
      assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
          ChatText.normalizeForSearch(raw)), raw);
    }
  }

  @Test
  void broadKnowledgeCategoriesAreDirectQueriesNotFakeNamedEntities() {
    for (String raw : List.of(
        "y que otros minerales hay?",
        "que nodos hay?",
        "que pociones?",
        "iso que comandos hay?",
        "que razas hay?")) {
      String normalized = ChatText.normalizeForSearch(raw);
      assertTrue(WikiQueryClassifier.looksLikeBroadWikiCategoryQuery(normalized), raw);
      assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(normalized), raw);
      assertFalse(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery(raw), raw);
      assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(normalized), raw);
    }
  }

  @Test
  void broadCategoryBoostsPreferAuthoritativeOverviewSections() {
    assertTrue(WikiEvidence.wikiBroadCategoryBoost(
        "que otros minerales hay", "obtain-mining-materials", false)
        > WikiEvidence.wikiBroadCategoryBoost(
            "que otros minerales hay", "ore-viridita", true));
    assertTrue(WikiEvidence.wikiBroadCategoryBoost(
        "que nodos hay", "tree-nodes-overview", false) >= 24);
    assertTrue(WikiEvidence.wikiBroadCategoryBoost(
        "que pociones", "crafting-consumables-basic", false) >= 24);
    assertTrue(WikiEvidence.wikiBroadCategoryBoost(
        "que comandos hay", "commands-help", false) >= 22);
  }

  @Test
  void deicticTravelQuestionStaysOnPreviousPlaceSubject() {
    String raw = "como llego hasta alli?";
    String normalized = ChatText.normalizeForSearch(raw);
    assertFalse(WikiQueryClassifier.wikiQueryHasIndependentSubject(normalized));
    assertTrue(WikiQueryClassifier.looksLikeWikiFollowUp(normalized));
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        raw,
        "que es Gamura?",
        "lore-gamura",
        "Gamura, lobby, cómo llegar a Gamura, /lobby",
        "Gamura es un refugio. Los aventureros pueden viajar a Gamura utilizando /lobby."));
  }

  @Test
  void shortAndSubjectSwapCanInheritPreviousWikiIntentWithoutOldSubject() {
    assertTrue(WikiQueryClassifier.looksLikeEllipticalWikiSubjectSwap(
        ChatText.normalizeForSearch("y los nudos vivos")));
    assertTrue(WikiQueryClassifier.looksLikeEllipticalWikiSubjectSwap(
        ChatText.normalizeForSearch("y la umbrita")));
    assertFalse(WikiQueryClassifier.looksLikeEllipticalWikiSubjectSwap(
        ChatText.normalizeForSearch("y que otros minerales hay?")));
    assertFalse(WikiQueryClassifier.looksLikeEllipticalWikiSubjectSwap(
        ChatText.normalizeForSearch("y para que sirve la viridita?")));
    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "como consigo nudos vivos",
        "obtain-tree-nodes",
        "Cómo conseguir Nudo Vivo, Tronco Corrupto, Nudo Rúnico y otros nodos",
        "Nudo Vivo: Requiere Poder de Hacha 4. Cada nodo entrega 2 Nudos Vivos."));
  }

  @Test
  void genericMultiEntityMetadataCannotAuthorizeMissingEntityStats() {
    assertFalse(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso cuanta vida tiene Goblin Arquero?",
        "iso cuanta vida tiene Goblin Arquero?",
        "mobs-goblins-common",
        "Goblin Arquero, Goblin Fuerte, estadísticas y drops",
        "Los goblins son hostiles. Goblin Fuerte posee Vida base: 30."));
  }

  @Test
  void raceFactsAndStatsCanUseDedicatedRacePageWithEnglishKey() {
    String description = "Elfo, raza élfica, elfos, estadísticas del Elfo, vida, maná, magia y proyectiles.";
    String content = """
        El Elfo es una raza ágil, sabia y especialmente afín al maná.
        Estadísticas base:
        Vida máxima: 17.
        Maná máximo: 32.
        Daño a distancia: +3%.
        Daño mágico: +3%.
        """;
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "iso que hace la raza elfo?", "iso que hace la raza elfo?",
        "race-elf", description, content));
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        "que estadisticas tienen los elfos iso?", "que estadisticas tienen los elfos iso?",
        "race-elf", description, content));
  }

  @Test
  void raceWeaponRecommendationIsBroadAdviceNotInventedNamedWeapon() {
    String query = "iso que armas son mejores para la clase elfo";
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch(query)));
    assertFalse(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery(query));

    String elf = "El Elfo tiene Maná máximo 32, Daño a distancia +3% y Daño mágico +3%.";
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        query, query, "race-elf", "Elfo, raza élfica, estadísticas del Elfo", elf));
    assertTrue(WikiEvidence.candidateSupportsWikiFactIntent(
        query, query, "magic-weapons-common", "Armas mágicas comunes, bastones y cetros",
        "Las siguientes armas mágicas son de calidad Común. Bastón del Bosque: Daño mágico +50%."));
  }

  @Test
  void wikiRetryAndCommercePhrasingAreRecognizedWithoutAnotherModelCall() {
    assertTrue(WikiQueryClassifier.looksLikeWikiMetaRetry(
        ChatText.normalizeForSearch("busca en la wiki, en razas ahi aparece")));
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch("donde comercio?")));
    assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
        ChatText.normalizeForSearch("donde puedo comprar cosas?")));
  }

  @Test
  void leadingAndBroadCategoryListingsStayDirectAndAuthoritative() {
    for (String raw : List.of(
        "y que nodos hay?",
        "y que comandos hay?",
        "y que pociones hay?",
        "y cuales minerales existen?",
        "decime los minerales",
        "nombrame los nodos")) {
      String normalized = ChatText.normalizeForSearch(raw);
      assertTrue(WikiQueryClassifier.looksLikeBroadWikiCategoryQuery(normalized), raw);
      assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(normalized), raw);
      assertFalse(WikiQueryClassifier.looksLikeWikiFollowUp(normalized), raw);
      assertFalse(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery(raw), raw);
    }
    assertTrue(WikiEvidence.wikiBroadCategoryBoost(
        "y que nodos hay", "tree-nodes-overview", false) >= 24);
    assertTrue(WikiEvidence.wikiBroadCategoryBoost(
        "y que otros minerales hay", "obtain-mining-materials", false) >= 22);
  }

  @Test
  void rioplatenseAndPoliteWrappersNeverPolluteWikiIdentity() {
    for (String raw : List.of(
        "me podes decir como consigo viridita?",
        "me podrias explicar donde sale viridita?",
        "sabrias decirme donde encuentro viridita?",
        "tenes idea de donde sale viridita?",
        "me ayudas a saber como consigo viridita?")) {
      String routed = WikiQueryClassifier.canonicalWikiRoutingQuery(raw);
      assertTrue(routed.contains("viridita"), raw + " -> " + routed);
      assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
          ChatText.normalizeForSearch(raw)), raw);
      assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
          raw,
          "ore-viridita",
          "Viridita, veta de Viridita, dónde encontrar Viridita, altura y poder de pico",
          "Viridita: Se encuentra entre Y -15 y Y 0. Requiere Poder de Pico 4."), raw);
    }
  }

  @Test
  void functionQuestionsAndCommerceAreWikiFactsNotFreeChat() {
    for (String raw : List.of(
        "iso y para q me sirve la viridita?",
        "para q me sirve un denar?",
        "que puedo hacer con viridita?",
        "en que se usa la viridita?",
        "donde comercio?",
        "donde puedo comprar cosas?")) {
      assertTrue(WikiQueryClassifier.looksLikeLikelyWikiKnowledgeRequest(
          ChatText.normalizeForSearch(raw)), raw);
    }
    assertFalse(WikiQueryClassifier.looksLikeConcreteNamedEntityQuery("donde comercio?"));
    assertTrue(WikiEvidence.candidateStronglyMatchesConcreteEntity(
        "para q me sirve un denar?",
        "economy-denar",
        "Denar, denares, moneda de MDVCRAFT, dinero, mercado",
        "Denar: El Denar es la moneda utilizada en MDVCRAFT."));
  }

  @Test
  void broadCategoryCanBecomeSafeLocalFollowUpAnchor() {
    assertEquals("pocion", WikiQueryClassifier.buildWikiSubjectAnchor("que pociones?"));
    assertEquals("nodo", WikiQueryClassifier.buildWikiSubjectAnchor("y que nodos hay?"));
    assertEquals("mineral", WikiQueryClassifier.buildWikiSubjectAnchor("que otros minerales hay?"));
  }


}
