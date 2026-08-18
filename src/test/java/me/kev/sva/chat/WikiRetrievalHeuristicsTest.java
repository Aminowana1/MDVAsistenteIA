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
      String normalized = ConversationManager.normalizeForSearch(raw);
      assertFalse(ConversationManager.wikiQueryHasIndependentSubject(normalized), raw);
      assertTrue(ConversationManager.looksLikeWikiFollowUp(normalized), raw);
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
      String normalized = ConversationManager.normalizeForSearch(raw);
      assertTrue(ConversationManager.wikiQueryHasIndependentSubject(normalized), raw);
      assertFalse(ConversationManager.looksLikeWikiFollowUp(normalized), raw);
      assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(normalized), raw);
      assertTrue(ConversationManager.looksLikeConcreteNamedEntityQuery(raw), raw);
    }
  }

  @Test
  void unknownSingleTokenEntityCannotBeProvedByGenericDropPages() {
    assertFalse(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso que dropea Zorok?",
        "mobs-goblins-common",
        "Goblins comunes, estadísticas y drops",
        "Goblin Arquero. Drops: 1 Esencia del Bosque: 20%."));
    assertFalse(ConversationManager.candidateMatchesWikiTopic(
        "iso que dropea Zorok?",
        "mobs-goblins-common",
        "Goblins comunes, estadísticas y drops",
        "Goblin Arquero. Drops: 1 Esencia del Bosque: 20%."));

    assertTrue(ConversationManager.candidateMatchesWikiTopic(
        "iso que dropea Galumrog?",
        "lore-galumrog",
        "Galumrog, líder de la Hueste Insepulta",
        "Galumrog es el gran líder de la Hueste Insepulta."));
  }

  @Test
  void directEssenceQueryMatchesEssenceKnowledgeNotPreviousMangoTopic() {
    assertTrue(ConversationManager.candidateMatchesWikiTopic(
        "iso como consigo esencia del bosque?",
        "obtain-faction-materials-t1",
        "Cómo conseguir Esencia del Bosque, Corteza Viva y otros materiales",
        "Esencia del Bosque: Se obtiene principalmente derrotando criaturas del Bosque Viviente."));
    assertFalse(ConversationManager.candidateMatchesWikiTopic(
        "iso como consigo esencia del bosque?",
        "obtain-wood-crafted-materials",
        "Cómo fabricar Mango Resinoso y Mango Arcano",
        "Mango Resinoso: 9 Ramas Resinosas -> 1 Mango Resinoso."));
  }

  @Test
  void requestedPropertyNounDoesNotReplaceTheActualNamedSubject() {
    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso que habilidades tiene el Hierofante del Eclipse?",
        "mob-acolitos-hierofante-eclipse",
        "Hierofante del Eclipse, jefe lunar, estadísticas y fases",
        "Hierofante del Eclipse: Lluvia del Eclipse y Juicio del Cénit."));

    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "iso que materiales necesita Mango Resinoso?",
        "obtain-wood-crafted-materials",
        "Mango Resinoso y Mango Arcano",
        "Mango Resinoso: 9 Ramas Resinosas -> 1 Mango Resinoso."));

    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
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
      String normalized = ConversationManager.normalizeForSearch(raw);
      assertFalse(ConversationManager.wikiQueryHasIndependentSubject(normalized), raw);
      assertTrue(ConversationManager.looksLikeWikiFollowUp(normalized), raw);
    }
  }

  @Test
  void multiWordEntityNameCannotBeProvedByTwoDifferentNearbyPhrases() {
    String query = "iso cuanta vida tiene el Hierofante del Eclipse?";
    assertFalse(ConversationManager.candidateStronglyMatchesConcreteEntity(
        query,
        "mobs-acolitos-hierofante-summons",
        "Fragmento del Hierofante, Corona del Eclipse e invocaciones",
        "Fragmento de la Totalidad: Vida base: 20. Habilidad del Hierofante."));
    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
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

    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
        "iso que dropea Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
        "iso cuanta vida tiene Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
        "iso que arma usa Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        "iso donde vive Galumrog?", "lore-galumrog", "Galumrog y la Hueste", galumrogLore));
  }

  @Test
  void craftingEvidenceMustDescribeTheRequestedOutputNotJustUseItAsIngredient() {
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        "iso como se craftea Mango Resinoso?",
        "obtain-wood-crafted-materials",
        "Cómo conseguir Mango Resinoso",
        "Mango Resinoso:\nSe fabrica con:\n9 Ramas Resinosas → 1 Mango Resinoso."));

    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
        "iso como se craftea Mango Resinoso?",
        "crafting-melee-t1",
        "Crafteos de espadas",
        "Espada Hoja:\n2 Nudos Vivos + 1 Mango Resinoso → 1 Espada Hoja."));

    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
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
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        "iso cuanta vida tiene Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        "iso que dropea Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
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

    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
        "y que habilidades tiene?",
        "iso que sabes del Goblin Arquero?",
        "mobs-goblins-common", "Goblins comunes", multi));
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
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

    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        "y que mobs la dropean?",
        "iso como consigo esencia del bosque?",
        "mobs-goblins-common", "Goblins comunes", goblins));

    String unrelatedDrop = """
        Esencia del Bosque aparece mencionada en la introducción.

        Goblin Arquero:
        Drops:
        - 1 Carne de Goblin: 60%.
        """;
    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
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
    assertTrue(ConversationManager.extractWikiFallbackAnswer(
        "y cuanta vida tiene?", block).isBlank());
  }



  @Test
  void politeConversationalWrappersDoNotBecomeEntityIdentity() {
    String raw = "me dices como consigo viridita?";
    String normalized = ConversationManager.normalizeForSearch(raw);
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(normalized));
    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        raw,
        "ore-viridita",
        "Viridita, veta de Viridita, dónde encontrar Viridita, altura y poder de pico",
        "Viridita: Se encuentra entre Y -15 y Y 0. Requiere Poder de Pico 4."));
    assertTrue(ConversationManager.canonicalWikiRoutingQuery(raw).startsWith("como consigo viridita"));

    for (String variant : List.of(
        "iso me puedes decir como consigo viridita?",
        "podrias decirme donde sale viridita?",
        "me explicas como consigo viridita?",
        "sabes donde encuentro viridita?",
        "quisiera saber como consigo viridita?",
        "quiero saber sobre viridita",
        "podrias contarme sobre viridita?")) {
      assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
          ConversationManager.normalizeForSearch(variant)), variant);
    }
  }

  @Test
  void shorthandAndNaturalUseQuestionsStillGroundInWiki() {
    for (String raw : List.of(
        "iso y para que me sirve la viridita?",
        "para q me sirve un denar?",
        "de que sirve la viridita?",
        "la viridita sirve de algo?")) {
      assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
          ConversationManager.normalizeForSearch(raw)), raw);
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
      String normalized = ConversationManager.normalizeForSearch(raw);
      assertTrue(ConversationManager.looksLikeBroadWikiCategoryQuery(normalized), raw);
      assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(normalized), raw);
      assertFalse(ConversationManager.looksLikeConcreteNamedEntityQuery(raw), raw);
      assertFalse(ConversationManager.looksLikeWikiFollowUp(normalized), raw);
    }
  }

  @Test
  void broadCategoryBoostsPreferAuthoritativeOverviewSections() {
    assertTrue(ConversationManager.wikiBroadCategoryBoost(
        "que otros minerales hay", "obtain-mining-materials", false)
        > ConversationManager.wikiBroadCategoryBoost(
            "que otros minerales hay", "ore-viridita", true));
    assertTrue(ConversationManager.wikiBroadCategoryBoost(
        "que nodos hay", "tree-nodes-overview", false) >= 24);
    assertTrue(ConversationManager.wikiBroadCategoryBoost(
        "que pociones", "crafting-consumables-basic", false) >= 24);
    assertTrue(ConversationManager.wikiBroadCategoryBoost(
        "que comandos hay", "commands-help", false) >= 22);
  }

  @Test
  void deicticTravelQuestionStaysOnPreviousPlaceSubject() {
    String raw = "como llego hasta alli?";
    String normalized = ConversationManager.normalizeForSearch(raw);
    assertFalse(ConversationManager.wikiQueryHasIndependentSubject(normalized));
    assertTrue(ConversationManager.looksLikeWikiFollowUp(normalized));
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        raw,
        "que es Gamura?",
        "lore-gamura",
        "Gamura, lobby, cómo llegar a Gamura, /lobby",
        "Gamura es un refugio. Los aventureros pueden viajar a Gamura utilizando /lobby."));
  }

  @Test
  void shortAndSubjectSwapCanInheritPreviousWikiIntentWithoutOldSubject() {
    assertTrue(ConversationManager.looksLikeEllipticalWikiSubjectSwap(
        ConversationManager.normalizeForSearch("y los nudos vivos")));
    assertTrue(ConversationManager.looksLikeEllipticalWikiSubjectSwap(
        ConversationManager.normalizeForSearch("y la umbrita")));
    assertFalse(ConversationManager.looksLikeEllipticalWikiSubjectSwap(
        ConversationManager.normalizeForSearch("y que otros minerales hay?")));
    assertFalse(ConversationManager.looksLikeEllipticalWikiSubjectSwap(
        ConversationManager.normalizeForSearch("y para que sirve la viridita?")));
    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "como consigo nudos vivos",
        "obtain-tree-nodes",
        "Cómo conseguir Nudo Vivo, Tronco Corrupto, Nudo Rúnico y otros nodos",
        "Nudo Vivo: Requiere Poder de Hacha 4. Cada nodo entrega 2 Nudos Vivos."));
  }

  @Test
  void genericMultiEntityMetadataCannotAuthorizeMissingEntityStats() {
    assertFalse(ConversationManager.candidateSupportsWikiFactIntent(
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
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        "iso que hace la raza elfo?", "iso que hace la raza elfo?",
        "race-elf", description, content));
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        "que estadisticas tienen los elfos iso?", "que estadisticas tienen los elfos iso?",
        "race-elf", description, content));
  }

  @Test
  void raceWeaponRecommendationIsBroadAdviceNotInventedNamedWeapon() {
    String query = "iso que armas son mejores para la clase elfo";
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch(query)));
    assertFalse(ConversationManager.looksLikeConcreteNamedEntityQuery(query));

    String elf = "El Elfo tiene Maná máximo 32, Daño a distancia +3% y Daño mágico +3%.";
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        query, query, "race-elf", "Elfo, raza élfica, estadísticas del Elfo", elf));
    assertTrue(ConversationManager.candidateSupportsWikiFactIntent(
        query, query, "magic-weapons-common", "Armas mágicas comunes, bastones y cetros",
        "Las siguientes armas mágicas son de calidad Común. Bastón del Bosque: Daño mágico +50%."));
  }

  @Test
  void wikiRetryAndCommercePhrasingAreRecognizedWithoutAnotherModelCall() {
    assertTrue(ConversationManager.looksLikeWikiMetaRetry(
        ConversationManager.normalizeForSearch("busca en la wiki, en razas ahi aparece")));
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch("donde comercio?")));
    assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
        ConversationManager.normalizeForSearch("donde puedo comprar cosas?")));
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
      String normalized = ConversationManager.normalizeForSearch(raw);
      assertTrue(ConversationManager.looksLikeBroadWikiCategoryQuery(normalized), raw);
      assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(normalized), raw);
      assertFalse(ConversationManager.looksLikeWikiFollowUp(normalized), raw);
      assertFalse(ConversationManager.looksLikeConcreteNamedEntityQuery(raw), raw);
    }
    assertTrue(ConversationManager.wikiBroadCategoryBoost(
        "y que nodos hay", "tree-nodes-overview", false) >= 24);
    assertTrue(ConversationManager.wikiBroadCategoryBoost(
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
      String routed = ConversationManager.canonicalWikiRoutingQuery(raw);
      assertTrue(routed.contains("viridita"), raw + " -> " + routed);
      assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
          ConversationManager.normalizeForSearch(raw)), raw);
      assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
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
      assertTrue(ConversationManager.looksLikeLikelyWikiKnowledgeRequest(
          ConversationManager.normalizeForSearch(raw)), raw);
    }
    assertFalse(ConversationManager.looksLikeConcreteNamedEntityQuery("donde comercio?"));
    assertTrue(ConversationManager.candidateStronglyMatchesConcreteEntity(
        "para q me sirve un denar?",
        "economy-denar",
        "Denar, denares, moneda de MDVCRAFT, dinero, mercado",
        "Denar: El Denar es la moneda utilizada en MDVCRAFT."));
  }

  @Test
  void broadCategoryCanBecomeSafeLocalFollowUpAnchor() {
    assertEquals("pocion", ConversationManager.buildWikiSubjectAnchor("que pociones?"));
    assertEquals("nodo", ConversationManager.buildWikiSubjectAnchor("y que nodos hay?"));
    assertEquals("mineral", ConversationManager.buildWikiSubjectAnchor("que otros minerales hay?"));
  }


}
