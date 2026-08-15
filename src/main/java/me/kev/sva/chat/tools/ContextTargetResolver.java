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
        if (containsWholeToken(latestText, ToolManager.normalize(candidate))) {
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
        if (containsWholeToken(normalizedSceneText, ToolManager.normalize(candidate))) {
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

  public static boolean containsWholeToken(String normalizedText, String normalizedToken) {
    if (normalizedText == null || normalizedToken == null
        || normalizedText.isBlank() || normalizedToken.isBlank()) return false;
    String text = " " + normalizedText.toLowerCase(Locale.ROOT).trim() + " ";
    String token = " " + normalizedToken.toLowerCase(Locale.ROOT).trim() + " ";
    return text.contains(token);
  }
}
