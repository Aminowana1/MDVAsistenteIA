package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Classifies a player's wording for local wiki routing: knowledge request or small
 * talk, which fact is asked (craft, drop, location...), and which subject it names.
 * Pure functions over the query text only.
 */
final class WikiQueryClassifier {
  private WikiQueryClassifier() {
  }

  static boolean looksLikeEllipticalWikiSubjectSwap(String normalized) {
    normalized = canonicalWikiRoutingQuery(normalized);
    if (normalized.isBlank() || !normalized.startsWith("y ")) return false;
    if (looksLikeBroadWikiCategoryQuery(normalized)) return false;
    if (!wikiQueryHasIndependentSubject(normalized)) return false;
    if (isWikiCraftIntent(normalized) || isWikiObtainIntent(normalized) || isWikiDropIntent(normalized)
        || isWikiLocationIntent(normalized) || isWikiTravelIntent(normalized)
        || isWikiFunctionIntent(normalized) || hasWikiPropertyFactCue(normalized)
        || isWikiRecommendationIntent(normalized)) return false;
    return normalized.split("\\s+").length <= 8;
  }

  static boolean looksLikeLikelyWikiKnowledgeRequest(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);

    // Direct observation belongs to local context tools, not the static wiki.
    if (containsAnyTerm(normalized,
        "inventario", "que tengo equipado", "que tiene equipado", "que tiene en la mano",
        "donde esta el jugador", "donde esta aminowana", "donde esta kroattan")) {
      return false;
    }

    if (looksLikeBroadWikiCategoryQuery(normalized)
        || isWikiRecommendationIntent(normalized)
        || isWikiTravelIntent(normalized)
        || isWikiCommerceIntent(normalized)
        || isWikiCraftIntent(normalized)
        || isWikiObtainIntent(normalized)
        || isWikiDropIntent(normalized)
        || isWikiLocationIntent(normalized)
        || isWikiFunctionIntent(normalized)
        || looksLikeWikiMetaRetry(normalized)) {
      return true;
    }

    if (containsAnyTerm(normalized,
        "que es ", "que son ", "que significa ",
        "que sabes de ", "que sabes del ", "quien es ", "quienes son ",
        "existe ", "existen ", "conoces a ", "te suena ", "hablame de ",
        "contame de ", "cuentame de ", "que me puedes decir de ",
        "para que sirve", "para que me sirve", "para que sirven", "para que se usa",
        "que utilidad tiene", "sirve de algo", "que puedo hacer con", "que se puede hacer con",
        "en que se usa", "en que se usan", "como funciona",
        "como se craftea", "como crafteo", "como craftear", "crafteo", "crafteos", "craftear",
        "como se fabrica", "como fabrico", "fabricar", "como se hace", "receta", "recetas",
        "como consigo", "como se consigue", "como obtengo", "como se obtiene",
        "de donde consigo", "de donde sale", "donde sale", "donde salen",
        "donde consigo", "donde se consigue", "donde se obtiene", "donde aparece",
        "donde aparecen", "donde vive", "donde viven", "donde habita", "donde habitan",
        "como saco", "donde saco", "de donde saco", "como obtener", "como conseguir",
        "como encontrar", "donde encuentro", "donde encontrar", "puedo encontrar", "encontrar un ",
        "en que coordenadas", "coordenadas puedo", "coordenadas de",
        "como hago para", "que comandos", "comandos hay",
        "que minerales", "que armas", "que armaduras", "que sets", "que herramientas",
        "que nodos", "que nudos", "que pociones", "que consumibles", "que razas",
        "que facciones", "que eventos", "que mobs", "que criaturas",
        "que raza tiene", "que profesion", "drops", "drop ", "dropea", "droppea",
        "que da el ", "que suelta", "que puede soltar", "que deja", "que tira",
        "cuanto cuesta", "cuanto vale")) {
      return true;
    }

    // Direct stat/property questions are wiki-worthy only when the current wording
    // names its own subject. Subjectless property wording is handled by the per-player
    // follow-up anchor instead.
    return hasWikiPropertyFactCue(normalized) && wikiQueryHasIndependentSubject(normalized);
  }

  static boolean hasWikiPropertyFactCue(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " cuanta vida ", " cuanto vida ", " cuantos corazones ", " cuantas vidas ",
        " cuanto hp ", " cuanta hp ", " que hp ",
        " cuanto dano ", " cuanta dano ", " que dano ", " q dano ",
        " cuanto pega ", " cuanto golpea ", " que dano hace ",
        " cuanto mana ", " cuanta mana ", " que mana ", " cuanto cooldown ", " que cooldown ",
        " que durabilidad ", " cuanta durabilidad ", " cuanto dura ", " que duracion ",
        " que rareza ", " q rareza ", " que calidad ", " q calidad ", " que tier ", " q tier ",
        " que habilidad ", " que habilidades ", " q habilidad ", " q habilidades ",
        " que stats ", " q stats ", " que estadisticas ", " q estadisticas ",
        " que resistencia ", " que resistencias ", " que reduccion ", " que regeneracion ",
        " que velocidad ", " cuanto alcance ", " que alcance ", " cuanto radio ",
        " que efectos ", " que efecto ", " que precio ", " cuanto precio ",
        " cuanto cuesta ", " cuanto vale ", " que porcentaje ", " cuanto porcentaje ",
        " que probabilidad ", " cuanta probabilidad ", " que chance ", " cuanto chance ",
        " que material ", " que materiales ", " que arma ", " que armas ",
        " que item ", " que items ", " que objeto ", " que objetos ", " que equipo ",
        " que equipamiento ");
  }

  static boolean isWikiCraftIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " como se craftea ", " como crafteo ", " como craftear ", " craftea ",
        " crafteo ", " crafteos ", " craftear ", " como se fabrica ", " como fabrico ", " fabricar ",
        " receta ", " recetas ", " dame los crafteos ", " dame crafteos ",
        " que necesito para craftear ", " que necesito para fabricar ", " materiales para craftear ",
        " se puede craftear ", " se puede fabricar ",
        " como se hace ", " como lo crafteo ", " como lo fabrico ");
  }

  static boolean isWikiDropIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " drops ", " drop ", " dropea ", " dropean ", " droppea ", " droppean ",
        " suelta ", " sueltan ", " deja ", " dejan ", " tira ", " tiran ",
        " que da ", " q da ", " que dan ", " q dan ", " lo da ", " la da ",
        " los dan ", " las dan ");
  }

  static boolean isWikiLocationIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " donde esta ", " donde estan ", " donde sale ", " donde salen ",
        " donde aparece ", " donde aparecen ", " donde vive ", " donde viven ",
        " donde habita ", " donde habitan ", " donde encuentro ", " donde encontrar ",
        " donde lo encuentro ", " donde la encuentro ", " donde hallo ", " donde lo hallo ",
        " donde comercio ", " donde puedo comerciar ", " donde compro ", " donde vendo ",
        " coordenada ", " coordenadas ", " ubicacion ", " ubicado ", " ubicada ", " spawn ");
  }

  static boolean isWikiFunctionIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " para que sirve ", " para que me sirve ", " para que te sirve ",
        " para que sirven ", " para que se usa ", " para que se usan ",
        " de que sirve ", " sirve de algo ", " que utilidad ", " utilidad tiene ",
        " que uso tiene ", " que usos tiene ", " como se usa ", " como uso ",
        " en que se usa ", " en que se usan ", " que puedo hacer con ",
        " que se puede hacer con ", " que hago con ", " para que lo uso ",
        " para que la uso ", " como funciona ", " que hace ", " que puede hacer ",
        " que efecto ", " que efectos ");
  }

  static boolean isWikiTravelIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " como llego ", " como llegar ", " como voy ", " como ir ", " como entro ",
        " como entro a ", " como vuelvo ", " como viajo ", " como viajar ",
        " que comando uso para ir ", " comando para ir ", " ir hasta alli ", " llegar hasta alli ");
  }

  static boolean isWikiCommerceIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " donde comercio ", " donde puedo comerciar ", " donde compro ", " donde puedo comprar ",
        " donde vendo ", " donde puedo vender ", " como comercio ", " como comerciar ",
        " como intercambio ", " como intercambiar ", " con quien comercio ");
  }

  /**
   * True for a factual continuation whose wording still lacks its own named subject.
   * The caller must separately verify that a recent per-player wiki anchor exists.
   */
  private static boolean hasSubjectlessWikiFactIntent(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    if (wikiQueryHasIndependentSubject(normalized)) return false;
    String q = " " + normalized + " ";
    return hasWikiPropertyFactCue(normalized)
        || isWikiCraftIntent(normalized)
        || isWikiObtainIntent(normalized)
        || isWikiDropIntent(normalized)
        || isWikiLocationIntent(normalized)
        || isWikiTravelIntent(normalized)
        || isWikiCommerceIntent(normalized)
        || isWikiFunctionIntent(normalized)
        || looksLikeWikiMetaRetry(normalized)
        || containsAnyTerm(q, " que efectos ", " como lo hago ", " como la hago ");
  }

  private static final Set<String> WIKI_BROAD_CATEGORY_NOUNS = Set.of(
      "comando", "comandos", "mineral", "minerales", "arma", "armas",
      "armadura", "armaduras", "set", "sets", "herramienta", "herramientas",
      "material", "materiales", "pocion", "pociones", "consumible", "consumibles",
      "nodo", "nodos", "nudo", "nudos", "raza", "razas", "profesion", "profesiones",
      "faccion", "facciones", "evento", "eventos", "mob", "mobs", "criatura", "criaturas",
      "enemigo", "enemigos", "mision", "misiones", "quest", "quests");

  static String canonicalWikiRoutingQuery(String rawOrNormalized) {
    String n = normalizeForSearch(rawOrNormalized);
    if (n.isBlank()) return n;
    n = (" " + n + " ").replace(" q ", " que ").replace(" pa que ", " para que ").trim();
    // Strip only conversational wrappers; never remove the factual subject itself.
    String previous;
    do {
      previous = n;
      n = n.replaceFirst("^(?:iso|isolda)\\s+", "");
      n = n.replaceFirst("^(?:oye|che|eh|hola)\\s+(?:iso|isolda)?\\s*", "");
      n = n.replaceFirst("^(?:me\\s+)?(?:dices|decis|dirias|cuentas|explicas)\\s+", "");
      n = n.replaceFirst("^(?:me\\s+)?(?:puedes|podes|podrias|podria)\\s+(?:decirme|decir|contarme|contar|explicarme|explicar)\\s+", "");
      n = n.replaceFirst("^(?:sabrias|sabes)\\s+(?:decirme\\s+|decir\\s+)?(?=(?:como|donde|que|quien|cual|cuanto|para|en)\\b)", "");
      n = n.replaceFirst("^(?:me\\s+)?(?:ayudas|ayudarias)\\s+(?:a\\s+)?(?:saber|entender)\\s+", "");
      n = n.replaceFirst("^(?:tenes|tienes)\\s+idea\\s+de\\s+(?=(?:como|donde|que|quien|cual|cuanto|para|en)\\b)", "");
      n = n.replaceFirst("^(?:quiero|quisiera|queria)\\s+saber\\s+(?:sobre|acerca\\s+de)\\s+", "que sabes de ");
      n = n.replaceFirst("^(?:quiero|quisiera|queria)\\s+saber\\s+", "");
    } while (!n.equals(previous));
    if (n.startsWith("sobre ")) n = "que sabes de " + n.substring(6).trim();
    if (n.startsWith("acerca de ")) n = "que sabes de " + n.substring(9).trim();
    return n.trim();
  }

  static boolean looksLikeBroadWikiCategoryQuery(String normalized) {
    normalized = canonicalWikiRoutingQuery(normalized);
    if (normalized.isBlank()) return false;
    // A leading conversational "y" must not turn a category listing into a fake
    // follow-up to the previous entity. Live examples: "y que nodos hay?" and
    // "y que otros minerales hay?".
    String core = normalized.startsWith("y ") ? normalized.substring(2).trim() : normalized;
    String q = " " + core + " ";
    boolean listingShape = containsAnyTerm(q,
        " que otros ", " que otras ", " cuales son los ", " cuales son las ",
        " que tipos de ", " tipos de ", " lista de ", " listado de ", " que hay de ",
        " dime los ", " dime las ", " decime los ", " decime las ",
        " nombrame los ", " nombrame las ", " menciona los ", " menciona las ")
        || core.matches("^(?:que|cuales)\\s+(?:otros?\\s+|otras?\\s+)?[\\p{L}0-9_-]+\\s+(?:hay|existen|conoces).*")
        || core.matches("^(?:que|cuales)\\s+[\\p{L}0-9_-]+(?:\\s+[\\p{L}0-9_-]+)?$")
        || core.matches("^(?:dime|decime|nombrame|menciona|lista|listame)\\s+(?:los|las)?\\s*[\\p{L}0-9_-]+.*");
    if (!listingShape) return false;
    for (String token : core.split("\\s+")) {
      if (WIKI_BROAD_CATEGORY_NOUNS.contains(token)) return true;
      String singular = singularWikiEntityNoun(token);
      if (singular != null && WIKI_BROAD_CATEGORY_NOUNS.contains(singular)) return true;
    }
    return false;
  }

  static String wikiBroadCategory(String normalized) {
    normalized = canonicalWikiRoutingQuery(normalized);
    for (String token : normalized.split("\\s+")) {
      String singular = singularWikiEntityNoun(token);
      String candidate = singular != null ? singular : token;
      if (WIKI_BROAD_CATEGORY_NOUNS.contains(token) || WIKI_BROAD_CATEGORY_NOUNS.contains(candidate)) {
        return switch (candidate) {
          case "nudo" -> "nodo";
          case "pocion" -> "pocion";
          default -> candidate;
        };
      }
    }
    return "";
  }

  static boolean isWikiRecommendationIntent(String normalized) {
    normalized = canonicalWikiRoutingQuery(normalized);
    if (normalized.isBlank()) return false;
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " que arma es mejor ", " que armas son mejores ", " que armas me recomiendas ",
        " que arma me recomiendas ", " que equipamiento recomiendas ",
        " que set recomiendas ", " cual conviene ", " cuales convienen ",
        " mejor para la raza ", " mejores para la raza ", " mejor para elfo ",
        " mejores para elfo ", " mejor para orco ", " mejores para orco ",
        " mejor para humano ", " mejores para humano ");
  }

  static boolean looksLikeWikiMetaRetry(String normalized) {
    normalized = canonicalWikiRoutingQuery(normalized);
    if (normalized.isBlank()) return false;
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " busca en la wiki ", " mira en la wiki ", " revisa la wiki ", " fijate en la wiki ",
        " esta en la wiki ", " aparece en la wiki ", " ahi aparece ", " ahi esta ");
  }

  /**
   * Fallback for short natural questions that name a server entity but omit classic
   * interrogative wording. Example: "epicardo y la espada ultracita? iso". The
   * local wiki search is cheap and, importantly, can return result=no_match so the
   * model is explicitly grounded instead of inventing an NPC/quest/item.
   */
  static boolean looksLikeImplicitServerEntityQuestion(String raw, String normalized) {
    if (raw == null || normalized == null || normalized.isBlank()) return false;
    if (!raw.contains("?")) return false;
    if (isObviousSocialSmallTalk(normalized) || looksLikeDeicticOpinionSmallTalk(normalized)) {
      return false;
    }

    Set<String> terms = new LinkedHashSet<>(meaningfulTerms(normalized));
    terms.removeAll(Set.of("opinas", "opinion", "piensas", "parece", "crees"));
    if (terms.isEmpty()) return false;

    String q = " " + normalized + " ";
    boolean serverEntityNoun = containsAnyTerm(q,
        " espada ", " arma ", " armas ", " pico ", " hacha ", " azada ", " arco ",
        " ballesta ", " baculo ", " cetro ", " tomo ", " daga ", " maza ", " lanza ",
        " armadura ", " casco ", " coraza ", " botas ", " pantalon ", " set ",
        " item ", " items ", " objeto ", " objetos ", " material ", " materiales ",
        " mineral ", " minerales ", " mena ", " menas ", " mob ", " mobs ",
        " enemigo ", " enemigos ", " criatura ", " criaturas ", " bicho ", " bichos ",
        " monstruo ", " monstruos ", " bestia ", " bestias ", " animal ", " animales ",
        " jefe ", " jefes ",
        " boss ", " miniboss ", " npc ", " npcs ", " comerciante ", " comerciantes ",
        " mercader ", " mercaderes ", " mision ", " misiones ", " quest ", " quests ",
        " pocion ", " pociones ", " consumible ", " consumibles ",
        " nodo ", " nodos ", " nudo ", " nudos ", " herramienta ", " herramientas ",
        " comando ", " comandos ", " receta ", " recetas ", " drop ", " drops ", " evento ", " eventos ",
        " faccion ", " facciones ", " raza ", " razas ", " profesion ", " profesiones ",
        " habilidad ", " habilidades ", " hechizo ", " hechizos ");

    // A concrete server noun plus at least one descriptive/name term is enough.
    // This deliberately does NOT make every question mark a wiki lookup.
    return serverEntityNoun && terms.size() >= 2;
  }

  static final Set<String> WIKI_ENTITY_NOUNS = Set.of(
      "arma", "espada", "sable", "filo", "daga", "maza", "lanza", "guja", "tridente",
      "arco", "ballesta", "baculo", "cetro", "tomo", "baston", "escudo",
      "pico", "hacha", "azada", "armadura", "casco", "coraza", "botas",
      "pantalon", "set", "amuleto", "anillo", "collar", "item", "objeto",
      "material", "mineral", "mena", "mob", "enemigo", "criatura", "bicho", "monstruo",
      "bestia", "animal", "jefe", "miniboss", "boss", "npc", "comerciante", "mercader",
      "mision", "quest", "pocion", "consumible", "nodo", "nudo", "comando", "herramienta",
      "evento", "faccion", "raza", "profesion", "habilidad", "hechizo");

  static final Set<String> WIKI_ENTITY_QUERY_NOISE = Set.of(
      "sabes", "saber", "info", "informacion", "datos", "dato", "sobre", "acerca",
      "dices", "decis", "dirias", "decir", "decirme", "diga", "digas", "puedes", "podes", "podrias", "puede", "podria",
      "sabrias", "tenes", "idea", "ayudas", "ayudarias", "ayudar",
      "explicas", "explicar", "explicarme", "cuentas", "contar", "contarme", "mencionas",
      "quisiera", "queria", "pregunto", "preguntarte", "consulta", "consultar",
      "quieres", "quiero", "dame", "mostrame", "muestrame",
      "decime", "dime", "contame", "cuentame", "explicame",
      "porfa", "favor", "sisi", "dale", "bueno", "mas", "algo", "exactamente",
      "otro", "otros", "otra", "otras", "demas", "tipo", "tipos", "clase", "clases",
      "mejor", "mejores", "recomiendas", "recomendar", "recomendarias", "conviene", "convienen",
      "alli", "ahi", "aca", "aqui", "hasta", "llego", "llegar", "voy", "ir", "entro",
      "vuelvo", "viajo", "viajar", "wiki", "busca", "buscar", "mira", "revisa", "revisar",
      "comercio", "comerciar", "comercia", "intercambio", "intercambiar", "compro", "comprar",
      "vendo", "vender", "tienda", "tiendas",
      "hago", "hacer", "hecho", "fabrica", "fabrico", "fabricar", "fabricacion",
      "crafteo", "crafteos", "receta", "recetas",
      "drops", "drop", "dropea", "dropean", "droppea", "droppean", "suelta", "sueltan",
      "deja", "dejan", "tira", "tiran", "sale", "salen", "saco", "sacan", "sacar",
      "hallo", "hallan", "hallar", "dura", "duran", "cuanto", "cuanta", "cuantos", "cuantas", "cuesta", "cuestan",
      "vale", "valen", "funciona", "funcionan", "usar", "usa", "usan",
      "aparece", "aparecen", "spawn", "coordenada", "coordenadas", "lugar", "lugares",
      "ubicado", "ubicada", "ubicados", "ubicadas", "ubicacion", "esta", "estan",
      "tiene", "tienen", "tienes", "hace", "hacen", "vive", "viven", "habita", "habitan",
      "necesita", "necesitan", "requiere", "requieren", "vende", "venden", "incluye", "incluyen",
      "contiene", "contienen", "haber", "visto", "viste");

  /**
   * Returns true only for a question that appears to name one concrete server entity,
   * not a broad category such as "que armas hay?". These queries are fail-closed:
   * generic wiki pages cannot prove that the named entity exists.
   */
  static boolean looksLikeConcreteNamedEntityQuery(String rawOrNormalized) {
    String normalized = canonicalWikiRoutingQuery(rawOrNormalized);
    if (normalized.isBlank()) return false;
    if (looksLikeBroadWikiCategoryQuery(normalized) || isWikiRecommendationIntent(normalized)) return false;
    Set<String> identityTerms = wikiIdentityTerms(normalized);

    String noun = firstWikiEntityNoun(normalized);
    if (noun != null) {
      // A category + at least one qualifier means a concrete named thing:
      // "espada hoja", "espada de nagamuta", "arco de la jungla".
      // A bare category such as "que armas hay?" remains broad.
      return !identityTerms.isEmpty();
    }

    String q = " " + normalized + " ";
    boolean identityCue = containsAnyTerm(q,
        " quien es ", " quienes son ", " conoces a ", " te suena ",
        " que sabes de ", " que sabes del ", " q sabes de ", " q sabes del ",
        " existe ", " existen ");
    if (identityCue && !identityTerms.isEmpty()) return true;

    boolean specificFactCue = isWikiObtainIntent(normalized)
        || hasWikiPropertyFactCue(normalized)
        || isWikiFunctionIntent(normalized)
        || containsAnyTerm(q, " como se hace ", " como se craftea ", " como crafteo ",
            " craftea ", " crafteo ", " craftear ", " receta ",
            " donde esta ", " donde sale ", " donde salen ", " donde aparece ",
            " donde aparecen ", " donde encuentro ", " donde vive ", " donde habita ",
            " que dropea ", " que dropean ", " que droppea ", " que droppean ",
            " que suelta ", " que sueltan ");
    // One identity word is enough for a strict fail-closed lookup: "que dropea
    // Galumrog?" or "donde sale Viridita?" must never fall back to generic pages.
    return specificFactCue && !identityTerms.isEmpty();
  }

  private static String firstWikiEntityNoun(String normalized) {
    if (normalized == null || normalized.isBlank()) return null;
    for (String token : normalized.split("\\s+")) {
      if (WIKI_ENTITY_NOUNS.contains(token)) return token;
      String singular = singularWikiEntityNoun(token);
      if (singular != null) return singular;
    }
    return null;
  }

  /** Social opinion prompts with deictic wording stay outside static wiki retrieval. */
  static boolean looksLikeDeicticOpinionSmallTalk(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    String q = " " + normalized + " ";
    boolean opinionCue = q.contains(" opinas ") || q.contains(" opinion ")
        || q.contains(" piensas ") || q.contains(" que te parece ")
        || q.contains(" q te parece ");
    if (!opinionCue) return false;

    // "de eso/esto" has no concrete server subject to retrieve. It belongs to the
    // surrounding social conversation and should never create WIKI no_match blocks.
    return q.contains(" de eso ") || q.contains(" de esto ") || q.contains(" de esa ")
        || q.contains(" de ese ") || q.contains(" sobre eso ") || q.contains(" sobre esto ")
        || q.contains(" opinas de eso ") || q.contains(" piensas de eso ");
  }

  /**
   * Reverse drop questions ask which mobs/sources provide the remembered item. The
   * item is therefore expected inside another entity's Drops block rather than as the
   * heading of its own wiki entry. Example: "que mobs la dropean?" after asking
   * about Esencia del Bosque.
   */
  static boolean isInverseDropSourceQuestion(String normalized) {
    if (normalized == null || normalized.isBlank() || !isWikiDropIntent(normalized)) return false;
    String q = " " + normalized + " ";
    return containsAnyTerm(q,
        " que mob ", " que mobs ", " que criatura ", " que criaturas ",
        " que bicho ", " que bichos ", " que monstruo ", " que monstruos ",
        " que enemigo ", " que enemigos ", " que bestia ", " que bestias ",
        " quien lo ", " quien la ", " quienes lo ", " quienes la ",
        " cuales lo ", " cuales la ");
  }

  static boolean isWikiObtainIntent(String query) {
    query = canonicalWikiRoutingQuery(query);
    return containsAnyTerm(query,
        "como consigo", "como se consigue", "como obtengo", "como se obtiene",
        "de donde consigo", "de donde sale", "donde sale", "donde salen",
        "donde consigo", "donde se consigue", "donde se obtiene", "donde aparece",
        "donde aparecen", "como saco", "donde saco", "de donde saco", "como lo saco",
        "como la saco", "de donde lo saco", "de donde la saco", "como obtener", "como conseguir",
        "como encontrar", "donde encuentro", "donde encontrar", "donde puedo encontrar",
        "donde puedo conseguir", "donde puedo obtener", "como puedo conseguir", "como puedo obtener",
        "donde lo encuentro", "donde la encuentro", "como lo obtengo", "como la obtengo", "como los obtengo",
        "como las obtengo", "como lo consigo", "como la consigo", "como los consigo", "como las consigo");
  }

  static String expandWikiAliases(String query) {
    if (query == null || query.isBlank()) return "";
    return query
        .replace("mini jefe", "miniboss")
        .replace("mini-jefe", "miniboss")
        .replace("mini boss", "miniboss")
        .replace("necrodito", "necrotido");
  }

  enum WikiIntentKind {
    GENERAL, OBTAIN, CRAFT, DROP, LOCATION, TRAVEL, FUNCTION, STATS, LIST, RECOMMENDATION, COMMERCE
  }

  static WikiIntentKind wikiIntentKind(String normalized) {
    normalized = canonicalWikiRoutingQuery(normalized);
    if (isWikiCraftIntent(normalized)) return WikiIntentKind.CRAFT;
    if (isWikiObtainIntent(normalized)) return WikiIntentKind.OBTAIN;
    if (isWikiDropIntent(normalized)) return WikiIntentKind.DROP;
    if (isWikiTravelIntent(normalized)) return WikiIntentKind.TRAVEL;
    if (isWikiCommerceIntent(normalized)) return WikiIntentKind.COMMERCE;
    if (isWikiLocationIntent(normalized)) return WikiIntentKind.LOCATION;
    if (isWikiFunctionIntent(normalized)) return WikiIntentKind.FUNCTION;
    if (hasWikiPropertyFactCue(normalized)) return WikiIntentKind.STATS;
    if (isWikiRecommendationIntent(normalized)) return WikiIntentKind.RECOMMENDATION;
    if (looksLikeBroadWikiCategoryQuery(normalized)) return WikiIntentKind.LIST;
    return WikiIntentKind.GENERAL;
  }

  static String applyWikiIntentToSubject(WikiIntentKind kind, String subject) {
    if (subject == null || subject.isBlank()) return "";
    return switch (kind == null ? WikiIntentKind.GENERAL : kind) {
      case OBTAIN -> "como consigo " + subject;
      case CRAFT -> "como se craftea " + subject;
      case DROP -> "que dropea " + subject;
      case LOCATION, TRAVEL -> "donde encuentro " + subject;
      case FUNCTION -> "para que sirve " + subject;
      case STATS -> "que estadisticas tiene " + subject;
      case RECOMMENDATION -> "que recomiendas para " + subject;
      case COMMERCE -> "donde comercio " + subject;
      case LIST, GENERAL -> "que sabes de " + subject;
    };
  }

  private static final Set<String> WIKI_FOLLOW_UP_SUBJECT_NOISE = Set.of(
      "si", "sisi", "dame", "mostrame", "muestrame",
      "decime", "dime", "contame", "cuentame", "explicame",
      "dices", "decis", "decir", "decirme", "puedes", "podrias", "podria", "explicas", "cuentas",
      "dale", "bueno", "porfa", "favor", "mas", "ver", "saber", "info",
      "informacion", "dato", "datos", "sobre", "acerca", "cual", "cuales",
      "cuanto", "cuanta", "cuantos", "cuantas", "quien", "quienes",
      "dropea", "dropean", "droppea", "droppean", "dropear", "drop", "drops",
      "suelta", "sueltan", "deja", "dejan", "tira", "tiran", "dar", "dan", "sale", "salen", "saco", "sacan", "sacar",
      "hallo", "hallan", "hallar", "dura", "duran", "aparece", "aparecen",
      "spawn", "ubicacion", "ubicado", "ubicada", "coordenada", "coordenadas", "lugar", "lugares",
      "hace", "hacen", "hago", "tiene", "tienen", "tienes", "lleva", "llevan", "usa", "usan",
      "vive", "viven", "habita", "habitan", "necesita", "necesitan", "requiere", "requieren",
      "vende", "venden", "incluye", "incluyen", "contiene", "contienen",
      "uso", "efecto", "efectos", "stats",
      "estadistica", "estadisticas", "dano", "damage", "pega", "golpea", "hp", "mana", "cooldown", "durabilidad",
      "crafteo", "crafteos", "receta", "recetas", "materiales", "porcentaje", "probabilidad", "chance",
      "cantidad", "cantidades", "numero", "numeros", "valor", "valores", "nivel", "niveles",
      "rareza", "calidad", "tier", "tiers", "vida", "salud", "velocidad", "radio",
      "alcance", "duracion", "coste", "costo", "precio", "precios", "habilidad", "habilidades",
      "maximo", "maxima", "maximos", "maximas", "base", "total", "totales",
      "actual", "actuales", "otro", "otros", "otra", "otras", "demas", "tipo", "tipos",
      "clase", "clases", "mejor", "mejores", "recomiendas", "recomendarias",
      "alli", "ahi", "hasta", "llego", "llegar", "voy", "ir", "entro", "vuelvo",
      "viajo", "viajar", "wiki", "busca", "mira", "revisa",
      "fisico", "fisica", "fisicos", "fisicas", "magico", "magica",
      "magicos", "magicas", "proyectil", "proyectiles", "resistencia", "resistencias",
      "reduccion", "regeneracion", "tenacidad", "bloqueo", "critico", "critica",
      "criticos", "criticas", "corazon", "corazones", "segundo", "segundos", "bloque", "bloques");

  /**
   * Builds a compact local retrieval subject without carrying the old question's
   * intent verbs. Example: "iso como consigo esencia del bosque" ->
   * "esencia bosque". This keeps later follow-ups focused and cannot add AI tokens.
   */
  static String buildWikiSubjectAnchor(String normalized) {
    if (normalized == null || normalized.isBlank()) return "";
    normalized = canonicalWikiRoutingQuery(normalized);
    // A verified broad category is also a valid local subject: "que pociones?"
    // can safely anchor a following "dame los crafteos" without carrying old prose.
    if (looksLikeBroadWikiCategoryQuery(normalized)) {
      String category = wikiBroadCategory(normalized);
      if (!category.isBlank()) return category;
    }
    LinkedHashSet<String> kept = new LinkedHashSet<>();
    for (String term : meaningfulTerms(normalized)) {
      if (WIKI_FOLLOW_UP_SUBJECT_NOISE.contains(term)) continue;
      if (WIKI_ENTITY_QUERY_NOISE.contains(term)) continue;
      kept.add(term);
    }
    return String.join(" ", kept);
  }

  /**
   * True when the current wording itself names a subject independently of previous
   * chat. Generic category/property words do not count.
   */
  static boolean wikiQueryHasIndependentSubject(String normalized) {
    return !wikiIdentityTerms(normalized).isEmpty();
  }

  /**
   * Identity-bearing words from the current factual wording. Interrogatives, intent
   * verbs and generic stat/property words are deliberately excluded. Generic entity
   * nouns ("espada", "mob", "armadura") also do not prove a new subject by
   * themselves: a qualifier such as "Hoja", "Necrótico" or "Bosque" must remain.
   */
  static Set<String> wikiIdentityTerms(String normalized) {
    LinkedHashSet<String> identity = new LinkedHashSet<>();
    if (normalized == null || normalized.isBlank()) return identity;
    normalized = canonicalWikiRoutingQuery(normalized);
    for (String term : meaningfulTerms(normalized)) {
      if (WIKI_FOLLOW_UP_SUBJECT_NOISE.contains(term)) continue;
      if (WIKI_ENTITY_QUERY_NOISE.contains(term)) continue;
      if (isWikiEntityNounToken(term)) continue;
      identity.add(term);
    }
    return identity;
  }

  private static boolean isWikiEntityNounToken(String token) {
    if (token == null || token.isBlank()) return false;
    if (WIKI_ENTITY_NOUNS.contains(token)) return true;
    return singularWikiEntityNoun(token) != null;
  }

  static String singularWikiEntityNoun(String token) {
    if (token == null || token.isBlank()) return null;
    if (token.endsWith("es") && token.length() > 4) {
      String singularEs = token.substring(0, token.length() - 2);
      if (WIKI_ENTITY_NOUNS.contains(singularEs)) return singularEs;
    }
    if (token.endsWith("s") && token.length() > 3) {
      String singularS = token.substring(0, token.length() - 1);
      if (WIKI_ENTITY_NOUNS.contains(singularS)) return singularS;
    }
    return null;
  }

  static boolean looksLikeWikiFollowUp(String normalized) {
    if (normalized == null || normalized.isBlank()) return false;
    normalized = canonicalWikiRoutingQuery(normalized);
    if (looksLikeBroadWikiCategoryQuery(normalized) || isWikiRecommendationIntent(normalized)) return false;

    // Subject beats syntax: "como consigo esencia del bosque" is a NEW direct
    // question, while "y como se consigue?" is a true continuation.
    boolean hasIndependentSubject = wikiQueryHasIndependentSubject(normalized);
    String q = " " + normalized + " ";

    boolean conversationalContinuation = containsAnyTerm(q,
        " sisi decime ", " si decime ", " decime porfa ", " dime porfa ",
        " contame porfa ", " cuentame porfa ", " explicame porfa ",
        " dale decime ", " bueno decime ", " a ver decime ", " si contame ",
        " sisi contame ", " dime mas ", " decime mas ", " contame mas ");
    if (conversationalContinuation) return !hasIndependentSubject;
    if (looksLikeWikiMetaRetry(normalized)) return true;
    if (hasIndependentSubject) return false;
    if (hasSubjectlessWikiFactIntent(normalized)) return true;
    if (isWikiTravelIntent(normalized)) return true;

    // Once identity-bearing words are absent, length alone must not disqualify a
    // continuation. Natural property follow-ups such as "y cuanta vida maxima tiene?"
    // can contain several words while still depending entirely on the previous subject.
    return normalized.contains(" eso")
        || normalized.contains(" esa")
        || normalized.contains(" ese")
        || normalized.contains(" esto")
        || normalized.startsWith("y ")
        || normalized.contains(" como se craftea")
        || normalized.startsWith("como se craftea")
        || normalized.contains(" como lo crafteo")
        || normalized.contains(" de donde consigo")
        || normalized.startsWith("de donde consigo")
        || normalized.contains(" como consigo")
        || normalized.startsWith("como consigo")
        || normalized.contains(" como lo consigo")
        || normalized.startsWith("como lo consigo")
        || normalized.contains(" donde lo consigo")
        || normalized.startsWith("donde lo consigo")
        || normalized.contains(" donde se consigue")
        || normalized.startsWith("donde se consigue")
        || normalized.startsWith("donde sale")
        || normalized.startsWith("donde aparece")
        || normalized.startsWith("donde vive")
        || normalized.startsWith("donde habita")
        || normalized.startsWith("que dropea")
        || normalized.startsWith("que droppea")
        || normalized.startsWith("que suelta")
        || normalized.startsWith("quien lo dropea")
        || normalized.startsWith("quienes lo dropean")
        || normalized.startsWith("quien lo suelta")
        || normalized.startsWith("quienes lo sueltan")
        || normalized.contains(" como se hace")
        || normalized.startsWith("como se hace");
  }

  static boolean isObviousSocialSmallTalk(String normalized) {
    if (normalized == null || normalized.isBlank()) return true;
    String q = " " + normalized + " ";
    return q.contains(" como estas ")
        || q.contains(" como andas ")
        || q.contains(" como te va ")
        || q.contains(" todo bien ")
        || q.contains(" que tal iso ")
        || q.contains(" que tal isolda ")
        || q.contains(" que opinas de mi ")
        || q.contains(" que opinas de ")
        || q.contains(" que es ") && q.contains(" de ti ")
        || q.contains(" como te cae ")
        || q.contains(" como te llevas con ")
        || q.contains(" que sientes por ")
        || q.contains(" quien es tu pareja ")
        || q.contains(" tienes pareja ")
        || q.contains(" por que me tratas ")
        || q.contains(" porque me tratas ")
        || q.contains(" pq me tratas ")
        || q.contains(" por que eres mala conmigo ")
        || q.contains(" pq eres mala conmigo ")
        || q.contains(" eres mala conmigo ")
        || q.contains(" eres malo conmigo ")
        || q.contains(" nuestro amor ")
        || q.contains(" tu amor ")
        || q.contains(" me quieres ")
        || q.contains(" te quiero ")
        || q.contains(" me gustas ")
        || q.contains(" no me hagas llorar ")
        || q.contains(" mi ex me trataba ")
        || q.contains(" buena charla ")
        || q.contains(" hola iso ")
        || q.contains(" hola isolda ")
        || q.contains(" holaa iso ")
        || q.contains(" holaa isolda ")
        || q.trim().equals("hola")
        || q.trim().equals("holi")
        || q.trim().equals("holaa")
        || q.trim().equals("buenas");
  }
}
