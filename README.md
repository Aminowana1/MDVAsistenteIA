# ServerAssistant

Open-source Paper plugin for a context-aware AI character inside Minecraft.

## 1.4.1 highlights

1.4.1 keeps the optimized 1.4 routing/reliability layer but makes the normal AI path behave like the stable V1 setup again: **OpenAI `gpt-4o-mini` is primary** and Gemini is optional fallback.

- Primary and fallback use the same OpenAI-compatible Java client architecture.
- On a primary 429 or transient 5xx/timeout, the same pending batch can be retried through Gemini without committing or losing the player's question.
- Provider cooldowns and local rolling RPM buckets are independent and survive `/sva reload`.
- Logical group conversations, isolated participant timeouts, anti-human-chat false positives, recent-public-chat hand-off, serialized requests, bounded queues and wiki/tool limits from 1.4 remain intact.
- GPT-4o mini receives normal USER/ASSISTANT turns. The synthetic continuation workaround is now Gemini-only.
- `/sva status` reports primary and fallback state independently.

## Requirements

- Java 21
- Paper-compatible 1.21 server
- Maven 3.9+ for local builds
- API key for the configured provider

## Provider configuration

Recommended MDVCRAFT setup:

```yaml
ai:
  provider: "openai"
  api-key-env: "OPENAI_API_KEY"
  api-key: "YOUR_OPENAI_API_KEY_HERE"
  base-url: "https://api.openai.com/v1/"
  model: "gpt-4o-mini"
  max-output-tokens: 160
  temperature: 0.75
  max-requests-per-minute: 20

  fallback:
    enabled: true
    provider: "gemini"
    api-key-env: "GEMINI_API_KEY"
    api-key: "YOUR_GEMINI_API_KEY_HERE"
    base-url: "https://generativelanguage.googleapis.com/v1beta/openai/"
    model: "gemini-3.7-flash"
    max-output-tokens: 128
    temperature: 0.75
    max-requests-per-minute: 4
    max-wait-ms: 2500
```

Existing V1 flat `api-key` + `ai-model` settings are migrated to the primary `ai.*` section. Existing 1.4 configs remain explicit and are not silently rewritten from Gemini to OpenAI; use the supplied 1.4.1 config when intentionally changing provider.

## Building

```bash
mvn clean package
```

Output:

```text
target/ServerAssistant-1.4.1.jar
```

GitHub Actions is included at `.github/workflows/build.yml`.

## Rate-limit strategy

The plugin does not try to bypass provider quotas. OpenAI and Gemini have separate local caps/cooldowns. If OpenAI is temporarily rate-limited, the current uncommitted request can switch to Gemini; if both are unavailable, ServerAssistant sends the configured private busy notice rather than holding stale chat for a long time.

## Security / future 2.0 tools

1.4.1 still has no generic console execution. Plain model text is chat only. Tools must be explicitly registered in `ToolManager`, and unknown tool names are rejected. Future write/action tools should validate player permissions, arguments and current server state in Java before any action occurs.

## License

MIT. See `LICENSE`.
