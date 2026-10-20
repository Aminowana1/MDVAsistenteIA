package me.kev.sva.chat;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Text helpers shared by scene building, threading, wiki retrieval and reply shaping. */
final class ChatText {
  private ChatText() {
  }

  static String normalizeForSearch(String input) {
    String normalized = Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}_-]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
    return normalized;
  }

  static Set<String> meaningfulTerms(String text) {
    Set<String> stop = Set.of(
        "que", "como", "donde", "cuando", "quien", "para", "por", "con", "una", "uno", "unos",
        "unas", "del", "las", "los", "eso", "esta", "este", "esto", "soy", "eres", "hay", "iso",
        "isolda", "se", "un", "de", "en", "mi", "mis", "su", "sus", "es", "son", "quiero",
        "craftea", "craftear", "crafteo", "crafteos", "craft", "dame", "mostrame", "muestrame",
        "consigo", "consigue", "conseguir",
        "obtengo", "obtiene", "obtener", "encuentro", "encontrar", "sirve", "sirven",
        "dices", "decis", "dirias", "decir", "decirme", "puedes", "podes", "podrias", "podria", "explicas",
        "sabrias", "tenes", "tienes", "idea", "ayudas", "ayudarias", "ayudar",
        "explicar", "cuentas", "contar", "quisiera", "queria", "otro", "otros", "otra", "otras",
        "demas", "mejor", "mejores", "clase", "clases", "alli", "ahi", "hasta",
        "llego", "llegar", "voy", "ir", "entro", "vuelvo", "viajo", "viajar", "wiki",
        "the", "and", "what", "where", "how", "when",
        "you", "your");
    Set<String> result = new LinkedHashSet<>();
    for (String token : text.split("\\s+")) {
      if (token.length() >= 3 && !stop.contains(token)) {
        result.add(token);
      }
    }
    return result;
  }

  static boolean containsAnyTerm(String text, String... terms) {
    for (String term : terms) {
      if (text.contains(term)) return true;
    }
    return false;
  }

  static boolean containsWholeWordIgnoreCase(String text, String word) {
    if (text == null || word == null || word.isBlank()) return false;
    Pattern pattern = Pattern.compile(
        "(?iu)(?<![\\p{L}\\p{N}_])" + Pattern.quote(word) + "(?![\\p{L}\\p{N}_])");
    return pattern.matcher(text).find();
  }

  static boolean containsAnyWholeWord(String text, String... words) {
    for (String word : words) {
      if (containsWholeWordIgnoreCase(text, word)) return true;
    }
    return false;
  }

  static boolean containsAllTerms(String haystack, Set<String> terms) {
    if (haystack == null || haystack.isBlank() || terms == null || terms.isEmpty()) return false;
    for (String term : terms) {
      if (!containsWholeWordIgnoreCase(haystack, term)) return false;
    }
    return true;
  }

  static Set<String> tokenSet(String text) {
    LinkedHashSet<String> tokens = new LinkedHashSet<>();
    if (text == null) return tokens;
    for (String token : text.split("\\s+")) {
      if (token.length() >= 3) tokens.add(token);
    }
    return tokens;
  }

  static int commonPrefixLength(String a, String b) {
    int max = Math.min(a.length(), b.length());
    int i = 0;
    while (i < max && a.charAt(i) == b.charAt(i)) i++;
    return i;
  }

  static int clampInt(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
