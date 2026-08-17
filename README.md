# ServerAssistant 1.7.11

ServerAssistant 1.7.11 keeps the MDVCRAFT **single global conversation** architecture and **one model request per scene**, and adds a zero-token semantic gate before an existing SMART follow-up is allowed to create that next scene.

A SMART timer now means “this player may continue naturally”, not “every next public-chat line must call the model”. Java compares the new line with the player's last answered Isolda exchange and with recent competing side-chat. Clear continuations still trigger; clear public/other-player chatter stays in RAM and costs no request. Ambiguous lines are intentionally allowed by default so optimization does not make conversation brittle. The same gate also applies to SMART holders speaking inside somebody else's already-open group capture.

Examples: `bien gracias, que haces?`, `voy a minar` and `y tu que opinas?` continue; `alguien tiene piedra?`, `Wachi ven al spawn`, a side-thread `yo tengo`, or a bare `xd` normally do not create/renew an Isolda request. This uses only local Java scoring: **0 embeddings, 0 classifier calls, 0 extra tokens**.

The group router now uses a local **thread-affinity competition** instead of mainly relying on explicit bridge phrases. Every nearby line is scored against Isolda's active exchange and against recent competing side-chat. The score combines chronology, references to active participants, lexical/topic overlap, question-answer compatibility, deictic/continuation language and conversational shape. Explicit phrases such as `dile`, `decile` or `mientes` remain useful bonus evidence, but they are no longer the rule that decides membership.

A player joins Isolda's SMART conversation only when the active-thread affinity is high enough and beats the best recent side-thread by a configurable margin. This keeps `Pedrox: alguien tiene piedra` + `Wachi: yo tengo` outside Isolda's conversation while still allowing contextual lines such as `eso no tiene ningún sentido` or `Amino dejate de hacerte el que está bien` to join when they clearly react to the active exchange. Strong matches inherit follow-up locally; borderline candidates can still be confirmed by the same response's compact `f` field.

This routing is entirely Java-local: **0 extra requests, 0 embeddings and 0 extra input-token blocks**. Only lines that survive the local routing are exposed as group participant candidates to the already-existing model request.

Relationship-memory hardening from 1.7.9 is unchanged: memories must remain concrete/self-contained, vague legacy rows are cleaned conservatively, and the default memory summary budget remains 120 characters.

## 1.7.10 group affinity examples

```text
Aminowana: iso como estas?
Isolda: bien gracias, y tu?
Pedrox: oye alguien tiene piedra
Aminowana: bien gracias, que haces?
Wachi: yo tengo

=> "yo tengo" fits Pedrox's recent side question much better than Isolda's thread.
=> Pedrox/Wachi stay ambient and receive no SMART continuity from Isolda.
```

```text
Aminowana: iso como estas?
Isolda: bien gracias, y tu?
Pedrox: dile la verdad
Aminowana: bien gracias, que haces?
Wachi: Amino dejate de hacerte el que esta bien

=> Pedrox bridges into the active exchange.
=> Wachi references Aminowana + the current "estar bien" topic and strongly beats side-chat affinity.
=> Both may inherit SMART continuity without another API request.
```

```yaml
global-conversation:
  scene:
    group-threading:
      affinity:
        join-threshold: 48
        min-margin-over-side-thread: 12
        auto-follow-up-threshold: 68
        side-thread-lookback-ms: 8000
        max-side-lines: 5
        debug-log: false
```

## 1.7.9 memory and group-thread examples

```text
Vague model memory:  Experiencia compartida
Stored memory:       Aminowana dijo a Isolda: "me duele la cabeza cuando hablo contigo"

Vague model memory:  Propuesta inesperada
Stored memory:       En3Minutos le propuso a Isolda formalizar una relacion: "aceptas ser mi novia?"
```

```text
Aminowana: iso como estas?
Isolda: bien, gracias, y tu?
Pedrox: oye alguien tiene piedra
Aminowana: bien gracias, que haces?
Wachi: yo tengo

=> Pedrox/Wachi remain ambient; they do not gain SMART continuity.
```

```text
Aminowana: iso como estas?
Isolda: bien, gracias, y tu?
Pedrox: dile la verdad
Aminowana: bien gracias, que haces?
Wachi: Amino dejate de hacerte el que esta bien

=> Pedrox/Wachi are linked to Isolda's active thread and may continue briefly without saying Iso again.
```

## 1.7.7 activity journal + relationships

The rolling activity journal is RAM-only and bounded by `activity-journal.retention-minutes`, `max-records`, `max-context-records` and `max-context-chars`. It records public player chat plus trusted join/quit/kick/death/advancement events, but it is inserted into the AI prompt only for recognizable historical questions. Both history scopes start `admin-only` for safe testing and can be changed at runtime:

```text
/sva history status
/sva history player admin-only
/sva history player everyone
/sva history general admin-only
/sva history general everyone
```

Examples include `Iso, que paso con Kroattan en las ultimas 2 horas?` and `Iso, que paso mientras no estuve?`. Absence summaries are bounded to the recorded disconnect->rejoin interval. If the journal started recording before the player's first observed join but has no earlier disconnect marker, it can summarize only that recorded pre-join portion and labels it as partial instead of inventing activity before recording began. A full server restart intentionally clears this rolling journal; relationship data does not.

Relationships are stored in `relationships.db` with an in-RAM cache. Everyone begins at score `0` unless the YAML default is deliberately changed. `relationships.yml` controls score limits, anti-farming cooldowns, the maximum 8 persistent / 12 recent memories, recent-memory expiry, behavior tiers, enemy ignore chances, dynamic smart-follow-up windows, romance capacity and optional event reactions. `romance.max-partners: 0` disables romance completely by default; set it to `1` for one exclusive partner. Partner, below-threshold, disabled and capacity-full behavior are separately configurable. Current partners also receive `happy` / `strained` / `critical` behavior based on score, while the persisted romance flag remains authoritative until a real breakup. Java hard-enforces the global partner cap and exposes the current partner roster to every relevant scene so the model cannot forget an existing partner just because they are not speaking. The bundled SQLite journal mode is `DELETE`, so committed changes live in the main `.db` file and a FileZilla/DB Browser copy is easier to inspect; `WAL` remains configurable if desired.

1.7.1 makes relationship writes reliable even with small models: the model may return either the compact `r` string or a structured JSON object, and a conservative zero-token Java fallback records obvious events such as date proposals, strong affection, support, apologies and direct hostility when the model omits `r`. Up to two players can be updated in the same group scene, so an insult and another player's defense are not forced to compete for one slot. The fallback uses the same anti-farming limits and never starts romance by itself unless the current scene contains an explicit partnership proposal and Isolda's visible reply clearly accepts it. Spontaneous event reactions remain disabled by default because each firing intentionally consumes one normal AI request.

Admin diagnostics/data controls:

```text
/sva relationship info <player|uuid>
/sva relationship memories <player|uuid>
/sva relationship set <player|uuid> <-100..100>
/sva relationship romance <player|uuid> <on|off>
/sva relationship purge <player|uuid>
/sva data purge <player|uuid>
```

The purge command removes the relationship profile, stored memories, rolling activity attributable to the player and the plugin's small runtime conversation traces. Database deletion is serialized behind older writes so queued saves cannot restore data after the purge.

## Runtime reliability + modular integrations

ServerAssistant now keeps configuration in five focused files:

```text
plugins/ServerAssistant/
├── config.yml        # runtime, providers, scenes, tools, moderation, chat output
├── personality.yml   # Isolda character/tone prompt only
├── wiki.yml          # local retrieval settings + wiki entries
├── integrations.yml  # optional external plugin profile hooks
└── relationships.yml # relationship scores, memories, romance and reactions
```

`integrations.yml` is created automatically when upgrading. MMOCore and MDVSocial are independent soft integrations: they can be enabled/disabled without removing either plugin, and missing plugins are skipped safely. Profile context is local and read-only, so it does not add another AI request.

On startup and `/sva reload`, each file is compared with the bundled defaults. **Only missing schema/settings keys are added**; existing user values and personality text are preserved. `wiki.*` entries are treated as user content, so deleted/custom wiki pages are not silently resurrected or overwritten. This lets newer plugin versions introduce config options without making the admin manually copy them.

The first 1.6.2 -> 1.6.3 start automatically migrates `prompt:` to `personality.yml` and `advanced-context:` to `wiki.yml`, then removes those old sections from `config.yml`. Before that split migration, the plugin creates `plugins/ServerAssistant/backups/config-before-1.6.3.yml`.

Automatic updating intentionally does not overwrite existing values when a future default changes. Any change that truly requires rewriting an old value should be handled by an explicit version migration in Java instead.


## 1.6.x action-tool reliability

OpenAI primary requests use JSON-object response mode by default so a normal answer and ACTION calls remain in the same parseable `m`/`t` envelope. If Isolda says she performs a real server action, the matching action must be present in `t`; stage-direction roleplay is not a substitute for the tool. Lightning also accepts unique player-name prefixes, and inventory context now exposes the main-hand item plus a bounded amount of lore when relevant.

## Global scene model

1. `Iso` / `Isolda` (or an eligible per-player smart follow-up) opens one global scene.
2. Java reads a short configurable lookback from local chat/event logs.
3. Java listens for the configured capture window (default 4 seconds).
4. A local involvement graph removes unrelated players/messages.
5. The scene is capped after filtering (default 6 chat lines + 1 event).
6. Wiki/player/inventory/profile context is selected locally before the model call.
7. One normal model request is made and Isolda reacts to the scene as a whole.
8. Optional action tool calls may be returned in that same model response and are allow-listed/executed by Java.

There are no conversation slots, group routers or "assistant busy" replies. Ordinary chat and events only populate local logs and cost no API tokens until a scene is triggered.

## Features merged from the alternate branch

- `/sva trigger`
- `/sva listener playerchat <always|mention|smart|disabled>` (`/sva listen` alias)
- `/sva listener events <death|advancement|join|quit|kick|joinquit|all> <enabled|disabled>`
- Optional idle scheduling after chat inactivity (`/sva listener idle <enabled|disabled>`), implemented in 1.6 and disabled by default because it consumes API requests.
- Trusted server context with current time/date/online player names and server-derived admin markers.
- Player Data context tool.
- Inventory context tool.
- Curated global Sound action tool.
- Harmless Lightning action tool.
- Mute action tool.
- Schedule action tool (the alternate branch documented it as TODO; 1.6 implements it without a second AI call).
- Wiki is stored in `wiki.yml`; 1.6.3 automatically migrates the old `advanced-context.wiki` / `tools.wiki.pages` layouts.

## Optional MMOCore / MDVSocial profile integrations

The `profile` context tool activates only for relevant questions such as race/class, RPG level, professions, attributes, stats, mana/stamina/points, or equipped title. MMOCore classes can be labeled as races with `mmocore.class-as-race: true`. MDVSocial equipped titles are read through its public API.

```text
/sva integrations
/sva integrations set mmocore enabled
/sva integrations set mmocore disabled
/sva integrations set mdvsocial enabled
/sva integrations set all disabled
/sva tools run profile <player>
```

Profession/attribute/stat counts and individual sections are bounded/configurable in `integrations.yml`, so a large RPG profile cannot dump unlimited data into the prompt. Normal unrelated chat receives no profile block.

## Tool architecture

`CONTEXT` tools are resolved locally before the one model request:

- `wiki`
- `player-data`
- `inventory`
- `profile` (optional MMOCore/MDVSocial data)

`ACTION` tools may be emitted in the same structured model response:

- `sound <name>`
- `lightning <player>`
- `mute <player>`
- `schedule <seconds> <chat message>`

Tool activation modes:

- `smart`: available automatically when relevant.
- `ask`: model requests are placed in a real Java approval queue and **do not execute** until `/sva approve <id>`.
- `never`: unavailable.

The model never receives an arbitrary console-command tool. Only explicitly registered actions can execute.

### Tool admin commands

```text
/sva tools list
/sva tools pending
/sva tools set <tool> <smart|ask|never>
/sva tools run <tool> [args...]
/sva approve <id>
/sva deny <id>
```

`mute` defaults to `ask`, refuses OP/`sva.admin` targets unless explicitly allowed, and uses a configurable command template. In 1.6.5 the moderation policy can automatically act when directed-abuse strikes reach the configured threshold: `ask` queues a real approval, while `smart` executes the allow-listed mute immediately. Built-in Spanish profanity coverage is combined with custom `strike-terms`, and `/sva tools moderation` shows threshold/protection/pending state.

## Per-player smart follow-up

`global-conversation.smart-follow-up-ms` remains per player, never global. Only players whose direct/smart line survived the answered scene receive their own short continuation window. Context-only participants cannot wake Isolda without mentioning her.

## Recent events

Deaths/joins/quits/kicks/advancements remain **context only** and never create model calls themselves. 1.6 also has a small semantic recent-event memory so questions such as `Iso quien llegó?` can retrieve a trusted recent join even when it fell outside the normal scene lookback.

## Optional idle scheduling

The alternate branch contained configuration for an inactivity-triggered request but no working implementation. 1.6 implements it under:

```yaml
global-conversation:
  idle-scheduling:
    enabled: false
    min-delay-ms: 30000
    max-delay-ms: 120000
    require-online-players: true
```

After real player chat, a random timer is started/reset. If chat stays quiet, at most one autonomous idle scene can be sent. It is disabled by default because it intentionally adds API usage.

This is separate from the `schedule` tool: `schedule` delays an already-generated Isolda line and therefore needs no future AI request.


## OpenAI prompt caching + local wiki cache (1.6.9)

For the GPT/OpenAI provider, ServerAssistant keeps the largest stable prompt prefix identical across requests: CORE instructions, `personality.yml`, output rules and the stable capability catalogue come first. Request-specific ACTION permissions, selected wiki chunks, player identities, inventory/profile data, timestamps/events and the current scene come afterwards.

OpenAI prompt caching is automatic on eligible prompts; ServerAssistant additionally sets a stable `prompt_cache_key` only on the OpenAI provider to improve cache routing. The key includes a short hash of the loaded stable prefix/model, so changing personality/instructions and running `/sva reload` automatically creates a new routing key.

```yaml
ai:
  prompt-cache:
    enabled: true
    key-prefix: mdvcraft-isolda
    log-usage: false
```

Set `log-usage: true` temporarily to see the `cached_tokens` reported by OpenAI in the console. This diagnostic does not make another model call.

The complete `wiki.yml` is **not** sent to GPT or stored as one giant remote prompt. It remains local. On startup/reload, ServerAssistant pre-normalizes every wiki section into a small RAM index; each scene scores that index and sends only the configured best 1-2 sections. This reduces repeated CPU/string work without increasing API context.

## Ambient player identity (1.6.8)

When a player becomes involved in a model scene, ServerAssistant automatically attaches a small trusted identity snapshot containing the equipped MDVSocial title, MMOCore race/class and MMOCore main RPG level. This happens locally before the same model request, does not consume a CONTEXT-tool slot, and lets personality rules react to player identity without requiring an explicit profile question. `integrations.yml -> identity-context` controls the feature and its safety bound.

## Build/versioning

`pom.xml` is the single source of truth for the version. Maven filters it into `plugin.yml`, and GitHub Actions reads the same Maven coordinates to upload the correct JAR automatically.

For 1.7.7 the Maven version is:

```xml
<version>1.7.7</version>
```

The workflow automatically expects and uploads `target/ServerAssistant-1.7.7.jar`.


### Reused GitHub repositories

ServerAssistant 1.6.5 buildfix scopes Maven compilation to the `me.kev.sva` package. If this source is uploaded over a repository that still contains source files from another plugin (for example MDVSocial), those unrelated Java files are ignored by the ServerAssistant build. A separate clean repository is still recommended for maintainability.
