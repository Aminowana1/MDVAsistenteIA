# Changelog

## 1.6.0 - Global conversation + alternate-branch feature merge

### Preserved from MDVCRAFT 1.5.x

- One global public conversation; no player/group slots and no busy notices.
- Per-player smart follow-up timers.
- Pre-trigger lookback + post-trigger scene capture.
- Local involvement/relevance filtering before token caps.
- Events are local context only and never create requests by themselves.
- Small filtered scene history.
- Local one-call wiki retrieval.
- Serialized provider requests, local RPM/cooldown handling, optional fallback.
- Protocol leak blocking, plain-text recovery and assistant self-prefix stripping.
- Dynamic Maven/GitHub artifact versioning.

### Merged/improved from the alternate branch

- Added trusted current time/date/online-player context.
- Admin authority is marked only from Bukkit OP/`sva.admin`, never from player-written text.
- Added local Player Data context.
- Added local Inventory context.
- Added curated Sound action.
- Added harmless visual Lightning action.
- Added configurable Mute action.
- Added Schedule action; unlike the alternate branch TODO, this is implemented and schedules an already-generated line without another AI request.
- Added `smart` / `ask` / `never` tool modes.
- `ask` now has a real Java approval queue instead of trusting the model to ask first.
- Added `/sva approve <id>` and `/sva deny <id>`.
- Added `/sva tools list|pending|set|run`.
- Added `/sva trigger`.
- Added `/sva listener` and `/sva listen` controls for chat modes/events.
- Fixed the alternate event-toggle behavior so `disabled` actually stores `false`.
- Added optional idle scheduling from the alternate config and implemented it for real; disabled by default to protect API spend.
- Added `/sva listener idle <enabled|disabled>`.
- Wiki loader accepts the MDVCRAFT `advanced-context.wiki` layout and alternate `tools.wiki.pages` layout.
- Context tools are pre-resolved locally rather than causing tool-call -> second-model-request loops.
- Action calls return in the same compact response and are validated by a Java allow-list.

### Naturalness/context improvements

- Bundled Isolda 2.1 prompt discourages RPG/NPC receptionist patterns, repetitive "aventuras/historias/Gamura" phrasing and forced questions.
- Default temperature raised to `0.85` for a little more conversational variation.
- Trusted recent-event lookup can answer questions like `quien llegó?` from local event memory without always sending event logs.
- Server context exposes online names compactly so Isolda does not guess who is connected.

### Safety/abuse limits

- `mute` defaults to `ask` and protects OP/`sva.admin` targets by default.
- Action tools are explicit allow-listed names only; arbitrary console commands are not exposed.
- Max action calls per response and approval queue size/expiry are configurable.
- Schedule delays/pending count are bounded.
- Player/inventory local context and wiki chunks have configurable caps.

## 1.5.1 - Per-player smart follow-up

- Smart continuation rights are tracked independently per player.
- Context-only participants do not inherit follow-up rights.

## 1.5.0 - Single Global Conversation

- Replaced logical conversation slots/groups with one global scene pipeline.
- Added local chat/event rolling logs, involvement filtering and one-call local wiki retrieval.
