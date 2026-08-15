package me.kev.sva.chat.assistant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    String cleanedResponse = stripOuterCodeFence(response == null ? "" : response).trim();
    boolean structured = false;

    try {
      Object loaded = cleanedResponse.isBlank() ? null : new Yaml().load(cleanedResponse);

      if (loaded instanceof Map<?, ?> data) {
        boolean hasKnownField = containsAnyKey(
            data,
            "m", "messages", "message", "response", "text",
            "t", "tool-calls", "tool_calls", "tools",
            "c", "close-conversation", "close_conversation");

        if (hasKnownField) {
          parsedMessages = firstNonEmpty(
              getStringListFlexible(data, "m"),
              getStringListFlexible(data, "messages"),
              getStringListFlexible(data, "message"),
              getStringListFlexible(data, "response"),
              getStringListFlexible(data, "text"));

          parsedToolCalls = firstNonEmpty(
              getStringListFlexible(data, "t"),
              getStringListFlexible(data, "tool-calls"),
              getStringListFlexible(data, "tool_calls"),
              getStringListFlexible(data, "tools"));

          parsedCloseConversation = getBooleanFlexible(
              data,
              List.of("c", "close-conversation", "close_conversation"),
              false);
          structured = true;
        }
      } else if (loaded instanceof List<?> list) {
        parsedMessages = scalarListToStrings(list);
        structured = !parsedMessages.isEmpty();
      } else if (loaded instanceof String scalar) {
        parsedMessages = scalar.isBlank() ? List.of() : List.of(scalar);
        structured = true;
      }
    } catch (Exception ex) {
      if (plugin.getConfig().getBoolean("provider-response.debug-malformed-responses", false)) {
        plugin.getLogger().warning(
            "Could not parse AI response as structured YAML/JSON: "
                + ex.getClass().getSimpleName() + ": " + ex.getMessage());
      }
    }

    // Gemini (especially through compatibility endpoints) may occasionally ignore
    // the requested YAML envelope and return the natural-language answer directly.
    // During ordinary chat that answer is still useful, so recover it rather than
    // silently turning Isolda into an empty response. Tool calls still require the
    // structured envelope and therefore can never be invented by this fallback.
    if (!structured && !cleanedResponse.isBlank()
        && plugin.getConfig().getBoolean("provider-response.accept-plain-text-fallback", true)) {
      String recovered = recoverProtocolMessage(cleanedResponse, 0);
      if (!recovered.isBlank()) {
        parsedMessages = List.of(recovered);
      } else if (!looksLikeProtocolLeak(cleanedResponse)) {
        parsedMessages = List.of(cleanedResponse);
      } else {
        parsedMessages = List.of();
        plugin.getLogger().warning("Blocked malformed AI protocol text from reaching public chat.");
      }
      parsedToolCalls = List.of();
      parsedCloseConversation = false;

      if (plugin.getConfig().getBoolean("provider-response.log-fallbacks", false)) {
        plugin.getLogger().info("Recovered a non-structured AI response as safe plain chat text.");
      }
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

  /** Only the visible dialogue is sent back as assistant history. Protocol metadata stays in Java. */
  public String historyText() {
    return String.join(" ", messages).trim();
  }

  private static final Pattern PROTOCOL_MESSAGE_PATTERN = Pattern.compile(
      "(?is)(?:\"?(?:m|messages|message|response|text)\"?)\\s*:\\s*(?:-\\s*)?(?:\\[\\s*)?\"((?:\\\\.|[^\"\\\\])*)\"");

  private static String recoverProtocolMessage(String text, int depth) {
    if (text == null || text.isBlank() || depth > 2) {
      return "";
    }
    Matcher matcher = PROTOCOL_MESSAGE_PATTERN.matcher(text);
    if (!matcher.find()) {
      return "";
    }
    String value = unescapeProtocolString(matcher.group(1)).trim();
    if (value.isBlank()) {
      return "";
    }
    if (looksLikeProtocolLeak(value)) {
      String nested = recoverProtocolMessage(value, depth + 1);
      return nested.isBlank() ? "" : nested;
    }
    return value;
  }

  private static String unescapeProtocolString(String text) {
    return text
        .replace("\\\"", "\"")
        .replace("\\n", " ")
        .replace("\\r", " ")
        .replace("\\t", " ")
        .replace("\\\\", "\\");
  }

  private static boolean looksLikeProtocolLeak(String text) {
    if (text == null || text.isBlank()) {
      return false;
    }

    String lower = text.trim().toLowerCase();
    String compact = lower.replaceAll("\\s+", "");

    // Full or partial protocol envelopes must never become public chat.
    // In 1.4.3 a model response such as `m: [], t: [], c: false` was valid YAML,
    // but SnakeYAML interpreted the value of `m` as the scalar
    // `[], t: [], c: false`; that scalar was then broadcast as Isolda's speech.
    if (lower.startsWith("[core]") || lower.startsWith("messages:")
        || lower.startsWith("\"messages\"") || lower.startsWith("{m:")
        || lower.startsWith("{\"m\"") || lower.startsWith("m:")
        || lower.startsWith("\"m\":") || lower.startsWith("t:")
        || lower.startsWith("\"t\":") || lower.startsWith("c:")
        || lower.startsWith("\"c\":")) {
      return true;
    }

    // Detect the malformed value produced when the outer `m:` key consumed the
    // rest of a brace-less compact envelope: `[], t: [], c: false`.
    if ((compact.startsWith("[]") || compact.startsWith("[ ]"))
        && (compact.contains(",t:") || compact.contains(",\"t\":"))
        && (compact.contains(",c:") || compact.contains(",\"c\":"))) {
      return true;
    }

    int signals = 0;
    if (lower.contains("messages:") || lower.contains("\"messages\":")) signals++;
    if (lower.contains("tool-calls") || lower.contains("tool_calls")) signals++;
    if (lower.contains("close-conversation") || lower.contains("close_conversation")) signals++;
    if (compact.contains(",t:") || compact.contains(",\"t\":")) signals++;
    if (compact.contains(",c:") || compact.contains(",\"c\":")) signals++;
    return signals >= 2;
  }

  private static boolean containsAnyKey(Map<?, ?> data, String... keys) {
    for (String key : keys) {
      if (data.containsKey(key)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> getStringListFlexible(Map<?, ?> data, String key) {
    Object value = data.get(key);
    if (value instanceof String scalar) {
      return scalar.isBlank() ? List.of() : List.of(scalar);
    }
    if (value instanceof List<?> list) {
      return scalarListToStrings(list);
    }
    return List.of();
  }

  @SafeVarargs
  private static List<String> firstNonEmpty(List<String>... candidates) {
    for (List<String> candidate : candidates) {
      if (candidate != null && !candidate.isEmpty()) {
        return candidate;
      }
    }
    return List.of();
  }

  private static List<String> scalarListToStrings(List<?> list) {
    List<String> result = new ArrayList<>();
    for (Object value : list) {
      if (value == null) {
        continue;
      }
      if (value instanceof String scalar) {
        if (!scalar.isBlank()) {
          result.add(scalar);
        }
        continue;
      }
      if (value instanceof Number || value instanceof Boolean) {
        result.add(String.valueOf(value));
      }
    }
    return List.copyOf(result);
  }

  private static boolean getBooleanFlexible(
      Map<?, ?> data,
      List<String> keys,
      boolean fallback) {

    for (String key : keys) {
      Object value = data.get(key);
      if (value instanceof Boolean bool) {
        return bool;
      }
      if (value instanceof String text) {
        if ("true".equalsIgnoreCase(text.trim())) {
          return true;
        }
        if ("false".equalsIgnoreCase(text.trim())) {
          return false;
        }
      }
    }
    return fallback;
  }

  private static String stripOuterCodeFence(String text) {
    if (text == null) {
      return "";
    }

    String trimmed = text.trim();
    if (!trimmed.startsWith("```") || !trimmed.endsWith("```")) {
      return trimmed;
    }

    int firstNewline = trimmed.indexOf('\n');
    if (firstNewline < 0) {
      return trimmed;
    }

    String body = trimmed.substring(firstNewline + 1, trimmed.length() - 3);
    return body.trim();
  }

  private List<String> normalizeMessages(List<String> input) {
    int maxMessages = Math.max(
        plugin.getConfig().getInt("chat.max-messages-per-response", 1),
        0);

    int maxLength = Math.max(
        plugin.getConfig().getInt("chat.max-assistant-message-length", 250),
        0);

    List<String> result = new ArrayList<>();
    for (String rawMessage : input) {
      if (maxMessages > 0 && result.size() >= maxMessages) {
        break;
      }

      String source = rawMessage == null ? "" : rawMessage;
      if (looksLikeProtocolLeak(source)) {
        String recovered = recoverProtocolMessage(source, 0);
        if (recovered.isBlank()) {
          continue;
        }
        source = recovered;
      }

      source = stripAssistantSelfPrefix(source);
      String message = sanitizeMessage(source).trim();
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


  /**
   * The server already renders the assistant name. Models occasionally prepend
   * `Isolda >`, `Isolda:`, etc.; strip only a leading self-label, never normal
   * mentions of Isolda inside a sentence. This costs zero model tokens.
   */
  private String stripAssistantSelfPrefix(String text) {
    if (text == null || text.isBlank()) {
      return text == null ? "" : text;
    }

    String assistantName = plugin.getConfig().getString("assistant-name", "ServerAssistant");
    if (assistantName == null || assistantName.isBlank()) {
      return text;
    }

    String cleaned = text.trim();
    Pattern prefix = Pattern.compile(
        "(?i)^\\s*\\[?" + Pattern.quote(assistantName.trim())
            + "\\]?\\s*(?::|>|»|-)\\s*");

    // A double label is malformed too; remove at most two copies defensively.
    for (int i = 0; i < 2; i++) {
      Matcher matcher = prefix.matcher(cleaned);
      if (!matcher.find()) {
        break;
      }
      cleaned = cleaned.substring(matcher.end()).trim();
    }
    return cleaned;
  }

  /**
   * 1.6 keeps read/context tools local but allows a small explicit list of ACTION
   * calls in the same model response. ToolManager performs the real allow-list and
   * activation/approval checks before anything can affect the server.
   */
  private List<String> normalizeToolCalls(List<String> input) {
    if (!plugin.getConfig().getBoolean("tools.enabled", true)) {
      return List.of();
    }
    int maxCalls = Math.max(plugin.getConfig().getInt("tools.max-calls-per-response", 2), 0);
    if (maxCalls == 0 || input == null || input.isEmpty()) {
      return List.of();
    }

    List<String> result = new ArrayList<>();
    for (String raw : input) {
      if (result.size() >= maxCalls) break;
      if (raw == null) continue;
      String call = raw.replaceAll("[\r\n]+", " ")
          .replaceAll("\\s{2,}", " ")
          .trim();
      if (call.isBlank() || call.length() > 220) continue;
      result.add(call);
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
