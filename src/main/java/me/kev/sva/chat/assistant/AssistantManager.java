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
  private final ProviderSettings provider;
  private final OpenAIClient client;
  private final ExecutorService requestExecutor;
  private volatile boolean shutdown = false;

  public AssistantManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    this.provider = ProviderSettings.from(plugin);

    String apiKey = provider.resolveApiKey();
    if (apiKey == null || apiKey.isBlank()
        || apiKey.startsWith("YOUR_")
        || provider.baseUrl() == null || provider.baseUrl().isBlank()) {
      MessageSender.Error(provider.displayName() + " API key/provider not configured. AI features disabled.");
      client = null;
    } else {
      client = OpenAIOkHttpClient.builder()
          .apiKey(apiKey)
          .baseUrl(provider.baseUrl())
          .build();

      plugin.getLogger().info("AI provider: " + provider.displayName());
      plugin.getLogger().info("AI model: " + provider.model());
      plugin.getLogger().info("AI max output tokens: " + provider.maxOutputTokens());
    }

    AtomicInteger counter = new AtomicInteger();
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "ServerAssistant-AI-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    requestExecutor = Executors.newSingleThreadExecutor(factory);
  }

  public ProviderSettings getProviderSettings() {
    return provider;
  }

  public void shutdown() {
    shutdown = true;
    requestExecutor.shutdownNow();
    if (client != null) {
      try {
        client.close();
      } catch (Exception ignored) {
      }
    }
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

    if (provider.model() == null || provider.model().isBlank()) {
      completion.accept(null, new IllegalStateException("AI model is not configured."));
      return;
    }

    if (client == null) {
      completion.accept(null, new IllegalStateException("API client is not configured."));
      return;
    }

    ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams
        .builder()
        .model(provider.model())
        .maxCompletionTokens(provider.maxOutputTokens())
        .temperature(provider.temperature());

    appendSystemPromptsToBuilder(paramsBuilder, requestContext);
    appendConversationMessagesToBuilder(paramsBuilder, chatMessages);

    ChatCompletionCreateParams params = paramsBuilder.build();

    requestExecutor.submit(() -> {
      String responseText = "";
      Throwable failure = null;

      try {
        if (!shutdown) {
          var response = client.chat().completions().create(params);
          if (!response.choices().isEmpty()) {
            responseText = response.choices()
                .get(0)
                .message()
                .content()
                .orElse("");
          }
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
        plugin.getConfig().getInt("chat.max-assistant-message-length", 220),
        0);
    int maxMessages = Math.max(
        plugin.getConfig().getInt("conversation-control.max-messages-per-response", 1),
        0);

    paramsBuilder.addSystemMessage(
        "[OUTPUT] max_chars=" + maxAssistantMessageLength
            + ", max_chat_messages=" + maxMessages
            + ". Java enforces both limits.");

    paramsBuilder.addSystemMessage(AssistantContextualizer.getKnowledgeAndTools(plugin));
  }

  private void appendConversationMessagesToBuilder(
      ChatCompletionCreateParams.Builder paramsBuilder,
      List<ChatMessage> chatMessages) {

    int maxPlayerMessageLength = Math.max(
        plugin.getConfig().getInt("chat.max-player-message-length", 220),
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
        paramsBuilder.addSystemMessage(broadcastMessage.header + broadcastMessage.content);
      }
    }

    // Gemini's compatibility endpoint rejects an effective turn sequence ending
    // on the model. This trusted, constant continuation is never player-controlled.
    if (!hasUserTurn || lastConversationalTurnWasAssistant) {
      paramsBuilder.addUserMessage(
          "[TRUSTED CONTINUATION] Use the trusted context above. Reply only if useful; otherwise stay silent.");
    }
  }
}
