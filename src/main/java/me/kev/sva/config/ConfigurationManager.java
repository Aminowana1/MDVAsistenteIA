package me.kev.sva.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import me.kev.sva.ServerAssistantPlugin;

/**
 * Owns ServerAssistant's YAML files and performs non-destructive schema updates.
 *
 * <p>Technical/runtime settings stay in config.yml, character text lives in
 * personality.yml, local knowledge lives in wiki.yml, optional plugin hooks live in
 * integrations.yml, and relationship rules live in relationships.yml. On every startup/reload,
 * missing keys from the bundled files are
 * copied into the user's files while existing
 * values are preserved. Explicit migrations handle schema moves such as the 1.6.2
 * single-file layout.</p>
 */
public final class ConfigurationManager {
  private static final String PERSONALITY_FILE = "personality.yml";
  private static final String WIKI_FILE = "wiki.yml";
  private static final String INTEGRATIONS_FILE = "integrations.yml";
  private static final String RELATIONSHIPS_FILE = "relationships.yml";

  private final ServerAssistantPlugin plugin;
  private FileConfiguration personalityConfig;
  private FileConfiguration wikiConfig;
  private FileConfiguration integrationsConfig;
  private FileConfiguration relationshipsConfig;

  public ConfigurationManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  /** Loads, migrates and auto-updates all YAML files. Safe to call on /sva reload. */
  public void loadAndUpdate() {
    plugin.getDataFolder().mkdirs();
    ensureResource(PERSONALITY_FILE);
    ensureResource(WIKI_FILE);
    ensureResource(INTEGRATIONS_FILE);
    ensureResource(RELATIONSHIPS_FILE);

    File mainFile = new File(plugin.getDataFolder(), "config.yml");
    File personalityFile = new File(plugin.getDataFolder(), PERSONALITY_FILE);
    File wikiFile = new File(plugin.getDataFolder(), WIKI_FILE);
    File integrationsFile = new File(plugin.getDataFolder(), INTEGRATIONS_FILE);
    File relationshipsFile = new File(plugin.getDataFolder(), RELATIONSHIPS_FILE);

    YamlConfiguration main = loadUserFile(mainFile);
    YamlConfiguration personality = loadUserFile(personalityFile);
    YamlConfiguration wiki = loadUserFile(wikiFile);
    YamlConfiguration integrations = loadUserFile(integrationsFile);
    YamlConfiguration relationships = loadUserFile(relationshipsFile);

    boolean migrated = migrateSingleFileLayout(main, personality, wiki, mainFile);
    boolean actionSafetyMigrated = migrate166ActionSafetyDefault(main);
    boolean outputBudgetMigrated = migrate171OutputBudget(main);
    boolean relationshipPersonalityMigrated = migrateRelationshipPersonalityDefault(personality);
    boolean relationshipCapacityMigrated = migrate171RelationshipUpdateCapacity(relationships);
    boolean multiReplyMigrated = migrate176MultiReplyDefault(main);
    boolean relationshipContextMigrated = migrate176RelationshipContextCapacity(relationships);
    boolean archEnemyIgnoreMigrated = migrate176ArchEnemyIgnoreDefault(relationships);
    boolean relationshipMemoryBudgetMigrated = migrate179RelationshipMemoryBudget(relationships);
    boolean groupThreadDefaultsMigrated = migrate1712GroupThreadDefaults(main);
    boolean mainChanged = migrated | actionSafetyMigrated | outputBudgetMigrated | multiReplyMigrated
        | groupThreadDefaultsMigrated | mergeBundledDefaults(main, "config.yml");
    boolean personalityChanged = migrated | relationshipPersonalityMigrated | mergeBundledDefaults(personality, PERSONALITY_FILE);
    // wiki.* is user knowledge, not schema. Do not resurrect pages an admin intentionally removed.
    boolean wikiChanged = migrated | mergeBundledDefaults(wiki, WIKI_FILE, "wiki");
    boolean integrationsChanged = mergeBundledDefaults(integrations, INTEGRATIONS_FILE);
    boolean relationshipsChanged = relationshipCapacityMigrated | relationshipContextMigrated | archEnemyIgnoreMigrated
        | relationshipMemoryBudgetMigrated | mergeBundledDefaults(relationships, RELATIONSHIPS_FILE);

    mainChanged |= syncSchemaVersion(main, "config.yml");
    personalityChanged |= syncSchemaVersion(personality, PERSONALITY_FILE);
    wikiChanged |= syncSchemaVersion(wiki, WIKI_FILE);
    integrationsChanged |= syncSchemaVersion(integrations, INTEGRATIONS_FILE);
    relationshipsChanged |= syncSchemaVersion(relationships, RELATIONSHIPS_FILE);

    try {
      if (mainChanged) main.save(mainFile);
      if (personalityChanged) personality.save(personalityFile);
      if (wikiChanged) wiki.save(wikiFile);
      if (integrationsChanged) integrations.save(integrationsFile);
      if (relationshipsChanged) relationships.save(relationshipsFile);
    } catch (IOException ex) {
      throw new IllegalStateException("Could not save updated ServerAssistant YAML files", ex);
    }

    // Refresh JavaPlugin's normal config after our disk-level merge.
    plugin.reloadConfig();
    personalityConfig = loadUserFile(personalityFile);
    wikiConfig = loadUserFile(wikiFile);
    integrationsConfig = loadUserFile(integrationsFile);
    relationshipsConfig = loadUserFile(relationshipsFile);

    if (migrated) {
      plugin.getLogger().info("Migrated character prompt to personality.yml and local wiki to wiki.yml.");
    }
    if (actionSafetyMigrated) {
      plugin.getLogger().info("Migrated 1.6.6 action-safety default: suppress-reply-on-rejected-call=true.");
    }
    if (relationshipPersonalityMigrated) {
      plugin.getLogger().info("Migrated the old hardcoded Aminowana romance default to relationship-driven behavior.");
    }
    if (outputBudgetMigrated) {
      plugin.getLogger().info("Raised the old 75-token output ceiling to 120 for reliable m+t+r JSON responses.");
    }
    if (relationshipCapacityMigrated) {
      plugin.getLogger().info("Raised the old relationship updates-per-scene default from 1 to 2 for group scenes.");
    }
    if (multiReplyMigrated) {
      plugin.getLogger().info("Raised the old single-message chat output default to 3 short replies for multi-speaker scenes.");
    }
    if (relationshipContextMigrated) {
      plugin.getLogger().info("Raised relationship context capacity from 2 to 3 current addressed players.");
    }
    if (archEnemyIgnoreMigrated) {
      plugin.getLogger().info("Changed the old arch-enemy trivial-ignore default from 40% to 0% so maximum-hostility players are answered rather than silently dropped.");
    }
    if (relationshipMemoryBudgetMigrated) {
      plugin.getLogger().info("Raised relationship memory summaries from 90 to 120 chars so stored events can stay specific and self-contained.");
    }
    if (groupThreadDefaultsMigrated) {
      plugin.getLogger().info("Migrated 1.7.12 group-thread defaults: wider pre-call window and more natural zero-token join affinity.");
    }
  }

  public FileConfiguration personality() {
    return personalityConfig;
  }

  public FileConfiguration wiki() {
    return wikiConfig;
  }

  public FileConfiguration integrations() {
    return integrationsConfig;
  }

  public FileConfiguration relationships() {
    return relationshipsConfig;
  }

  /** Saves relationships.yml after a runtime toggle without touching the other YAML files. */
  public void saveRelationships() {
    if (relationshipsConfig == null) return;
    File file = new File(plugin.getDataFolder(), RELATIONSHIPS_FILE);
    try {
      relationshipsConfig.save(file);
    } catch (IOException ex) {
      throw new IllegalStateException("Could not save relationships.yml", ex);
    }
  }

  /** Saves integrations.yml after a runtime toggle without touching the other YAML files. */
  public void saveIntegrations() {
    if (integrationsConfig == null) return;
    File file = new File(plugin.getDataFolder(), INTEGRATIONS_FILE);
    try {
      integrationsConfig.save(file);
    } catch (IOException ex) {
      throw new IllegalStateException("Could not save integrations.yml", ex);
    }
  }

  /** Loads a user file strictly. Invalid YAML aborts reload instead of being overwritten. */
  private YamlConfiguration loadUserFile(File file) {
    YamlConfiguration config = new YamlConfiguration();
    try {
      config.load(file);
      return config;
    } catch (IOException | InvalidConfigurationException ex) {
      throw new IllegalStateException(
          "Invalid or unreadable YAML: " + file.getName() + ". The file was left unchanged.", ex);
    }
  }

  private void ensureResource(String resourceName) {
    File file = new File(plugin.getDataFolder(), resourceName);
    if (!file.exists()) {
      plugin.saveResource(resourceName, false);
    }
  }


  /**
   * 1.7.12 fixes group entry being stricter than group exit. Existing installations
   * using the exact 1.7.11 bundled values receive the new safe defaults; deliberate
   * custom tuning is preserved. New keys are merged normally afterwards.
   */
  private boolean migrate1712GroupThreadDefaults(YamlConfiguration main) {
    if (main.getInt("config-version", 0) > 15) return false;
    boolean changed = false;
    changed |= migrateExactInt(main,
        "global-conversation.scene.group-threading.pre-candidate-lookback-ms", 6000, 12000);
    changed |= migrateExactInt(main,
        "global-conversation.scene.group-threading.affinity.join-threshold", 48, 44);
    changed |= migrateExactInt(main,
        "global-conversation.scene.group-threading.affinity.min-margin-over-side-thread", 12, 10);
    changed |= migrateExactInt(main,
        "global-conversation.scene.group-threading.affinity.auto-follow-up-threshold", 68, 64);
    return changed;
  }

  private static boolean migrateExactInt(
      YamlConfiguration config, String path, int oldValue, int newValue) {
    if (!config.isSet(path) || config.getInt(path, oldValue) != oldValue) return false;
    config.set(path, newValue);
    return true;
  }

  /**
   * 1.7.9 stores memories as concrete self-contained events instead of tiny labels.
   * Migrate only the previous bundled 90-char value; custom budgets are preserved.
   */
  private boolean migrate179RelationshipMemoryBudget(YamlConfiguration relationships) {
    if (relationships.getInt("config-version", 0) > 5) return false;
    String path = "memories.max-summary-chars";
    if (!relationships.isSet(path) || relationships.getInt(path, 90) != 90) return false;
    relationships.set(path, 120);
    return true;
  }

  /**
   * 1.7.6 can answer up to three distinct current addressers inside the SAME model
   * request. Migrate only the old bundled value 1; deliberate custom values survive.
   */
  private boolean migrate176MultiReplyDefault(YamlConfiguration main) {
    if (main.getInt("config-version", 0) > 11) return false;
    String path = "chat.max-messages-per-response";
    if (!main.isSet(path) || main.getInt(path, 1) != 1) return false;
    main.set(path, 3);
    return true;
  }

  /**
   * Multi-speaker scenes need the relationship tier for every addressed player.
   * This changes only the old bundled value 2 so custom context budgets are preserved.
   */
  private boolean migrate176RelationshipContextCapacity(YamlConfiguration relationships) {
    if (relationships.getInt("config-version", 0) > 3) return false;
    String path = "context.max-players";
    if (!relationships.isSet(path) || relationships.getInt(path, 2) != 2) return false;
    relationships.set(path, 3);
    return true;
  }

  /**
   * 1.7.6 makes arch-enemy the active maximum-hostility tier. The previous bundled
   * 40% trivial-message ignore chance made testing look like random no-response bugs.
   * Migrate only that exact old default; deliberate custom probabilities survive.
   */
  private boolean migrate176ArchEnemyIgnoreDefault(YamlConfiguration relationships) {
    if (relationships.getInt("config-version", 0) > 3) return false;
    String path = "behavior.ignore.thresholds.-90";
    if (!relationships.isSet(path)) return false;
    double current = relationships.getDouble(path, 0.40);
    if (Math.abs(current - 0.40) > 0.000001) return false;
    relationships.set(path, 0.0);
    return true;
  }

  /**
   * 1.7.1 needs a little more headroom for the compact m+t+r envelope. This changes
   * only the exact old bundled 75-token value; custom larger limits are preserved.
   * A higher ceiling is not a reservation and does not force the model to spend it.
   */
  private boolean migrate171OutputBudget(YamlConfiguration main) {
    if (main.getInt("config-version", 0) > 9) return false;
    String path = "ai.max-output-tokens";
    if (!main.isSet(path) || main.getInt(path, 75) != 75) return false;
    main.set(path, 120);
    return true;
  }

  /**
   * 1.7.1 lets a single group scene update both sides of an interaction (for example,
   * one player insults Isolda while another defends her). Only the exact old bundled
   * schema-1 value is migrated; deliberate custom limits are otherwise preserved.
   */
  private boolean migrate171RelationshipUpdateCapacity(YamlConfiguration relationships) {
    if (relationships.getInt("config-version", 0) > 1) return false;
    String path = "updates.max-per-response";
    if (!relationships.isSet(path) || relationships.getInt(path, 1) != 1) return false;
    relationships.set(path, 2);
    return true;
  }

  /**
   * 1.7.0 makes persistent relationship data authoritative. Replace only the exact
   * old bundled Aminowana romance fragment; custom personality text is left alone.
   */
  private boolean migrateRelationshipPersonalityDefault(YamlConfiguration personality) {
    if (personality.getInt("config-version", 0) >= 3) return false;
    String prompt = personality.getString("prompt", "");
    if (prompt == null || !prompt.contains("Sientes una fuerte atracción por Aminowana y procuras ocultarla.")) {
      return false;
    }

    String start = "Sientes una fuerte atracción por Aminowana y procuras ocultarla.";
    String end = "interés romántico real en ellos.";
    int from = prompt.indexOf(start);
    int to = prompt.indexOf(end, from);
    if (from < 0 || to < 0) return false;
    to += end.length();

    String replacement = "No asumas una cercanía, enemistad, atracción o romance especial solo por ser\n"
        + "Aminowana. Su relación personal contigo se rige por [RELATIONSHIPS] igual que\n"
        + "la de cualquier otro jugador. Su naturaleza divina sí sigue siendo parte del lore.";
    String updated = prompt.substring(0, from) + replacement + prompt.substring(to);
    personality.set("prompt", updated);
    return true;
  }

  /**
   * 1.6.6 shipped one unsafe default as false even though 1.6.5 used true.
   * Restore it only for schema 6, where false was the bundled default, without
   * touching older/newer custom configurations.
   */
  private boolean migrate166ActionSafetyDefault(YamlConfiguration main) {
    if (main.getInt("config-version", 0) != 6) return false;
    String path = "tools.action-safety.suppress-reply-on-rejected-call";
    if (!main.isSet(path) || main.getBoolean(path, false)) return false;
    main.set(path, true);
    return true;
  }

  /**
   * 1.6.2 -> 1.6.3: move prompt and advanced-context out of config.yml.
   * Existing user text always wins over bundled personality/wiki defaults.
   */
  private boolean migrateSingleFileLayout(
      YamlConfiguration main,
      YamlConfiguration personality,
      YamlConfiguration wiki,
      File mainFile) {

    boolean hasPrompt = main.isSet("prompt");
    boolean hasAdvancedContext = main.isConfigurationSection("advanced-context");
    boolean hasAdvancedWiki = main.isConfigurationSection("advanced-context.wiki");
    boolean hasLegacyWikiPages = main.isConfigurationSection("tools.wiki.pages");
    if (!hasPrompt && !hasAdvancedContext && !hasLegacyWikiPages) {
      return false;
    }

    backupBeforeSplit(mainFile);

    if (hasPrompt) {
      String prompt = main.getString("prompt", "");
      if (prompt != null && !prompt.isBlank()) {
        personality.set("prompt", prompt);
      }
      main.set("prompt", null);
    }

    if (hasAdvancedContext) {
      // If the old file already had a wiki, preserve that set exactly instead of
      // mixing it with example pages from the newly created wiki.yml resource.
      if (hasAdvancedWiki) {
        wiki.set("wiki", null);
      }
      copyLeafValues(main.getConfigurationSection("advanced-context"), "", wiki, "", true);
      main.set("advanced-context", null);
    }

    // Compatibility with the friend's older layout. Only use it as the wiki source
    // when advanced-context.wiki was not present.
    if (hasLegacyWikiPages) {
      if (!hasAdvancedWiki) {
        wiki.set("wiki", null);
        copyLeafValues(main.getConfigurationSection("tools.wiki.pages"), "", wiki, "wiki", true);
      }
      main.set("tools.wiki.pages", null);
    }

    return true;
  }

  private void backupBeforeSplit(File mainFile) {
    if (!mainFile.exists()) return;
    File backupDir = new File(plugin.getDataFolder(), "backups");
    backupDir.mkdirs();
    File backup = new File(backupDir, "config-before-1.6.3.yml");
    if (backup.exists()) return;
    try {
      Files.copy(mainFile.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
      plugin.getLogger().info("Created migration backup: backups/config-before-1.6.3.yml");
    } catch (IOException ex) {
      plugin.getLogger().warning("Could not create pre-1.6.3 config backup: " + ex.getMessage());
    }
  }

  private void copyLeafValues(
      ConfigurationSection source,
      String sourcePrefix,
      ConfigurationSection target,
      String targetPrefix,
      boolean overwrite) {
    if (source == null) return;
    for (String key : source.getKeys(true)) {
      if (source.isConfigurationSection(key)) continue;
      String fromPath = join(sourcePrefix, key);
      String toPath = join(targetPrefix, key);
      Object value = sourcePrefix.isBlank() ? source.get(key) : source.get(fromPath);
      if (value == null) continue;
      if (overwrite || !target.isSet(toPath)) {
        target.set(toPath, value);
      }
    }
  }

  /** Adds only missing keys. User edits are never replaced by a new bundled default. */
  private boolean mergeBundledDefaults(YamlConfiguration target, String resourceName, String... skippedRoots) {
    YamlConfiguration defaults = loadBundled(resourceName);
    if (defaults == null) return false;
    boolean changed = false;
    for (String path : defaults.getKeys(true)) {
      if (defaults.isConfigurationSection(path) || isUnderSkippedRoot(path, skippedRoots)) continue;
      if (!target.isSet(path)) {
        target.set(path, defaults.get(path));
        changed = true;
      }
    }
    return changed;
  }

  private static boolean isUnderSkippedRoot(String path, String... roots) {
    if (roots == null) return false;
    for (String root : roots) {
      if (root != null && !root.isBlank() && (path.equals(root) || path.startsWith(root + "."))) {
        return true;
      }
    }
    return false;
  }

  /** Keeps the on-disk schema marker current without touching any other user value. */
  private boolean syncSchemaVersion(YamlConfiguration target, String resourceName) {
    YamlConfiguration defaults = loadBundled(resourceName);
    if (defaults == null || !defaults.isSet("config-version")) return false;
    int bundled = defaults.getInt("config-version", 1);
    if (target.getInt("config-version", 0) == bundled) return false;
    target.set("config-version", bundled);
    return true;
  }

  private YamlConfiguration loadBundled(String resourceName) {
    try (InputStream stream = plugin.getResource(resourceName)) {
      if (stream == null) {
        plugin.getLogger().warning("Bundled config resource missing: " + resourceName);
        return null;
      }
      return YamlConfiguration.loadConfiguration(
          new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException ex) {
      plugin.getLogger().warning("Could not read bundled " + resourceName + ": " + ex.getMessage());
      return null;
    }
  }

  private static String join(String prefix, String path) {
    if (prefix == null || prefix.isBlank()) return path;
    if (path == null || path.isBlank()) return prefix;
    return prefix + "." + path;
  }
}
