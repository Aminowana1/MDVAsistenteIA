# ServerAssistant

ServerAssistant 1.5 replaces the old logical conversation/slot router with one global public conversation designed for an NPC living in a Minecraft server chat.

## Scene model

1. A direct `Iso` / `Isolda` mention opens a scene (or a short smart follow-up from the same player who directly addressed Isolda).
2. Java reads a configurable amount of recent public chat/events from local logs.
3. Java listens for another configurable window (default 1500 ms).
4. Java builds an involvement graph and removes unrelated chat locally.
5. The scene is capped (default 10 chat lines + 2 events).
6. Relevant wiki sections are selected locally in Java.
7. One normal model request is sent and Isolda reacts to the scene as a whole.

Events and ordinary chat do not cost API tokens by themselves. There are no per-player slots, group sessions or busy notices.

## Important config

```yaml
global-conversation:
  trigger-mode: smart
  smart-follow-up-ms: 12000
  scene:
    capture-window-ms: 1500
    pre-lookback-ms: 10000
    max-chat-messages: 10
    max-pre-chat-messages: 5
    max-events: 2
    max-pre-events: 1
  history:
    max-scenes: 2
    max-messages-per-scene: 4
```

For the strictest API economy, use `trigger-mode: mention`, keep fallback disabled, `provider-retry.max-503-retries: 0`, and keep local wiki retrieval enabled.

## Per-player smart follow-up

`global-conversation.smart-follow-up-ms` is not a global chat latch. After Isolda replies, only players whose direct address to Isolda was actually included in that scene receive their own continuation timer. Players who merely appeared as contextual chat/events cannot trigger a new API call without saying `Iso`/`Isolda`. If two players both address Isolda inside the same 1.5s capture, both get independent timers.

This keeps natural follow-ups while preventing unrelated public chat from waking the AI and spending tokens.

## Versioning / build artifacts

`pom.xml` is the only place that owns the project version. Maven filters that value into `plugin.yml`, and GitHub Actions reads the same `artifactId` + `version` to locate and upload the generated JAR. Do not hard-code versioned JAR names in the workflow.

Example: after changing only `<version>1.5.2</version>` in `pom.xml`, the build automatically produces and uploads `ServerAssistant-1.5.2.jar`, and Paper sees version `1.5.2` in `plugin.yml`.
