# ServerAssistant

Open-source Paper plugin for a context-aware AI character inside Minecraft.

## 1.4.0 highlights

1.4.0 focuses on reliability, provider independence and lower API/token use.

- Conversation slots are logical conversations, so groups share one slot.
- Player conversations remain isolated from unrelated public chat.
- Explicit hand-offs such as `Iso responde a Kroattan` can import only Kroattan's very recent public line instead of exposing all global chat to the model.
- Provider throttling survives `/sva reload`; reloading can no longer erase a Gemini 429 cooldown and immediately hit the same external quota again.
- Fresh turns rejected by local/provider limits are no longer committed into conversation history as if the AI had answered them.
- Per-player message rate limits no longer reset merely because a player leaves one SVA conversation and joins another.
- 503 retries are bounded and still pass through the same request budget.
- Output tokens are capped at the provider request (`ai.max-output-tokens`) instead of generating a long answer and discarding most of it afterward.
- System/request prompts were compacted substantially to reduce repeated input tokens.
- `ToolManager` is now an explicit allow-list/registry. Only `wiki` is registered in 1.4.0; there is still no arbitrary console-command tool.
- The AI provider is configurable: Gemini, OpenAI, or another OpenAI-compatible endpoint can be selected from config without recompiling.
- OpenAI selection is no longer auto-migrated back to Gemini.
- The OpenAI-compatible client is closed correctly on reload/shutdown.
- `/sva status` displays provider, model, local 60-second request count, cooldown, queue and active conversation count.
- GitHub Actions installs its YAML validator explicitly and runs Maven tests/build.

## Requirements

- Java 21
- Paper-compatible 1.21 server
- Maven 3.9+ for local builds
- API key for the configured provider

## Provider configuration

Gemini example:

```yaml
ai:
  provider: "gemini"
  api-key-env: "GEMINI_API_KEY"
  api-key: "YOUR_GEMINI_API_KEY_HERE"
  base-url: "https://generativelanguage.googleapis.com/v1beta/openai/"
  model: "gemini-3.7-flash"
  max-output-tokens: 128
  temperature: 0.75
```

OpenAI / GPT-4o mini example:

```yaml
ai:
  provider: "openai"
  api-key-env: "OPENAI_API_KEY"
  api-key: "YOUR_OPENAI_API_KEY_HERE"
  base-url: "https://api.openai.com/v1/"
  model: "gpt-4o-mini"
  max-output-tokens: 128
  temperature: 0.75
```

Legacy 1.3.x flat provider keys are migrated once into `ai.*` without changing the user's selected model/provider.

## Building

```bash
mvn clean package
```

Output:

```text
target/ServerAssistant-1.4.0.jar
```

GitHub Actions is included at `.github/workflows/build.yml`.

## Free-tier recommendation

If the active Gemini project reports a 5 RPM limit, use a lower local limit such as:

```yaml
rate-limits:
  max-ai-requests-per-minute: 4
  min-conversation-request-gap-ms: 4000
  max-local-queue-delay-ms: 5000

provider-retry:
  max-503-retries: 1
```

The plugin cannot bypass a provider quota. These settings prevent unnecessary 429 loops and reduce wasted calls.

## Security / future 2.0 tools

1.4.0 still has no generic console execution. Plain model text is chat only. Tools must be explicitly registered in `ToolManager`, and unknown tool names are rejected. Future write/action tools should validate player permissions, arguments and current server state in Java before any action occurs.

## License

MIT. See `LICENSE`.
