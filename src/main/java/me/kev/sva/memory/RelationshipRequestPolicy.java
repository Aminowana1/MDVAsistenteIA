package me.kev.sva.memory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.UUID;

/**
 * Pure zero-token helper for relationship-driven request refusal.
 *
 * <p>The model still writes the natural dialogue, but Java decides once per scene
 * whether a hostile player is willing to receive compliance. The decision is
 * deterministic for the same scene/player/message, so context building, wiki/tool
 * filtering and action authorization cannot disagree with each other.</p>
 */
public final class RelationshipRequestPolicy {
  private RelationshipRequestPolicy() { }

  /** Returns true only for a current line that actually asks Isolda for something. */
  public static boolean looksLikeRequest(String raw) {
    if (raw == null || raw.isBlank()) return false;
    String text = normalize(raw);
    if (text.isBlank()) return false;

    // A real question mark is the cheapest strong signal and covers free-form wording.
    if (raw.indexOf('?') >= 0 || raw.indexOf('¿') >= 0) return true;

    return startsWithAny(text,
        "iso ", "isolda ") && containsAny(text,
            " como ", " que ", " q ", " cual ", " quien ", " donde ", " cuando ", " cuanto ", " por que ", " pq ",
            " dime", " decime", " me dices", " me decis", " puedes", " podrias", " quiero que", " revisa", " revisame",
            " busca", " buscame", " averigua", " contame", " cuentame", " explicame", " explica", " ensename", " mostrame", " muestrame",
            " ayudame", " ayuda", " dame", " ponme", " tirame", " haz ", " hace ", " saluda", " dile ", " decile ",
            " riete", " burlate", " defiendeme", " protege", " mata ", " mát", " mute", " silencia", " recuerda", " avisame")
        || startsWithAny(text,
            "como ", "que ", "q ", "cual ", "quien ", "donde ", "cuando ", "cuanto ", "por que ", "pq ",
            "dime ", "decime ", "me dices ", "me decis ", "puedes ", "podrias ", "quiero que ", "revisa ", "busca ",
            "averigua ", "contame ", "cuentame ", "explica ", "ensename ", "mostrame ", "muestrame ", "ayudame ", "dame ", "ponme ",
            "tirame ", "haz ", "hace ", "saluda ", "dile ", "decile ", "riete ", "burlate ", "defiendeme ",
            "protege ", "mata ", "mute ", "silencia ", "recuerdame ", "avisame ");
  }

  /**
   * Stable pseudo-random refusal for one scene. chance=0 always allows; chance=1
   * always refuses. No mutable RNG state and no model request are involved.
   */
  public static boolean shouldRefuse(
      double refusalChance,
      long sceneId,
      UUID playerId,
      String rawRequest) {
    if (refusalChance <= 0.0D) return false;
    if (refusalChance >= 1.0D) return true;
    if (playerId == null || !looksLikeRequest(rawRequest)) return false;

    long seed = sceneId * 0x9E3779B97F4A7C15L;
    seed ^= playerId.getMostSignificantBits();
    seed = Long.rotateLeft(seed, 17) ^ playerId.getLeastSignificantBits();
    seed ^= (long) normalize(rawRequest).hashCode() * 0xC2B2AE3D27D4EB4FL;
    long mixed = mix64(seed);
    double draw = (mixed >>> 11) * 0x1.0p-53;
    return draw < refusalChance;
  }

  static String normalize(String input) {
    if (input == null) return "";
    String normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replace('¿', ' ')
        .replace('?', ' ')
        .replaceAll("[^a-z0-9_ ]", " ")
        .replaceAll("\\s+", " ")
        .trim();
    return normalized;
  }

  private static boolean startsWithAny(String text, String... values) {
    for (String value : values) if (text.startsWith(value)) return true;
    return false;
  }

  private static boolean containsAny(String text, String... values) {
    for (String value : values) if (text.contains(value)) return true;
    return false;
  }

  private static long mix64(long z) {
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    return z ^ (z >>> 31);
  }
}
