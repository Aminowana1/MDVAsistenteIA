package me.kev.sva.chat.assistant;

import me.kev.sva.ServerAssistantPlugin;

/** Immutable provider configuration. Supports Gemini, OpenAI and generic OpenAI-compatible endpoints. */
public record ProviderSettings(
    String type,
    String displayName,
    String apiKeyEnv,
    String apiKey,
    String baseUrl,
    String model,
    long maxOutputTokens,
    double temperature) {

  public static ProviderSettings from(ServerAssistantPlugin plugin) {
    String type = value(plugin, "ai.provider", inferLegacyType(plugin)).toLowerCase();

    String defaultEnv;
    String defaultBase;
    String defaultModel;
    String displayName;

    switch (type) {
      case "openai" -> {
        defaultEnv = "OPENAI_API_KEY";
        defaultBase = "https://api.openai.com/v1/";
        defaultModel = "gpt-4o-mini";
        displayName = "OpenAI";
      }
      case "gemini" -> {
        defaultEnv = "GEMINI_API_KEY";
        defaultBase = "https://generativelanguage.googleapis.com/v1beta/openai/";
        defaultModel = "gemini-3.7-flash";
        displayName = "Gemini";
      }
      default -> {
        defaultEnv = "AI_API_KEY";
        defaultBase = value(plugin, "api-base-url", "");
        defaultModel = value(plugin, "ai-model", "");
        displayName = type.isBlank() ? "OpenAI-compatible" : type;
      }
    }

    String env = value(plugin, "ai.api-key-env", value(plugin, "api-key-env", defaultEnv));
    String key = value(plugin, "ai.api-key", value(plugin, "api-key", ""));
    String base = value(plugin, "ai.base-url", value(plugin, "api-base-url", defaultBase));
    String model = value(plugin, "ai.model", value(plugin, "ai-model", defaultModel));
    long maxOutput = Math.max(plugin.getConfig().getLong("ai.max-output-tokens", 96L), 1L);
    double temperature = plugin.getConfig().getDouble("ai.temperature", 0.75D);
    temperature = Math.max(0.0D, Math.min(2.0D, temperature));

    if (!base.isBlank() && !base.endsWith("/")) {
      base += "/";
    }

    return new ProviderSettings(type, displayName, env, key, base, model, maxOutput, temperature);
  }

  public String resolveApiKey() {
    if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
      String envValue = System.getenv(apiKeyEnv.trim());
      if (envValue != null && !envValue.isBlank()) {
        return envValue.trim();
      }
    }
    return apiKey == null ? "" : apiKey.trim();
  }

  /** Stable non-secret key used for throttling. Changing provider/model gets a fresh bucket. */
  public String throttleKey() {
    return type + "|" + baseUrl + "|" + model;
  }

  private static String inferLegacyType(ServerAssistantPlugin plugin) {
    String base = value(plugin, "api-base-url", "").toLowerCase();
    String model = value(plugin, "ai-model", "").toLowerCase();
    String env = value(plugin, "api-key-env", "").toUpperCase();
    if (base.contains("generativelanguage.googleapis.com") || model.startsWith("gemini") || env.contains("GEMINI")) {
      return "gemini";
    }
    if (base.contains("api.openai.com") || model.startsWith("gpt-") || model.startsWith("o") || env.contains("OPENAI")) {
      return "openai";
    }
    return "openai-compatible";
  }

  private static String value(ServerAssistantPlugin plugin, String path, String fallback) {
    String value = plugin.getConfig().getString(path);
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
