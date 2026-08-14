package me.kev.sva.chat.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import me.kev.sva.ServerAssistantPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class AssistantResponse {
  public final String raw;
  private final List<String> messages;
  private final List<String> toolCalls;
  private final boolean closeConversation;
  private final ServerAssistantPlugin plugin;

  public AssistantResponse(ServerAssistantPlugin plugin, String response) {
    this.plugin = plugin;

    List<String> parsedMessages = List.of();
    List<String> parsedToolCalls = List.of();
    boolean parsedCloseConversation = false;

    try {
      Object loaded = new Yaml().load(response);
      if (loaded instanceof Map<?, ?> data) {
        parsedMessages = getStringList(data, "messages");
        parsedToolCalls = getStringList(data, "tool-calls");
        parsedCloseConversation = getBoolean(data, "close-conversation", false);
      } else {
        plugin.getLogger().warning("AI returned non-map YAML. Treating it as an empty response.");
      }
    } catch (Exception ex) {
      plugin.getLogger().warning(
          "Could not parse AI response as YAML: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    this.messages = normalizeMessages(parsedMessages);
    this.toolCalls = normalizeToolCalls(parsedToolCalls);
    this.closeConversation = parsedCloseConversation;
    this.raw = toYaml(this.messages, this.toolCalls, this.closeConversation);
  }

  public AssistantResponse(
      ServerAssistantPlugin plugin,
      List<String> messages,
      List<String> toolCalls,
      boolean closeConversation) {

    this.plugin = plugin;
    this.messages = normalizeMessages(messages);
    this.toolCalls = normalizeToolCalls(toolCalls);
    this.closeConversation = closeConversation;
    this.raw = toYaml(this.messages, this.toolCalls, this.closeConversation);
  }

  public List<String> getMessages() {
    return List.copyOf(messages);
  }

  public List<String> getToolCalls() {
    return List.copyOf(toolCalls);
  }

  public boolean shouldCloseConversation() {
    return closeConversation;
  }

  private static List<String> getStringList(Map<?, ?> data, String key) {
    Object value = data.get(key);
    if (!(value instanceof List<?> list)) {
      return List.of();
    }

    return list.stream()
        .filter(String.class::isInstance)
        .map(String.class::cast)
        .toList();
  }

  private static boolean getBoolean(Map<?, ?> data, String key, boolean fallback) {
    Object value = data.get(key);
    return value instanceof Boolean bool ? bool : fallback;
  }

  private List<String> normalizeMessages(List<String> input) {
    int maxMessages = Math.max(
        plugin.getConfig().getInt("conversation-control.max-messages-per-response", 1),
        0);

    int maxLength = Math.max(
        plugin.getConfig().getInt("chat.max-assistant-message-length", 250),
        0);

    List<String> result = new ArrayList<>();
    for (String rawMessage : input) {
      if (maxMessages > 0 && result.size() >= maxMessages) {
        break;
      }

      String message = sanitizeMessage(rawMessage == null ? "" : rawMessage).trim();
      if (message.isEmpty()) {
        continue;
      }

      if (maxLength > 0 && message.length() > maxLength) {
        message = truncateNaturally(message, maxLength);
      }

      if (!message.isEmpty()) {
        result.add(message);
      }
    }
    return List.copyOf(result);
  }

  private List<String> normalizeToolCalls(List<String> input) {
    int maxToolCalls = Math.max(
        plugin.getConfig().getInt("conversation-control.max-tool-calls-per-response", 2),
        0);

    List<String> result = new ArrayList<>();
    for (String toolCall : input) {
      if (maxToolCalls > 0 && result.size() >= maxToolCalls) {
        break;
      }
      if (toolCall == null || toolCall.isBlank()) {
        continue;
      }
      result.add(toolCall.trim());
    }
    return List.copyOf(result);
  }

  private String toYaml(
      List<String> messages,
      List<String> toolCalls,
      boolean closeConversation) {

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("messages", messages);
    data.put("tool-calls", toolCalls);
    data.put("close-conversation", closeConversation);

    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);

    return new Yaml(options).dump(data);
  }

  public static Component formatMessage(ServerAssistantPlugin plugin, String message) {
    String assistantName = plugin.getConfig().getString(
        "assistant-name",
        "ServerAssistant");

    String format = plugin.getConfig().getString(
        "chat.assistant-format",
        "&b🤖 &b&l%assistant_name%: &r%message%");

    String rendered = format
        .replace("%assistant_name%", assistantName)
        .replace("%message%", message);
    return LegacyComponentSerializer.legacyAmpersand().deserialize(rendered);
  }

  private static String sanitizeMessage(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }

    String cleaned = text.replaceAll(
        "[\\x{1F000}-\\x{1FAFF}" +
            "\\x{2600}-\\x{27BF}" +
            "\\x{2300}-\\x{23FF}" +
            "\\x{2B00}-\\x{2BFF}" +
            "\\x{FE00}-\\x{FE0F}" +
            "\\x{1F1E6}-\\x{1F1FF}]",
        "");

    // Minecraft chat is not Markdown. Keep Isolda's output looking like a
    // normal player line rather than a generated document/list.
    cleaned = cleaned
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
        .replaceAll("[\\r\\n]+", " ")
        .replaceAll("\\s{2,}", " ")
        .trim();
    return cleaned;
  }

  private static String truncateNaturally(String message, int maxLength) {
    if (message == null || message.length() <= maxLength) {
      return message == null ? "" : message;
    }

    String candidate = message.substring(0, maxLength).trim();
    int sentenceCut = Math.max(
        candidate.lastIndexOf(". "),
        Math.max(candidate.lastIndexOf("! "), candidate.lastIndexOf("? ")));
    if (sentenceCut >= Math.max(40, maxLength / 2)) {
      return candidate.substring(0, sentenceCut + 1).trim();
    }

    int wordCut = candidate.lastIndexOf(' ');
    if (wordCut >= Math.max(20, maxLength / 2)) {
      candidate = candidate.substring(0, wordCut).trim();
    }
    return candidate + "…";
  }

  /** Broadcasts this already-normalized response to global chat. */
  public void broadcastMessages() {
    long delayMs = Math.max(
        plugin.getConfig().getLong(
            "chat.assistant-chained-messages-delay",
            750),
        0);

    long delayTicks = Math.max(1, (delayMs + 49) / 50);

    for (int i = 0; i < messages.size(); i++) {
      String message = messages.get(i);
      long ticks = delayTicks * i;

      plugin.getServer().getScheduler().runTaskLater(
          plugin,
          () -> plugin.getServer().broadcast(
              formatMessage(plugin, message)),
          ticks);
    }
  }
}
