package me.kev.sva.chat.tools;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.tools.all.InventoryTool;
import me.kev.sva.chat.tools.all.LightningTool;
import me.kev.sva.chat.tools.all.MuteTool;
import me.kev.sva.chat.tools.all.PlayerDataTool;
import me.kev.sva.chat.tools.all.ProfileTool;
import me.kev.sva.chat.tools.all.ScheduleTool;
import me.kev.sva.chat.tools.all.SoundTool;
import me.kev.sva.chat.tools.all.Tool;
import me.kev.sva.chat.tools.all.WikiTool;
import me.kev.sva.utils.MessageSender;

/**
 * Merged 1.6 tool system.
 *
 * <p>Read/context capabilities are resolved locally before the single AI call.
 * Action capabilities are an explicit allow-list and may be used in the same
 * model response. activation=ask is enforced in Java with a real approval queue
 * instead of merely trusting the model to ask first.</p>
 */
public final class ToolManager {
  private static final List<String> BUILTIN_STRIKE_TERMS = List.of(
      "idiota", "imbecil", "pelotudo", "boludo", "estupido", "estupida",
      "inutil", "basura", "callate", "mierda", "puta", "puto", "forro", "forra",
      "zorra", "cabron", "cabrona", "pendejo", "pendeja", "tarado", "tarada",
      "chingas", "chingar", "chingada", "chingado", "verga", "culero", "culera",
      "malparido", "malparida", "mamaguevo", "hijo de puta", "hdp",
      "vete a la verga", "vete al carajo");

  private final ServerAssistantPlugin plugin;
  private final Map<String, Tool> tools = new LinkedHashMap<>();
  private final Map<Long, PendingApproval> pendingApprovals = new LinkedHashMap<>();
  private final AtomicLong nextApprovalId = new AtomicLong(1L);
  private final Map<UUID, Deque<Long>> moderationStrikes = new LinkedHashMap<>();
  private final Map<UUID, Long> muteCooldownUntil = new LinkedHashMap<>();

  public ToolManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
    register(new WikiTool(plugin));
    register(new PlayerDataTool(plugin));
    register(new InventoryTool(plugin));
    register(new ProfileTool(plugin));
    register(new LightningTool(plugin));
    register(new SoundTool(plugin));
    register(new MuteTool(plugin));
    register(new ScheduleTool(plugin));
  }

  private void register(Tool tool) {
    tools.put(tool.name.toLowerCase(Locale.ROOT), tool);
  }

  public void shutdown() {
    for (Tool tool : tools.values()) {
      try {
        tool.shutdown();
      } catch (Exception ignored) {
      }
    }
    pendingApprovals.clear();
    moderationStrikes.clear();
    muteCooldownUntil.clear();
  }

  public Set<String> getToolNames() {
    return Set.copyOf(tools.keySet());
  }

  public Tool getTool(String name) {
    if (name == null) return null;
    return tools.get(name.toLowerCase(Locale.ROOT));
  }

  public boolean isToolEnabled(String name) {
    Tool tool = getTool(name);
    return tool != null && tool.enabled();
  }

  public ToolActivation getActivation(String name) {
    Tool tool = getTool(name);
    return tool == null ? ToolActivation.NEVER : tool.activation();
  }

  public boolean setActivation(String name, String activation) {
    Tool tool = getTool(name);
    if (tool == null) return false;
    ToolActivation parsed = ToolActivation.parse(activation);
    if (parsed == ToolActivation.NEVER
        && activation != null
        && !activation.equalsIgnoreCase("never")) {
      return false;
    }
    plugin.getConfig().set("tools." + tool.name + ".activation", parsed.configValue());
    plugin.saveConfig();
    return true;
  }

  /** Compact, cache-friendly summary of what Isolda can actually observe. */
  public String getCapabilitiesPrompt() {
    if (!plugin.getConfig().getBoolean("tools.enabled", true)) {
      return "[CAPABILITIES] local observation/actions disabled";
    }

    List<String> observations = new ArrayList<>();
    if (isToolEnabled("inventory")) {
      observations.add("inventory, held item, offhand and armor when [INVENTORY] is supplied");
    }
    if (isToolEnabled("player-data")) {
      observations.add("online player location/status when [PLAYER-DATA] is supplied");
    }
    if (isToolEnabled("profile")
        && plugin.getIntegrationsConfig() != null
        && plugin.getIntegrationsConfig().getBoolean("enabled", true)
        && plugin.getIntegrationsConfig().getBoolean("profile-context.enabled", true)) {
      observations.add("race/class, RPG level, professions, attributes and equipped title when [PROFILE] is supplied");
    }
    if (isToolEnabled("wiki")) {
      observations.add("server knowledge selected into [WIKI]");
    }

    if (observations.isEmpty()) {
      return "[CAPABILITIES] no local observation capability enabled";
    }
    return "[CAPABILITIES] Treat supplied trusted context as direct in-world observation. You can inspect: "
        + String.join("; ", observations)
        + ". Never say you cannot see a fact that is present in that context.";
  }

  /**
   * Current-scene action catalog. Old history never decides which ACTION tools are
   * exposed to the model. This both saves prompt tokens and prevents a previous
   * lightning/sound request from being repeated in the next scene.
   */
  public String getAvailableActionToolsPrompt(String currentActionText) {
    if (!plugin.getConfig().getBoolean("tools.enabled", true)) {
      return "[TOOLS] disabled; t must be []";
    }

    String text = normalize(currentActionText);
    if (text.isBlank()) {
      return "[TOOLS] No ACTION is authorized by the CURRENT scene; t must be [].";
    }

    List<String> rows = new ArrayList<>();
    for (Tool tool : tools.values()) {
      if (tool.kind() != ToolKind.ACTION || !tool.enabled()) continue;
      if (!hasCurrentActionIntent(tool.name, text)) continue;
      String mode = tool.activation().configValue();
      String suffix = tool.activation() == ToolActivation.ASK
          ? " Calling it creates a pending admin approval; it does NOT execute immediately."
          : "";
      rows.add(tool.name + " mode=" + mode + " | " + oneLine(tool.usage()) + suffix);
    }

    if (rows.isEmpty()) {
      return "[TOOLS] No ACTION is authorized by the CURRENT scene; t must be [].";
    }
    int maxCalls = Math.max(plugin.getConfig().getInt("tools.max-calls-per-response", 2), 0);
    return "[TOOLS] CURRENT scene authorizes at most " + maxCalls
        + " exact action calls from the list below. History/previous scenes authorize nothing. "
        + "If t is non-empty, m must describe the action as actually happening now; do not use refusal/conditional wording.\n"
        + String.join("\n", rows);
  }

  /** Compatibility/debug view: lists every enabled action tool. */
  public String getAvailableActionToolsPrompt() {
    return getAvailableActionToolsPrompt("rayo sonido recuerdame mute");
  }

  /** True when text contains any currently implemented ACTION intent. Used to keep old action requests out of model history. */
  public boolean hasAnyActionIntent(String rawText) {
    String text = normalize(rawText);
    if (text.isBlank()) return false;
    return hasCurrentActionIntent("lightning", text)
        || hasCurrentActionIntent("sound", text)
        || hasCurrentActionIntent("schedule", text)
        || containsAny(text, "mute", "mutea", "mutear", "silencia", "silenciar");
  }

  private boolean hasCurrentActionIntent(String toolName, String normalizedText) {
    if (toolName == null || normalizedText == null || normalizedText.isBlank()) return false;
    return switch (toolName) {
      case "lightning" -> containsAny(normalizedText,
          "rayo", "rayos", "relampago", "relampagos", "lightning", "electrifica", "electrocut");
      case "sound" -> containsAny(normalizedText,
          "sonido", "suena", "haz sonar", "pon sonido", "asusta", "asustame", "asustanos",
          "susto", "celebra", "celebracion", "festeja", "festejo");
      case "schedule" -> containsAny(normalizedText,
          "recuerdame", "recordame", "avisame", "avisa", "en unos segundos",
          "en 10 segundos", "en 20 segundos", "en 30 segundos", "despues");
      case "mute" -> {
        boolean requested = containsAny(normalizedText,
            "mute", "mutea", "mutear", "silencia", "silenciar");
        if (!requested) yield false;
        if (!plugin.getConfig().getBoolean("tools.mute.policy.require-eligibility-for-ai", true)) yield true;
        // Do not advertise mute to the model when deterministic moderation says no
        // currently named online target is eligible. Threshold auto-action remains Java-side.
        boolean eligible = false;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
          if (containsAny(normalizedText, normalize(player.getName())) && isMuteEligible(player)) {
            eligible = true;
            break;
          }
        }
        yield eligible;
      }
      default -> false;
    };
  }

  /**
   * Executes context tools locally based on Java intent matching. No extra model
   * request is created, which preserves the one-call global scene design.
   */
  public String buildLocalContext(
      List<ChatMessage> currentSceneMessages,
      Set<String> involvedPlayerNames) {

    if (!plugin.getConfig().getBoolean("tools.enabled", true)) return "";
    StringBuilder raw = new StringBuilder();
    for (ChatMessage message : currentSceneMessages) {
      if (message != null && message.content != null) raw.append(message.content).append(' ');
    }
    String normalized = normalize(raw.toString());
    if (normalized.isBlank()) return "";

    int maxTools = Math.max(plugin.getConfig().getInt("tools.local-context.max-tools", 2), 0);
    int maxChars = Math.max(plugin.getConfig().getInt("tools.local-context.max-chars-per-tool", 1800), 200);
    if (maxTools == 0) return "";

    List<String> names = involvedPlayerNames == null
        ? List.of()
        : new ArrayList<>(new LinkedHashSet<>(involvedPlayerNames));

    StringBuilder out = new StringBuilder();
    int used = 0;
    for (Tool tool : tools.values()) {
      if (used >= maxTools) break;
      if (tool.kind() != ToolKind.CONTEXT || !tool.enabled()) continue;
      // Wiki uses the stronger section scorer in ConversationManager.
      if ("wiki".equals(tool.name)) continue;
      if (!tool.shouldPrefetch(normalized, currentSceneMessages)) continue;
      String context;
      try {
        context = tool.buildLocalContext(names, normalized, currentSceneMessages);
      } catch (Exception ex) {
        plugin.getLogger().warning("Local context tool '" + tool.name + "' failed: " + ex.getMessage());
        continue;
      }
      if (context == null || context.isBlank()) continue;
      context = context.trim();
      if (context.length() > maxChars) context = context.substring(0, maxChars).trim();
      if (!out.isEmpty()) out.append('\n');
      out.append('[').append(tool.name.toUpperCase(Locale.ROOT)).append("] ").append(context);
      if (plugin.getConfig().getBoolean("tools.local-context.debug-log", false)) {
        plugin.getLogger().info("Local context selected: " + tool.name + " -> " + context);
      }
      used++;
    }
    String moderation = buildModerationContext();
    if (!moderation.isBlank()) {
      if (!out.isEmpty()) out.append('\n');
      out.append("[MODERATION] ").append(moderation);
    }
    return out.toString();
  }

  /**
   * Tracks configurable abusive messages directed at Isolda. This is deterministic
   * Java-side state; the model cannot invent eligibility.
   */
  public void observePlayerMessage(Player player, String message, boolean directedAtAssistant) {
    if (player == null || message == null || message.isBlank()) return;
    if (!plugin.getConfig().getBoolean("tools.mute.policy.enabled", true)) return;
    if (plugin.getConfig().getBoolean("tools.mute.policy.directed-only", true) && !directedAtAssistant) return;

    String normalized = normalize(message);
    if (normalized.isBlank() || !containsConfiguredStrikeTerm(normalized)) return;

    long now = System.currentTimeMillis();
    long windowMs = Math.max(plugin.getConfig().getLong("tools.mute.policy.window-ms", 60_000L), 5_000L);
    Deque<Long> strikes = moderationStrikes.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
    pruneOlderThan(strikes, now - windowMs);
    strikes.addLast(now);

    if (plugin.getConfig().getBoolean("tools.mute.policy.debug-log", false)) {
      plugin.getLogger().info("Moderation strike: " + player.getName() + " -> "
          + strikes.size() + "/"
          + Math.max(plugin.getConfig().getInt("tools.mute.policy.strikes-required", 3), 1)
          + " message='" + normalized + "'");
    }

    handleModerationThreshold(player);
  }

  private boolean containsConfiguredStrikeTerm(String normalizedMessage) {
    List<String> terms = new ArrayList<>();
    if (plugin.getConfig().getBoolean("tools.mute.policy.builtin-lexicon", true)) {
      terms.addAll(BUILTIN_STRIKE_TERMS);
    }
    List<String> configured = plugin.getConfig().getStringList("tools.mute.policy.strike-terms");
    if (configured != null) terms.addAll(configured);
    if (terms.isEmpty()) return false;

    String haystack = " " + normalizedMessage + " ";
    List<String> messageTokens = List.of(normalizedMessage.split(" "));
    boolean fuzzy = plugin.getConfig().getBoolean("tools.mute.policy.fuzzy-typos", true);

    for (String raw : terms) {
      String term = normalize(raw);
      if (term.isBlank()) continue;
      if (haystack.contains(" " + term + " ")) return true;

      // Conservative typo tolerance only for single profanity tokens (e.g. ptua -> puta).
      // Only adjacent swaps or one inserted/deleted character are accepted; ordinary
      // one-letter substitutions are NOT fuzzy-matched to reduce false positives.
      if (fuzzy && !term.contains(" ") && term.length() >= 4) {
        for (String token : messageTokens) {
          if (token.length() < 4 || Math.abs(token.length() - term.length()) > 1) continue;
          if (damerauDistanceAtMostOne(token, term)) return true;
        }
      }
    }
    return false;
  }

  private void handleModerationThreshold(Player player) {
    if (!plugin.getConfig().getBoolean("tools.mute.policy.auto-action-on-threshold", true)) return;
    if (!isMuteEligible(player)) return;

    Tool mute = getTool("mute");
    if (mute == null || !mute.enabled() || mute.activation() == ToolActivation.NEVER) return;
    if (hasPendingMuteApproval(player.getName())) return;

    if (mute.activation() == ToolActivation.ASK) {
      queueApproval(mute, player.getName(), "mute " + player.getName());
    } else {
      executeAndLog(mute, player.getName(), "moderation-policy");
    }
  }

  private boolean hasPendingMuteApproval(String playerName) {
    if (playerName == null || playerName.isBlank()) return false;
    pruneExpiredApprovals();
    return pendingApprovals.values().stream().anyMatch(p ->
        "mute".equalsIgnoreCase(p.toolName())
            && playerName.equalsIgnoreCase(p.arguments()));
  }

  private static boolean damerauDistanceAtMostOne(String a, String b) {
    if (a.equals(b)) return true;
    if (Math.abs(a.length() - b.length()) > 1) return false;

    // Same length: only one adjacent transposition.
    if (a.length() == b.length()) {
      int first = -1;
      int second = -1;
      int mismatches = 0;
      for (int i = 0; i < a.length(); i++) {
        if (a.charAt(i) == b.charAt(i)) continue;
        if (mismatches == 0) first = i;
        else if (mismatches == 1) second = i;
        mismatches++;
        if (mismatches > 2) return false;
      }
      if (mismatches != 2) return false;
      return second == first + 1
          && a.charAt(first) == b.charAt(second)
          && a.charAt(second) == b.charAt(first);
    }

    // One insertion/deletion.
    String shorter = a.length() < b.length() ? a : b;
    String longer = a.length() < b.length() ? b : a;
    int i = 0;
    int j = 0;
    boolean skipped = false;
    while (i < shorter.length() && j < longer.length()) {
      if (shorter.charAt(i) == longer.charAt(j)) {
        i++;
        j++;
      } else if (!skipped) {
        skipped = true;
        j++;
      } else {
        return false;
      }
    }
    return true;
  }

  private String buildModerationContext() {
    if (!plugin.getConfig().getBoolean("tools.mute.policy.enabled", true)) return "";
    pruneModerationState();
    List<String> rows = new ArrayList<>();
    int required = Math.max(plugin.getConfig().getInt("tools.mute.policy.strikes-required", 3), 1);
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      int strikes = currentStrikeCount(player.getUniqueId());
      if (strikes <= 0) continue;
      boolean eligible = isMuteEligible(player);
      boolean pending = hasPendingMuteApproval(player.getName());
      rows.add(player.getName() + " strikes=" + strikes + "/" + required
          + " eligible=" + eligible
          + " pending=" + pending
          + " reason=" + moderationReason(player));
    }
    return rows.isEmpty()
        ? "no recent moderation strikes; do not call mute"
        : String.join("; ", rows)
            + ". Only an eligible=true target may be muted; pending=true means approval is already queued.";
  }

  public List<String> moderationSummaries() {
    pruneModerationState();
    int required = Math.max(plugin.getConfig().getInt("tools.mute.policy.strikes-required", 3), 1);
    List<String> rows = new ArrayList<>();
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      int strikes = currentStrikeCount(player.getUniqueId());
      if (strikes > 0 || isMuteEligible(player) || hasPendingMuteApproval(player.getName())) {
        rows.add(player.getName()
            + " strikes=" + strikes + "/" + required
            + " eligible=" + isMuteEligible(player)
            + " pending=" + hasPendingMuteApproval(player.getName())
            + " reason=" + moderationReason(player));
      }
    }
    return rows;
  }

  private String moderationReason(Player player) {
    if (player == null) return "offline";
    if (!plugin.getConfig().getBoolean("tools.mute.allow-admin-targets", false)
        && (player.isOp() || player.hasPermission("sva.admin"))) return "admin-protected";
    if (muteCooldownUntil.getOrDefault(player.getUniqueId(), 0L) > System.currentTimeMillis()) return "cooldown";
    int required = Math.max(plugin.getConfig().getInt("tools.mute.policy.strikes-required", 3), 1);
    if (currentStrikeCount(player.getUniqueId()) < required) return "below-threshold";
    if (hasPendingMuteApproval(player.getName())) return "approval-pending";
    return "threshold-met";
  }

  /** Process model-generated ACTION calls from the same response. */
  public void processModelCalls(List<String> calls) {
    processModelCalls(calls, "");
  }

  /**
   * Returns false only when the response contained ACTION calls but none of the
   * processed calls were accepted. A valid current action is not silenced just
   * because the model also leaked one stale call from history.
   */
  public boolean processModelCalls(List<String> calls, String currentActionText) {
    if (calls == null || calls.isEmpty() || !plugin.getConfig().getBoolean("tools.enabled", true)) return true;
    int maxCalls = Math.max(plugin.getConfig().getInt("tools.max-calls-per-response", 2), 0);
    if (maxCalls == 0) return true;

    boolean anyAccepted = false;
    boolean anyRejected = false;
    int processed = 0;
    Set<String> dedupe = new LinkedHashSet<>();
    for (String raw : calls) {
      if (processed >= maxCalls) break;
      if (raw == null) continue;
      String call = raw.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
      if (call.isBlank() || call.length() > 220 || !dedupe.add(call.toLowerCase(Locale.ROOT))) continue;

      ParsedCall parsed = parse(call);
      Tool tool = getTool(parsed.name());
      if (tool == null || tool.kind() != ToolKind.ACTION || !tool.enabled()) {
        plugin.getLogger().warning("Ignored unavailable AI tool call: " + call);
        continue;
      }
      processed++;

      String authorizedArguments = parsed.arguments();
      if ("lightning".equals(tool.name)) {
        authorizedArguments = resolveAuthorizedLightningTarget(currentActionText, parsed.arguments());
        if (authorizedArguments == null) {
          anyRejected = true;
          plugin.getLogger().warning("Ignored stale/policy-blocked AI tool call: " + call);
          continue;
        }
        if (!authorizedArguments.equalsIgnoreCase(parsed.arguments() == null ? "" : parsed.arguments().trim())) {
          plugin.getLogger().info("Corrected lightning target from model '" + parsed.arguments()
              + "' to current requester target '" + authorizedArguments + "'.");
        }
      }

      if (!isModelCallAuthorized(tool, authorizedArguments, currentActionText)) {
        anyRejected = true;
        plugin.getLogger().warning("Ignored stale/policy-blocked AI tool call: " + call);
        continue;
      }

      if (tool.activation() == ToolActivation.ASK) {
        queueApproval(tool, authorizedArguments, tool.name + " " + authorizedArguments);
      } else {
        executeAndLog(tool, authorizedArguments, "AI");
      }
      anyAccepted = true;
    }
    return anyAccepted || !anyRejected;
  }

  private boolean isModelCallAuthorized(Tool tool, String arguments, String currentActionText) {
    if (!plugin.getConfig().getBoolean("tools.action-safety.require-current-scene-intent", true)) {
      return !"mute".equals(tool.name) || isMuteEligible(arguments);
    }

    String text = normalize(currentActionText);
    if (text.isBlank()) return false;

    if ("mute".equals(tool.name)) {
      // Moderation eligibility is never enough by itself: the CURRENT scene must
      // also explicitly request a mute. This prevents an old eligible mute call
      // from leaking out of history into a later scene.
      if (!hasCurrentActionIntent("mute", text)) return false;
      if (!plugin.getConfig().getBoolean("tools.mute.policy.require-eligibility-for-ai", true)) return true;
      return isMuteEligible(arguments);
    }

    if (!hasCurrentActionIntent(tool.name, text)) return false;
    if ("lightning".equals(tool.name)) {
      Set<String> targets = authorizedLightningTargets(currentActionText);
      return arguments != null && targets.stream().anyMatch(name -> name.equalsIgnoreCase(arguments.trim()));
    }
    return true;
  }

  private String resolveAuthorizedLightningTarget(String currentActionText, String modelArgument) {
    Set<String> targets = authorizedLightningTargets(currentActionText);
    if (targets.isEmpty()) return null;

    String requested = modelArgument == null ? "" : modelArgument.trim();
    if (!requested.isBlank()) {
      for (String target : targets) {
        if (target.equalsIgnoreCase(requested)) return target;
      }
      List<String> prefix = targets.stream()
          .filter(target -> target.toLowerCase(Locale.ROOT).startsWith(requested.toLowerCase(Locale.ROOT)))
          .toList();
      if (prefix.size() == 1) return prefix.get(0);
    }

    // In a mixed social scene GPT can bind "tirame" to the wrong participant.
    // If Java can see exactly one currently authorized lightning target, correct it
    // instead of executing on the wrong player or wasting the already-paid reply.
    return targets.size() == 1 ? targets.iterator().next() : null;
  }

  private Set<String> authorizedLightningTargets(String currentActionText) {
    LinkedHashSet<String> targets = new LinkedHashSet<>();
    if (currentActionText == null || currentActionText.isBlank()) return targets;

    for (String rawLine : currentActionText.split("\\R")) {
      String line = rawLine == null ? "" : rawLine.trim();
      if (line.isBlank()) continue;
      String speaker = "";
      String content = line;
      if (line.startsWith("speaker=")) {
        int separator = line.indexOf('|');
        if (separator > 8) {
          speaker = line.substring(8, separator).trim();
          content = line.substring(separator + 1).trim();
        }
      }
      String normalized = normalize(content);
      if (!hasCurrentActionIntent("lightning", normalized)) continue;

      LinkedHashSet<String> explicit = new LinkedHashSet<>();
      for (Player online : plugin.getServer().getOnlinePlayers()) {
        if (ContextTargetResolver.mentionsName(normalized, online.getName())) {
          explicit.add(online.getName());
        }
      }
      targets.addAll(explicit);

      boolean selfWording = containsAny(normalized,
          "tirame", "echame", "a mi", "ami", "sobre mi", "encima mio", "electrocutame");
      if (!speaker.isBlank() && (explicit.isEmpty() || selfWording)) {
        Player requester = plugin.getServer().getPlayerExact(speaker);
        if (requester != null) targets.add(requester.getName());
      }
    }
    return targets;
  }

  private boolean isMuteEligible(String requestedName) {
    String name = requestedName == null ? "" : requestedName.trim();
    if (name.isBlank() || name.contains(" ")) return false;
    Player player = plugin.getServer().getPlayerExact(name);
    return player != null && isMuteEligible(player);
  }

  private boolean isMuteEligible(Player player) {
    if (player == null || !plugin.getConfig().getBoolean("tools.mute.policy.enabled", true)) return false;
    if (!plugin.getConfig().getBoolean("tools.mute.allow-admin-targets", false)
        && (player.isOp() || player.hasPermission("sva.admin"))) return false;
    long now = System.currentTimeMillis();
    if (muteCooldownUntil.getOrDefault(player.getUniqueId(), 0L) > now) return false;
    int required = Math.max(plugin.getConfig().getInt("tools.mute.policy.strikes-required", 3), 1);
    return currentStrikeCount(player.getUniqueId()) >= required;
  }

  private int currentStrikeCount(UUID playerId) {
    Deque<Long> strikes = moderationStrikes.get(playerId);
    if (strikes == null) return 0;
    long windowMs = Math.max(plugin.getConfig().getLong("tools.mute.policy.window-ms", 60_000L), 5_000L);
    pruneOlderThan(strikes, System.currentTimeMillis() - windowMs);
    if (strikes.isEmpty()) moderationStrikes.remove(playerId);
    return strikes.size();
  }

  private void pruneModerationState() {
    long now = System.currentTimeMillis();
    long windowMs = Math.max(plugin.getConfig().getLong("tools.mute.policy.window-ms", 60_000L), 5_000L);
    moderationStrikes.entrySet().removeIf(entry -> {
      pruneOlderThan(entry.getValue(), now - windowMs);
      return entry.getValue().isEmpty();
    });
    muteCooldownUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
  }

  private static void pruneOlderThan(Deque<Long> values, long cutoff) {
    while (!values.isEmpty() && values.peekFirst() < cutoff) values.removeFirst();
  }

  private static boolean containsAny(String text, String... terms) {
    for (String term : terms) {
      if (text.contains(term)) return true;
    }
    return false;
  }

  private void queueApproval(Tool tool, String arguments, String rawCall) {
    pruneExpiredApprovals();
    if ("mute".equalsIgnoreCase(tool.name) && hasPendingMuteApproval(arguments)) {
      return;
    }
    int maxPending = Math.max(plugin.getConfig().getInt("tools.approvals.max-pending", 8), 1);
    while (pendingApprovals.size() >= maxPending) {
      Long oldest = pendingApprovals.keySet().iterator().next();
      pendingApprovals.remove(oldest);
    }

    long now = System.currentTimeMillis();
    long expiry = now + Math.max(plugin.getConfig().getLong("tools.approvals.expires-ms", 30_000L), 5_000L);
    long id = nextApprovalId.getAndIncrement();
    pendingApprovals.put(id, new PendingApproval(id, tool.name, arguments, rawCall, now, expiry));

    String assistantName = plugin.getConfig().getString("assistant-name", "Isolda");
    if (assistantName == null || assistantName.isBlank()) assistantName = "Isolda";
    String notice = assistantName + " requested tool '" + rawCall + "' [approval " + id
        + "]. Use /sva approve " + id + " or /sva deny " + id + ".";
    MessageSender.Success(notice);
    for (Player player : plugin.getServer().getOnlinePlayers()) {
      if (player.isOp() || player.hasPermission("sva.admin")) {
        MessageSender.Success(player, notice);
      }
    }
  }

  public String approve(long id, CommandSender sender) {
    pruneExpiredApprovals();
    PendingApproval pending = pendingApprovals.remove(id);
    if (pending == null) return "No pending approval with id " + id + ".";
    Tool tool = getTool(pending.toolName());
    if (tool == null || !tool.globallyEnabled() || tool.activation() == ToolActivation.NEVER) {
      return "Tool is no longer available; approval cancelled.";
    }
    String result = executeAndLog(tool, pending.arguments(), sender == null ? "admin" : sender.getName());
    return "Approved #" + id + ": " + result;
  }

  public String deny(long id) {
    pruneExpiredApprovals();
    PendingApproval pending = pendingApprovals.remove(id);
    return pending == null
        ? "No pending approval with id " + id + "."
        : "Denied #" + id + " (" + pending.rawCall() + ").";
  }

  public List<String> pendingApprovalSummaries() {
    pruneExpiredApprovals();
    long now = System.currentTimeMillis();
    return pendingApprovals.values().stream()
        .map(p -> "#" + p.id() + " " + p.rawCall() + " expires=" + Math.max(0L, (p.expiresAt() - now + 999L) / 1000L) + "s")
        .toList();
  }

  /** Explicit admin testing/execution bypasses model activation approval but remains allow-listed. */
  public String executeAdminTool(String call, CommandSender sender) {
    if (call == null || call.isBlank()) return "Missing tool call.";
    ParsedCall parsed = parse(call.trim());
    Tool tool = getTool(parsed.name());
    if (tool == null) return "Unknown tool: " + parsed.name();
    return executeAndLog(tool, parsed.arguments(), sender == null ? "admin" : sender.getName());
  }

  public List<String> describeTools() {
    return tools.values().stream()
        .map(tool -> tool.name + "=" + tool.activation().configValue() + " (" + tool.kind().name().toLowerCase(Locale.ROOT) + ")")
        .toList();
  }

  private String executeAndLog(Tool tool, String arguments, String source) {
    try {
      String result = tool.execute(arguments == null ? "" : arguments);
      if ("mute".equals(tool.name) && result != null && result.startsWith("Muted ")) {
        Player target = plugin.getServer().getPlayerExact(arguments == null ? "" : arguments.trim());
        if (target != null) {
          moderationStrikes.remove(target.getUniqueId());
          long cooldown = Math.max(plugin.getConfig().getLong("tools.mute.policy.cooldown-ms", 300_000L), 0L);
          if (cooldown > 0L) muteCooldownUntil.put(target.getUniqueId(), System.currentTimeMillis() + cooldown);
        }
      }
      plugin.getLogger().info("Tool " + tool.name + " executed by " + source + ": " + result);
      return result;
    } catch (Exception ex) {
      plugin.getLogger().warning("Tool " + tool.name + " failed safely: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
      return "Tool failed safely.";
    }
  }

  private void pruneExpiredApprovals() {
    long now = System.currentTimeMillis();
    pendingApprovals.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
  }

  private static ParsedCall parse(String call) {
    int split = call.indexOf(' ');
    if (split < 0) return new ParsedCall(call.toLowerCase(Locale.ROOT), "");
    return new ParsedCall(
        call.substring(0, split).toLowerCase(Locale.ROOT),
        call.substring(split + 1).trim());
  }

  private static String oneLine(String text) {
    return text == null ? "" : text.replaceAll("[\\r\\n]+", " ").replaceAll("\\s{2,}", " ").trim();
  }

  public static String normalize(String input) {
    return Normalizer.normalize(input == null ? "" : input, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}_@.-]+", " ")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private record ParsedCall(String name, String arguments) {
  }

  private record PendingApproval(
      long id,
      String toolName,
      String arguments,
      String rawCall,
      long createdAt,
      long expiresAt) {
  }
}
