# Changelog

## 1.6.5 - Grounded observation + deterministic moderation

- Fixed held-item intent matching for natural possessive phrases such as `que tengo en mi mano?`; `mano` is now matched as a token instead of relying on a few exact phrases.
- Inventory context is query-specific (`requested=held|armor|general`) so hand/armor questions receive a small unambiguous trusted block instead of a noisy full inventory dump.
- Added target-aware local context selection: explicit named players are preferred for inventory, player-data and profile queries. `donde esta tablos16?` now supplies Tablos' row rather than leading with the requester.
- Added an auto-updated `personality.yml` `capabilities-note` plus a dynamic `[CAPABILITIES]` system summary. Isolda is explicitly told that supplied inventory/player/profile data is direct in-world observation and must not answer `no puedo verlo` when the fact is present.
- Moderation now includes a built-in Spanish profanity lexicon combined with the user's `strike-terms`, plus optional one-edit/adjacent-transposition typo tolerance.
- Added deterministic `tools.mute.policy.auto-action-on-threshold`: with `activation=ask`, reaching the configured threshold automatically creates a real admin approval; with `activation=smart`, it executes immediately. The model no longer has to decide to request the mute.
- `/sva tools moderation` now reports `strikes`, `eligible`, `pending` and a reason such as `admin-protected`, `below-threshold`, `approval-pending` or `cooldown`.
- Admin protection remains unchanged: OP/`sva.admin` targets cannot be muted while `allow-admin-targets: false`, although their strikes may still be visible for testing.
- Added moderation debug logging switch and new auto-updated config keys without overwriting existing user values.

## 1.6.4 - Modular MMOCore + MDVSocial profile integrations

- Added `integrations.yml`, auto-created and non-destructively updated alongside the existing runtime/personality/wiki files.
- Added independently toggleable read-only integrations for MMOCore and MDVSocial; both are soft dependencies and safely disappear when the external plugin is absent.
- Added the local `profile` CONTEXT tool. It merges relevant external player data before the same single model request, so it does not create a tool-call/model-call loop.
- MMOCore profile context can expose class-as-race, RPG level/experience, configured professions, attributes, selected/auto-discovered stats, resources and unspent points.
- MMOCore uses reflection first and PlaceholderAPI as a configurable fallback, avoiding a hard compile dependency. Profession/attribute/stat outputs are bounded to protect prompt size.
- MDVSocial integration reads the equipped title through the public `MDVSocialAPI` when available, with PlaceholderAPI fallback.
- Added `/sva integrations [list|set <mmocore|mdvsocial|all> <enabled|disabled>]`. Runtime toggles are persisted to `integrations.yml` and do not require a restart.
- Added `/sva tools run profile <player>` for deterministic integration testing.
- Removed generic RPG `nivel/xp` intent from vanilla `player-data` so MMOCore level is not confused with Minecraft experience level; explicit `nivel vanilla/xp vanilla` still uses player-data.
- Added trusted PROFILE grounding to CORE so Isolda uses the supplied race/title/RPG values rather than guessing.

## 1.6.3 - Split YAML configuration + non-destructive auto-update

- Split the old monolithic `config.yml` into `config.yml` (runtime), `personality.yml` (character prompt), and `wiki.yml` (retrieval + knowledge).
- Added non-destructive automatic config updates on startup and `/sva reload`: bundled keys missing from a user file are added automatically while existing values are preserved.
- Added one-time 1.6.2 migration: existing `prompt:` is moved to `personality.yml`, `advanced-context:` is moved to `wiki.yml`, and legacy `tools.wiki.pages` is imported when present.
- Creates `backups/config-before-1.6.3.yml` before the first split migration.
- Added per-file `config-version` schema markers for future explicit migrations.
- `/sva reload` now reloads and auto-updates all three YAML files.
- Wiki retrieval and `/sva tools run wiki ...` now read `wiki.yml`; AI personality now reads `personality.yml`.
- Updated YAML validation to check all three bundled files.

## 1.6.2 - Fresh-action gating + grounded inventory + moderation policy

- ACTION calls are now Java-gated against the current trigger/window, so an old lightning request cannot keep firing in later scenes.
- Stale/policy-blocked ACTION calls can suppress their paired misleading chat reply.
- Inventory context puts `mainhand` first and CORE treats trusted inventory/player context as direct observation; Isolda should answer held-item questions instead of asking the player what they hold.
- Added deterministic mute eligibility: configurable directed abuse strikes in a rolling window. Chat requests such as `mutea a X` cannot bypass the policy.
- `mute.activation: ask` still requires admin approval after eligibility; switching it to `smart` allows automatic execution only after the same Java policy passes.
- Added `/sva tools moderation` to inspect current strike/eligibility state.

## 1.6.1 - Action-tool reliability + held-item context

- OpenAI primary requests can force JSON-object response mode (`provider-response.force-json-object-openai: true`) so the `m`/`t` envelope stays machine-readable and ACTION calls are not lost to plain-text fallback.
- CORE now forbids fake stage-direction actions such as `*invoca un rayo*` unless the matching ACTION call is present in `t`.
- ACTION tool prompt explicitly distinguishes spoken chat (`m`) from real server actions (`t`).
- Lightning accepts a unique online-player prefix (for example `tablos` -> `tablos16`) while still rejecting ambiguous prefixes.
- Sound tool descriptions now include semantic hints so requests like `asustanos` or `celebra` can map more naturally to configured sounds.
- Inventory intent matching recognizes common typos such as `invetnario` and hand-related phrases.
- Inventory context now includes `mainhand` explicitly and can expose a small bounded amount of held-item lore for questions such as `que tengo en la mano?` / `que dice?`.

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
