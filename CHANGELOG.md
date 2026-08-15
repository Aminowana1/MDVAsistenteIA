# Changelog

## Build system - automatic version propagation

- `pom.xml` is now the single source of truth for the project version.
- `plugin.yml` receives `${project.version}` through Maven resource filtering.
- GitHub Actions discovers the artifactId/version from `pom.xml` and uploads the generated JAR dynamically.
- GitHub Actions no longer contains a hard-coded `target/ServerAssistant-X.Y.Z.jar` path.
- Updated official GitHub actions to Node 24-capable majors (`checkout@v5`, `setup-java@v5`, `upload-artifact@v6`).
- `build_replace.py` no longer requires `target/` to exist before the first build.

## 1.5.1 - Per-player smart follow-up

- `smart-follow-up-ms` is now tracked independently per player.
- Only players who directly addressed Isolda in the answered scene may continue without saying `Iso` again.
- Context-only participants do not inherit follow-up rights.
- Multiple direct addressers inside one capture each receive their own timer.
- Expired/disconnected players are removed from the follow-up map.
- `/sva status` now reports `smart_followups=<count>`.
- Smart follow-up scenes no longer seed every actor from the previous scene into the new relevance filter; the compact scene history already provides continuity, reducing unrelated context/tokens.

## 1.5.0 - Single Global Conversation

Major routing rewrite based on MDVCRAFT live testing.

- Removed player conversation slots, warm sessions, participant join routing, hand-offs, busy notices and group-slot lifecycle.
- Isolda now has one global public-chat conversation.
- A direct mention (or short global smart follow-up) opens one scene window.
- Scene defaults: 10s local lookback + 1.5s post-trigger capture, max 10 relevant chat lines and 2 relevant events.
- Public chat/events are kept in local rolling logs and cost zero AI tokens until a scene is triggered.
- Java expands an involvement graph (A -> B mentions A -> C mentions B -> C kills B) and filters unrelated players before sending context.
- Events never trigger AI requests on their own.
- One normal model request per scene. Removed the model wiki tool loop from the request path.
- Wiki lookup is now local Java retrieval before the model request, with configurable max sections/size.
- Short scene memory keeps only a configurable number of recent filtered lines/replies.
- No "Isolda is busy with other conversations" message exists anymore.
- Provider requests remain serialized; extra completed scenes can wait in a small global queue.
- Kept OpenAI/Gemini provider fallback, rate-limit cooldown handling, output sanitation and self-prefix/protocol leak protections.
