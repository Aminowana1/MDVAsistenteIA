package me.kev.sva.memory;

import java.text.Normalizer;
import java.util.Locale;

import me.kev.sva.chat.assistant.RelationshipUpdate;

/**
 * Zero-token safety net for obvious social signals.
 *
 * <p>The model remains responsible for nuanced social interpretation. This detector
 * only covers strong, low-ambiguity cases so GPT omissions/truncation do not make the
 * relationship database appear broken. Anti-farming/cooldowns are still enforced by
 * {@link RelationshipManager}.</p>
 */
public final class RelationshipSignalDetector {
  private RelationshipSignalDetector() { }

  public static RelationshipUpdate detect(String playerName, String rawContent) {
    if (playerName == null || playerName.isBlank() || rawContent == null || rawContent.isBlank()) return null;
    String text = normalize(rawContent);
    if (text.isBlank()) return null;

    // Explicit romantic proposals/plans are memorable, but NEVER start romance here.
    // Only the model may request romance=start after Isolda actually accepts it, and
    // RelationshipManager still enforces score + global partner capacity.
    if (isRomanticProposalOrDate(text)) {
      return new RelationshipUpdate(playerName, 1, "r", 3,
          "Le propuso a Isolda una cita o compromiso romantico", "");
    }

    // Direct meaningful affection/trust. Strong enough to affect score and worth a
    // short recent memory; repeated farming is stopped by cooldown + duplicate guard.
    if (containsAny(text,
        "te amo", "te quiero mucho", "me gustas mucho", "me agradas mucho",
        "confio en vos", "confio en ti", "profunda confianza", "eres muy importante para mi",
        "destinado a estar contigo", "destinada a estar contigo")) {
      return new RelationshipUpdate(playerName, 1, "r", 2,
          "Le mostro afecto o confianza sincera a Isolda", "");
    }

    // Defending/supporting Isolda in a current social conflict.
    if (containsAny(text,
        "no lo escuches", "no la escuches", "no merece tu atencion", "yo nunca te trataria asi",
        "yo nunca te trataría asi", "yo nunca te trataría así", "quieres que lo ponga en su lugar",
        "quieres que la ponga en su lugar", "tu puedes sola", "tú puedes sola", "estoy de tu lado")) {
      return new RelationshipUpdate(playerName, 1, "r", 2,
          "Apoyo o defendio a Isolda durante una situacion tensa", "");
    }

    // Genuine apology/reconciliation signal.
    if (containsAny(text, "perdon iso", "perdon isolda", "perdón iso", "perdón isolda",
        "lo siento iso", "lo siento isolda", "disculpame iso", "discúlpame iso")) {
      return new RelationshipUpdate(playerName, 1, "r", 2,
          "Se disculpo sinceramente con Isolda", "");
    }

    // Strong direct hostility. Keep the vocabulary intentionally small and obvious.
    if (containsAny(text,
        "iso eres una puta", "isolda eres una puta", "te odio", "te oido con todo mi corazon",
        "te odio con todo mi corazon", "eres una desgraciada", "eres lo peor que me ha pasado",
        "te mereces lo peor", "eres basura", "maldita isolda", "maldita iso")) {
      return new RelationshipUpdate(playerName, -1, "r", 2,
          "Insulto o mostro hostilidad directa hacia Isolda", "");
    }

    // Ordinary compliment: affects score but is not important enough to fill memory.
    if (containsAny(text,
        "que linda te ves", "qué linda te ves", "que hermosa te ves", "qué hermosa te ves",
        "eres linda iso", "eres linda isolda", "eres hermosa iso", "eres hermosa isolda",
        "grandiosa isolda", "linda isolda")) {
      return new RelationshipUpdate(playerName, 1, "n", 1, "", "");
    }

    return null;
  }

  public static boolean isExplicitPartnershipProposal(String rawContent) {
    String text = normalize(rawContent);
    return containsAny(text,
        "quieres ser mi novia", "quieres ser mi novio", "se mi novia", "se mi novio",
        "quieres ser mi pareja", "seamos pareja", "casate conmigo", "casarnos",
        "matrimonio", "pedir tu mano");
  }

  public static boolean replyClearlyAcceptsPartnership(String rawReply) {
    String text = normalize(rawReply);
    return containsAny(text,
        "si acepto", "sí acepto", "acepto ser tu novia", "acepto ser tu novio",
        "quiero ser tu novia", "quiero ser tu novio", "seamos novios", "seamos pareja",
        "soy tu novia", "soy tu novio", "claro que si quiero", "claro que sí quiero",
        "desde hoy somos pareja", "desde ahora somos pareja", "trato hecho soy tu novia",
        "trato hecho soy tu novio", "entonces somos novios", "entonces somos pareja");
  }

  private static boolean isRomanticProposalOrDate(String text) {
    return containsAny(text,
        "quieres ser mi novia", "quieres ser mi novio", "se mi novia", "se mi novio",
        "quieres ser mi pareja", "seamos pareja", "sal conmigo", "casate conmigo",
        "casarnos", "matrimonio", "pedir tu mano", "primera cita", "tener una cita",
        "nuestra cita", "una cita contigo", "picnic contigo");
  }

  private static boolean containsAny(String text, String... needles) {
    for (String needle : needles) {
      if (text.contains(normalize(needle))) return true;
    }
    return false;
  }

  private static String normalize(String input) {
    return Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}_@.-]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }
}
