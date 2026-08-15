# ServerAssistant 1.6.0

ServerAssistant 1.6 keeps the MDVCRAFT **single global conversation** design from 1.5 and merges the useful capabilities from the alternate ServerAssistant branch without restoring conversation slots or multi-request tool loops.

## Global scene model

1. `Iso` / `Isolda` (or an eligible per-player smart follow-up) opens one global scene.
2. Java reads a short configurable lookback from local chat/event logs.
3. Java listens for the configured capture window (default 2 seconds).
4. A local involvement graph removes unrelated players/messages.
5. The scene is capped after filtering (default 10 chat lines + 2 events).
6. Wiki/player/inventory context is selected locally before the model call.
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
- Wiki compatibility with both `advanced-context.wiki` and the alternate branch's `tools.wiki.pages` layout.

## Tool architecture

`CONTEXT` tools are resolved locally before the one model request:

- `wiki`
- `player-data`
- `inventory`

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

`mute` defaults to `ask`, refuses OP/`sva.admin` targets unless explicitly allowed, and uses a configurable command template.

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

To release 1.6.1, for example, change only:

```xml
<version>1.6.1</version>
```

The workflow automatically expects and uploads `target/ServerAssistant-1.6.1.jar`.
