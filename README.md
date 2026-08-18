# ServerAssistant 1.7.17

ServerAssistant 1.7.17 hardens natural-language wiki routing after live server tests. Conversational wrappers, broad category questions, use/function questions, commerce, deictic travel, and short subject swaps are all resolved locally before the normal model request. The architecture remains **one global conversation** with **one model request per scene**; no classifier/model request, embedding call, retry, or external wiki lookup was added.

## 1.7.17 natural wiki routing

- Natural wrappers such as `me dices`, `me podes decir`, `me podrias explicar`, `sabrias decirme`, `tenes idea de` and `me ayudas a saber` no longer become part of an item/entity name.
- `para q me sirve X?`, `que puedo hacer con X?`, `en que se usa X?`, `donde comercio?` and similar factual language now routes to the local wiki.
- `y que nodos hay?`, `y que otros minerales hay?`, `y que pociones hay?` and other broad category listings are direct category queries, not accidental continuations of one previous entity.
- Verified broad categories can anchor a later subjectless fact query (`que pociones?` -> `dame los crafteos`) without storing assistant prose.
- `y los nudos vivos` can replace the prior subject while inheriting the prior obtain/craft/drop intent.
- `que es Gamura?` -> `como llego hasta alli?` keeps only the verified place subject, allowing the wiki to answer with the documented `/lobby` route.
- Unknown direct entities do not poison the next follow-up anchor. Known entities remain valid subjects even when one requested property is undocumented.
- A factual continuation with no trusted subject fails closed instead of leaving GPT free to invent server-specific information.
- All changes are Java/RAM-only and preserve the configured wiki section/token caps.

## 1.7.16 wiki retrieval audit and hardening

- Fixes `y cuanta vida tiene?` / `cuanta vida maxima tiene?` being misread as new subjects. Generic stat/property words are now explicitly excluded from wiki identity terms, so these remain subjectless follow-ups.
- A subjectless factual follow-up is searched only when the same player has a live, immediately relevant wiki subject anchor. Without one, Java skips the wiki instead of searching generic `vida`, `drops`, `rareza`, etc. across all sections.
- Fact evidence is now scoped to the remembered entity entry for property/craft/function and subject-centric drop questions. A page mentioning `Goblin Arquero` can no longer borrow abilities or stats from `Goblin Asaltante` later in the same section.
- Reverse drop questions such as `y que mobs la dropean?` use a separate relation check: the remembered item must occur inside nearby drop/acquisition evidence, so unrelated Drops blocks do not prove the answer.
- Multi-word entity names must occur as one compact name. Separate phrases such as `Fragmento del Hierofante` and `Corona del Eclipse` cannot jointly prove `Hierofante del Eclipse`.
- Existence and requested-fact evidence are separate. A lore page may prove that Galumrog exists without proving an exact life value, weapon or drop table. Missing requested facets become `result=no_match` rather than invitations to improvise.
- The emergency local wiki extractor refuses to guess a subject for subjectless follow-ups; this removes another path for cross-entity factual leakage when a provider returns an empty reply.
- GitHub Actions now runs `validate_source.py` before Maven to catch duplicate JUnit methods, duplicate exact Java method signatures, duplicate `Set.of(...)` literals, conflict markers, version drift and accidental committed OpenAI keys before compilation.
- All changes are local Java/RAM logic. There is still exactly the same normal provider request for the scene and no extra OpenAI request, classifier, embedding call or retry.
- The source now contains 80 JUnit regression methods, and the GitHub workflow runs a source-structure validator before Maven so copy/paste duplicates fail early.

## 1.7.15 direct-first wiki follow-ups

The local wiki router now separates a **new named subject** from an **incomplete continuation** before ranking sections. For example, after asking about `Mango Resinoso`, `como consigo Esencia del Bosque?` is searched only as `Esencia del Bosque`; it is never concatenated with the mango question. A later `y que mobs la dropean?` can reuse the compact `Esencia del Bosque` subject locally.

This uses no second model/classifier call and does not add API requests. The subject anchor is Java-only, tiny, expires quickly, and never includes prior assistant prose.

## 1.7.14 strict named-entity grounding

Concrete server-entity questions now fail closed. A generic page about swords, jungles, spawns or crafting is not enough to prove that a requested named object exists. Java verifies that the candidate actually contains the requested entity name (with typo and compact-ID tolerance) before exposing that section as trusted wiki context. Unknown names therefore produce `result=no_match` even if related category pages score weakly.

The response pipeline also has a final Java no-match guard. If a small model still tries to invent a fact after `result=no_match`, the unsupported reply is replaced locally with a short uncertainty line before it reaches chat. Short factual follow-ups such as `sisi decime porfa` keep the previous wiki subject, and only prior player wording is used for entity verification so an earlier assistant hallucination can never become evidence.


## 1.7.13 wiki/no-match hardening

- Social follow-ups such as `q opinas de eso?` and `que piensas de eso iso` stay social and no longer create wiki `no_match` blocks.
- The local multi-speaker coverage fallback only uses real selected wiki sections. `result=no_match` is never treated as factual content and never creates extra per-player chat lines.
- Internal wiki control text is blocked at both extraction and final response normalization, so phrases such as `No trusted wiki section matched...` cannot reach public chat.
- Short server-entity questions such as `epicardo y la espada ultracita? iso` can trigger the local RAM wiki even without classic `que es/como consigo` wording. If nothing matches, the same model request receives `result=no_match` and must answer uncertainty instead of inventing NPCs, quests, stock or item sources.

## 1.7.13 group-entry routing

Java still decides group membership locally with **0 extra AI calls**. A nearby player is now compared not only with the aggregate thread but also with two strong active anchors: the last Isolda reply and the direct/SMART root lines in the current capture. This makes short contextual interventions such as `que cosas importantes?`, `como es eso de tension?`, `que si sientes?` or `no propongas nada En3Minutos` eligible to join without saying `Iso`.

The bundled pre-call candidate lookback is now 12 seconds, so a player may participate shortly **before** another player calls Isolda as well as during the capture window after the call. Only up to two pre-call candidate lines are promoted, and side-thread competition remains active. Clear lateral chat such as `alguien tiene piedra?` -> `yo tengo` stays outside the Isolda thread. Direct messages to an active participant with unrelated content (for example `En3 dame piedra`) are also kept lateral instead of joining merely because they mention a participant.

Default affinity tuning is now:

```yaml
global-conversation:
  scene:
    group-threading:
      pre-candidate-lookback-ms: 12000
      max-pre-candidate-lines: 2
      affinity:
        join-threshold: 44
        active-anchor-join-threshold: 34
        min-margin-over-side-thread: 10
        auto-follow-up-threshold: 64
        side-thread-lookback-ms: 8000
        max-side-lines: 5
```

Existing 1.7.11 installs using the exact bundled values (`6000/48/12/68`) are migrated non-destructively to the new defaults. Deliberately customized values are preserved.

## 1.7.13 reply shaping

`chat.max-messages-per-response: 3` still allows 2-3 independent replies in one model call, but Java no longer lets one ordinary answer become multiple chat bubbles just because the model returned `m:[line1,line2]`. Multiple public bubbles survive only when the model explicitly targets different current Isolda-thread players by name; otherwise the fragments are merged locally into one message. This costs **0 extra tokens** and prevents single-player turns such as `auch` from producing two consecutive Isolda messages.

The 1.7.11 semantic SMART trigger gate remains unchanged: a live SMART timer is permission to continue, not permission for every next public message to spend a request. Public/other-player side chat stays local; natural continuations still trigger.

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
        join-threshold: 44
        active-anchor-join-threshold: 34
        min-margin-over-side-thread: 10
        auto-follow-up-threshold: 64
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
