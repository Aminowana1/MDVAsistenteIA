package me.kev.sva.chat.assistant;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionCreateParams;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.AssistantChatMessage;
import me.kev.sva.chat.message.BroadcastChatMessage;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.message.SystemContextMessage;
import me.kev.sva.utils.MessageSender;

/**
 * Provider-neutral request executor.
 *
 * <p>1.4.4 keeps the simple V1 OpenAI path as the normal path while allowing a
 * separately configured OpenAI-compatible fallback (Gemini by default). The
 * ConversationManager decides when to switch provider; this class only builds
 * and executes one request against the selected provider.</p>
 */
public class AssistantManager {
  public static final int PRIMARY = 0;
  public static final int FALLBACK = 1;

  private final ServerAssistantPlugin plugin;
  private final ProviderRuntime primary;
  private final ProviderRuntime fallback;
  private final ExecutorService requestExecutor;
  private volatile boolean shutdown = false;

  public AssistantManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    this.primary = createRuntime(ProviderSettings.primary(plugin), "primary");

    ProviderSettings fallbackSettings = ProviderSettings.fallback(plugin);
    this.fallback = fallbackSettings == null ? null : createRuntime(fallbackSettings, "fallback");

    logRuntime(primary, "Primary");
    if (fallback != null) {
      logRuntime(fallback, "Fallback");
    } else {
      plugin.getLogger().info("AI fallback: disabled");
    }

    AtomicInteger counter = new AtomicInteger();
    ThreadFactory factory = runnable -> {
      Thread thread = new Thread(runnable, "ServerAssistant-AI-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
    requestExecutor = Executors.newSingleThreadExecutor(factory);
  }

  private ProviderRuntime createRuntime(ProviderSettings settings, String role) {
    if (settings == null || !settings.isConfigured()) {
      if (settings != null) {
        MessageSender.Error(role + " " + settings.displayName() + " API key/provider not configured.");
      }
      return new ProviderRuntime(settings, null);
    }

    OpenAIClient client = OpenAIOkHttpClient.builder()
        .apiKey(settings.resolveApiKey())
        .baseUrl(settings.baseUrl())
        .build();
    return new ProviderRuntime(settings, client);
  }

  private void logRuntime(ProviderRuntime runtime, String role) {
    if (runtime == null || runtime.settings() == null) {
      return;
    }
    ProviderSettings settings = runtime.settings();
    plugin.getLogger().info(role + " AI provider: " + settings.displayName());
    plugin.getLogger().info(role + " AI model: " + settings.model());
    plugin.getLogger().info(role + " AI max output tokens: " + settings.maxOutputTokens());
    plugin.getLogger().info(role + " AI local RPM cap: "
        + (settings.maxRequestsPerMinute() == 0 ? "unlimited" : settings.maxRequestsPerMinute()));
  }

  public ProviderSettings getProviderSettings(int providerIndex) {
    ProviderRuntime runtime = runtime(providerIndex);
    return runtime == null ? null : runtime.settings();
  }

  public ProviderSettings getPrimaryProviderSettings() {
    return primary.settings();
  }

  public ProviderSettings getFallbackProviderSettings() {
    return fallback == null ? null : fallback.settings();
  }

  public boolean isProviderAvailable(int providerIndex) {
    ProviderRuntime runtime = runtime(providerIndex);
    return runtime != null && runtime.client() != null && runtime.settings() != null;
  }

  public boolean hasFallback() {
    return isProviderAvailable(FALLBACK);
  }

  private ProviderRuntime runtime(int providerIndex) {
    return providerIndex == FALLBACK ? fallback : primary;
  }

  public void shutdown() {
    shutdown = true;
    requestExecutor.shutdownNow();
    closeRuntime(primary);
    closeRuntime(fallback);
  }

  private void closeRuntime(ProviderRuntime runtime) {
    if (runtime == null || runtime.client() == null) {
      return;
    }
    try {
      runtime.client().close();
    } catch (Exception ignored) {
    }
  }

  /**
   * Builds the request synchronously from trusted main-thread snapshots, then
   * performs only the network call off-thread. Completion always returns to the
   * Bukkit main thread.
   */
  public void sendAIRequest(
      int providerIndex,
      List<ChatMessage> chatMessages,
      AssistantRequestContext requestContext,
      BiConsumer<AssistantResponse, Throwable> completion) {

    if (shutdown) {
      completion.accept(null, new IllegalStateException("Assistant manager is shut down."));
      return;
    }

    ProviderRuntime runtime = runtime(providerIndex);
    if (runtime == null || runtime.settings() == null) {
      completion.accept(null, new IllegalStateException("Selected AI provider is not configured."));
      return;
    }

    ProviderSettings provider = runtime.settings();
    if (provider.model() == null || provider.model().isBlank()) {
      completion.accept(null, new IllegalStateException("AI model is not configured."));
      return;
    }

    if (runtime.client() == null) {
      completion.accept(null, new IllegalStateException(provider.displayName() + " API client is not configured."));
      return;
    }

    ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams
        .builder()
        .model(provider.model())
        .maxCompletionTokens(provider.maxOutputTokens())
        .temperature(provider.temperature());

    // OpenAI JSON mode guarantees a syntactically valid JSON object. This matters
    // for one-call ACTION tools because a plain-text fallback cannot carry `t`.
    // Keep OpenAI-compatible fallback providers untouched unless explicitly supported.
    if ("openai".equalsIgnoreCase(provider.type())
        && plugin.getConfig().getBoolean("provider-response.force-json-object-openai", true)) {
      paramsBuilder.responseFormat(ResponseFormatJsonObject.builder().build());
    }

    appendSystemPromptsToBuilder(paramsBuilder, requestContext);
    appendConversationMessagesToBuilder(paramsBuilder, chatMessages, provider);

    ChatCompletionCreateParams params = paramsBuilder.build();

    requestExecutor.submit(() -> {
      String responseText = "";
      Throwable failure = null;

      try {
        if (!shutdown) {
          var response = runtime.client().chat().completions().create(params);
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

    String personalityPrompt = plugin.getPersonalityConfig().getString(
        "prompt",
        AssistantContextualizer.DEFAULT_PERSONALITY_PROMPT);
    String capabilitiesNote = plugin.getPersonalityConfig().getString("capabilities-note", "");
    StringBuilder personalitySystem = new StringBuilder(
        AssistantContextualizer.PERSONALITY_PROMPT_HEADER).append(personalityPrompt);
    if (capabilitiesNote != null && !capabilitiesNote.isBlank()) {
      personalitySystem.append("\n\n[WORLD CAPABILITIES]\n").append(capabilitiesNote.trim());
    }
    paramsBuilder.addSystemMessage(personalitySystem.toString());

    int maxAssistantMessageLength = Math.max(
        plugin.getConfig().getInt("chat.max-assistant-message-length", 190),
        0);

    // Stable prefix first so prompt caching can reuse CORE + personality + output/tool rules.
    paramsBuilder.addSystemMessage(
        "[OUTPUT] max_chars=" + maxAssistantMessageLength
            + ", max_chat_messages=1. Java enforces both limits.");
    if (plugin.getToolManager() != null) {
      paramsBuilder.addSystemMessage(plugin.getToolManager().getCapabilitiesPrompt());
      paramsBuilder.addSystemMessage(plugin.getToolManager().getAvailableActionToolsPrompt());
    } else {
      paramsBuilder.addSystemMessage("[CAPABILITIES] no local tool capabilities available");
      paramsBuilder.addSystemMessage("[TOOLS] disabled; t must be []");
    }

    // Dynamic/local context follows the cache-friendly prefix. Wiki/player/inventory
    // retrieval happens in Java before this one model call. Action tools execute
    // from the same response, so there is no model tool loop.
    paramsBuilder.addSystemMessage(AssistantContextualizer.getLocalKnowledge(requestContext));
    paramsBuilder.addSystemMessage(AssistantContextualizer.getServerContext());
    paramsBuilder.addSystemMessage(AssistantContextualizer.getRequestContext(requestContext));
  }

  private void appendConversationMessagesToBuilder(
      ChatCompletionCreateParams.Builder paramsBuilder,
      List<ChatMessage> chatMessages,
      ProviderSettings provider) {

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

        // Unlike V1, player text is a real user turn instead of a system message.
        // This is both more natural for GPT-4o mini and safer against prompt injection.
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

    // Gemini's OpenAI-compatibility endpoint can reject an effective sequence
    // ending on the model. OpenAI does not need this synthetic continuation, so
    // keep the normal GPT-4o mini path as close to V1 as possible.
    if ("gemini".equalsIgnoreCase(provider.type())
        && (!hasUserTurn || lastConversationalTurnWasAssistant)) {
      paramsBuilder.addUserMessage(
          "[TRUSTED CONTINUATION] Use the trusted context above. Reply only if useful; otherwise stay silent.");
    }
  }

  private record ProviderRuntime(ProviderSettings settings, OpenAIClient client) {
  }
}
