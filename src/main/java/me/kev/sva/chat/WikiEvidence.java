package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;
import static me.kev.sva.chat.WikiQueryClassifier.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a wiki section really supports a query: that it names the asked
 * entity and actually contains the requested fact. Pure functions; no Bukkit state.
 */
final class WikiEvidence {
  private WikiEvidence() {
  }

  /**
   * Strict evidence gate for named entities. A section must actually contain the
   * requested entity name (or a close typo/compact spelling), not merely share a
   * generic word such as "espada" or "jungla" somewhere else in the page.
   */
  static boolean candidateStronglyMatchesConcreteEntity(
      String rawOrNormalizedQuery,
      String key,
      String description,
      String content) {
    String query = canonicalWikiRoutingQuery(rawOrNormalizedQuery);
    if (!looksLikeConcreteNamedEntityQuery(query)) return true;

    String candidate = normalizeForSearch(
        (key == null ? "" : key.replace('-', ' ').replace('_', ' ')) + " "
            + (description == null ? "" : description) + " "
            + (content == null ? "" : content));
    Set<String> candidateTokens = tokenSet(candidate);

    LinkedHashSet<String> qualifiers = new LinkedHashSet<>(wikiIdentityTerms(query));
    if (qualifiers.isEmpty()) return true;
    String noun = bestWikiEntityNounForQuery(query, qualifiers);

    // Every identity-bearing qualifier must be represented. This alone rejects
    // "Espada de Nagamuta" against a generic page that merely lists other swords.
    for (String qualifier : qualifiers) {
      if (containsWholeWordIgnoreCase(candidate, qualifier)) continue;
      if (lexicalVariantMatches(qualifier, candidateTokens)) continue;
      if (bestFuzzyTokenScore(qualifier, candidateTokens) > 0) continue;
      if (noun != null && containsCompactEntityToken(candidateTokens, noun, qualifier)) continue;
      return false;
    }

    if (noun == null) {
      return qualifiers.size() == 1 || termsFormCompactEntityName(candidate, qualifiers);
    }

    // The category itself must be part of the same local mention. This prevents a
    // Saurio spawn page containing "jungla" from validating "Arco de la Jungla".
    if (qualifiers.size() == 1
        && containsCompactEntityToken(candidateTokens, noun, qualifiers.iterator().next())) return true;
    return nounOccursNearQualifiers(candidate, noun, qualifiers, 5);
  }

  private static final Set<String> WIKI_ENTITY_RELATION_VERBS = Set.of(
      "tiene", "tienen", "tienes", "usa", "usan", "lleva", "llevan",
      "necesita", "necesitan", "requiere", "requieren", "da", "dan", "hace", "hacen",
      "dropea", "dropean", "suelta", "sueltan", "deja", "dejan", "tira", "tiran",
      "vende", "venden", "funciona", "funcionan");

  /**
   * Chooses the entity-category noun that actually belongs to the named subject.
   * This avoids treating a requested PROPERTY as the entity head: in
   * "que habilidades tiene el Hierofante del Eclipse", "habilidades" is not the
   * subject; in "que materiales necesita Mango Resinoso", "materiales" is not
   * the subject. By contrast, "espada de Nagamuta" and "arco de la jungla" keep
   * their category noun because no relation verb separates it from the qualifier.
   */
  private static String bestWikiEntityNounForQuery(
      String normalizedQuery, Set<String> qualifiers) {
    if (normalizedQuery == null || normalizedQuery.isBlank()
        || qualifiers == null || qualifiers.isEmpty()) return null;
    String[] tokens = normalizedQuery.split("\\s+");
    String bestNoun = null;
    int bestDistance = Integer.MAX_VALUE;

    for (int i = 0; i < tokens.length; i++) {
      String noun = WIKI_ENTITY_NOUNS.contains(tokens[i])
          ? tokens[i]
          : singularWikiEntityNoun(tokens[i]);
      if (noun == null) continue;

      int maxDistance = 0;
      boolean valid = true;
      for (String qualifier : qualifiers) {
        int nearest = -1;
        int nearestDistance = Integer.MAX_VALUE;
        for (int j = 0; j < tokens.length; j++) {
          if (!tokens[j].equals(qualifier)) continue;
          int distance = Math.abs(j - i);
          if (distance < nearestDistance) {
            nearest = j;
            nearestDistance = distance;
          }
        }
        if (nearest < 0 || nearestDistance > 6) {
          valid = false;
          break;
        }
        int from = Math.min(i, nearest) + 1;
        int to = Math.max(i, nearest);
        for (int j = from; j < to; j++) {
          if (WIKI_ENTITY_RELATION_VERBS.contains(tokens[j])) {
            valid = false;
            break;
          }
        }
        if (!valid) break;
        maxDistance = Math.max(maxDistance, nearestDistance);
      }
      if (valid && maxDistance < bestDistance) {
        bestDistance = maxDistance;
        bestNoun = noun;
      }
    }
    return bestNoun;
  }

  private static boolean lexicalVariantMatches(String term, Set<String> candidateTokens) {
    if (term == null || term.isBlank() || candidateTokens == null || candidateTokens.isEmpty()) return false;
    LinkedHashSet<String> variants = new LinkedHashSet<>();
    variants.add(term);
    if (term.endsWith("s") && term.length() > 3) variants.add(term.substring(0, term.length() - 1));
    if (term.endsWith("es") && term.length() > 4) variants.add(term.substring(0, term.length() - 2));
    for (String candidate : candidateTokens) {
      if (candidate == null || candidate.isBlank()) continue;
      LinkedHashSet<String> candidateVariants = new LinkedHashSet<>();
      candidateVariants.add(candidate);
      if (candidate.endsWith("s") && candidate.length() > 3) {
        candidateVariants.add(candidate.substring(0, candidate.length() - 1));
      }
      if (candidate.endsWith("es") && candidate.length() > 4) {
        candidateVariants.add(candidate.substring(0, candidate.length() - 2));
      }
      for (String a : variants) if (candidateVariants.contains(a)) return true;
    }
    return false;
  }

  private static boolean containsCompactEntityToken(Set<String> tokens, String noun, String qualifier) {
    if (tokens == null || noun == null || qualifier == null) return false;
    String a = noun + qualifier;
    String b = qualifier + noun;
    for (String token : tokens) {
      if (token.equals(a) || token.equals(b)) return true;
    }
    return false;
  }

  private static boolean nounOccursNearQualifiers(
      String normalizedCandidate,
      String noun,
      Set<String> qualifiers,
      int maxDistance) {
    if (normalizedCandidate == null || noun == null || qualifiers == null || qualifiers.isEmpty()) return false;
    String[] tokens = normalizedCandidate.split("\\s+");
    for (int i = 0; i < tokens.length; i++) {
      if (!tokens[i].equals(noun)) continue;
      boolean allNear = true;
      for (String qualifier : qualifiers) {
        boolean found = false;
        int from = Math.max(0, i - maxDistance);
        int to = Math.min(tokens.length - 1, i + maxDistance);
        for (int j = from; j <= to; j++) {
          if (tokens[j].equals(qualifier)
              || lexicalVariantMatches(qualifier, Set.of(tokens[j]))
              || bestFuzzyTokenScore(qualifier, Set.of(tokens[j])) > 0) {
            found = true;
            break;
          }
        }
        if (!found) {
          allNear = false;
          break;
        }
      }
      if (allNear) return true;
    }
    return false;
  }

  /**
   * Multi-word entity names must appear as one compact name, not merely as two
   * nearby words belonging to different phrases (for example "Fragmento del
   * Hierofante, Corona del Eclipse" must not prove "Hierofante del Eclipse").
   */
  private static boolean termsFormCompactEntityName(
      String normalizedCandidate, Set<String> orderedTerms) {
    if (normalizedCandidate == null || orderedTerms == null || orderedTerms.size() < 2) return false;
    List<String> terms = new ArrayList<>(orderedTerms);
    String[] tokens = normalizedCandidate.split("\\s+");
    if (orderedTermsMatchCompactly(tokens, terms)) return true;
    if (terms.size() == 2) {
      List<String> reversed = List.of(terms.get(1), terms.get(0));
      return orderedTermsMatchCompactly(tokens, reversed);
    }
    return false;
  }

  private static boolean orderedTermsMatchCompactly(String[] tokens, List<String> terms) {
    for (int start = 0; start < tokens.length; start++) {
      if (!tokens[start].equals(terms.get(0))
          && !lexicalVariantMatches(terms.get(0), Set.of(tokens[start]))
          && bestFuzzyTokenScore(terms.get(0), Set.of(tokens[start])) == 0) continue;
      int cursor = start;
      boolean all = true;
      for (int t = 1; t < terms.size(); t++) {
        int found = -1;
        // Permit at most one connector word ("del", "de", "la") between name parts.
        for (int j = cursor + 1; j <= Math.min(tokens.length - 1, cursor + 2); j++) {
          if (j == cursor + 2 && !Set.of("de", "del", "la", "el", "los", "las").contains(tokens[cursor + 1])) {
            continue;
          }
          if (tokens[j].equals(terms.get(t))
              || lexicalVariantMatches(terms.get(t), Set.of(tokens[j]))
              || bestFuzzyTokenScore(terms.get(t), Set.of(tokens[j])) > 0) {
            found = j;
            break;
          }
        }
        if (found < 0) {
          all = false;
          break;
        }
        cursor = found;
      }
      if (all) return true;
    }
    return false;
  }

  private static boolean termsOccurNearEachOther(
      String normalizedCandidate,
      Set<String> terms,
      int maxSpan) {
    if (normalizedCandidate == null || terms == null || terms.isEmpty()) return false;
    String[] tokens = normalizedCandidate.split("\\s+");
    for (int start = 0; start < tokens.length; start++) {
      int end = Math.min(tokens.length, start + maxSpan + 1);
      Set<String> window = new LinkedHashSet<>();
      for (int i = start; i < end; i++) window.add(tokens[i]);
      boolean all = true;
      for (String term : terms) {
        if (window.contains(term) || lexicalVariantMatches(term, window)
            || bestFuzzyTokenScore(term, window) > 0) continue;
        all = false;
        break;
      }
      if (all) return true;
    }
    return false;
  }

  static int wikiBroadCategoryBoost(String query, String key, boolean subjectMatched) {
    if (key == null) return 0;
    key = key.toLowerCase(Locale.ROOT);
    String category = wikiBroadCategory(query);
    return switch (category) {
      case "mineral" -> key.equals("obtain-mining-materials") ? 22
          : key.equals("ores-tier3") ? 18
          : key.startsWith("ore-") ? 12
          : key.startsWith("materials-mining") ? 10 : 0;
      case "nodo" -> key.equals("tree-nodes-overview") ? 24
          : key.equals("tree-nodes-types") ? 22
          : key.equals("obtain-tree-nodes") ? 20 : 0;
      case "comando" -> key.equals("commands-help") ? 22
          : key.startsWith("commands-") ? 12 : 0;
      case "pocion", "consumible" -> key.equals("crafting-consumables-basic") ? 24
          : key.startsWith("crafting-consumables") ? 20
          : key.contains("consumable") ? 12 : 0;
      case "raza" -> key.equals("races-overview") ? 24 : key.startsWith("race-") ? 12 : 0;
      case "arma" -> key.contains("weapons") || key.startsWith("weapon-") ? 16
          : key.startsWith("crafting-") ? 8 : 0;
      case "armadura", "set" -> key.startsWith("armor-") ? 16 : key.startsWith("crafting-armor") ? 8 : 0;
      case "herramienta" -> key.startsWith("tools-") ? 16 : key.startsWith("crafting-tools") ? 10 : 0;
      case "profesion" -> key.startsWith("profession-") || key.equals("professions-overview") ? 16 : 0;
      case "faccion" -> key.startsWith("lore-") || key.startsWith("spawn-") ? 8 : 0;
      case "evento" -> key.contains("event") || key.startsWith("spawn-") ? 8 : 0;
      case "mob", "criatura", "enemigo" -> key.startsWith("mob-") || key.startsWith("mobs-") ? 10 : 0;
      default -> subjectMatched
          && (key.contains("overview") || key.startsWith("obtain-") || key.startsWith("commands-")) ? 8 : 0;
    };
  }

  /**
   * Final local evidence gate for the requested fact facet. Named-entity matching
   * proves only that the subject exists; this method additionally proves that the
   * selected section contains the kind of information the player asked for.
   * No API/model call is involved.
   */
  private static boolean isDedicatedWikiSectionKey(String key) {
    if (key == null || key.isBlank()) return false;
    String k = key.toLowerCase(Locale.ROOT);
    if (k.contains("overview")) return false;
    return k.startsWith("race-") || k.startsWith("lore-") || k.startsWith("mob-")
        || k.startsWith("weapon-") || k.startsWith("armor-") || k.startsWith("ore-")
        || k.startsWith("profession-") || k.startsWith("consumable-")
        || k.startsWith("magic-tome-");
  }

  private static boolean requiresEntityScopedFactEvidence(
      String factQuery, String subjectQuery) {
    if (!looksLikeConcreteNamedEntityQuery(subjectQuery)) return false;
    return isWikiCraftIntent(factQuery)
        || hasWikiPropertyFactCue(factQuery)
        || isWikiFunctionIntent(factQuery)
        || (isWikiDropIntent(factQuery) && !isInverseDropSourceQuestion(factQuery));
  }

  /**
   * Extracts the structured entry belonging to the requested concrete subject.
   * Most MDVCRAFT wiki facts are organized as "Entity:" followed by its stats,
   * drops or recipe. This prevents a property from another entity in the same
   * section from being mistaken as evidence for the requested one.
   */
  private static String concreteEntityEntryWindow(String query, String content) {
    if (query == null || query.isBlank() || content == null || content.isBlank()) return "";
    String[] lines = content.split("\\R");
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i] == null ? "" : lines[i].trim();
      if (!line.endsWith(":")) continue;
      if (!candidateStronglyMatchesConcreteEntity(query, "", "", line)) continue;

      StringBuilder block = new StringBuilder(line);
      for (int j = i + 1; j < lines.length; j++) {
        String next = lines[j] == null ? "" : lines[j].trim();
        if (isLikelyWikiEntryHeading(next)) break;
        if (!next.isBlank()) block.append('\n').append(next);
      }
      return block.toString();
    }
    return "";
  }

  private static boolean isLikelyWikiEntryHeading(String rawLine) {
    if (rawLine == null || rawLine.isBlank() || !rawLine.endsWith(":")) return false;
    String n = normalizeForSearch(rawLine.substring(0, rawLine.length() - 1));
    if (n.isBlank()) return false;
    if (n.startsWith("drops") || n.startsWith("drop") || n.startsWith("habilidad")
        || n.startsWith("estadistic") || n.startsWith("stats") || n.startsWith("efecto")
        || n.startsWith("resistencia") || n.startsWith("ataque") || n.startsWith("fase")
        || n.startsWith("se fabrica") || n.startsWith("se crea") || n.startsWith("se obtiene")
        || n.startsWith("puede obtenerse") || n.startsWith("receta")) {
      return false;
    }
    return !Set.of(
        "piezas", "recompensas", "materiales", "ingredientes", "primera fase",
        "segunda fase", "tercera fase").contains(n);
  }

  /**
   * For reverse drop/source questions, require the remembered item/material to occur
   * in acquisition/drop evidence rather than merely somewhere else in the same page.
   * This is deliberately local and deterministic.
   */
  private static boolean candidateSupportsInverseDropSource(
      String rawSubjectQuery, String content) {
    Set<String> identity = wikiIdentityTerms(normalizeForSearch(rawSubjectQuery));
    if (identity.isEmpty()) return false;
    String raw = (content == null ? "" : content);
    String[] lines = raw.split("\\R");

    for (int i = 0; i < lines.length; i++) {
      String normalizedLine = normalizeForSearch(lines[i]);
      if (!allIdentityTermsMatchText(identity, normalizedLine)) continue;

      StringBuilder neighborhood = new StringBuilder(normalizedLine);
      for (int j = Math.max(0, i - 3); j <= Math.min(lines.length - 1, i + 2); j++) {
        if (j == i) continue;
        neighborhood.append(' ').append(normalizeForSearch(lines[j]));
      }
      String n = " " + neighborhood + " ";
      if (containsAnyTerm(n,
          " drops ", " drop ", " dropea ", " dropean ",
          " suelta ", " sueltan ", " se obtiene ", " se obtienen ",
          " derrotando ", " al derrotar ", " al matar ")) {
        return true;
      }
    }

    // Do not fall back to "subject somewhere + Drops somewhere" across the full
    // section: that is exactly how facts from a neighboring entity can leak.
    return false;
  }

  private static boolean allIdentityTermsMatchText(Set<String> identity, String normalizedText) {
    if (identity == null || identity.isEmpty() || normalizedText == null || normalizedText.isBlank()) {
      return false;
    }
    Set<String> tokens = tokenSet(normalizedText);
    for (String term : identity) {
      if (containsWholeWordIgnoreCase(normalizedText, term)) continue;
      if (bestFuzzyTokenScore(term, tokens) > 0) continue;
      return false;
    }
    return true;
  }

  private static boolean sectionKeyDirectlyNamesConcreteEntity(
      String rawSubjectQuery, String key) {
    if (rawSubjectQuery == null || rawSubjectQuery.isBlank() || key == null || key.isBlank()) {
      return false;
    }
    Set<String> identity = wikiIdentityTerms(normalizeForSearch(rawSubjectQuery));
    if (identity.isEmpty()) return false;
    String normalizedKey = normalizeForSearch(key.replace('-', ' ').replace('_', ' '));
    return allTermsMatchKey(identity, normalizedKey, tokenSet(normalizedKey));
  }

  static boolean candidateSupportsWikiFactIntent(
      String rawOrNormalizedQuery, String key, String description, String content) {
    return candidateSupportsWikiFactIntent(
        rawOrNormalizedQuery, rawOrNormalizedQuery, key, description, content);
  }

  /**
   * Variant used by follow-ups. {@code factQuery} is what the player asks NOW
   * (for example "y cuanta vida tiene?"), while {@code subjectQuery} is the
   * previously verified subject. Keeping those roles separate prevents a multi-entity
   * page from borrowing another entity's stats while preserving reverse questions such
   * as "que mobs la dropean?".
   */
  static boolean candidateSupportsWikiFactIntent(
      String rawFactQuery, String rawSubjectQuery,
      String key, String description, String content) {
    String query = canonicalWikiRoutingQuery(rawFactQuery);
    if (query.isBlank()) return true;
    String subjectQuery = canonicalWikiRoutingQuery(rawSubjectQuery);
    if (subjectQuery.isBlank()) subjectQuery = query;

    String fullRaw = ((key == null ? "" : key) + " "
        + (description == null ? "" : description) + " "
        + (content == null ? "" : content));
    String evidenceRaw = fullRaw;
    if (requiresEntityScopedFactEvidence(query, subjectQuery)) {
      String entry = concreteEntityEntryWindow(subjectQuery, content);
      if (entry.isBlank()) {
        // Dedicated sections such as lore-galumrog may not repeat the entity as a
        // "Name:" heading. Only trust the whole section when its KEY itself names
        // every identity term; a generic multi-entity page cannot use this escape hatch.
        if (!sectionKeyDirectlyNamesConcreteEntity(subjectQuery, key)
            && !(isDedicatedWikiSectionKey(key)
                && candidateStronglyMatchesConcreteEntity(subjectQuery, key, description, ""))) return false;
        evidenceRaw = content == null ? "" : content;
      } else {
        evidenceRaw = entry;
      }
    }
    String candidate = normalizeForSearch(evidenceRaw);
    String keyLower = key == null ? "" : key.toLowerCase(Locale.ROOT);
    String q = " " + query + " ";
    String c = " " + candidate + " ";

    if (isInverseDropSourceQuestion(query)) {
      if (!candidateSupportsInverseDropSource(subjectQuery, content)) return false;
    }

    // Multiple facets can coexist (for example "qué probabilidad de drop tiene X").
    // Every explicitly requested facet must be evidenced by the selected section.
    if (isWikiCraftIntent(query)) {
      boolean supported = keyLower.startsWith("crafting-")
          || containsAnyTerm(c, " se fabrica ", " se crea ", " se craftea ", " se elabora ",
              " crafteo ", " crafteos ", " receta ", " recetas ")
          || evidenceRaw.contains("→") || evidenceRaw.contains("->");
      if (!supported) return false;
    }

    if (isWikiDropIntent(query)) {
      boolean supported = containsAnyTerm(c,
          " drops ", " drop ", " dropea ", " dropean ", " droppea ", " droppean ",
          " suelta ", " sueltan ", " derrotando ", " al derrotar ", " al matar ");
      if (!supported) return false;
    }

    if (containsAnyTerm(q, " cuanta vida ", " cuanto vida ", " cuantos corazones ",
        " cuantas vidas ", " cuanto hp ", " cuanta hp ", " que hp ")) {
      if (!containsAnyTerm(c, " vida ", " corazones ", " salud ", " hp ")) return false;
    }
    if (containsAnyTerm(q, " cuanto dano ", " cuanta dano ", " que dano ", " q dano ",
        " cuanto pega ", " cuanto golpea ", " que dano hace ")) {
      if (!containsAnyTerm(c, " dano ", " damage ")) return false;
    }
    if (containsAnyTerm(q, " cuanto mana ", " cuanta mana ", " que mana ")) {
      if (!c.contains(" mana ")) return false;
    }
    if (q.contains(" cooldown ") && !c.contains(" cooldown ")) return false;
    if (q.contains(" durabilidad ") && !c.contains(" durabilidad ")) return false;
    if (containsAnyTerm(q, " que rareza ", " q rareza ", " que calidad ", " q calidad ",
        " que tier ", " q tier ")) {
      if (!containsAnyTerm(c, " rareza ", " calidad ", " tier ", " comun ", " especial ",
          " raro ", " epico ", " legendario ", " reliquia ")) return false;
    }
    if (containsAnyTerm(q, " que habilidad ", " que habilidades ", " q habilidad ", " q habilidades ")) {
      if (!containsAnyTerm(c, " habilidad ", " habilidades ")) return false;
    }
    if (containsAnyTerm(q, " que stats ", " q stats ", " que estadisticas ", " q estadisticas ")) {
      if (!containsAnyTerm(c, " estadisticas ", " vida ", " dano ", " mana ", " durabilidad ",
          " velocidad ", " resistencia ", " reduccion ")) return false;
    }
    if (containsAnyTerm(q, " que resistencia ", " que resistencias ")
        && !containsAnyTerm(c, " resistencia ", " resistencias ")) return false;
    if (q.contains(" reduccion ") && !c.contains(" reduccion ")) return false;
    if (q.contains(" regeneracion ") && !c.contains(" regeneracion ")) return false;
    if (q.contains(" velocidad ") && !c.contains(" velocidad ")) return false;
    if (q.contains(" alcance ") && !c.contains(" alcance ")) return false;
    if (q.contains(" radio ") && !c.contains(" radio ")) return false;
    if (containsAnyTerm(q, " cuanto dura ", " que duracion ")
        && !containsAnyTerm(c, " duracion ", " segundos ", " segundo ")) return false;
    if (containsAnyTerm(q, " que efecto ", " que efectos ")
        && !containsAnyTerm(c, " efecto ", " efectos ", " aplica ", " otorga ")) return false;
    if (containsAnyTerm(q, " que porcentaje ", " cuanto porcentaje ",
        " que probabilidad ", " cuanta probabilidad ", " que chance ", " cuanto chance ")) {
      if (!(evidenceRaw.contains("%") || containsAnyTerm(c, " probabilidad ", " porcentaje ", " chance "))) {
        return false;
      }
    }
    if (containsAnyTerm(q, " que precio ", " cuanto precio ", " cuanto cuesta ", " cuanto vale ")) {
      if (!containsAnyTerm(c, " precio ", " cuesta ", " costo ", " vale ", " denar ", " denares ")) {
        return false;
      }
    }
    if (containsAnyTerm(q, " que material ", " que materiales ")) {
      boolean supported = keyLower.startsWith("crafting-")
          || containsAnyTerm(c, " se fabrica ", " se crea ", " requiere ", " ingredientes ")
          || evidenceRaw.contains("→") || evidenceRaw.contains("->");
      if (!supported) return false;
    }
    if (containsAnyTerm(q, " que arma ", " que armas ")) {
      boolean weaponEvidence = containsAnyTerm(c,
          " arma ", " espada ", " sable ", " daga ", " maza ", " lanza ", " arco ",
          " ballesta ", " baculo ", " cetro ", " tomo ", " baston ", " escudo ");
      boolean recommendationTraitEvidence = isWikiRecommendationIntent(query)
          && containsAnyTerm(c, " raza ", " dano magico ", " dano a distancia ",
              " mana ", " vida maxima ", " proyectil ", " proyectiles ");
      if (!weaponEvidence && !recommendationTraitEvidence) return false;
    }

    // Pure location questions (not acquisition wording) require location evidence.
    if (isWikiLocationIntent(query) && !isWikiObtainIntent(query)) {
      boolean supported = keyLower.startsWith("spawn-")
          || containsAnyTerm(c,
              " se encuentra ", " se encuentran ", " aparece ", " aparecen ", " habita ",
              " habitan ", " vive ", " viven ", " ubicacion ", " coordenada ",
              " coordenadas ", " altura ", " bioma ", " biomas ", " region ", " regiones ",
              " viajar ", " llegar ", " utilizando /", " usar /", " mercado ", " lobby ");
      if (!supported) return false;
    }

    if (isWikiTravelIntent(query)) {
      boolean supported = containsAnyTerm(c,
          " viajar ", " llegar ", " ir a ", " utilizando /", " usar /", " comando ",
          " /lobby ", " /survival ", " /mercado ", " /warp ", " portal ");
      if (!supported) return false;
    }

    if (isWikiCommerceIntent(query)) {
      boolean supported = containsAnyTerm(c,
          " mercado ", " comerciante ", " comerciantes ", " aldeano ", " aldeanos ",
          " comercio ", " comerciar ", " intercambio ", " /comercio ", " /mercado ");
      if (!supported) return false;
    }

    // "como/donde consigo X" may be answered by obtain, crafting, drops or a
    // concrete location. Merely describing X is not acquisition evidence.
    if (isWikiObtainIntent(query)) {
      boolean supported = keyLower.startsWith("obtain-") || keyLower.startsWith("spawn-")
          || keyLower.startsWith("ore-") || keyLower.startsWith("crafting-")
          || containsAnyTerm(c,
              " se obtiene ", " se obtienen ", " se consigue ", " se consiguen ",
              " se fabrica ", " se crea ", " se encuentra ", " se encuentran ",
              " aparece ", " aparecen ", " drops ", " drop ", " derrotando ", " al derrotar ",
              " al matar ", " minando ", " talando ", " pescando ", " cultivando ", " recolectando ");
      if (!supported) return false;
    }

    return true;
  }

  /** Topic words used only to decide whether an intent bonus is allowed. */
  static Set<String> wikiTopicTerms(String query) {
    LinkedHashSet<String> terms = new LinkedHashSet<>(meaningfulTerms(query));
    terms.removeAll(WIKI_ENTITY_QUERY_NOISE);
    return terms;
  }

  static boolean candidateMatchesWikiTopic(
      WikiIndexEntry indexed, Set<String> topicTerms) {
    if (indexed == null || topicTerms == null || topicTerms.isEmpty()) return false;
    Set<String> keyDescriptionTokens = tokenSet(
        indexed.normalizedKey() + " " + indexed.normalizedDescription());
    for (String term : topicTerms) {
      if (containsWholeWordIgnoreCase(indexed.normalizedKey(), term)
          || containsWholeWordIgnoreCase(indexed.normalizedDescription(), term)
          || containsWholeWordIgnoreCase(indexed.normalizedContent(), term)
          || bestFuzzyTokenScore(term, keyDescriptionTokens) > 0) {
        return true;
      }
    }
    return false;
  }

  /** Pure regression hook: verifies topic matching without constructing a Bukkit plugin. */
  static boolean candidateMatchesWikiTopic(
      String rawQuery, String key, String description, String content) {
    String query = expandWikiAliases(normalizeForSearch(rawQuery));
    WikiIndexEntry indexed = new WikiIndexEntry(
        key == null ? "" : key,
        description == null ? "" : description,
        content == null ? "" : content,
        normalizeForSearch((key == null ? "" : key).replace('-', ' ').replace('_', ' ')),
        normalizeForSearch(description),
        normalizeForSearch(content));
    return candidateMatchesWikiTopic(indexed, wikiTopicTerms(query));
  }

  static Set<String> wikiEntityTerms(Set<String> queryTerms) {
    LinkedHashSet<String> entity = new LinkedHashSet<>();
    if (queryTerms == null) return entity;
    Set<String> intentNoise = Set.of(
        "drops", "drop", "dropea", "droppea", "suelta", "coordenadas", "coordenada",
        "puedo", "puede", "pueden", "miniboss", "boss", "jefe", "raros", "raro");
    for (String term : queryTerms) {
      if (intentNoise.contains(term) || WIKI_ENTITY_QUERY_NOISE.contains(term)) continue;
      entity.add(term);
    }
    return entity;
  }

  static boolean allTermsMatchKey(Set<String> terms, String normalizedKey, Set<String> keyTokens) {
    if (terms == null || terms.isEmpty()) return false;
    for (String term : terms) {
      if (containsWholeWordIgnoreCase(normalizedKey, term)) continue;
      if (bestFuzzyTokenScore(term, keyTokens) > 0) continue;
      return false;
    }
    return true;
  }

  static int bestFuzzyTokenScore(String queryTerm, Set<String> candidateTokens) {
    if (queryTerm == null || queryTerm.length() < 6 || candidateTokens == null) return 0;
    double best = 0.0;
    for (String token : candidateTokens) {
      if (token == null || token.length() < 5) continue;
      if (queryTerm.charAt(0) != token.charAt(0)) continue;
      if (commonPrefixLength(queryTerm, token) < 2) continue;
      if (Math.abs(queryTerm.length() - token.length()) > 4) continue;
      best = Math.max(best, lcsSimilarity(queryTerm, token));
    }
    if (best >= 0.86) return 5;
    if (best >= 0.74) return 4;
    return 0;
  }

  private static double lcsSimilarity(String a, String b) {
    if (a.isEmpty() || b.isEmpty()) return 0.0;
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int i = 1; i <= a.length(); i++) {
      java.util.Arrays.fill(current, 0);
      for (int j = 1; j <= b.length(); j++) {
        if (a.charAt(i - 1) == b.charAt(j - 1)) current[j] = previous[j - 1] + 1;
        else current[j] = Math.max(previous[j], current[j - 1]);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return (2.0 * previous[b.length()]) / (a.length() + b.length());
  }
}
