# ServerAssistant

Open-source Paper plugin that adds a context-aware AI assistant to a Minecraft server.

## 1.3.1 highlights

Version 1.3.1 keeps the 1.2 group router and switches the AI backend to Gemini 3.7 Flash. Version 1.2 changed the chat router from **one conversation slot per player** to **one slot per logical conversation**. A conversation can contain several players while unrelated public chat remains excluded.

- `smart` conversations may be individual or group conversations.
- Default maximum: **2 simultaneous logical conversations**, not 2 players.
- Default maximum: **6 participants inside one conversation**.
- `A + B + C + SVA` consumes one slot; `D + SVA` may consume the second.
- A newcomer can safely join a recent group by referencing an existing participant or using a conservative contextual continuation such as `@SVA y el báculo?`.
- With several active SVA conversations, ambiguous messages open a new slot (if available) instead of being merged into the wrong group.
- Every group message keeps its speaker label, so the model can distinguish who said what.
- Each participant has their own timeout, follow-up limit, rate limit and human-conversation detection state.
- Talking directly to another member of the same SVA group does not kick anyone out.
- Talking to someone outside the group releases only that participant from SVA.
- `gracias`, `chau`, etc. release only the participant who said it; they do not close the rest of the group.
- AI requests remain globally serialized, keeping response order stable.
- Player content remains **USER** content in the OpenAI-compatible request format, never SYSTEM content.
- Tool-call and tool-iteration limits, hard output limits, private busy notices, and API-key environment-variable support remain enabled.

## Requirements

- Java 21
- Paper-compatible server using the `1.21` API family
- Maven 3.9+
- A Gemini API key (Google AI Studio)

## Build locally

```bash
mvn clean package
```

The shaded plugin jar is produced at:

```text
target/ServerAssistant-1.3.1.jar
```

## Build on GitHub

The repository includes `.github/workflows/build.yml`.

1. Upload/push the project to GitHub.
2. Open the **Actions** tab.
3. Run **Build ServerAssistant** (or push to `main`/`master`).
4. Open the finished workflow run.
5. Download the `ServerAssistant-1.3.1` artifact.

Do **not** commit a real API key. On the Minecraft host, either set the `GEMINI_API_KEY` environment variable or configure `api-key` locally in `plugins/ServerAssistant/config.yml`.

When upgrading from 1.2.0, known default OpenAI placeholders/model values are migrated automatically to the Gemini defaults. A real custom API key is never overwritten; replace it manually with a Gemini key if necessary.

### Updating from 1.1.0

You do not have to delete an existing 1.1/1.2 config just to boot 1.3.1. The old `conversation-control.max-active-player-conversations` value is read as a compatibility fallback when the new `max-active-conversations` key is absent, and the group-routing options have safe Java defaults. Copy the new `group-conversations` block into your live config only if you want to tune those values explicitly.

## How group routing works

Default configuration:

```yaml
request-triggers:
  player-messages:
    mode: smart
    smart-active-time: 20000

conversation-control:
  max-active-conversations: 2

  group-conversations:
    enabled: true
    max-participants: 6
    join-window-ms: 15000
    join-on-contextual-follow-up: true
```

Example:

```text
A: @SVA ¿cómo consigo el set lunar?
SVA: ...
B: @SVA y el báculo?
SVA: ...
A: ¿cuál pega más?
B: creo que el báculo
SVA: ...
```

A and B share one logical conversation and therefore one slot. Their messages carry separate speaker labels in the model context.

An unrelated question stays separate:

```text
A: @SVA ¿cómo consigo el set lunar?
D: @SVA ¿cuándo es la próxima raid?
```

`D` does not contain a contextual continuation or reference to A, so it opens another conversation instead of contaminating A's context.

If two SVA conversations already exist, the router never guesses which one a newcomer meant unless the message explicitly references a participant from exactly one group.

## Security note for future action tools

Version 1.3.1 intentionally does **not** add generic console-command execution. Future 2.0 action tools should be narrowly scoped and validate permissions/arguments in Java. The model must never be the authority that decides whether an administrative action is allowed.

## License

MIT. See `LICENSE`.


## Gemini 3.7 Flash

ServerAssistant 1.3.1 uses Google's Gemini OpenAI-compatible endpoint by default.
The default model is `gemini-3.7-flash`.

Recommended configuration:

```yaml
api-key-env: "GEMINI_API_KEY"
api-key: "YOUR_GEMINI_API_KEY_HERE"
api-base-url: "https://generativelanguage.googleapis.com/v1beta/openai/"
ai-model: "gemini-3.7-flash"
```

Prefer the `GEMINI_API_KEY` environment variable. Keep real API keys out of public repositories.
The current plugin continues to use Chat Completions through Gemini's OpenAI-compatible API; the conversation routing, batching, wiki tools and anti-spam behavior are unchanged from 1.2.0.
