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
 * Owns ServerAssistant's four YAML files and performs non-destructive schema updates.
 *
 * <p>Technical/runtime settings stay in config.yml, character text lives in
 * personality.yml, local knowledge lives in wiki.yml, and optional plugin hooks live in
 * integrations.yml. On every startup/reload, missing keys from the bundled files are
 * copied into the user's files while existing
 * values are preserved. Explicit migrations handle schema moves such as the 1.6.2
 * single-file layout.</p>
 */
public final class ConfigurationManager {
  private static final String PERSONALITY_FILE = "personality.yml";
  private static final String WIKI_FILE = "wiki.yml";
  private static final String INTEGRATIONS_FILE = "integrations.yml";

  private final ServerAssistantPlugin plugin;
  private FileConfiguration personalityConfig;
  private FileConfiguration wikiConfig;
  private FileConfiguration integrationsConfig;

  public ConfigurationManager(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  /** Loads, migrates and auto-updates all YAML files. Safe to call on /sva reload. */
  public void loadAndUpdate() {
    plugin.getDataFolder().mkdirs();
    ensureResource(PERSONALITY_FILE);
    ensureResource(WIKI_FILE);
    ensureResource(INTEGRATIONS_FILE);

    File mainFile = new File(plugin.getDataFolder(), "config.yml");
    File personalityFile = new File(plugin.getDataFolder(), PERSONALITY_FILE);
    File wikiFile = new File(plugin.getDataFolder(), WIKI_FILE);
    File integrationsFile = new File(plugin.getDataFolder(), INTEGRATIONS_FILE);

    YamlConfiguration main = loadUserFile(mainFile);
    YamlConfiguration personality = loadUserFile(personalityFile);
    YamlConfiguration wiki = loadUserFile(wikiFile);
    YamlConfiguration integrations = loadUserFile(integrationsFile);

    boolean migrated = migrateSingleFileLayout(main, personality, wiki, mainFile);
    boolean mainChanged = migrated | mergeBundledDefaults(main, "config.yml");
    boolean personalityChanged = migrated | mergeBundledDefaults(personality, PERSONALITY_FILE);
    // wiki.* is user knowledge, not schema. Do not resurrect pages an admin intentionally removed.
    boolean wikiChanged = migrated | mergeBundledDefaults(wiki, WIKI_FILE, "wiki");
    boolean integrationsChanged = mergeBundledDefaults(integrations, INTEGRATIONS_FILE);

    mainChanged |= syncSchemaVersion(main, "config.yml");
    personalityChanged |= syncSchemaVersion(personality, PERSONALITY_FILE);
    wikiChanged |= syncSchemaVersion(wiki, WIKI_FILE);
    integrationsChanged |= syncSchemaVersion(integrations, INTEGRATIONS_FILE);

    try {
      if (mainChanged) main.save(mainFile);
      if (personalityChanged) personality.save(personalityFile);
      if (wikiChanged) wiki.save(wikiFile);
      if (integrationsChanged) integrations.save(integrationsFile);
    } catch (IOException ex) {
      throw new IllegalStateException("Could not save updated ServerAssistant YAML files", ex);
    }

    // Refresh JavaPlugin's normal config after our disk-level merge.
    plugin.reloadConfig();
    personalityConfig = loadUserFile(personalityFile);
    wikiConfig = loadUserFile(wikiFile);
    integrationsConfig = loadUserFile(integrationsFile);

    if (migrated) {
      plugin.getLogger().info("Migrated character prompt to personality.yml and local wiki to wiki.yml.");
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
