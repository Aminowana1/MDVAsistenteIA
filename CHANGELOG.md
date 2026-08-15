# Changelog

## 1.6.9 - OpenAI prompt-cache friendly prefix + cached wiki index

- Keeps the GPT/OpenAI path as the primary target; Gemini/fallback providers do not receive OpenAI-specific `prompt_cache_key`.
- Builds CORE + `personality.yml` + stable output/capability instructions once per plugin load/reload and preserves them byte-for-byte at the beginning of every OpenAI request.
- Moves the per-scene ACTION allow-list after the stable prefix so lightning/sound/mute/schedule intent changes no longer invalidate the large reusable personality prefix.
- Adds `ai.prompt-cache.enabled`, `key-prefix` and optional `log-usage` settings. The routing key includes a short hash of the loaded stable prefix and model, so `/sva reload` naturally rotates it after personality/instruction changes.
- Optional cache diagnostics report OpenAI `prompt_tokens`, `prompt_tokens_details.cached_tokens` and completion tokens without creating an extra API request.
- Wiki remains local retrieval only; the full wiki is never sent to GPT. Its key/description/content normalization is now precomputed once into a RAM index instead of being repeated for every wiki section on every scene.
- `/sva reload` reconstructs both the stable prompt prefix and wiki index, so edits do not leave stale cached local data.
- No changes to the 4-second group-scene behavior, one-call architecture, action isolation, player identity context, moderation rules or wiki scoring weights.

## 1.6.8 - Ambient player identity context

- Every player involved in a model scene now carries a trusted compact identity snapshot with equipped MDVSocial title, MMOCore race/class and MMOCore main RPG level.
- Identity is injected automatically even when nobody asks for `/profile`; it creates no extra model request and does not consume a local CONTEXT-tool slot.
- Current PLAYER headers include `race=`, `level=` and `title=` so scene history keeps the identity attached to the speaker.
- EVENT actors and recent-event memory preserve identity snapshots as well, including quit/death actors who may be offline by the time the scene is sent.
- Referenced online players that do not speak still receive an entry in trusted `[PLAYER IDENTITIES]` / `[SCENE] involved` context.
- `level` is explicitly grounded as the main MMOCore level, never vanilla XP level; `title` is grounded as the equipped MDVSocial visual title, not automatically a rank.
- Added `identity-context` controls to `integrations.yml` with a safety bound of 12 players per scene. Existing integration settings are preserved by the non-destructive updater.
- Keeps all 1.6.7 safety fixes and the 1.6.6 four-second single group-scene architecture intact.

## 1.6.7 - Safety hotfix + intent precision

- Keeps 1.6.6 group-scene/action isolation behavior intact.
- `tools.action-safety.suppress-reply-on-rejected-call` is restored to `true`, so Isolda does not claim an action happened when every model action call was rejected. A narrow schema-6 migration repairs the mistaken 1.6.6 default while leaving other user settings untouched.
- Inventory enchantment intent no longer treats the generic words `encantado`/`encantada` as an enchantment query, avoiding false activation on names such as `Amatista Encantada`.
- Hidden Bukkit enchants (`HIDE_ENCHANTS`) are not exposed to the model; MMOItems/resource-pack glint enchants are reported only as `none_visible`.
- Player-data prefetch now requires a personal/deictic player-location or status intent instead of firing for generic wiki questions such as `donde aparecen los goblins?` or `cuanta vida tiene un goblin?`.
- Audited the event-listener command bug reported against the older `EventsCommandNode` branch. This source uses `CommandManager#setEvent` and already persists the requested `enabled` boolean instead of hard-coding `true`; an explicit regression comment was added.

## 1.6.6 - Group-scene grounding + action isolation

- Preserves the single 4-second global scene behavior: multiple players can still be interpreted as one social situation and Isolda may combine related reactions naturally.
- Marks previous scene/history as context-only and the new scene as the only ACTION authority. Old lightning/sound requests no longer look like fresh tool permissions.
- ACTION tool catalog is now dynamic per current scene. When the current scene does not request an action, the model receives `t must be []`; when it requests one, only the relevant action tool is exposed. This also lowers prompt tokens.
- A valid current action is no longer treated as failed merely because the model also leaked one stale action call. Java still blocks every stale/policy-invalid call.
- Local CONTEXT tools now resolve their targets from tool-relevant lines inside a group scene, so a later unrelated speaker does not steal another player's location/inventory/profile query.
- Inventory inspection now recognizes enchantment questions and common held-item references such as `mi espada` and `que es esto`. It supplies real Bukkit enchantments for the main hand and armor, including explicit `none` when an enchantment query finds none.
- Player-data recognizes `zona`, `region` and `bioma` and includes the current biome with world/coordinates.
- Recent-death retrieval recognizes natural phrases such as `me mataron`, `asesinaron` and `abatieron`.
- Local wiki scoring now lets one exact meaningful content hit satisfy the default `min-score: 2`, fixing short questions such as `hay misiones?` when the keyword exists in content but not in the section description.
- CORE grounding is stricter: broad wiki facts may not be expanded into invented merchant stock, item properties, mechanics or locations. Server clock data may not be presented as a player's real-world local timezone.
- Direct mentions are instructed to always produce one natural chat line; an optional diagnostic can log empty direct replies without making another AI request.
- Bundled config schema is now version 6. Existing user values remain preserved by the non-destructive updater.

## 1.6.5 buildfix

- Maven now compiles only `me/kev/sva/**/*.java` for the main plugin and its tests.
- Stale Java sources from a reused repository (for example `com/mdvcraft/mdvsocial/**`) no longer get compiled into ServerAssistant.
- Packaged resources are restricted to ServerAssistant's own YAML files plus the filtered `plugin.yml`, preventing stale resources from another plugin from leaking into the JAR.
- GitHub Actions prints a warning when unrelated Java sources are detected so repository contamination is visible without breaking the build.
- No runtime behavior, config format, or AI token usage changed. Plugin version remains 1.6.5.

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
