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

    // Do not punish an immediately retracted joke such as "te odio era mentira jaja".
    boolean retractedHostility = containsAny(text,
        "te odio era mentira", "era mentira te odio", "te odio mentira", "te odio es broma",
        "te odio era broma", "te odio jaja era mentira");

    // Strong direct hostility. Keep this deterministic fallback conservative, but
    // cover the obvious insults seen in real server tests when the model omits r.
    if (!retractedHostility && containsAny(text,
        "iso eres una puta", "isolda eres una puta", "eres una puta", "puta de mierda",
        "te odio", "te oido con todo mi corazon", "te odio con todo mi corazon",
        "eres una desgraciada", "eres lo peor que me ha pasado", "te mereces lo peor",
        "eres basura", "eres horrible", "eres muy fea", "eres fea y mala",
        "persona mas fea", "maldita isolda", "maldita iso")) {
      return new RelationshipUpdate(playerName, -1, "r", 2,
          "Insulto o mostro hostilidad directa hacia Isolda", "");
    }

    // Ordinary compliment: affects score but is not important enough to fill memory.
    if (containsAny(text,
        "que linda te ves", "qué linda te ves", "que hermosa te ves", "qué hermosa te ves",
        "eres linda iso", "eres linda isolda", "eres hermosa iso", "eres hermosa isolda",
        "chica mas linda", "la mas linda del server", "eres la mejor",
        "grandiosa isolda", "linda isolda")) {
      return new RelationshipUpdate(playerName, 1, "n", 1, "", "");
    }

    return null;
  }

  /**
   * Messages that talk ABOUT the relationship, greet Isolda, or are tiny reactions
   * must not change score merely because the existing tier is positive/negative.
   * This blocks self-reinforcing loops such as -90 -> -91 for "por que me tratas asi?"
   * or 90 -> 91 for a bare "holaa iso".
   */
  public static boolean isBookkeepingNeutral(String rawContent) {
    String text = normalize(rawContent);
    if (text.isBlank()) return true;
    if (detect("player", rawContent) != null || isExplicitPartnershipProposal(rawContent)) return false;

    if (containsAny(text,
        "hola", "hola iso", "hola isolda", "holaa", "holaa iso", "holaa isolda",
        "holi", "holi iso", "holi isolda", "buenas", "iso estas ahi", "isolda estas ahi",
        "como estas", "como andas", "como te va", "que tal",
        "que opinas de mi", "por que me tratas asi", "porque me tratas asi", "pq me tratas asi",
        "por que eres mala conmigo", "porque eres mala conmigo", "pq eres mala conmigo",
        "por que eres asi conmigo", "porque eres asi conmigo", "pq eres asi conmigo",
        "me quieres", "aun me quieres", "todavia me quieres", "que somos",
        "lo amas", "la amas", "nuestro amor", "tu amor con",
        "como te llevas con", "como te cae", "que sientes por",
        "quien es tu pareja", "qn es tu pareja", "quien es tu novio", "quien es tu novia",
        "tienes pareja", "tienes novio", "tienes novia",
        "te queria decir algo", "por eso me gustaria preguntar", "cual es tu respuesta",
        "necesito una respuesta", "de lo que te dije", "muy seguro", "por supuesto",
        "asi es el amor", "defiendeme", "defiendeme iso", "me odia",
        "es tu enemigo", "tu enemigo", "decile algo", "dile algo",
        "que pasa si mato", "q pasa si mato", "sono como un halago", "sono como un alago",
        "no me hagas llorar", "mi ex me trataba mejor",
        "te odio era mentira", "te odio era broma", "te odio es broma")) {
      return true;
    }

    // Pure information/tool/history requests are not interpersonal events. The model
    // may answer them differently because of the tier, but that tier must not mutate
    // just because the player asked for a recipe, inventory view or absence summary.
    if (containsAny(text,
        "que es ", "q es ", "como se craftea", "como crafteo", "como consigo",
        "de donde consigo", "como se hace", "que comandos", "q comandos",
        "inventario", "que tiene ", "que tengo ", "mi nivel", "nivel de ",
        "donde esta ", "que hizo ", "que ha hecho ", "que paso mientras",
        "mientras no estaba", "mientras no estuve", "que me perdi", "hazme un resumen",
        "resumen de que paso", "tirale un rayo", "tira un rayo", "rayo a ",
        "mutea a ", "mutealo", "banealo", "banea a ", "en segundos")) {
      return true;
    }

    // normalize() strips ':' from emoticons, so "queee :c" becomes "queee c".
    // Treat tiny reaction + emoticon combinations as neutral bookkeeping too.
    return text.matches("^(iso |isolda )?(?:que+|xd+|jaja+|jeje+|mmm+|uff+)(?: [cuv3])?$")
        || text.matches("^(iso |isolda )?[cuv3]$");
  }

  /** Reject tier-descriptions/attitude text that are not memories of an event. */
  public static boolean isGenericMemorySummary(String rawMemory) {
    String text = normalize(rawMemory);
    if (text.isBlank()) return false;
    return containsAny(text,
        "confianza y afecto profundos", "confianza y afecto profundo",
        "confianza real y afecto", "es una persona cercana", "es un amigo cercano",
        "me gusta burlarme de el", "me gusta burlarme de ella",
        "insufferable como siempre", "insoportable como siempre");
  }

  public static boolean isExplicitPartnershipProposal(String rawContent) {
    String text = normalize(rawContent);
    return containsAny(text,
        "quieres ser mi novia", "quieres ser mi novio", "se mi novia", "se mi novio",
        "quieres ser mi pareja", "seamos pareja", "casate conmigo", "casarnos",
        "matrimonio", "pedir tu mano",
        "aceptas ser mi novia", "aceptas ser mi novio", "aceptas ser mi pareja",
        "aceptarias ser mi novia", "aceptarias ser mi novio", "aceptarias ser mi pareja",
        "te pregunte si querias ser mi novia", "te pregunte si querias ser mi novio",
        "te pregunte si querias ser mi pareja", "te pregunte si aceptabas ser mi novia",
        "te pregunte si aceptabas ser mi novio");
  }

  public static boolean replyClearlyAcceptsPartnership(String rawReply) {
    String text = normalize(rawReply);
    if (text.equals("si") || text.equals("si quiero") || text.equals("acepto")
        || text.equals("claro") || text.equals("por supuesto") || text.equals("me encantaria")) {
      return true;
    }
    return containsAny(text,
        "si acepto", "acepto ser tu novia", "acepto ser tu novio",
        "quiero ser tu novia", "quiero ser tu novio", "seamos novios", "seamos pareja",
        "soy tu novia", "soy tu novio", "claro que si quiero", "si quiero ser tu pareja",
        "me encantaria ser tu novia", "me encantaria ser tu novio", "me encantaria ser tu pareja",
        "desde hoy somos pareja", "desde ahora somos pareja", "trato hecho soy tu novia",
        "trato hecho soy tu novio", "entonces somos novios", "entonces somos pareja");
  }

  public static boolean replyClearlyRejectsPartnership(String rawReply) {
    String text = normalize(rawReply);
    if (text.equals("no") || text.equals("no quiero") || text.equals("prefiero que no")) return true;
    return containsAny(text,
        "no puedo aceptar", "no quiero ser tu novia", "no quiero ser tu novio",
        "no quiero ser tu pareja", "prefiero que no", "todavia no", "aun no",
        "no estoy buscando un noviazgo", "no estoy lista para eso", "no estoy listo para eso",
        "no siento lo mismo", "solo amistad", "somos solo amigos", "eres solo un amigo",
        "eres solo una amiga", "ya tengo pareja");
  }

  public static boolean isPartnerIdentityQuestion(String rawContent) {
    String text = normalize(rawContent);
    return containsAny(text,
        "quien es tu pareja", "qn es tu pareja", "quienes son tus parejas",
        "quien es tu novio", "qn es tu novio", "quien es tu novia", "qn es tu novia",
        "tienes pareja", "tienes novio", "tienes novia", "con quien estas de pareja");
  }

  private static boolean isRomanticProposalOrDate(String text) {
    return containsAny(text,
        "quieres ser mi novia", "quieres ser mi novio", "se mi novia", "se mi novio",
        "quieres ser mi pareja", "seamos pareja", "sal conmigo", "casate conmigo",
        "aceptas ser mi novia", "aceptas ser mi novio", "aceptas ser mi pareja",
        "aceptarias ser mi novia", "aceptarias ser mi novio", "aceptarias ser mi pareja",
        "te pregunte si querias ser mi novia", "te pregunte si querias ser mi novio",
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
