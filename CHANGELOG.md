# Changelog

## 1.4.1 - OpenAI primary + Gemini fallback

- Restored **OpenAI `gpt-4o-mini` as the default/primary provider**, matching the simple V1 request path.
- Added an optional `ai.fallback` provider; MDVCRAFT defaults it to Gemini 3.7 Flash.
- A primary 429 or transient 5xx/timeout can switch the **same uncommitted batch** to fallback without losing, duplicating, or restarting the player's conversation.
- Primary and fallback keep independent rolling request buckets and provider cooldowns; `/sva reload` does not erase either cooldown.
- If the primary is locally unavailable/missing but fallback is configured, the current request can still be served while the startup error remains visible in logs.
- Gemini-only synthetic continuation messages are now sent only to Gemini; normal GPT-4o mini conversations keep the cleaner V1-style turn sequence.
- `/sva status` now reports primary and fallback model, rolling request usage and cooldown independently.
- V1 flat `api-key` + `ai-model` settings migrate to the provider-neutral primary `ai.*` section without silently forcing Gemini.
- MDVCRAFT defaults: 60s smart conversation window, 12 logical history messages, 20 local OpenAI RPM, 4 local Gemini fallback RPM, 1s same-conversation request spacing and 1.2s batching.
- Preserves all 1.4 conversation routing, group slots, human-chat release logic, explicit hand-off, bounded queues, output caps, wiki tools, tool iterations, plain-text response recovery, 429 handling and 503 retry protections.

## 1.3.2

- Fixed Gemini responses being silently discarded when the model returns plain text instead of the requested YAML map.
- Added tolerant parsing for YAML/JSON maps, scalar text, string lists, fenced payloads, and common field-name variants.
- Plain-text fallback is deliberately read-only: it can create chat messages but never tool calls or server actions.
- Added optional provider-response diagnostics without logging malformed model output by default.

## 1.3.1

- Fixed Gemini 400 `Requests ending with a model turn are not supported` for ambient events and wiki follow-ups.
- Removed the hidden `hello world!` assistant seed from active conversation initialization.
- Added provider cooldown handling for Gemini 429 responses.
- Added bounded retry/backoff for temporary Gemini 503/high-demand responses.
- Lowered the default local AI request limit to 4/min for safer Free Tier testing.
- Fresh player turns can be rejected with a private in-character notice instead of silently waiting through long quota delays.
- Plain Minecraft chat output strips common Markdown/newlines and truncates at natural boundaries.
- Fixed Adventure/legacy color parsing for assistant and private notice messages.
- Strengthened core instructions against generic/hallucinated custom-server advice.

## 1.3.0 - Gemini 3.7 Flash

- Switched the default AI backend from OpenAI to Gemini 3.7 Flash (`gemini-3.7-flash`).
- Uses Google's official OpenAI-compatible endpoint so the existing Java request pipeline remains simple and stable.
- Added configurable `api-base-url`, defaulting to the Gemini compatibility endpoint.
- Changed the preferred API-key environment variable to `GEMINI_API_KEY`.
- Added safe fallback support for a Gemini key in `config.yml`.
- Added automatic migration of the old 1.2 default `OPENAI_API_KEY` / `gpt-4o-mini` values without overwriting real custom secrets or model choices.
- Logs the selected provider/model at startup without logging the API key.
- Preserves all 1.2.0 group conversations, slot limits, smart routing, batching, wiki lookup and rate-limit behavior.
- Keeps the architecture ready for native/structured function tools in the planned 2.0 agent layer.

## 1.2.0

### Group conversations
- Conversation slots now represent **logical conversations**, not individual players.
- One slot can contain several accepted participants (default maximum: 6).
- New players may join a recent group by explicitly referencing an existing participant or by using a conservative contextual follow-up such as `@SVA y el báculo?`.
- When multiple SVA conversations are active, the router refuses ambiguous auto-joins instead of guessing.
- Every accepted player's messages share the same conversation history, preserving speaker labels so the model knows who said what.
- Each participant has an independent smart timeout and follow-up counter.
- Addressing another member of the same SVA group does not eject either player from the conversation.
- Addressing someone outside the group still releases only the sender, not the whole group.
- A participant saying thanks/goodbye releases only that participant after the response; it cannot close everyone else's conversation.
- AI-requested closing is conservative in groups and never lets one speaker accidentally destroy the entire shared session.

### Anti-spam / compatibility
- Renamed the main slot setting to `conversation-control.max-active-conversations`.
- Older `max-active-player-conversations` configs are still read as a compatibility fallback.
- Busy notices remain private and joining an existing group does not consume an additional slot.

## 1.1.0

### Security
- Changed player chat from OpenAI SYSTEM role to USER role.
- Added bounded tool iterations and tool calls.
- Added per-player and global request rate limits.
- Added API-key environment variable support.
- No generic console execution capability is exposed.

### Conversation routing
- Replaced global `smart` activity state with isolated per-player sessions.
- Added configurable maximum active player conversations (default 2).
- Added private busy notices.
- Added deterministic detection for messages explicitly addressed to another online player.
- Added follow-up limits, session timeout, history reset, closing phrases, and AI-requested conversation closing.
- Player requests take priority over ambient/global event requests.

### Reliability
- All Bukkit/session state is now main-thread owned.
- AI network calls use a dedicated worker and return to the main thread.
- AI requests are globally serialized to preserve response order.
- Added bounded pending queues and event queues.
- Added hard output length/message limits.
- Added robust YAML parsing fallback.

### Configuration
- `advanced-context.lazy-mode: false` now preloads the full wiki.
- Implemented optional inactivity scheduling (disabled by default).
- Added GitHub Actions Maven build workflow.
