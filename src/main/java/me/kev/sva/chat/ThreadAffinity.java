package me.kev.sva.chat;

import static me.kev.sva.chat.ChatText.*;

import java.util.LinkedHashSet;
import java.util.Set;

import me.kev.sva.chat.tools.ContextTargetResolver;

/**
 * Zero-token scoring of whether a public chat line belongs to Isolda's active
 * conversation or to a competing side thread. Pure functions; no Bukkit state.
 */
final class ThreadAffinity {
  private ThreadAffinity() {
  }

  static boolean shouldJoinGroupCandidate(
      boolean strongAuthority,
      boolean explicitParticipantSideAddress,
      boolean broadPublicSide,
      int isoldaAffinity,
      int sideAffinity,
      int joinThreshold,
      int anchorAffinity,
      int anchorJoinThreshold,
      int requiredMargin) {
    if (strongAuthority) return true;
    if (explicitParticipantSideAddress || broadPublicSide) return false;
    if (isoldaAffinity - sideAffinity < requiredMargin) return false;
    return isoldaAffinity >= joinThreshold || anchorAffinity >= anchorJoinThreshold;
  }

  /**
   * Zero-token local affinity between one public line and Isolda's active thread.
   * This intentionally combines several weak signals instead of relying on a phrase
   * whitelist: participant references, lexical topic overlap, reply shape, deictic
   * language, question/answer compatibility and recency all contribute.
   */
  static int localThreadAffinity(
      String raw,
      String threadText,
      String previousThreadRaw,
      Set<String> participantNames,
      long ageMs,
      boolean beforeTrigger) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return 0;

    int score = 0;
    boolean referencesParticipant = referencesAnyParticipantAlias(raw, participantNames);
    int topic = topicOverlapScore(raw, threadText, participantNames);

    if (referencesParticipant) score += 28;
    score += topic;
    if (looksLikeSocialThreadInterjection(raw)) score += 18;
    boolean replyShape = looksLikeConversationalReplyShape(raw);
    boolean deictic = containsDeicticReference(raw);
    if (replyShape) score += 8;
    if (deictic) score += 12;
    // A deictic/continuation-shaped sentence arriving immediately after the active
    // exchange is often a reply even when it repeats no nouns ("eso no tiene sentido").
    // This is intentionally generic, not a phrase whitelist; side-thread competition
    // below still wins when the same short reply fits another nearby conversation better.
    if (replyShape || deictic) {
      if (ageMs <= 1500L) score += 14;
      else if (ageMs <= 3000L) score += 8;
      else if (ageMs <= 5000L) score += 4;
    }
    score += questionAnswerCompatibility(raw, previousThreadRaw);
    score += recencyAffinity(ageMs);

    if (looksLikeBroadPublicRequest(raw) && !referencesParticipant) score -= 22;
    if (isVeryShortElliptical(raw) && !referencesParticipant && topic == 0
        && questionAnswerCompatibility(raw, previousThreadRaw) == 0) {
      score -= 8;
    }
    if (beforeTrigger) score -= 8;
    return clampInt(score, 0, 100);
  }

  /**
   * Pairwise zero-token affinity against one strong active-thread anchor (the last
   * Isolda reply or a direct/SMART root line). This is deliberately more sensitive
   * to short echo questions such as "que cosas importantes?" / "que si sientes?"
   * than the broad aggregate score, while side-thread competition still has veto power.
   */
  static int activeThreadLineAffinity(
      String raw,
      String anchorRaw,
      Set<String> participantNames,
      long ageMs) {
    String text = normalizeForSearch(raw);
    String anchor = normalizeForSearch(anchorRaw);
    if (text.isBlank() || anchor.isBlank()) return 0;

    int score = 0;
    int topic = topicOverlapScore(raw, anchorRaw, participantNames);
    boolean referencesParticipant = referencesAnyParticipantAlias(raw, participantNames);
    boolean replyShape = looksLikeConversationalReplyShape(raw);
    boolean deictic = containsDeicticReference(raw);
    boolean question = raw != null && (raw.contains("?")
        || text.matches("^(?:que|como|donde|cuando|quien|cual|por que)\\b.*"));
    int words = text.split("\\s+").length;

    score += topic;
    if (referencesParticipant) score += 22;
    if (replyShape) score += 8;
    if (deictic) score += 10;
    if (question && topic > 0) score += 14;
    if (topic > 0 && words <= 7) score += 8;
    score += recencyAffinity(ageMs);

    return clampInt(score, 0, 100);
  }

  /**
   * Competing affinity with one recent side-chat line. A candidate joins Isolda only
   * when her thread beats this lateral score by a configurable margin.
   */
  static int localLateralAffinity(
      String raw,
      String priorRaw,
      String priorSpeaker,
      long ageMs) {
    String text = normalizeForSearch(raw);
    String prior = normalizeForSearch(priorRaw);
    if (text.isBlank() || prior.isBlank()) return 0;

    int score = 0;
    if (priorSpeaker != null && !priorSpeaker.isBlank()
        && referencesAnyParticipantAlias(raw, Set.of(priorSpeaker))) {
      score += 34;
    }
    score += Math.min(30, topicOverlapScore(raw, priorRaw, Set.of()));
    score += questionAnswerCompatibility(raw, priorRaw);
    if (looksLikeConversationalReplyShape(raw)) score += 8;
    if (containsDeicticReference(raw)) score += 6;
    if (isVeryShortElliptical(raw)) score += 5;
    score += lateralRecencyAffinity(ageMs);
    return clampInt(score, 0, 100);
  }

  static int topicOverlapScore(String a, String b, Set<String> participantNames) {
    Set<String> left = new LinkedHashSet<>(meaningfulTerms(normalizeForSearch(a)));
    Set<String> right = new LinkedHashSet<>(meaningfulTerms(normalizeForSearch(b)));
    if (participantNames != null) {
      for (String name : participantNames) {
        String normalizedName = normalizeForSearch(name);
        if (normalizedName.isBlank()) continue;
        left.remove(normalizedName);
        right.remove(normalizedName);
      }
    }
    left.remove("iso"); left.remove("isolda");
    right.remove("iso"); right.remove("isolda");

    int score = 0;
    for (String l : left) {
      for (String r : right) {
        if (l.equals(r)) {
          score += 12;
          break;
        }
        if (l.length() >= 5 && r.length() >= 5
            && commonPrefixLength(l, r) >= Math.min(5, Math.min(l.length(), r.length()))) {
          score += 7;
          break;
        }
      }
      if (score >= 30) return 30;
    }
    return Math.min(score, 30);
  }

  static boolean looksLikeConversationalReplyShape(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return text.matches("^(?:si|no|nah|pero|porque|pues|igual|tambien|entonces|exacto|claro|obvio|literal|eso|esa|ese|yo|tu|vos|el|ella|ellos|ellas|ustedes)(?:\\s+.*)?$");
  }

  static boolean containsDeicticReference(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return containsAnyWholeWord(text,
        "eso", "esa", "ese", "esto", "aquello", "ahi", "alla", "tambien",
        "igual", "entonces", "el", "ella", "ellos", "ellas", "ustedes");
  }

  static int questionAnswerCompatibility(String replyRaw, String priorRaw) {
    String reply = normalizeForSearch(replyRaw);
    String prior = normalizeForSearch(priorRaw);
    if (reply.isBlank() || prior.isBlank()) return 0;

    boolean priorQuestion = (priorRaw != null && priorRaw.contains("?"))
        || prior.matches("^(?:oye|che|ey|eh)?\\s*(?:que|como|donde|cuando|quien|cual|por que|tienes|tenes|hay|puedes|podes)\\b.*")
        || prior.matches("^(?:oye|che|ey|eh)?\\s*(?:alguien|alguno|alguna|quien)\\b.*\\b(?:tiene|tienen|tenga|hay)\\b.*");
    if (!priorQuestion) return 0;

    int score = 0;
    if (reply.matches("^(?:si|no|nah|claro|obvio|exacto|yo|yo si|yo no)(?:\\s+.*)?$")) score += 18;
    if (prior.matches(".*\\b(?:alguien|quien|alguno|alguna)\\b.*\\b(?:tiene|tienen|tenga|hay)\\b.*")
        && reply.matches("^(?:yo|yo tengo|tengo|aca|aqui|yo si)(?:\\s+.*)?$")) {
      score += 22;
    }
    int topic = topicOverlapScore(replyRaw, priorRaw, Set.of());
    score += Math.min(12, topic);
    return Math.min(score, 34);
  }

  static boolean looksLikeBroadPublicRequest(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return text.matches("^(?:oye|che|ey|eh)?\\s*(?:alguien|alguno|alguna|quien)\\b.*")
        || text.matches("^(?:alguien|alguno|alguna|quien)\\b.*");
  }

  static boolean isVeryShortElliptical(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return true;
    return text.split("\\s+").length <= 3;
  }

  private static int recencyAffinity(long ageMs) {
    if (ageMs <= 1200L) return 14;
    if (ageMs <= 3000L) return 11;
    if (ageMs <= 5000L) return 7;
    if (ageMs <= 8000L) return 3;
    return 0;
  }

  private static int lateralRecencyAffinity(long ageMs) {
    if (ageMs <= 1200L) return 15;
    if (ageMs <= 3000L) return 12;
    if (ageMs <= 5000L) return 8;
    if (ageMs <= 8000L) return 4;
    return 0;
  }

  static boolean looksLikeGroupContinuation(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return looksLikeConversationalReplyShape(raw)
        && !text.matches("^(?:yo tengo|tengo|yo|aca|aqui)$");
  }

  static boolean looksLikeSocialThreadInterjection(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return containsAnyTerm(text,
        "mientes", "mentira", "no es cierto", "dejate", "deja de", "exagera",
        "calmate", "tranquilo", "te creo", "no te creo", "tiene razon",
        "no tiene razon", "estoy de acuerdo", "no estoy de acuerdo", "eso es verdad",
        "que dices", "haces que estas bien", "hacerte el que esta bien");
  }

  static boolean referencesAnyParticipantAlias(String raw, Set<String> participantNames) {
    if (raw == null || raw.isBlank() || participantNames == null || participantNames.isEmpty()) return false;
    String normalized = normalizeForSearch(raw);
    for (String name : participantNames) {
      if (name == null || name.isBlank()) continue;
      if (ContextTargetResolver.mentionsName(normalized, name)) return true;
      String canonical = normalizeForSearch(name).replace(" ", "");
      if (canonical.length() < 6) continue;
      for (String token : normalized.split("\\s+")) {
        // Human group-chat nicknames such as Aminowana -> "Amino" are safe here
        // because the candidate set is already limited to the tiny active thread.
        if (token.length() >= 5 && canonical.startsWith(token)) return true;
      }
    }
    return false;
  }

  static boolean sharesConversationTopic(String a, String b, Set<String> participantNames) {
    return topicOverlapScore(a, b, participantNames) > 0;
  }

  static boolean looksLikeExplicitGroupBridge(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return text.matches("^(?:contale|cuentale|decile|dile|preguntale|respond(?:e|ele)|avisale|explicale|mostrale|muestrale)(?:\\s+.*)?$")
        || text.matches("^(?:si|no|exacto|tal cual|eso mismo)\\s+(?:iso|isolda)(?:\\s+.*)?$");
  }

  /** Local score for whether a SMART holder's next line is actually a continuation. */
  static int smartFollowUpAffinity(
      String raw,
      String previousPlayerRaw,
      String previousAssistantRaw,
      Set<String> participantNames,
      long ageMs) {
    String thread = ((previousPlayerRaw == null ? "" : previousPlayerRaw) + " "
        + (previousAssistantRaw == null ? "" : previousAssistantRaw)).trim();
    int score = localThreadAffinity(
        raw, thread, previousAssistantRaw, participantNames,
        Math.min(Math.max(ageMs, 0L), 8001L), false);

    String text = normalizeForSearch(raw);
    String assistant = normalizeForSearch(previousAssistantRaw);
    if (text.isBlank()) return 0;

    // A short answer after Isolda asked something is normally a continuation even
    // when it shares no nouns ("bien gracias", "voy a minar"). Broad public calls
    // are deliberately excluded from this bonus.
    boolean previousWasQuestion = previousAssistantRaw != null
        && (previousAssistantRaw.contains("?")
            || assistant.matches("^(?:que|como|donde|cuando|quien|cual|por que)\\b.*")
            || assistant.matches(".*\\b(?:y tu|y vos)$"));
    int words = text.split("\\s+").length;
    if (previousWasQuestion && words <= 12 && !looksLikeBroadSmartSideRequest(raw)) {
      score += 20;
    }

    // Generic second-person follow-ups ("y tu?", "que haces?", "que opinas?")
    // are strong evidence because the only conversational addressee represented by
    // this SMART anchor is Isolda unless another player is explicitly addressed.
    if (looksLikeSecondPersonFollowUp(raw)) score += 22;

    // Avoid interpreting "yo tengo" as an answer to an unrelated social question.
    // It should instead be free to attach to a nearby "alguien tiene piedra?" side thread.
    if (text.matches("^(?:yo\\s+)?tengo(?:\\s+.*)?$")
        && !assistant.matches(".*\\b(?:tienes|tenes|tiene|tengo|hay|llevas|llevas)\\b.*")) {
      score -= 22;
    }

    // SMART can last longer than the 8s group lookback. Keep a tiny recency signal
    // for the remainder of the configured relationship-based follow-up window.
    if (ageMs > 8000L && ageMs <= 15_000L) score += 5;
    else if (ageMs > 15_000L && ageMs <= 30_000L) score += 2;

    return clampInt(score, 0, 100);
  }

  static boolean looksLikeSecondPersonFollowUp(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    return containsAnyWholeWord(text, "tu", "vos", "tuyo", "tuya", "contigo")
        || text.matches("^(?:y\\s+)?(?:que|como|donde|cuando|por que)\\s+(?:haces|estas|andas|piensas|opinas|sientes|quieres|queres|vas)\\b.*")
        || text.matches("^(?:y\\s+)?(?:te|a ti)\\b.*");
  }

  /** Broad request clearly aimed at public chat, not merely any sentence starting "alguien". */
  static boolean looksLikeBroadSmartSideRequest(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return false;
    boolean anyoneLead = text.matches("^(?:oye|che|ey|eh)?\\s*(?:alguien|alguno|alguna)\\b.*");
    boolean whoLead = text.matches("^(?:oye|che|ey|eh)?\\s*quien\\b.*");
    if (!anyoneLead && !whoLead) return false;
    boolean crowdAction = text.matches(".*\\b(?:tiene|tienen|vende|venden|compra|compran|sabe|saben|puede|pueden|quiere|quieren|va|van|hay|presta|regala|da|dame)\\b.*");
    // "quien es Tablos?" is a perfectly normal SMART question to Isolda;
    // "quien tiene piedra?" is clearly addressed to the public room.
    return crowdAction || (anyoneLead && raw != null && raw.contains("?"));
  }

  /**
   * Conservative direct-recipient detector. Mentioning somebody as a topic does not
   * count; the line must begin by addressing that player and then use a second-person
   * question/request shape ("Wachi ven", "Pedro dame", "Amino que haces?").
   */
  static boolean looksLikeExplicitOtherPlayerAddress(String raw, Set<String> otherPlayerNames) {
    if (raw == null || raw.isBlank() || otherPlayerNames == null || otherPlayerNames.isEmpty()) return false;
    String text = normalizeForSearch(raw);
    String stripped = text.replaceFirst("^(?:oye|che|ey|eh)\\s+", "");
    for (String name : otherPlayerNames) {
      if (name == null || name.isBlank()) continue;
      String canonical = normalizeForSearch(name).replace(" ", "");
      if (canonical.length() < 3) continue;
      String[] parts = stripped.split("\\s+", 2);
      if (parts.length < 2) continue;
      String first = parts[0].replaceAll("[^\\p{L}\\p{N}_]", "");
      boolean matchesName = first.equals(canonical)
          || (canonical.length() >= 6 && first.length() >= 4 && canonical.startsWith(first));
      if (!matchesName) continue;
      String rest = parts[1];
      if (rest.matches("^(?:ven|veni|vente|dame|dime|decime|anda|ve|mira|mirame|espera|esperame|ayuda|ayudame|pasame|presta|prestame|tp|tpa|vamos|callate|deja|dejame|haz|hace|haceme|tienes|tenes|puedes|podes|quieres|queres)\\b.*")
          || rest.matches("^(?:que|como|donde|cuando|por que)\\s+(?:haces|estas|andas|piensas|opinas|vas|tienes|tenes|quieres|queres)\\b.*")) {
        return true;
      }
    }
    return false;
  }

  static boolean isTrivialSmartAcknowledgement(String raw) {
    String text = normalizeForSearch(raw);
    if (text.isBlank()) return true;
    return text.matches("^(?:x+d+|xd+|(?:ja){2,}|(?:je){2,}|(?:ji){2,}|lol|lmao|ok|okay|oki|dale|va|joya|nice|ah|ahh|uh|uhh|mmm|mhm)$");
  }
}
