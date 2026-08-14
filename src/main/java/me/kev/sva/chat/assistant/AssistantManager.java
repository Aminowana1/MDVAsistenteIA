package me.kev.sva.chat.assistant;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.BroadcastChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;
import me.kev.sva.utils.MessageSender;

public class AssistantManager {
  private final ServerAssistantPlugin plugin;
  private final OpenAIClient client;
  private final ExecutorService requestExecutor;
  private volatile boolean shutdown = false;

  public AssistantManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;

    String apiKey = resolveApiKey(plugin);
    String baseUrl = resolveBaseUrl(plugin);

    if (apiKey == null || apiKey.isBlank()
        || apiKey.equals("YOUR_API_KEY_HERE")
        || apiKey.equals("YOUR_GEMINI_API_KEY_HERE")) {
      MessageSender.Error("Gemini API key not configured. AI features disabled.");
      client = null;
    } else {
      client = OpenAIOkHttpClient.builder()
          .apiKey(apiKey)
          .baseUrl(baseUrl)
          .build();

      String model = plugin.getConfig().getString("ai-model", "gemini-3.7-flash");
      plugin.getLogger().info("AI provider: Gemini (OpenAI-compatible endpoint)");
      plugin.getLogger().info("AI model: " + model);
    }

    AtomicInteger counter = new AtomicInteger();
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "ServerAssistant-AI-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    requestExecutor = Executors.newSingleThreadExecutor(factory);
  }

  private static String resolveApiKey(ServerAssistantPlugin plugin) {
    String envName = plugin.getConfig().getString("api-key-env", "GEMINI_API_KEY");
    if (envName != null && !envName.isBlank()) {
      String envValue = System.getenv(envName.trim());
      if (envValue != null && !envValue.isBlank()) {
        return envValue.trim();
      }
    }
    return plugin.getConfig().getString("api-key");
  }

  private static String resolveBaseUrl(ServerAssistantPlugin plugin) {
    String configured = plugin.getConfig().getString(
        "api-base-url",
        "https://generativelanguage.googleapis.com/v1beta/openai/");

    if (configured == null || configured.isBlank()) {
      return "https://generativelanguage.googleapis.com/v1beta/openai/";
    }

    String normalized = configured.trim();
    return normalized.endsWith("/") ? normalized : normalized + "/";
  }

  public void shutdown() {
    shutdown = true;
    requestExecutor.shutdownNow();
  }

  /**
   * Builds the request synchronously from trusted main-thread snapshots, then
   * performs only the network call off-thread. Completion always returns to the
   * Bukkit main thread.
   */
  public void sendAIRequest(
      List<ChatMessage> chatMessages,
      AssistantRequestContext requestContext,
      BiConsumer<AssistantResponse, Throwable> completion) {

    if (shutdown) {
      completion.accept(null, new IllegalStateException("Assistant manager is shut down."));
      return;
    }

    String configuredModel = plugin.getConfig().getString("ai-model", "gemini-3.7-flash");
    if (configuredModel == null || configuredModel.isBlank()) {
      completion.accept(null, new IllegalStateException("ai-model is not configured."));
      return;
    }

    if (client == null) {
      completion.accept(null, new IllegalStateException("API client is not configured."));
      return;
    }

    ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams
        .builder()
        .model(configuredModel);

    appendSystemPromptsToBuilder(paramsBuilder, requestContext);
    appendConversationMessagesToBuilder(paramsBuilder, chatMessages);

    ChatCompletionCreateParams params = paramsBuilder.build();

    requestExecutor.submit(() -> {
      String responseText = "";
      Throwable failure = null;

      try {
        if (!shutdown) {
          var response = client.chat().completions().create(params);
          responseText = response.choices()
              .get(0)
              .message()
              .content()
              .orElse("");
        }
      } catch (Throwable error) {
        failure = error;
      }

      String finalResponseText = responseText;
      Throwable finalFailure = failure;

      if (!shutdown && plugin.isEnabled()) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
          AssistantResponse parsed = finalResponseText.isBlank()
              ? new AssistantResponse(plugin, List.of(), List.of(), false)
              : new AssistantResponse(plugin, finalResponseText);
          completion.accept(parsed, finalFailure);
        });
      }
    });
  }

  private void appendSystemPromptsToBuilder(
      ChatCompletionCreateParams.Builder paramsBuilder,
      AssistantRequestContext requestContext) {

    paramsBuilder.addSystemMessage(AssistantContextualizer.PRIMARY_SYSTEM_INSTRUCTIONS);

    String personalityPrompt = plugin.getConfig().getString(
        "prompt",
        AssistantContextualizer.DEFAULT_PERSONALITY_PROMPT);
    paramsBuilder.addSystemMessage(
        AssistantContextualizer.PERSONALITY_PROMPT_HEADER + personalityPrompt);

    paramsBuilder.addSystemMessage(AssistantContextualizer.getServerContext());
    paramsBuilder.addSystemMessage(AssistantContextualizer.getRequestContext(requestContext));

    int maxAssistantMessageLength = Math.max(
        plugin.getConfig().getInt("chat.max-assistant-message-length", 250),
        0);
    int maxMessages = Math.max(
        plugin.getConfig().getInt("conversation-control.max-messages-per-response", 1),
        0);

    paramsBuilder.addSystemMessage("""
        [OUTPUT LIMITS]
        Maximum characters per assistant chat message: %d
        Maximum public chat messages in this response: %d
        These limits are also enforced by Java after generation.
        """.formatted(maxAssistantMessageLength, maxMessages));

    paramsBuilder.addSystemMessage(AssistantContextualizer.getKnowledgeAndTools(plugin));
  }

  private void appendConversationMessagesToBuilder(
      ChatCompletionCreateParams.Builder paramsBuilder,
      List<ChatMessage> chatMessages) {

    int maxPlayerMessageLength = Math.max(
        plugin.getConfig().getInt("chat.max-player-message-length", 250),
        0);

    boolean hasUserTurn = false;
    boolean lastConversationalTurnWasAssistant = false;

    for (ChatMessage message : chatMessages) {
      if (message instanceof AssistantChatMessage assistantMessage) {
        paramsBuilder.addAssistantMessage(assistantMessage.content);
        lastConversationalTurnWasAssistant = true;
        continue;
      }

      if (message instanceof PlayerChatMessage playerMessage) {
        String msg = message.content;
        if (maxPlayerMessageLength > 0 && msg.length() > maxPlayerMessageLength) {
          msg = msg.substring(0, maxPlayerMessageLength);
        }

        // CRITICAL: player content is USER content, never SYSTEM content.
        paramsBuilder.addUserMessage(playerMessage.header + msg);
        hasUserTurn = true;
        lastConversationalTurnWasAssistant = false;
        continue;
      }

      if (message instanceof SystemContextMessage systemMessage) {
        paramsBuilder.addSystemMessage(systemMessage.header + systemMessage.content);
        continue;
      }

      if (message instanceof BroadcastChatMessage broadcastMessage) {
        // Trusted server events remain SYSTEM context. Gemini's OpenAI-compatible
        // endpoint still requires a user turn after an assistant/model turn, so a
        // controlled continuation trigger is appended below when needed.
        paramsBuilder.addSystemMessage(broadcastMessage.header + broadcastMessage.content);
      }
    }

    // Gemini rejects requests whose effective conversation ends on a model turn.
    // This happens naturally for ambient events and our YAML wiki-tool follow-ups:
    // assistant -> trusted SYSTEM event/result -> next request. Add a plugin-owned
    // USER turn so the model has something to answer without pretending that a
    // real player sent it. This content is constant and never player-controlled.
    if (!hasUserTurn || lastConversationalTurnWasAssistant) {
      paramsBuilder.addUserMessage(
          "[TRUSTED CONTINUATION] Use the trusted server context/tool results above. "
              + "Respond only if a response is useful; otherwise return an empty messages list.");
    }
  }
}
