package me.kev.sva.chat.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;

final class ContextTargetResolverTest {

  @Test
  void explicitNamedTargetWinsOverRequester() {
    List<ChatMessage> messages = List.of(new PlayerChatMessage(
        null, UUID.randomUUID(), "Aminowana", "Aminowana", true,
        "iso donde esta tablos16?"));

    List<String> targets = ContextTargetResolver.resolve(
        List.of("Aminowana", "tablos16"),
        ToolManager.normalize("iso donde esta tablos16?"),
        messages,
        2);

    assertEquals(List.of("tablos16"), targets);
  }

  @Test
  void firstPersonQueryUsesLatestSpeaker() {
    List<ChatMessage> messages = List.of(new PlayerChatMessage(
        null, UUID.randomUUID(), "Aminowana", "Aminowana", true,
        "iso que tengo en mi mano?"));

    List<String> targets = ContextTargetResolver.resolve(
        List.of("Aminowana", "tablos16"),
        ToolManager.normalize("iso que tengo en mi mano?"),
        messages,
        2);

    assertEquals("Aminowana", targets.getFirst());
  }
  @Test
  void firstPersonFollowUpBeatsOlderNamedTarget() {
    UUID playerId = UUID.randomUUID();
    List<ChatMessage> messages = List.of(
        new PlayerChatMessage(null, playerId, "Aminowana", "Aminowana", true,
            "iso donde esta tablos16?"),
        new PlayerChatMessage(null, playerId, "Aminowana", "Aminowana", true,
            "y yo donde estoy?"));

    List<String> targets = ContextTargetResolver.resolve(
        List.of("Aminowana", "tablos16"),
        ToolManager.normalize("iso donde esta tablos16? y yo donde estoy?"),
        messages,
        2);

    assertEquals(List.of("Aminowana"), targets);
  }

  @Test
  void spacedNumericNameResolvesToCompactMinecraftName() {
    List<ChatMessage> messages = List.of(new PlayerChatMessage(
        null, UUID.randomUUID(), "Aminowana", "Aminowana", true,
        "iso que tiene en 3 minutos en su inventario?"));

    List<String> targets = ContextTargetResolver.resolve(
        List.of("Aminowana", "En3Minutos"),
        ToolManager.normalize("iso que tiene en 3 minutos en su inventario?"),
        messages,
        1);

    assertEquals(List.of("En3Minutos"), targets);
  }

  @Test
  void smallTypoCanResolveAlreadyInvolvedOnlineName() {
    List<ChatMessage> messages = List.of(new PlayerChatMessage(
        null, UUID.randomUUID(), "Aminowana", "Aminowana", true,
        "iso que tiene white en su inventario?"));

    List<String> targets = ContextTargetResolver.resolve(
        List.of("Aminowana", "WITHE9033"),
        ToolManager.normalize("iso que tiene white en su inventario?"),
        messages,
        1);

    assertEquals(List.of("WITHE9033"), targets);
  }


}
