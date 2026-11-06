# Plan: separar Isolda del acceso al modelo y abrir una API de IA para otros plugins

> **Para el agente que ejecute este plan.** Léelo completo antes de tocar código. Está escrito sobre el estado del repo en el commit `245a7d9` ("Split ConversationManager into focused classes"). Si el código cambió desde entonces, adapta los pasos, pero mantén los **invariantes** de la sección 3.
>
> - El código, los comentarios, el `CHANGELOG.md` y los mensajes de commit van en **inglés**, como el resto del repo.
> - La config y los mensajes para jugadores siguen el idioma que ya usan.
> - Trabaja en una rama `feature/ai-gateway` y haz **un commit por fase**.
> - No hagas push ni abras PR salvo que el usuario lo pida.
> - No commitees `dependency-reduced-pom.xml` ni el `.gitignore` sin trackear que ya existen. No son tuyos.

---

## 1. Objetivo

1. **Separar dos responsabilidades** que hoy están mezcladas en `me.kev.sva.chat.assistant.AssistantManager`:
   - **Acceso al modelo:** clientes OpenAI/compatibles, primario y fallback, executor, timeout y watchdog, control de peticiones por minuto, registro de tokens y clasificación de errores. Pasa a un paquete nuevo `me.kev.sva.ai`, independiente de Isolda.
   - **Lógica de Isolda:** prompt estable, personalidad, herramientas, contexto de escena, sobre JSON `m/t`, continuación de Gemini y `prompt_cache_key`. Se queda en `chat.assistant` y usa el gateway.
2. **Abrir una API pública** (`me.kev.sva.api`) para que otros plugins (MDVTrivia, MDVTutorial…) hagan llamadas puntuales al modelo con **sus propias instrucciones**. Se registra en el `ServicesManager` de Bukkit.
3. **Que Isolda no empeore.** Las llamadas externas no pueden dejarla sin cuota, no cambian su prompt y no rompen la caché de prompts de OpenAI.

**Fuera de alcance** (fases futuras, no implementar):
- Los cambios en MDVTrivia y MDVTutorial que usarán la API.
- Que Isolda reaccione a eventos de otros plugins.
- Embeddings, streaming y herramientas para las llamadas externas.

---

## 2. Estado actual (resumen para orientarte)

| Pieza | Dónde | Qué hace hoy |
|---|---|---|
| `ServerAssistantPlugin` | `me/kev/sva/ServerAssistantPlugin.java` | Crea `ProviderThrottleRegistry` (sobrevive a `/sva reload`). `initializePlugin()` recrea `ToolManager`, `ConversationManager` y `ChatListener` en cada reload. |
| `SceneRequestQueue` | `chat/SceneRequestQueue.java` | **Crea su propio `AssistantManager`** en el constructor, así que se recrea en cada reload. Gestiona la cola de escenas (una petición en curso), el retraso por límite de peticiones, el **cambio a fallback**, los reintentos de 503, el cooldown tras un 429 (`isRateLimitError`, `isTransientProviderError`, `parseProviderRetryDelay`) y `providerThrottle.recordAttempt(...)` antes de cada llamada. |
| `AssistantManager` | `chat/assistant/AssistantManager.java` | Constantes `PRIMARY=0` y `FALLBACK=1`. Construye los `ProviderRuntime` (`OpenAIOkHttpClient`, `maxRetries(0)`, timeout). Executor con hilos daemon `ServerAssistant-AI-N`. `sendAIRequest(providerIndex, chatMessages, requestContext, completion)` monta el prompt de Isolda, llama al modelo fuera del hilo principal, vuelve al principal, parsea `AssistantResponse` y tiene un watchdog que garantiza que la respuesta llega **exactamente una vez**. También el registro de tokens/caché y `promptCacheKey`. |
| `ProviderSettings` | `chat/assistant/ProviderSettings.java` | Record con la config de proveedor (`ai.*` y `ai.fallback.*`), compatible con claves antiguas; `throttleKey()` y `resolveApiKey()`. |
| `ProviderThrottleRegistry` | `chat/ProviderThrottleRegistry.java` | Peticiones por minuto y cooldown por `throttleKey`. Tiene tests. |
| Otros usos | `ConversationManager` (líneas ~375 y ~552), `SceneBuilder` (~452) | Solo usan `AssistantManager.PRIMARY` como valor inicial de `SceneRequest.providerIndex`. |
| Config | `src/main/resources/config.yml` | Cabecera `SERVER ASSISTANT 1.7.19` y `config-version: 18`. `ConfigurationManager.mergeBundledDefaults` añade solas las claves nuevas. |
| CI | `.github/workflows/build.yml`, `validate_config.py`, `validate_source.py` | Validan el YAML y que la versión del `pom` coincida con la cabecera de `config.yml`. |
| Plugin | `plugin.yml` | **`name: ServerAssistant`** (no "MDVAsistenteIA"), `groupId me.kev`, `artifactId ServerAssistant`. `openai-java` va empaquetado **sin relocalizar**. |

---

## 3. Invariantes que NO se pueden romper

Revísalos al final de cada fase:

1. **Prompt de Isolda idéntico, byte a byte y en el mismo orden:**
   - `stableSystemMessages` (CORE → personalidad + capabilities-note → `[OUTPUT]` → catálogo de capacidades),
   - luego la lista de ACTION disponibles, la información local, el contexto del servidor y el contexto de la petición,
   - luego los mensajes de conversación con sus cabeceras,
   - y la continuación `[TRUSTED CONTINUATION]` solo para Gemini.
2. `prompt_cache_key` **solo** para `type=openai` con `ai.prompt-cache.enabled`. Su formato `prefix-modelHash8-stableHash16` no cambia.
3. JSON mode (`ResponseFormatJsonObject`) en Isolda **solo** si `type=openai` y `provider-response.force-json-object-openai`.
4. Cliente HTTP con `maxRetries(0)` y `timeout(requestTimeoutMs)`. Watchdog = timeout + 5 s de margen.
5. La respuesta de cada llamada llega **exactamente una vez**, en el **hilo principal**, tanto si gana la llamada como si gana el watchdog.
6. `ProviderThrottleRegistry` sigue viviendo en el plugin y **sobrevive a `/sva reload`**.
7. **Isolda decide igual que antes:** la lógica de fallback/429/503/cola de `SceneRequestQueue` no cambia de comportamiento. Si mueves los clasificadores de errores, compórtate igual.
8. **Registro de tokens con el mismo formato:** las líneas `AI token usage: scene=... provider=... model=... input=... cached=... output=... total=... players=...` siguen igual para Isolda. Las externas usan su propio formato (sección 6.6).
9. No se envían coordenadas de jugadores ni secretos. Nada de la API pública expone tipos de `com.openai.*`.

---

## 4. Arquitectura final

```
me.kev.sva
├── ai/                         (interno; Isolda y la API externa lo usan)
│   ├── AiGateway.java          clientes, executor, watchdog, llamada única, registro de tokens
│   ├── ProviderSlot.java       enum PRIMARY / FALLBACK (o mantener int; ver 5.2)
│   ├── ProviderSettings.java   movido desde chat.assistant (sin cambios de lógica)
│   ├── ProviderRuntime.java    record(settings, OpenAIClient)
│   ├── ProviderErrors.java     isRateLimit / isTransient / parseRetryDelay (movidos)
│   ├── ProviderThrottleRegistry.java  movido desde chat (opcional; ver 5.1)
│   ├── CompletionRequest.java  petición neutra: mensajes + overrides + flags
│   ├── CompletionMessage.java  record(Role, String content)
│   ├── CompletionResult.java   texto + uso + proveedor/modelo + latencia
│   └── TokenUsage.java         record(input, cached, output)
├── api/                        (PÚBLICO y estable; lo único que ven otros plugins)
│   ├── AiService.java
│   ├── AiRequest.java          (+ Builder)
│   ├── AiMessage.java, AiRole.java, AiResponseFormat.java, AiPriority.java
│   ├── AiResult.java
│   └── AiException.java        (+ enum Reason)
├── api/internal/  (o ai/external/)
│   ├── AiServiceImpl.java      fachada registrada en ServicesManager; sobrevive a reload
│   ├── ExternalRequestQueue.java  cola, concurrencia, prioridad, fallback, reintentos
│   ├── ApiSettings.java        lee la sección api.* (perfiles y clientes)
│   ├── ApiProfile.java, ApiClientPolicy.java
│   └── ApiQuotaTracker.java    peticiones por minuto por cliente/jugador y tokens diarios (reloj inyectable)
└── chat/assistant/AssistantManager.java   SOLO lógica de Isolda; delega en AiGateway
```

Quién crea qué (ciclo de vida):
- `ServerAssistantPlugin.onEnable`:
  - crea `AiServiceImpl` **una sola vez** y lo registra en el ServicesManager;
  - en `initializePlugin()` crea un `AiGateway` nuevo **antes** de `ConversationManager` y se lo pasa a `AiServiceImpl.swapGateway(...)`.
- `SceneRequestQueue` ya **no** crea su `AssistantManager` desde cero: obtiene el gateway del plugin (`plugin.getAiGateway()`) y construye `new AssistantManager(plugin, gateway)`.
- `/sva reload`:
  - apaga la cola de Isolda (como ahora) y apaga el gateway anterior;
  - las peticiones externas en curso o en cola terminan con `AiException(Reason.RELOADING)`;
  - crea el gateway nuevo y se lo pasa a la fachada. Los plugins que guardaron la referencia a `AiService` siguen funcionando.
- `onDisable`: anula el registro del servicio, termina todo lo pendiente con `Reason.SHUTDOWN` y apaga el gateway.

---

## 5. Fase 1 — Extraer `AiGateway` (refactor sin cambios de comportamiento)

### 5.1 Mover clases
- `chat/assistant/ProviderSettings.java` → `ai/ProviderSettings.java`. Actualiza los imports (`ServerAssistantPlugin.migrateLegacyProviderConfig` lo usa).
- `chat/ProviderThrottleRegistry.java` → `ai/ProviderThrottleRegistry.java`. Mueve también su test `src/test/java/me/kev/sva/chat/ProviderThrottleRegistryTest.java` → `.../ai/`. Si prefieres no moverlo, déjalo, pero el gateway debe recibirlo por constructor.
- Saca de `SceneRequestQueue` los métodos estáticos `isRateLimitError`, `isTransientProviderError` y `parseProviderRetryDelay` a `ai/ProviderErrors` como `public static`, con la **misma lógica**. `SceneRequestQueue` los llama desde ahí.

### 5.2 Petición neutra
```java
// ai/CompletionMessage.java
public record CompletionMessage(Role role, String content) {
  public enum Role { SYSTEM, USER, ASSISTANT }
}

// ai/CompletionRequest.java
public record CompletionRequest(
    List<CompletionMessage> messages,   // ya en orden final
    @Nullable String modelOverride,     // null = modelo del proveedor
    @Nullable Long maxOutputTokens,     // null = el del proveedor
    @Nullable Double temperature,       // null = la del proveedor
    @Nullable Long timeoutMsOverride,   // null = el del proveedor; nunca menor de 3000
    boolean jsonObject,                 // aplicar ResponseFormatJsonObject (el gateway solo lo hace si type=openai)
    @Nullable String promptCacheKey,    // el gateway solo lo envía si type=openai
    String usageLogLabel) {}            // texto que va tras "AI token usage: "
```
- Usa `ProviderSlot { PRIMARY, FALLBACK }` en el gateway.
- Para no tocar `SceneRequest.providerIndex()` (int), deja en `AssistantManager` las constantes `PRIMARY`/`FALLBACK` = `0`/`1` y conviértelas con `ProviderSlot.fromIndex(int)`. **No cambies `SceneRequest`** en esta fase.

### 5.3 `AiGateway`
API interna (todo se llama desde el hilo principal):
```java
public final class AiGateway {
  public AiGateway(ServerAssistantPlugin plugin, ProviderThrottleRegistry throttle);
  public boolean isAvailable(ProviderSlot slot);
  public boolean hasFallback();
  public @Nullable ProviderSettings settings(ProviderSlot slot);
  /** Delay before this slot may be called again (throttle + cooldown). */
  public long delayMs(ProviderSlot slot, @Nullable String modelOverride);
  public void applyCooldown(ProviderSlot slot, @Nullable String modelOverride, long ms);
  /**
   * Records one attempt on the slot's throttle bucket, runs the call off-thread and
   * completes EXACTLY ONCE on the main thread (call result or watchdog timeout).
   */
  public void complete(ProviderSlot slot, CompletionRequest request,
                       BiConsumer<CompletionResult, Throwable> completion);
  public void shutdown();
}
```
- **Mueve tal cual** desde `AssistantManager`: `createRuntime`, `logRuntime`, `closeRuntime`, el executor (`newCachedThreadPool` con hilos daemon `ServerAssistant-AI-N`), el patrón `AtomicBoolean completed` + watchdog `runTaskLater` con `WATCHDOG_GRACE_MS = 5000`, y `oneLineForUsageLog`.
- **Registro de tokens:** generaliza `logTokenUsage` para que reciba `usageLogLabel`. Para Isolda, `AssistantManager` monta exactamente `"scene=<id> "` y el gateway añade `provider=... model=... input=... cached=... output=... total=...`. Después va `" players=<...>"` si no está vacío. **El resultado tiene que ser idéntico a la línea actual.** La forma más fácil es que `CompletionRequest` lleve `labelPrefix` (`scene=42`) y `labelSuffix` (`players=...`). Elige lo que dé una línea idéntica.
- `logPromptCacheUsage` también va al gateway y se activa cuando `promptCacheKey != null`.
- **`recordAttempt` pasa al gateway** (dentro de `complete`), y se **quita** de `SceneRequestQueue.startRequest`. Así todas las llamadas, de Isolda y externas, cuentan en el mismo bucket. Comprueba que no quede contado dos veces.
- **Override de modelo:** el bucket del throttle se calcula con el modelo efectivo (`type|baseUrl|modelEfectivo`). Añade a `ProviderSettings` un método `throttleKey(String modelOverride)` y deja el `throttleKey()` actual sin cambios.
- `CompletionResult(String text, ProviderSlot slot, String providerName, String model, TokenUsage usage /*nullable*/, long latencyMs)`.
- El gateway **no** parsea JSON ni conoce `AssistantResponse`.

### 5.4 `AssistantManager` (solo Isolda)
- Constructor `AssistantManager(ServerAssistantPlugin plugin, AiGateway gateway)`.
- Conserva `buildStableSystemMessages`, `stableSystemHash`, `promptCacheKey`/`sanitizeCacheKeyPrefix`/`shortSha256`, `isOpenAIPromptCacheEnabled`, el equivalente de `appendSystemPromptsToBuilder` y de `appendConversationMessagesToBuilder` (incluida la continuación de Gemini y el recorte `chat.max-player-message-length`). Ahora producen una `List<CompletionMessage>` en lugar de llamar a `paramsBuilder`.
- `sendAIRequest(int providerIndex, List<ChatMessage>, AssistantRequestContext, BiConsumer<AssistantResponse, Throwable>)` **mantiene la firma**:
  1. monta los mensajes en el mismo orden;
  2. monta `CompletionRequest`: `jsonObject = provider-response.force-json-object-openai`, `promptCacheKey = isOpenAIPromptCacheEnabled ? key : null`, sin overrides;
  3. llama a `gateway.complete(...)`;
  4. en la respuesta (ya en el hilo principal) aplica la misma conversión que ahora: texto vacío → `new AssistantResponse(plugin, List.of(), List.of(), false)`, y si no `new AssistantResponse(plugin, text)`. Si el parseo falla, se devuelve el error de la llamada si lo había y, si no, el del parseo.
- Los métodos públicos que usa `SceneRequestQueue` (`getProviderSettings`, `getFallbackProviderSettings`, `isProviderAvailable`, `hasFallback`) delegan en el gateway.
- `shutdown()` de `AssistantManager` **ya no** cierra clientes. El gateway es del plugin.
- `SceneRequestQueue.providerDelay` pasa a `gateway.delayMs(...)`, con el mismo resultado. `applyCooldown` pasa a `gateway.applyCooldown(...)`.

### 5.5 Ciclo de vida en `ServerAssistantPlugin`
- Campo `private AiGateway aiGateway;` y getter `getAiGateway()`.
- En `initializePlugin()`, antes de `new ConversationManager(this)`: si hay un gateway anterior, apágalo; luego `aiGateway = new AiGateway(this, providerThrottleRegistry)`.
- En `onDisable()`: apaga el gateway **después** de `conversationManager.shutdown()`.

### 5.6 Verificación de la fase 1
- `mvn -o clean verify` (o sin `-o` si faltan dependencias), `python validate_config.py` y `python validate_source.py` en verde.
- **Test nuevo `AssistantPromptOrderTest`** (en `src/test/java/me/kev/sva/chat/assistant/`). Si construir `AssistantManager` sin servidor es inviable, extrae el montaje de mensajes a un método/clase pura (`IsoldaPromptAssembler`) que reciba strings y la lista de `ChatMessage`, y testea eso. Cubre:
  - el orden y el contenido exacto de los mensajes de sistema;
  - que `PlayerChatMessage` → USER con `header + texto recortado`, `AssistantChatMessage` → ASSISTANT, `SystemContextMessage`/`BroadcastChatMessage` → SYSTEM `header + content`;
  - la continuación de Gemini (cuando no hay turno de usuario, o el último es del asistente) y su ausencia con OpenAI.
- Test de `ProviderErrors` con los mismos casos que hoy reconocen los métodos privados: `RateLimitException`, "429", "resource_exhausted", 500/502/503/504, "timed out", "retry after 12s" → 12250.
- Commit: `Extract AiGateway from AssistantManager (no behavior change)`.

---

## 6. Fase 2 — API pública y cola externa

### 6.1 Paquete `me.kev.sva.api` (contrato público, documentado con Javadoc)
```java
public interface AiService {
  /** True if the plugin is enabled, api.enabled is true and the primary or fallback provider is configured. */
  boolean isAvailable();

  /** Whether this caller plugin is allowed and which profiles it may use. */
  boolean isAllowed(Plugin caller);

  /**
   * Queues one model call with the caller's own instructions.
   * May be called from any thread; the returned future ALWAYS completes on the
   * Bukkit main thread. Never call join()/get() on the main thread.
   * Fails with AiException (see Reason) instead of throwing synchronously.
   */
  CompletableFuture<AiResult> request(Plugin caller, AiRequest request);
}

public enum AiRole { SYSTEM, USER, ASSISTANT }
public record AiMessage(AiRole role, String content) {}
public enum AiResponseFormat { TEXT, JSON_OBJECT }
public enum AiPriority { INTERACTIVE, BACKGROUND }

public final class AiRequest {
  public static Builder builder(String profile);   // "default" if null/blank
  // getters: profile(), messages(), format(), priority(), maxOutputTokens() (@Nullable Integer),
  //          temperature() (@Nullable Double), playerId() (@Nullable UUID), tag() (String, for logs)
  public static final class Builder {
    Builder system(String text);      // may be called several times; order is preserved
    Builder user(String text);
    Builder assistant(String text);   // few-shot examples
    Builder messages(List<AiMessage>);
    Builder format(AiResponseFormat f);
    Builder priority(AiPriority p);   // default BACKGROUND
    Builder maxOutputTokens(int n);   // clamped to the profile's limit
    Builder temperature(double t);    // clamped to [0, 2]
    Builder forPlayer(UUID playerId); // enables per-player limits
    Builder tag(String tag);          // e.g. "trivia:generate"
    AiRequest build();                // validates; throws IllegalArgumentException
  }
}

public record AiResult(
    String text, String provider, String model,
    int inputTokens, int cachedTokens, int outputTokens,
    long latencyMs, boolean usedFallback) {
  /** Parses text as a JSON object, tolerating ```json fences and text around the first {...} block. */
  public Optional<com.google.gson.JsonObject> json();
}

public final class AiException extends Exception {
  public enum Reason {
    DISABLED, NOT_CONFIGURED, CLIENT_NOT_ALLOWED, PROFILE_NOT_ALLOWED, INVALID_REQUEST,
    RATE_LIMITED, BUDGET_EXCEEDED, QUEUE_FULL, QUEUE_TIMEOUT, TIMEOUT, PROVIDER_ERROR,
    RELOADING, SHUTDOWN
  }
  public Reason reason();
}
```
**Validación de `build()`:**
- al menos un mensaje USER, y el último mensaje debe ser USER (así se evita el problema de Gemini en las llamadas externas);
- contenido no vacío;
- como mucho `api.limits.max-messages` mensajes (por defecto 20) y `api.limits.max-input-chars` caracteres en total (por defecto 12000). Este segundo límite se comprueba en `AiServiceImpl` porque depende de la config. Si se supera → `INVALID_REQUEST`.

**Gson:** `com.google.gson` lo trae Paper, así que no hay que añadir dependencias. Si `json()` no puede parsear, devuelve `Optional.empty()`.

**Nada de `com.openai.*` en el paquete `api`.** Añade a `validate_source.py` una comprobación que falle si algún archivo de `src/main/java/me/kev/sva/api/` importa `com.openai` o `me.kev.sva.ai`/`me.kev.sva.chat`.

### 6.2 Config nueva (`config.yml`, sección `api`)
Ponla después de `ai:` y antes de `global-conversation:`, comentada en el mismo estilo:
```yaml
# ============================================================
# AI API FOR OTHER PLUGINS
# ============================================================
# Other plugins (MDVTrivia, MDVTutorial...) can send their own one-off
# requests through Bukkit's ServicesManager (me.kev.sva.api.AiService).
# They reuse the providers above but never Isolda's prompt or memory.
api:
  enabled: true
  # Requests running at the same time for ALL external plugins.
  max-concurrent-requests: 2
  # External requests per minute, all plugins together (0 = no extra cap).
  max-requests-per-minute: 6
  # BACKGROUND requests wait while the provider bucket has fewer than this many
  # free slots per minute, so Isolda always keeps room. INTERACTIVE ignores it.
  reserve-for-assistant-rpm: 4
  # Max time a request may wait in the queue before failing with QUEUE_TIMEOUT.
  max-queue-wait-ms: 15000
  max-queue-size: 20
  # deny = only plugins listed under clients may call the API.
  unknown-clients: deny
  log-requests: true
  limits:
    max-messages: 20
    max-input-chars: 12000

  profiles:
    default:
      provider: primary        # primary | fallback
      model: ''                # '' = model of that provider
      max-output-tokens: 300
      temperature: 0.7
      request-timeout-ms: 20000
      allow-fallback: true
    trivia-generate:
      max-output-tokens: 1200
      temperature: 0.9
      request-timeout-ms: 30000
    tutorial-hint:
      max-output-tokens: 150
      temperature: 0.6
      request-timeout-ms: 12000

  clients:
    MDVTrivia:
      enabled: true
      profiles: [trivia-generate, default]
      max-requests-per-minute: 3
      max-requests-per-player-per-minute: 0
      daily-token-budget: 200000
    MDVTutorial:
      enabled: true
      profiles: [tutorial-hint, default]
      max-requests-per-minute: 6
      max-requests-per-player-per-minute: 2
      daily-token-budget: 100000
```
- **Perfiles:** los campos que falten se heredan de `default`, y lo que falte en `default` se toma del proveedor. `max-output-tokens` de la petición se recorta a lo que permita el perfil. El timeout nunca baja de 3000.
- **Clientes:** la clave es `Plugin#getName()`. `profiles` vacío = solo `default`. `0` = sin límite.
- Sube `config-version` a **19**. `mergeBundledDefaults` añadirá la sección `api` a los configs existentes; no hace falta migración. Comprueba que el merge mete bien las claves anidadas de `profiles`/`clients` en un config 18 real. Si `mergeBundledDefaults` solo añade claves que faltan, un config existente recibirá los perfiles y clientes por defecto, que es lo que queremos.

### 6.3 `ApiQuotaTracker` (puro y testeable)
- Constructor con `LongSupplier clock` y `ZoneId zone`.
- Peticiones por minuto por cliente, por cliente+jugador y global externo: ventana deslizante de 60 s, como en `ProviderThrottleRegistry`.
- Tokens diarios por cliente (`input + output`), que se reinician al cambiar de día en `zone` (zona del sistema).
  - **Solo en memoria:** se reinician al reiniciar el servidor. Documéntalo en el README.
  - El presupuesto se comprueba **antes** de encolar con lo ya consumido. Como no se conoce el coste de antemano, una petición puede pasarse un poco; es aceptable.
- Métodos: `@Nullable AiException.Reason checkAdmission(client, playerId)`, `recordStart(client, playerId)`, `recordUsage(client, tokens)` y `snapshot(client)` para el comando de estado.

### 6.4 `ExternalRequestQueue`
- **Estado e hilos:** solo en el hilo principal. `AiServiceImpl.request` llamado desde otro hilo pasa al principal con `Bukkit.getScheduler().runTask`.
- **Dos colas**, `INTERACTIVE` y `BACKGROUND`; siempre se atiende antes `INTERACTIVE`.
- **Admisión, al llamar a `request()`, en este orden:**
  1. `api.enabled` → si no, `DISABLED`;
  2. el gateway tiene algún proveedor → si no, `NOT_CONFIGURED`;
  3. el cliente está en la lista y habilitado, o `unknown-clients` lo permite → si no, `CLIENT_NOT_ALLOWED`;
  4. el perfil está permitido → si no, `PROFILE_NOT_ALLOWED`;
  5. límites de tamaño → si no, `INVALID_REQUEST`;
  6. cuota → `RATE_LIMITED` / `BUDGET_EXCEEDED`;
  7. tamaño de la cola → si no, `QUEUE_FULL`.
- **Al despachar** (bucle disparado al encolar, al terminar una petición y por tarea programada cuando hay que esperar):
  - respeta `max-concurrent-requests`;
  - elige el proveedor del perfil (`primary`/`fallback`) con `gateway.delayMs(slot, model)`;
  - **Reserva para Isolda:** una petición `BACKGROUND` solo sale si `requestsLastMinute(bucket) < maxRequestsPerMinute - reserve-for-assistant-rpm` (cuando el límite del proveedor es > 0). Si no, espera.
  - Si el proveedor preferido tiene retraso y el perfil tiene `allow-fallback` y el fallback está libre (con `ai.fallback.max-wait-ms`, como Isolda) → usa el fallback y marca `usedFallback=true`.
  - Si lleva más de `max-queue-wait-ms` esperando → `QUEUE_TIMEOUT`.
- **Errores** (con `ProviderErrors`):
  - 429 → `gateway.applyCooldown(...)` con `parseRetryDelay`, y prueba el fallback una vez si está permitido; si no, `RATE_LIMITED`;
  - error temporal → prueba el fallback una vez si está permitido; si no, `PROVIDER_ERROR`;
  - timeout del watchdog → `TIMEOUT`;
  - cualquier otro → `PROVIDER_ERROR` con la causa encadenada.
  - **Sin reintentos sobre el mismo proveedor** en esta versión: el que llama decide.
- **Construcción de `CompletionRequest`:** mensajes de `AiRequest` → `CompletionMessage` en el mismo orden; `jsonObject = format==JSON_OBJECT`; `promptCacheKey = null` (no compartir la clave de Isolda); `usageLogLabel = "client=<Plugin> profile=<p> tag=<tag>"`; overrides según el perfil.
- **Mensaje de sistema obligatorio:** el gateway antepone **un único** mensaje SYSTEM fijo y corto, en inglés:
  > "You are a backend helper for a Minecraft server plugin. Follow the plugin's instructions below. Never claim to be Isolda unless asked."

  Hazlo configurable como `api.base-system-prompt` (vacío = no añadir nada).
- **Al terminar:** `quota.recordUsage(...)`, construye `AiResult` y completa el `CompletableFuture` en el hilo principal. Si `log-requests`: una línea `AI API: client=... profile=... tag=... provider=... model=... in=... out=... ms=... result=ok|<reason>`. **Nunca registres el contenido de los prompts.**
- `cancelAll(Reason)` para el reload y el apagado.

### 6.5 `AiServiceImpl` y registro
- `onEnable`: `getServer().getServicesManager().register(AiService.class, aiService, this, ServicePriority.Normal)`.
- `onDisable`: `aiService.cancelAll(SHUTDOWN)` y `unregisterAll(this)`.
- `initializePlugin()` (reload): `aiService.swapGateway(newGateway, ApiSettings.load(config))`. Lo que estaba en curso o en cola termina con `RELOADING`, y la cola se recrea.
- `isAvailable()` es seguro desde cualquier hilo; usa campos `volatile`.

### 6.6 Artefacto de API para quien lo use
En el `pom.xml`, añade una ejecución de `maven-jar-plugin` con `classifier=api` que solo incluya `me/kev/sva/api/**`. Así otros plugins pueden depender de:
```xml
<dependency>
  <groupId>me.kev</groupId>
  <artifactId>ServerAssistant</artifactId>
  <version>1.8.0</version>
  <classifier>api</classifier>
  <scope>provided</scope>
</dependency>
```
(instalado con `mvn install`). Comprueba que el jar con shade normal no cambia de nombre: el CI busca `target/ServerAssistant-<ver>.jar`.

### 6.7 Tests de la fase 2
- `ApiQuotaTrackerTest`: ventana por minuto, límite por jugador, presupuesto diario y reinicio al cambiar de día (reloj falso).
- `AiRequestTest`: validaciones de `build()`, orden de mensajes, recorte de temperatura, perfil por defecto.
- `ApiSettingsTest`: carga con `YamlConfiguration.loadConfiguration(new StringReader(...))`, herencia perfil → default → proveedor, `unknown-clients`, permisos de perfiles.
- `AiResultJsonTest`: JSON limpio, con ```json fences, con texto alrededor, inválido → vacío.
- `ExternalRequestQueue`: extrae la decisión "¿puede salir ya esta petición y por qué proveedor?" a una función pura (`DispatchPolicy`) y testea la reserva para Isolda, la prioridad INTERACTIVE, el fallback por retraso y el `QUEUE_TIMEOUT`.
- Commit: `Add public AiService API for other plugins`.

---

## 7. Fase 3 — Comandos de administración

En `CommandManager` (con permiso `sva.admin`), subcomando `api`:
- `/sva api status`:
  - si la API está activa;
  - proveedores disponibles;
  - tamaño de las colas INTERACTIVE/BACKGROUND y peticiones en curso;
  - por cliente: peticiones en el último minuto / límite, tokens de hoy / presupuesto, perfiles.
- `/sva api test <perfil> <texto...>`: hace una petición como el propio ServerAssistant (cliente interno `ServerAssistant`, siempre permitido para este comando) y muestra la respuesta recortada a 300 caracteres, más proveedor/modelo/tokens/latencia.
- Autocompletado para `api`, `status`, `test` y los nombres de perfiles.
- Añade una línea de la API a `/sva status` si es fácil.
- Commit: `Add /sva api status and test commands`.

---

## 8. Fase 4 — Versión, documentación y changelog

- `pom.xml` `1.7.19` → **`1.8.0`**. Cabecera de `config.yml` → `SERVER ASSISTANT 1.8.0`. `config-version: 19`. `validate_source.py` exige que coincidan.
- `CHANGELOG.md`: mueve la entrada `Unreleased` a `## 1.8.0 - AI gateway and public AI API` y añade:
  - el refactor interno (Isolda/gateway, sin cambio de comportamiento);
  - la API pública y la sección `api`;
  - los comandos;
  - que `recordAttempt` ahora lo hace el gateway.
- `README.md`: sección **"Using the AI from another plugin"** con:
  - `softdepend: [ServerAssistant]`;
  - la dependencia Maven `classifier=api`;
  - un ejemplo completo;
  - reglas: el futuro se completa en el hilo principal; nunca `join()` en el principal; siempre tener una alternativa sin IA; no enviar coordenadas ni datos privados; configurar el cliente en `api.clients`.

  Ejemplo:
  ```java
  AiService ai = Bukkit.getServicesManager().load(AiService.class);
  if (ai == null || !ai.isAvailable()) { sendStaticHint(player); return; }

  AiRequest req = AiRequest.builder("tutorial-hint")
      .system("You help new players of a Spanish Minecraft RPG server. Answer in Spanish, max 2 sentences.")
      .user("El jugador pregunta: " + question)
      .forPlayer(player.getUniqueId())
      .priority(AiPriority.INTERACTIVE)
      .tag("tutorial:hint")
      .build();

  ai.request(this, req).whenComplete((result, error) -> {
    if (error != null) { sendStaticHint(player); return; }
    player.sendMessage(result.text());
  });
  ```
  Y otro para JSON (`format(AiResponseFormat.JSON_OBJECT)` + `result.json()`) pensado en generar preguntas de trivia.
- Commit: `Release 1.8.0 docs and version bump`.

---

## 9. Verificación final (antes de dar el trabajo por terminado)

1. `mvn clean verify`, `python validate_config.py` y `python validate_source.py` en verde, con todos los tests (los antiguos y los nuevos).
2. `target/ServerAssistant-1.8.0.jar` existe, y también `target/ServerAssistant-1.8.0-api.jar` solo con `me/kev/sva/api/**`.
3. Busca y confirma: `grep -rn "com.openai" src/main/java/me/kev/sva/api` sin resultados; `grep -rn "new OpenAIOkHttpClient" src/main/java` solo en `ai/AiGateway.java`.
4. **Revisión de invariantes** (sección 3): relee el diff de `AssistantManager` y `SceneRequestQueue` y comprueba uno a uno el orden de prompts, la clave de caché, el JSON mode, la respuesta única y la decisión de fallback.
5. **Prueba manual en un servidor de pruebas.** No la puedes hacer tú: déjala escrita en el resumen final para el usuario.
   - Isolda responde igual que antes a una mención. Con `ai.usage-debug.enabled: true`, la línea de tokens tiene el mismo formato y, en OpenAI, aparece `cached>0` a partir de la segunda petición.
   - `/sva reload` no rompe a Isolda, y una petición externa en curso termina con `RELOADING`.
   - `/sva api test default Hola` responde.
   - Con `api.enabled: false`, `/sva api test` falla con `DISABLED`.
   - Un plugin no listado recibe `CLIENT_NOT_ALLOWED`.
   - Lanzando 10 peticiones BACKGROUND seguidas, Isolda sigue respondiendo a menciones (gracias a la reserva).
6. Resumen final para el usuario: qué cambió, qué tests se añadieron, qué falta probar en el servidor, cómo se llaman las ramas/commits, y que **no** se ha hecho push.

---

## 10. Riesgos y decisiones ya tomadas

| Tema | Decisión |
|---|---|
| ¿Separar la API en un plugin propio? | No, por ahora. Va dentro de ServerAssistant con un jar `-api` aparte. Separarla más adelante no rompe a quien la use si se mantiene el paquete `me.kev.sva.api`. |
| ¿Renombrar el plugin a MDVAsistenteIA? | **No** en este cambio. Quien la use pone `softdepend: [ServerAssistant]`. Si se renombra, que sea un cambio aparte con migración de la carpeta de datos. |
| Reintentos automáticos para llamadas externas | Ninguno sobre el mismo proveedor; solo un salto al fallback. Así el coste es predecible. |
| Presupuesto diario persistente | Solo en memoria en 1.8.0; se puede persistir más adelante. |
| Streaming / tools / embeddings | Fuera de alcance. |
| Caché de prompts para llamadas externas | No se envía `prompt_cache_key`. OpenAI sigue cacheando automáticamente prefijos idénticos. Quien llame puede poner sus instrucciones estables primero para aprovecharlo (documéntalo). |
| Seguridad | Solo los plugins de `api.clients` pueden llamar (`unknown-clients: deny`). La identidad sale del objeto `Plugin` que se pasa. No se registran los prompts. |
