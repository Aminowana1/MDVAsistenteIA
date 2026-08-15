package me.kev.sva.chat.assistant;

import me.kev.sva.ServerAssistantPlugin;

/** Immutable provider configuration for the primary or fallback AI provider. */
public record ProviderSettings(
    String type,
    String displayName,
    String apiKeyEnv,
    String apiKey,
    String baseUrl,
    String model,
    long maxOutputTokens,
    double temperature,
    int maxRequestsPerMinute) {

  /** Primary provider. The ai.* section is intentionally backward compatible with 1.4.0. */
  public static ProviderSettings primary(ServerAssistantPlugin plugin) {
    String type = (plugin.getConfig().isSet("ai.provider")
        ? value(plugin, "ai.provider", "openai")
        : inferLegacyType(plugin)).toLowerCase();
    int legacyLimit = Math.max(
        plugin.getConfig().getInt("rate-limits.max-ai-requests-per-minute", 20),
        0);
    return fromSection(plugin, "ai", type, legacyLimit, true);
  }

  /** Optional secondary provider used only when the primary is unavailable. */
  public static ProviderSettings fallback(ServerAssistantPlugin plugin) {
    if (!plugin.getConfig().isSet("ai.fallback.enabled")
        || !plugin.getConfig().getBoolean("ai.fallback.enabled", false)) {
      return null;
    }
    String type = value(plugin, "ai.fallback.provider", "gemini").toLowerCase();
    return fromSection(plugin, "ai.fallback", type, 4, false);
  }

  private static ProviderSettings fromSection(
      ServerAssistantPlugin plugin,
      String section,
      String type,
      int defaultRequestsPerMinute,
      boolean allowLegacyKeys) {

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
        defaultBase = "";
        defaultModel = "";
        displayName = type.isBlank() ? "OpenAI-compatible" : type;
      }
    }

    String legacyEnv = allowLegacyKeys ? value(plugin, "api-key-env", defaultEnv) : defaultEnv;
    String legacyKey = allowLegacyKeys ? value(plugin, "api-key", "") : "";
    String legacyBase = allowLegacyKeys ? value(plugin, "api-base-url", defaultBase) : defaultBase;
    String legacyModel = allowLegacyKeys ? value(plugin, "ai-model", defaultModel) : defaultModel;

    String env = explicitValue(plugin, section + ".api-key-env", legacyEnv);
    String key = explicitValue(plugin, section + ".api-key", legacyKey);
    String base = explicitValue(plugin, section + ".base-url", legacyBase);
    String model = explicitValue(plugin, section + ".model", legacyModel);
    long maxOutput = Math.max(plugin.getConfig().getLong(section + ".max-output-tokens", 160L), 1L);
    double temperature = plugin.getConfig().getDouble(section + ".temperature", 0.75D);
    temperature = Math.max(0.0D, Math.min(2.0D, temperature));
    int maxRequests = Math.max(
        plugin.getConfig().getInt(section + ".max-requests-per-minute", defaultRequestsPerMinute),
        0);

    if (!base.isBlank() && !base.endsWith("/")) {
      base += "/";
    }

    return new ProviderSettings(
        type,
        displayName,
        env,
        key,
        base,
        model,
        maxOutput,
        temperature,
        maxRequests);
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

  public boolean isConfigured() {
    String resolved = resolveApiKey();
    return resolved != null
        && !resolved.isBlank()
        && !resolved.startsWith("YOUR_")
        && baseUrl != null
        && !baseUrl.isBlank()
        && model != null
        && !model.isBlank();
  }

  /** Stable non-secret key used for independent throttling of primary/fallback. */
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
    return "openai"; // Version 1 compatibility: api-key + gpt-4o-mini was OpenAI.
  }

  /** Reads only values physically present in the user's config, not bundled defaults. */
  private static String explicitValue(ServerAssistantPlugin plugin, String path, String fallback) {
    if (!plugin.getConfig().isSet(path)) {
      return fallback;
    }
    String value = plugin.getConfig().getString(path);
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private static String value(ServerAssistantPlugin plugin, String path, String fallback) {
    String value = plugin.getConfig().getString(path);
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
