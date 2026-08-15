# ServerAssistant 1.6.5

ServerAssistant 1.6.5 keeps the MDVCRAFT **single global conversation** design from 1.5 and merges the useful capabilities from the alternate ServerAssistant branch without restoring conversation slots or multi-request tool loops.

1.6.5 hardens direct observation and moderation: held-item/armor/location context is target-aware and query-specific, personality gains a capability framing note without overwriting the main prompt, and moderation can deterministically queue/execute mute policy actions once configurable directed-abuse strikes reach the threshold.


## 1.6.5 reliability + modular integrations

ServerAssistant now keeps configuration in four focused files:

```text
plugins/ServerAssistant/
├── config.yml        # runtime, providers, scenes, tools, moderation, chat output
├── personality.yml   # Isolda character/tone prompt only
├── wiki.yml          # local retrieval settings + wiki entries
└── integrations.yml  # optional external plugin profile hooks
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
3. Java listens for the configured capture window (default 2 seconds).
4. A local involvement graph removes unrelated players/messages.
5. The scene is capped after filtering (default 10 chat lines + 2 events).
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

## Build/versioning

`pom.xml` is the single source of truth for the version. Maven filters it into `plugin.yml`, and GitHub Actions reads the same Maven coordinates to upload the correct JAR automatically.

To release 1.6.5, for example, change only:

```xml
<version>1.6.5</version>
```

The workflow automatically expects and uploads `target/ServerAssistant-1.6.5.jar`.
