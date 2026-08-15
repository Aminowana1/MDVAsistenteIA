package me.kev.sva.integrations;

import java.text.Normalizer;
import java.util.Locale;

/** Compact intent flags used by optional player-profile integrations. */
public record ProfileQuery(
    boolean general,
    boolean identity,
    boolean title,
    boolean professions,
    boolean attributes,
    boolean stats,
    boolean resources,
    boolean points) {

  public static ProfileQuery from(String input) {
    String text = normalize(input);
    boolean general = containsAny(text,
        "perfil", "profile", "quien soy", "quien es", "que soy", "datos de", "info de", "informacion de");
    boolean identity = general || containsAny(text,
        "raza", "races", "race", "clase", "class", "nivel", "level", "experiencia", " xp ", "exp ");
    boolean title = general || containsAny(text,
        "titulo", "titulos", "title", "titles", "apodo", "distintivo", "titulo equipado", "titulo activo");
    boolean professions = general || containsAny(text,
        "profesion", "profesiones", "profession", "professions", "trabajo", "oficio",
        "minero", "mineria", "mining", "lenador", "tala", "woodcut", "agricultor", "agricultura", "farming",
        "pescador", "pesca", "fishing", "cazador", "caza", "hunting", "alchemy", "alquimia", "smelting", "forja");
    boolean attributes = general || containsAny(text,
        "atributo", "atributos", "attribute", "attributes", "fuerza", "destreza", "inteligencia", "strength", "dex", "int");
    boolean stats = containsAny(text,
        "stat", "stats", "estadistica", "estadisticas", "armadura", "armor", "toughness", "dano", "damage",
        "magico", "magic damage", "velocidad de ataque", "attack speed");
    boolean resources = containsAny(text,
        "mana", "stamina", "aguante", "stellium", "recurso", "recursos", "resource", "energia");
    boolean points = containsAny(text,
        "puntos de habilidad", "skill points", "puntos de clase", "class points", "puntos de atributo", "attribute points");
    return new ProfileQuery(general, identity, title, professions, attributes, stats, resources, points);
  }

  public static ProfileQuery all() {
    return new ProfileQuery(true, true, true, true, true, true, true, true);
  }

  public boolean any() {
    return general || identity || title || professions || attributes || stats || resources || points;
  }

  private static boolean containsAny(String text, String... terms) {
    String padded = " " + text + " ";
    for (String term : terms) {
      String normalized = normalize(term);
      if (normalized.isBlank()) continue;
      if (text.contains(normalized) || padded.contains(" " + normalized + " ")) return true;
    }
    return false;
  }

  public static String normalize(String input) {
    return Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}_@%.-]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }
}
