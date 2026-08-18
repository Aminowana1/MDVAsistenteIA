# Changelog

## 1.7.17 - Natural wiki routing / live-chat hardening

- Fixes natural conversational wrappers such as `me dices como consigo viridita?`, `me podes decir...`, `me podrias explicar...`, `sabrias decirme...`, `tenes idea de...` and `me ayudas a saber...` contaminating entity identity. Wrapper language is stripped/ignored locally before entity verification.
- Fixes factual function/use questions that previously bypassed the wiki, including `para q me sirve un denar?`, `y para que me sirve la viridita?`, `que puedo hacer con X?` and `en que se usa X?`.
- Fixes broad list questions with conversational `y`, especially `y que nodos hay?`, `y que otros minerales hay?`, `y que pociones hay?` and `y que comandos hay?`. These are direct category lookups, not follow-ups to the previous concrete entity.
- Broad verified categories can now become tiny local anchors, so `que pociones?` -> `dame los crafteos` stays on potions without carrying prior assistant prose.
- Fixes elliptical subject swaps such as `y los nudos vivos` retaining the previous factual intent while replacing the subject (`Rama Resinosa` -> `Nudo Vivo`) instead of falling back to free model knowledge.
- Fixes deictic travel continuity such as `que es Gamura?` -> `como llego hasta alli?`; the second line reuses only the verified `Gamura` anchor and can retrieve `/lobby` from the same local wiki.
- Commerce verbs (`comercio`, `comprar`, `vender`, `intercambiar`, etc.) are treated as intent/noise rather than fake entity names, preventing over-strict rejection of `donde comercio?`.
- Subjectless factual turns with no trusted wiki anchor now fail closed with a local `result=no_match` block instead of silently returning no wiki context and allowing the model to improvise.
- Wiki anchors are now updated only after local subject/category existence was actually verified. Unknown direct entities clear an older unrelated anchor; known entities may remain anchored even when the exact requested property is undocumented.
- Preserves the 1.7.16 entity/fact evidence gates, reverse-drop validation, named-entity anti-hallucination guard, group threading and SMART behavior.
- No second OpenAI request, classifier, embedding, retry or external lookup was added. All routing and verification remains local Java over the existing in-memory wiki index.
- Regression coverage expanded to 94 JUnit test methods plus source-structure validation.

## 1.7.16 - Wiki retrieval audit / fact-scoped grounding

- Fixes the failing `propertyOnlyWikiQuestionsRemainFollowUps` regression: `y cuanta vida tiene?`, `que habilidades tiene?`, `que probabilidad tiene?` and equivalent property-only wording no longer creates a fake independent subject.
- Subjectless factual follow-ups now require a live per-player wiki anchor from the immediately previous wiki scene; otherwise local retrieval is skipped safely instead of searching generic property words.
- Adds entity-scoped fact evidence for property, crafting, function and subject-centric drop questions so stats/abilities/recipes from a neighboring entry in the same wiki section cannot leak into the requested entity.
- Adds separate reverse-drop evidence validation for questions such as `que mobs la dropean?`: the remembered item/material must occur near actual drop/acquisition evidence.
- Tightens multi-word entity verification so two nearby but unrelated phrases cannot jointly prove a made-up compound entity name.
- Separates entity existence from requested-fact existence: a page that proves an entity exists cannot answer life, drops, weapons, price, abilities, etc. unless that fact type is actually present.
- Local fallback extraction no longer guesses the missing subject of subjectless wiki follow-ups.
- Adds `validate_source.py` to the GitHub workflow before Maven to catch duplicate tests/method signatures, duplicate `Set.of` literals, merge markers, version mismatches and obvious committed OpenAI keys.
- No additional model/API call, embedding, classifier or retry was added. Wiki ranking, subject routing and all new safety checks are Java-local over the existing in-memory index.
- Regression coverage is expanded to 80 JUnit test methods; the current wiki routing helpers were also audited against the 220-section MDVCRAFT wiki used in live testing.

## 1.7.15 - Direct-first wiki subjects

- Wiki retrieval now treats every self-contained factual question as a fresh direct subject, even when it uses continuation-like wording such as `como consigo`.
- Added a tiny per-player local wiki subject anchor used only for genuinely incomplete follow-ups (`y donde sale?`, `y como se consigue?`, `sisi decime porfa`).
- The anchor stores a compact subject instead of previous player/assistant prose, preventing topic contamination and query growth.
- Entity verification on follow-ups uses the original trusted subject query, while current property words only influence ranking.
- New direct factual questions replace the anchor immediately; stale anchors expire after 120 seconds and are also gated by the immediately previous wiki scene.
- No extra OpenAI/model request is added; all routing/retrieval changes are local Java over the existing in-RAM wiki index.
- Added regression coverage for Mango Resinoso -> Esencia del Bosque topic switching and subjectless follow-ups.

## 1.7.14 - Strict named-entity wiki grounding

- Fixes invented items such as `Espada de Artera`, `Arco de la Jungla` and `Espada de Nagamuta` receiving unrelated but superficially related wiki pages. Concrete named-entity questions now require the selected section to actually contain the requested entity name (with typo/compact-name tolerance) before it can count as trusted evidence.
- Fixes a ranking bug where `donde/como consigo` gave generic `spawn-*` pages intent bonuses even when they had zero subject match; intent bonuses now only re-rank sections that already matched the actual subject.
- Adds strong local scoring for verified concrete names, including compact wiki identifiers such as `ARCOBOSQUE`, while keeping unknown names fail-closed as `result=no_match`.
- Adds a Java-side final no-match grounding guard: for a single factual speaker, any model hallucination after `result=no_match` is replaced locally with `no tengo informacion fiable sobre eso`; in group scenes, replies tied to an unverified speaker/entity are removed and replaced with the same honest fallback. No second AI request is used.
- Short factual continuations such as `sisi decime porfa`, `dale decime mas` and similar now keep the previous wiki subject instead of becoming an ungrounded SMART turn. Assistant-generated text is never used as entity-verification evidence, so an earlier hallucination cannot bootstrap itself into a later fact.
- Adds `que sabes de/del` as explicit wiki intent and strengthens CORE/default personality wording that related generic pages do not prove a named entity exists.
- Keeps 1.7.12/1.7.13 group threading, SMART gating, reply coalescing, relationship behavior and one-request-per-scene architecture unchanged.

## 1.7.13 - Wiki no-match / group fallback hardening

- Fixes deictic social questions such as `q opinas de eso?` / `que piensas de eso iso` being misclassified as wiki follow-ups merely because they contain `eso`. They now remain normal social/group conversation and do not generate `WIKI result=no_match` blocks.
- Fixes the local multi-speaker factual-coverage safety net treating `result=no_match` as if it were trusted factual content. Local coverage now runs only when an actual trusted wiki section was selected.
- Prevents internal wiki control text such as `No trusted wiki section matched this server-knowledge question...` from ever being extracted or broadcast as Isolda dialogue. Adds a final response-layer leak guard as defense in depth.
- Adds a zero-token implicit server-entity wiki fallback for short natural questions such as `epicardo y la espada ultracita? iso`: plausible item/NPC/entity questions can now perform local wiki retrieval even without `que es/como consigo`. A no-match then grounds the same model request instead of leaving it free to invent server lore.
- Adds `quien es`, `existe`, `conoces a` and `te suena` to explicit wiki knowledge intent while preserving social exceptions such as `quien es tu pareja`.
- Empty direct replies with a wiki no-match now recover to one natural Spanish uncertainty line; they never echo protocol/control text.
- Keeps 1.7.12 group-entry scoring, side-thread filtering, SMART behavior, one-request architecture and reply-burst coalescing unchanged. No second model call was added.

## 1.7.12 - Natural group entry + single-subject reply shaping

- Fixes group **entry** being stricter than group exit/SMART continuation. Java now scores a nearby player against the aggregate Isolda thread, the last Isolda reply, and direct/SMART root lines, then still compares the result against competing side-chat.
- Short contextual echoes such as `que cosas importantes?`, `como es eso de tension?`, `que si sientes?`, and participant-linked interjections can join without explicitly saying `Iso` when they fit the active exchange.
- Increases the bundled pre-call candidate lookback from 6s to 12s so a player can join from a relevant line shortly **before** another player calls Isolda, as well as from lines during the normal post-trigger capture window. Only the tiny configured pre-candidate cap is promoted.
- Retunes bundled group defaults from `join=48 / margin=12 / auto=68` to `join=44 / margin=10 / auto=64` and adds `active-anchor-join-threshold=34`. Exact untouched 1.7.11 values are migrated; custom tuning is preserved.
- Keeps unrelated direct player-to-player requests lateral even when they mention an active participant, preventing `En3 dame piedra` from joining a romance/social thread merely because `En3` is present.
- Adds richer optional group debug logging (`base`, `assistant_anchor`, `root_anchor`, side score and decision), all Java-local and zero-token.
- Fixes one model thought becoming two or three public Isolda messages. Multiple `m[]` entries are now merged into one bubble unless they explicitly target different current Isolda-thread players. Independent multi-speaker replies remain possible in the same single model request.
- Adds regression tests for natural group-entry anchors and reply-burst coalescing.
- Config schema is now 16. No embeddings, no classifier request and no second model call were added.

## 1.7.11 - Zero-token semantic SMART trigger gate

- A live SMART timer no longer makes every next message from that player open an AI request. Before triggering, Java locally checks whether the line still belongs to Isolda's exchange.
- Clear public-chat requests such as `alguien tiene piedra?`, direct side-addresses such as `Wachi ven al spawn`, trivial acknowledgements such as `xd`, and replies that fit a recent competing side-thread better now remain local and cost **0 API requests / 0 model tokens**.
- Natural continuations such as `bien gracias, que haces?`, `voy a minar`, `y tu que opinas?` and other short answers after Isolda asked a question continue normally.
- Ambiguous human chat remains allowed by default (`allow-ambiguous: true`) so the optimization prefers a rare extra request over breaking a real conversation.
- The same semantic SMART gate is applied when another player's 4-second group capture is already open, preventing an unrelated SMART holder from being force-promoted merely because their timer is still alive.
- SMART expiration remains relationship-based and unchanged; a side/public message does not renew the timer, so the player naturally falls out if they keep talking elsewhere.
- Adds `global-conversation.smart-trigger-gate.*` thresholds and optional local debug logging. Missing keys merge non-destructively; config schema is now 15.
- No embeddings, no second classifier model, no extra provider call and no extra prompt block were added.

## 1.7.10.2 - Request-refusal edge hotfix

- Fixed `RelationshipRequestPolicy.shouldRefuse(...)` evaluating `chance >= 1.0` before confirming that the current line was actually a request.
- A configured 100% refusal now means **refuse every request**, not every ordinary/chat line. For example, `hola iso` remains non-request dialogue even with `arch-enemy: 1.00`.
- Keeps the existing deterministic per-scene refusal roll, zero-token routing, wiki/tool withholding and all 1.7.10.1 group-thread behavior unchanged.
- The existing regression `probabilityEdgesAreHardGuarantees` now matches the implementation contract.

## 1.7.10.1 - Compile hotfix

- Removed a duplicate `commonPrefixLength(String, String)` helper accidentally introduced in `ConversationManager`.
- No runtime behavior, token usage, wiki routing, relationship logic, or group-thread affinity behavior changed from 1.7.10.

## 1.7.10 - Zero-token group thread affinity router

- Replaces the 1.7.9 mostly rule/phrase-driven group candidate check with a local weighted affinity router.
- Every nearby public line is scored against Isolda's active thread and against recent competing side-chat before it can gain group SMART continuity.
- Active-thread scoring combines participant-name/alias references, lexical topic overlap, conversational reply shape, deictic references, question/answer compatibility and recency.
- Recent side-chat uses the same local evidence plus generic elliptical-answer detection, so `alguien tiene piedra?` -> `yo tengo` remains attached to the lateral conversation instead of Isolda.
- Explicit bridges/social phrases remain high-confidence bonus signals but are no longer a whitelist or the primary source of intelligence. Unlisted contextual replies such as `eso no tiene ningun sentido` can join from structure + recency even when no hardcoded phrase matches.
- Adds configurable `group-threading.affinity` thresholds, side-thread lookback/cap and optional zero-token debug score logging. Missing keys auto-merge non-destructively.
- Strong affinity can grant SMART continuity locally; borderline candidates may still be confirmed through the existing `f` field in the same normal model response.
- No embeddings, no classifier model and no additional API request were added. Side-thread routing happens over a handful of recent RAM chat lines.

## 1.7.9 - Specific memories + safe group thread routing

- Relationship memories are now required to be concrete and self-contained instead of vague labels such as `Experiencia compartida`, `Propuesta inesperada`, `Confianza y afecto profundos` or `Me gusta hablar contigo`.
- Vague model memories are replaced locally, with zero extra AI requests, by a grounded summary built from the player's actual line. The player's name is always retained so future scenes cannot confuse who did or said something.
- On first 1.7.9 load, legacy memory rows that are too vague to reconstruct safely are removed from active storage instead of continuing to influence Isolda; specific old rows are preserved and automatically prefixed with their owning player when the actor was missing.
- The bundled relationship memory budget rises from 90 to 120 characters (only the untouched old default is migrated) so proposals, complaints, promises and other events can retain enough detail to be useful later.
- Group SMART continuity no longer promotes every player who happened to speak during the capture window. Java now performs a small local thread check using chronology, references to current participants, conversation topic and explicit social bridges.
- Side chat such as `Pedrox: alguien tiene piedra` + `Wachi: yo tengo` is excluded from Isolda's conversation and receives no SMART continuation merely for being nearby.
- Social bridges such as `Pedrox: dile la verdad` and linked interventions such as `Wachi: Amino dejate de hacerte el que esta bien` can join the active Isolda thread even when they do not literally say `Iso`, then receive the normal relationship-based SMART follow-up window.
- Only prefiltered `[GROUP PARTICIPANT CANDIDATES]` can be confirmed by the model's compact `f` bookkeeping field; ambient public chatter cannot grant itself follow-up, relationship authority or tool authority.
- Very recent pre-trigger bridge lines can be considered when deciding whether a player is joining the still-open exchange, while normal old chatter remains context-only.
- Fixed a stable-system-prompt contradiction that still advertised `max_chat_messages=1`; it now uses the actual configured `chat.max-messages-per-response` value, allowing the intended 1-3 short group replies in one model request.
- No classifier/model request was added. Memory grounding, thread routing and participant filtering are Java-local.

## 1.7.7 - Natural group scenes + hostility request refusal

- Group scenes are no longer treated as one ticket per addressed player. The same single model request now distinguishes shared discussion, independent requests and mixed scenes; a shared conversation may receive one natural group reaction, while truly independent questions may receive 2-3 short `m` lines.
- Java no longer auto-fills a missing social reply for every participant. The zero-call coverage fallback is now restricted to omitted independent wiki/factual questions that already had trusted context selected.
- Independent factual replies in multi-speaker scenes are name-prefixed only for coverage verification; shared/group reactions do not need player prefixes.
- Adds `behavior.request-refusal.by-tier` in `relationships.yml`: bundled refusal chances are arch-enemy 90%, enemy 65%, hostile 35%, disliked 15%. The decision is made once locally per scene and is stable for that scene.
- A refused hostile request receives no wiki/read-context and no ACTION authority, reducing prompt tokens and preventing accidental compliance. The same normal model request can answer with a hostile refusal such as telling an arch-enemy to look it up themselves.
- `current_request_policy=REFUSE/ALLOW_THIS_TIME` is injected only for hostile-tier requests with configured refusal chance. REFUSE is authoritative even for factual/wiki or harmless ACTION requests; ALLOW_THIS_TIME permits reluctant compliance without changing the stored relationship.
- Shared conversational statements are not classified as requests, so two players arguing about the same third person can still get one natural group response instead of random refusal routing.
- Empty direct-mention fallback keeps refusal decisions and trusted factual recovery without creating a second provider request.
- Adds pure Java regression tests for request detection/refusal stability and multi-speaker factual coverage.
- No extra AI request/classifier was added. Refusal routing, wiki/context withholding and coverage checks are Java-local.

## 1.7.6 - Multi-speaker replies + wiki hardening + no-silence fallback

- A single captured scene can now return up to three short public replies in the SAME `m` array/model request, so 2-3 players who directly address Isolda during one capture window can all receive a brief answer without extra API calls.
- Migrates the old bundled `chat.max-messages-per-response: 1` to `3` and relationship context capacity `2` to `3`; deliberate custom values are preserved.
- Wiki retrieval now recognizes natural obtain/drop/location wording such as `como se consigue`, `que da el` and `en que coordenadas puedo encontrar`, and can retrieve up to one targeted wiki result per addressed speaker in multi-player scenes.
- Adds typo-tolerant entity ranking and dedicated-page preference, fixing cases such as `acohilitico necrotido` selecting generic Necrótido pages instead of `mob-hueste-acolito-necrotico`.
- Wiki `result=no_match` is now explicit trusted context, preventing GPT from filling missing MDVCRAFT facts with vanilla/general Minecraft guesses.
- Direct mentions that return an empty `m` with no action are recovered locally from already-selected wiki context when possible, otherwise with an honest short acknowledgement. No retry/model call is created.
- Wiki fallback can extract simple crafting/obtain lines and mob drop blocks locally, including `9 Ramas Resinosas -> 1 Mango Resinoso` and the exact Acólito Necrótico drops.
- Relationship context for group scenes is scoped to actual addressed request speakers. Enemy/arch-enemy state now explicitly controls social willingness as well as tone: factual answers stay accurate, but social favors/affection/praise requests are normally refused, mocked or twisted rather than obediently accepted.
- The stable CORE now exposes addressed speakers and trigger mode, prevents invented proximity requirements for talking to Isolda, and instructs the model to keep simultaneous players' intents separate.
- Player-data location intent now recognizes common `coors/coor` shorthand in addition to `coords/coordenadas`, fixing direct coordinate questions that previously produced an empty reply.
- Relationship bookkeeping treats identity/server-info queries as neutral and accepts additional malformed labelled compact `r` formats seen in live GPT-4o-mini output.
- Multi-line group replies are no longer collapsed by the legacy single-line romance/partner factual guard.
- No additional AI request was added; wiki ranking, typo recovery, direct-message fallback and relationship safeguards are Java-local.

## 1.7.5.1 - Build test hotfix

- Fixed `RelationshipSignalDetector.isBookkeepingNeutral` for tiny reactions with emoticons such as `queee :c`. Normalization removes `:`, so the previous regex saw `queee c` and incorrectly marked it non-neutral.
- This keeps the intended anti-drift behavior: tiny reactions do not alter relationship score/bookkeeping.
- No API-call or token-cost change.

## 1.7.5 - Romance continuity + group-action binding hotfix

- Formal romance proposals now recognize natural variants such as `aceptas ser mi novia?`, `aceptarias ser mi pareja?` and `te pregunte si querias ser mi novia`.
- Eligible formal proposals must receive a decisive yes/no instead of repeated `asi de golpe?` / `estas seguro?` stalling. If the model still stalls, a short RAM-only pending proposal keeps the question alive for follow-ups such as `cual es tu respuesta?`.
- A clear accepting follow-up can complete romance locally even if GPT omits `r`; Java still enforces minimum score, explicit/pending proposal authority, visible acceptance and the global partner cap.
- Relationship context now includes a tiny global partner roster, so Isolda cannot forget an existing partner merely because that player is absent from the current scene.
- Direct `quien es tu pareja/novio/novia?` questions receive a Java factual guard backed by persisted romance state, preventing contradictory `no tengo pareja` replies.
- Partner behavior now has `happy`, `strained` and `critical` score bands. Lowering a current partner to 30 or -10 therefore changes the romantic tone without automatically deleting the relationship.
- Lightning requests are bound locally to the player/target that actually requested the action. In mixed scenes, a model call aimed at the wrong participant is corrected when Java can resolve one authorized target.
- Exact repeated replies on SMART follow-ups are suppressed locally, fixing cases where a bare `xd` caused Isolda to repeat the previous sentence verbatim.
- Relationship anti-drift now treats more meta/setup lines (`como te llevas con`, `que sientes por`, `defiendeme`, `cual es tu respuesta`, etc.) as non-scoring unless the current message itself contains a genuine social signal.
- Keeps all 1.7.4 journal truthfulness, wiki gating, parser recovery, SQLite persistence and one-model-call architecture. No extra AI request was added.


## 1.7.3 - Wiki follow-up continuity + parser/debug cleanup

- Fixed ambiguous wiki follow-ups such as `y de donde consigo eso?` / `como se craftea?`: retrieval now reuses only the immediately previous same-speaker wiki scene as a local zero-token query seed.
- Prevented obvious social small-talk such as `Iso como estas?` from selecting random wiki sections in large wikis.
- Wiki debug now reports when a query was expanded from a follow-up or intentionally skipped.
- Normalizes literal TAB characters before structured response parsing, preserving the safe plain-text fallback as a final guard.
- History-only assistant messages no longer reparse visible chat as protocol, removing duplicate `Model relationship payload: []` debug lines.
- No extra model requests were added; wiki continuation remains entirely local.


## 1.7.2 - Scene isolation + context reliability hotfix

- Separates `pre-lookback` chat/events from the CURRENT scene. Old immediate context is now marked context-only and cannot be re-answered as if it were the new request.
- Wiki, inventory, player-data and profile prefetching are driven only by current players who actually addressed Isolda, preventing unrelated group chatter from consuming a context-tool slot or stealing the target.
- Fixes repeated answers caused by a smart follow-up inheriting the previous 12-18 seconds as fresh player input.
- Fixes stale pre-lookback requests leaking into the next tool/action decision; the existing Java action authorization remains a second safety layer.
- Improves player target resolution for natural name variants: compact/spaced numeric names (`En3Minutos` -> `en 3 minutos`) and small typos of already-involved names (`WITHE9033` -> `white`).
- Applies the same involved-name matching to activity-journal target lookup, improving recent-history questions.
- Improves local wiki ranking for multi-word item/recipe names and queries wiki only from the current addressed request instead of unrelated scene chatter.
- Adds `wiki.yml -> local-retrieval.debug-log` for zero-call diagnostics showing the normalized query and selected section keys.
- Adds `activity-journal.debug-log` to verify when player/general history queries are recognized, also with zero AI calls.
- Strengthens CORE priority rules: PRE-CONTEXT/PREVIOUS SCENE may not be answered again; explicit trusted WIKI/INVENTORY/ACTIVITY data must be used when it directly answers the current question.
- Keeps 1.7.1 relationship persistence, guarded romance, zero-token fallback, SQLite cache design and one-model-call architecture unchanged.

## 1.7.1 - Relationship reliability + guarded romance

- Fixes relationship/social-memory updates appearing inert when GPT-4o-mini omits `r`: a conservative Java fallback now records obvious date proposals, strong affection/trust, support/defense, apologies, direct hostility and basic compliments without any additional AI request.
- Accepts both compact relationship strings and structured JSON objects in `r`, avoiding silent drops when a compatible model chooses an object shape.
- Strengthens the relationship prompt so every direct social interaction is evaluated instead of treating `r` as merely optional bookkeeping.
- Allows up to two relationship updates in one group scene (migrating only the old default `1`), so an insult and another player's defense can both affect their own profiles without another AI call.
- Adds diagnostic toggles for raw model relationship payloads, rejected updates and applied updates.
- Raises the old bundled OpenAI output ceiling from 75 to 120 tokens (migration only touches the exact old 75 value). This is a ceiling, not reserved usage, and keeps the same one-call architecture.
- Adds configurable partner behavior plus below-threshold, romance-disabled and partner-capacity-full behavior in `relationships.yml`.
- Romance start is now guarded in Java by minimum score, global `max-partners`, an explicit current-scene partnership proposal and a visibly accepting Isolda reply. `max-partners: 1` therefore cannot create a second partner.
- Adds `/sva relationship romance <player|uuid> <on|off>` for admin testing; enabling romance obeys the same score/capacity rules.
- Admin `/sva relationship set` and romance changes clear the tiny conversational carry-over so an old friendly scene cannot overpower a newly forced hostile score (or vice versa).
- Uses SQLite `DELETE` journal mode by default (still configurable) so committed changes are visible in the main `.db` file when inspecting a FileZilla copy instead of being hidden temporarily in a `.db-wal` sidecar.
- Adds a final zero-token dialogue guard so a small model cannot verbally accept a prohibited second partner after Java rejects the state change.
- Previous scenes containing ACTION requests are no longer fed back as conversational history, preventing an old `rayo` request from leaking into a later unrelated scene while Java's existing action safety remains enabled.
- Keeps SQLite + RAM cache, bounded memories, anti-farming and the GitHub Actions/Maven Java 21 build flow.

## 1.7.0 - Activity journal + persistent relationships

- Adds a bounded RAM-only activity journal for public player chat and trusted server events. Normal scenes receive zero journal tokens unless a historical question is detected.
- Adds named-player history queries with configurable retention (120 minutes by default) and broad `since I disconnected` summaries using a per-player disconnect marker.
- Adds independent `admin-only|everyone` access controls for named-player and general history; both ship `admin-only` for safe testing.
- Adds `/sva history status|player|general` runtime controls.
- Adds persistent SQLite relationship profiles with score `-100..100`, an in-RAM read cache and serialized asynchronous writes. Everyone starts neutral at `0` by default.
- Adds bounded social memory: 8 persistent and 12 recent memories by default, with recent expiry, importance, deduplication and replacement limits.
- Relationship mutations are returned through compact `r` metadata in the SAME model response as normal chat/actions. No second AI call is made. Java clamps updates and applies anti-farming cooldown/hourly limits.
- Adds configurable relationship behavior tiers, local hostile-player ignore chances and tier-based smart follow-up windows. Ignored trivial messages are dropped before the API call.
- Adds optional relationship-aware spontaneous event reactions. They are disabled by default and have score/chance/cooldown/hourly gates because each accepted reaction spends one normal request.
- Adds exclusive romance capacity controlled by `relationships.yml -> romance.max-partners`; the default `0` disables romance completely.
- Removes the old bundled hardcoded Aminowana romance assumption and migrates only that exact legacy default so persistent relationship state is authoritative.
- Adds `/sva relationship info|memories|set|purge` and `/sva data purge`. Purge removes persistent relationship/memory rows plus new-system RAM traces and serializes deletion behind queued DB writes.
- Adds `relationships.yml` and bundles the SQLite JDBC driver in the shaded plugin JAR.
- Keeps GitHub Actions/Maven Java 21 build flow and the single-global-scene architecture.


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
