# Changelog

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
