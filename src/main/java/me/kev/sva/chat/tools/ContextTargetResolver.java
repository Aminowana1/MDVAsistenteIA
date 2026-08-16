package me.kev.sva.chat.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;

/**
 * Small deterministic helper for local CONTEXT tools.
 *
 * <p>If the latest player line explicitly names another involved player, that
 * player is treated as the query target. Otherwise the latest speaker is first.
 * This avoids context such as "where is Tablos?" being answered from the
 * requester's own location just because the requester was inserted first.</p>
 */
public final class ContextTargetResolver {
  private ContextTargetResolver() {
  }

  public static List<String> resolve(
      List<String> involvedPlayerNames,
      String normalizedSceneText,
      List<ChatMessage> currentSceneMessages,
      int maxPlayers) {

    int limit = Math.max(maxPlayers, 1);
    if (involvedPlayerNames == null || involvedPlayerNames.isEmpty()) return List.of();

    List<String> candidates = involvedPlayerNames.stream()
        .filter(name -> name != null && !name.isBlank())
        .distinct()
        .toList();
    if (candidates.isEmpty()) return List.of();

    String latestText = "";
    String latestSpeaker = "";
    if (currentSceneMessages != null) {
      for (int i = currentSceneMessages.size() - 1; i >= 0; i--) {
        ChatMessage message = currentSceneMessages.get(i);
        if (message instanceof PlayerChatMessage playerMessage) {
          latestText = ToolManager.normalize(playerMessage.content);
          latestSpeaker = playerMessage.playerName;
          break;
        }
      }
    }

    Set<String> explicit = new LinkedHashSet<>();
    if (!latestText.isBlank()) {
      for (String candidate : candidates) {
        if (mentionsName(latestText, candidate)) {
          explicit.add(candidate);
        }
      }
    }

    List<String> ordered = new ArrayList<>();
    if (!explicit.isEmpty()) {
      ordered.addAll(explicit);
      // An explicit third-person target should not be diluted with requester data.
      return List.copyOf(ordered.subList(0, Math.min(limit, ordered.size())));
    }

    // First-person follow-ups ("y yo?", "que tengo en mi mano?") must stay on
    // the speaker even if an older line in the same lookback mentioned someone else.
    if (looksFirstPerson(latestText) && !latestSpeaker.isBlank()) {
      for (String candidate : candidates) {
        if (candidate.equalsIgnoreCase(latestSpeaker)) return List.of(candidate);
      }
    }

    // If the latest line did not name anyone and is not first-person, a name
    // mentioned elsewhere in the filtered scene can preserve a multi-line target.
    if (normalizedSceneText != null && !normalizedSceneText.isBlank()) {
      for (String candidate : candidates) {
        if (mentionsName(normalizedSceneText, candidate)) {
          explicit.add(candidate);
        }
      }
      if (!explicit.isEmpty()) {
        ordered.addAll(explicit);
        return List.copyOf(ordered.subList(0, Math.min(limit, ordered.size())));
      }
    }

    if (!latestSpeaker.isBlank()) {
      for (String candidate : candidates) {
        if (candidate.equalsIgnoreCase(latestSpeaker)) {
          ordered.add(candidate);
          break;
        }
      }
    }
    for (String candidate : candidates) {
      if (ordered.stream().noneMatch(existing -> existing.equalsIgnoreCase(candidate))) {
        ordered.add(candidate);
      }
      if (ordered.size() >= limit) break;
    }
    return List.copyOf(ordered);
  }

  private static boolean looksFirstPerson(String text) {
    if (text == null || text.isBlank()) return false;
    String padded = " " + text + " ";
    return padded.contains(" yo ")
        || padded.contains(" mi ")
        || padded.contains(" me ")
        || padded.contains(" mio ")
        || padded.contains(" mia ")
        || padded.contains(" tengo ")
        || padded.contains(" llevo ")
        || padded.contains(" estoy ")
        || padded.contains(" soy ")
        || padded.contains(" dame ")
        || padded.contains(" dime ");
  }



  /**
   * Resolves common human ways of writing Minecraft names without turning target
   * selection into a broad fuzzy search. Exact names still win, then a compact
   * no-space form handles names such as "En3Minutos" written as "en 3 minutos".
   * Finally, only the alphabetic stem of an involved name may match a single
   * similarly-sized token with a tiny edit distance (e.g. WITHE9033 -> "white").
   *
   * <p>The candidate set is already restricted to players involved/online in the
   * scene, so this does not search arbitrary offline identities.</p>
   */
  public static boolean mentionsName(String normalizedText, String candidateName) {
    if (mentionsNameStrict(normalizedText, candidateName)) return true;
    if (normalizedText == null || candidateName == null
        || normalizedText.isBlank() || candidateName.isBlank()) return false;

    String text = ToolManager.normalize(normalizedText);
    String candidate = ToolManager.normalize(candidateName);
    String compactCandidate = candidate.replaceAll("[^\\p{L}\\p{N}]+", "");
    String alphaCandidate = compactCandidate.replaceAll("[^\\p{L}]+", "");
    if (alphaCandidate.length() < 4) return false;

    int allowedDistance = alphaCandidate.length() >= 5 ? 2 : 1;
    for (String rawToken : text.split("\\s+")) {
      String token = rawToken.replaceAll("[^\\p{L}]+", "");
      if (token.length() < 4) continue;
      if (Math.abs(token.length() - alphaCandidate.length()) > allowedDistance) continue;
      if (levenshteinWithin(token, alphaCandidate, allowedDistance)) return true;
    }
    return false;
  }

  /**
   * Exact/compact form only; safe to use while scanning every online player.
   */
  public static boolean mentionsNameStrict(String normalizedText, String candidateName) {
    if (normalizedText == null || candidateName == null
        || normalizedText.isBlank() || candidateName.isBlank()) return false;

    String text = ToolManager.normalize(normalizedText);
    String candidate = ToolManager.normalize(candidateName);
    if (candidate.isBlank()) return false;
    if (containsWholeToken(text, candidate)) return true;

    String compactText = text.replaceAll("[^\\p{L}\\p{N}]+", "");
    String compactCandidate = candidate.replaceAll("[^\\p{L}\\p{N}]+", "");
    return compactCandidate.length() >= 4 && compactText.contains(compactCandidate);
  }

  private static boolean levenshteinWithin(String left, String right, int maxDistance) {
    if (left.equals(right)) return true;
    if (Math.abs(left.length() - right.length()) > maxDistance) return false;

    int[] previous = new int[right.length() + 1];
    int[] current = new int[right.length() + 1];
    for (int j = 0; j <= right.length(); j++) previous[j] = j;

    for (int i = 1; i <= left.length(); i++) {
      current[0] = i;
      int rowMin = current[0];
      for (int j = 1; j <= right.length(); j++) {
        int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
        current[j] = Math.min(
            Math.min(current[j - 1] + 1, previous[j] + 1),
            previous[j - 1] + cost);
        rowMin = Math.min(rowMin, current[j]);
      }
      if (rowMin > maxDistance) return false;
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[right.length()] <= maxDistance;
  }

  public static boolean containsWholeToken(String normalizedText, String normalizedToken) {
    if (normalizedText == null || normalizedToken == null
        || normalizedText.isBlank() || normalizedToken.isBlank()) return false;
    String text = " " + normalizedText.toLowerCase(Locale.ROOT).trim() + " ";
    String token = " " + normalizedToken.toLowerCase(Locale.ROOT).trim() + " ";
    return text.contains(token);
  }
}
