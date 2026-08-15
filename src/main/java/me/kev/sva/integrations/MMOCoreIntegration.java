package me.kev.sva.integrations;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import me.kev.sva.ServerAssistantPlugin;

/** Read-only optional MMOCore player profile integration. No hard dependency. */
public final class MMOCoreIntegration implements PlayerContextIntegration {
  private final ServerAssistantPlugin plugin;

  public MMOCoreIntegration(ServerAssistantPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public String id() {
    return "mmocore";
  }

  @Override
  public boolean enabled() {
    return plugin.getIntegrationsConfig().getBoolean("enabled", true)
        && plugin.getIntegrationsConfig().getBoolean("mmocore.enabled", true);
  }

  @Override
  public boolean available() {
    Plugin mmocore = Bukkit.getPluginManager().getPlugin("MMOCore");
    return mmocore != null && mmocore.isEnabled();
  }

  @Override
  public String status() {
    return "mmocore=" + (enabled() ? "enabled" : "disabled")
        + ",available=" + available();
  }

  @Override
  public String build(Player player, ProfileQuery query) {
    if (player == null || !enabled() || !available()) return "";

    Object data = getPlayerData(player);
    List<String> fields = new ArrayList<>();

    if (query.identity() || query.general()) appendIdentity(fields, player, data);
    if (query.professions() || query.general()) appendProfessions(fields, player, data);
    if (query.attributes() || query.general()) appendAttributes(fields, player, data);
    if (query.stats() || (query.general() && plugin.getIntegrationsConfig().getBoolean("mmocore.stats.include-on-general", false))) {
      appendStats(fields, player, data);
    }
    if (query.resources() || (query.general() && plugin.getIntegrationsConfig().getBoolean("mmocore.resources.include-on-general", false))) {
      appendResources(fields, player, data);
    }
    if (query.points() || (query.general() && plugin.getIntegrationsConfig().getBoolean("mmocore.points.include-on-general", false))) {
      appendPoints(fields, player, data);
    }

    return fields.isEmpty() ? "" : "MMOCORE " + String.join(" ", fields);
  }

  private void appendIdentity(List<String> fields, Player player, Object data) {
    boolean classAsRace = plugin.getIntegrationsConfig().getBoolean("mmocore.class-as-race", true);

    if (plugin.getIntegrationsConfig().getBoolean("mmocore.basic.race", true)) {
      String className = "";
      String classId = "";
      if (data != null) {
        Object profess = invokeNoArgs(data, "getProfess");
        className = clean(asString(invokeNoArgs(profess, "getName")));
        classId = clean(asString(invokeNoArgs(profess, "getId")));
      }
      if (className.isBlank()) className = resolvePlaceholder(player, "%mmocore_class%");
      if (!className.isBlank()) fields.add((classAsRace ? "race=" : "class=") + compact(className));
      if (plugin.getIntegrationsConfig().getBoolean("mmocore.basic.race-id", false)) {
        if (classId.isBlank()) classId = resolvePlaceholder(player, "%mmocore_class_id%");
        if (!classId.isBlank()) fields.add((classAsRace ? "race_id=" : "class_id=") + compact(classId));
      }
    }

    if (plugin.getIntegrationsConfig().getBoolean("mmocore.basic.level", true)) {
      String level = data == null ? "" : asString(invokeNoArgs(data, "getLevel"));
      if (level.isBlank()) level = resolvePlaceholder(player, "%mmocore_level%");
      if (!level.isBlank()) fields.add("level=" + compact(level));
    }

    if (plugin.getIntegrationsConfig().getBoolean("mmocore.basic.experience", false)) {
      String exp = data == null ? "" : formatNumber(invokeNoArgs(data, "getExperience"));
      if (exp.isBlank()) exp = resolvePlaceholder(player, "%mmocore_experience%");
      if (!exp.isBlank()) fields.add("experience=" + compact(exp));
    }
  }

  private void appendProfessions(List<String> fields, Player player, Object data) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.professions.enabled", true)) return;
    int max = Math.max(plugin.getIntegrationsConfig().getInt("mmocore.professions.max", 8), 0);
    if (max == 0) return;

    List<String> whitelist = lowerList(plugin.getIntegrationsConfig().getStringList("mmocore.professions.ids"));
    Map<String, String> professionNames = discoverProfessions();
    if (!whitelist.isEmpty()) {
      professionNames.keySet().removeIf(id -> !whitelist.contains(id.toLowerCase(Locale.ROOT)));
      for (String id : whitelist) professionNames.putIfAbsent(id, id);
    }

    Object collection = data == null ? null : invokeNoArgs(data, "getCollectionSkills");
    List<String> values = new ArrayList<>();
    for (Map.Entry<String, String> entry : professionNames.entrySet()) {
      if (values.size() >= max) break;
      String id = entry.getKey();
      String level = collection == null ? "" : asString(invoke(collection, "getLevel", new Class<?>[]{String.class}, id));
      if (level.isBlank()) level = resolvePlaceholder(player, "%mmocore_profession_" + id + "%");
      if (level.isBlank()) continue;
      values.add(compact(entry.getValue()) + ":" + compact(level));
    }
    if (!values.isEmpty()) fields.add("professions={" + String.join(",", values) + "}");
  }

  private void appendAttributes(List<String> fields, Player player, Object data) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.attributes.enabled", true)) return;
    int max = Math.max(plugin.getIntegrationsConfig().getInt("mmocore.attributes.max", 8), 0);
    if (max == 0) return;

    List<String> whitelist = lowerList(plugin.getIntegrationsConfig().getStringList("mmocore.attributes.ids"));
    Map<String, String> displayNames = discoverAttributeNames();
    Map<String, Object> levels = new LinkedHashMap<>();
    if (data != null) {
      Object mapped = invokeNoArgs(data, "mapAttributeLevels");
      if (mapped instanceof Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
          Object key = entry.getKey();
          String id = clean(asString(invokeNoArgs(key, "getId")));
          if (id.isBlank()) id = clean(String.valueOf(key));
          if (!id.isBlank()) levels.put(id, entry.getValue());
        }
      }
    }

    if (levels.isEmpty()) {
      for (String id : !whitelist.isEmpty() ? whitelist : displayNames.keySet()) {
        String value = resolvePlaceholder(player, "%mmocore_attribute_" + id + "%");
        if (!value.isBlank()) levels.put(id, value);
      }
    }

    List<String> values = new ArrayList<>();
    for (Map.Entry<String, Object> entry : levels.entrySet()) {
      if (values.size() >= max) break;
      String id = entry.getKey();
      if (!whitelist.isEmpty() && !whitelist.contains(id.toLowerCase(Locale.ROOT))) continue;
      String display = displayNames.getOrDefault(id, id);
      values.add(compact(display) + ":" + compact(formatNumber(entry.getValue())));
    }
    if (!values.isEmpty()) fields.add("attributes={" + String.join(",", values) + "}");
  }

  private void appendStats(List<String> fields, Player player, Object data) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.stats.enabled", true)) return;
    List<String> ids = new ArrayList<>(plugin.getIntegrationsConfig().getStringList("mmocore.stats.ids"));
    int max = Math.max(plugin.getIntegrationsConfig().getInt("mmocore.stats.max", 8), 0);
    if (ids.isEmpty() && plugin.getIntegrationsConfig().getBoolean("mmocore.stats.auto-discover", true)) {
      ids.addAll(discoverStats());
    }
    if (ids.isEmpty() || max == 0) return;

    Object stats = data == null ? null : invokeNoArgs(data, "getStats");
    List<String> values = new ArrayList<>();
    for (String rawId : ids) {
      if (values.size() >= max) break;
      String id = rawId == null ? "" : rawId.trim();
      if (id.isBlank()) continue;
      String value = stats == null ? "" : formatNumber(invoke(stats, "getStat", new Class<?>[]{String.class}, id));
      if (value.isBlank()) value = resolvePlaceholder(player, "%mmocore_stat_" + id + "%");
      if (!value.isBlank()) values.add(compact(id) + ":" + compact(value));
    }
    if (!values.isEmpty()) fields.add("stats={" + String.join(",", values) + "}");
  }

  private void appendResources(List<String> fields, Player player, Object data) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.resources.enabled", true)) return;
    appendResource(fields, player, data, "mana", "getMana");
    appendResource(fields, player, data, "stamina", "getStamina");
    if (plugin.getIntegrationsConfig().getBoolean("mmocore.resources.stellium", false)) {
      appendResource(fields, player, data, "stellium", "getStellium");
    }
  }

  private void appendResource(List<String> fields, Player player, Object data, String id, String method) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.resources." + id, true)) return;
    String value = data == null ? "" : formatNumber(invokeNoArgs(data, method));
    if (value.isBlank()) value = resolvePlaceholder(player, "%mmocore_" + id + "%");
    if (!value.isBlank()) fields.add(id + "=" + compact(value));
  }

  private void appendPoints(List<String> fields, Player player, Object data) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.points.enabled", true)) return;
    appendPoint(fields, player, data, "skill_points", "getSkillPoints", "%mmocore_skill_points%");
    appendPoint(fields, player, data, "class_points", "getClassPoints", "%mmocore_class_points%");
    appendPoint(fields, player, data, "attribute_points", "getAttributePoints", "%mmocore_attribute_points%");
  }

  private void appendPoint(List<String> fields, Player player, Object data, String label, String method, String placeholder) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.points." + label.replace('_', '-'), true)) return;
    String value = data == null ? "" : asString(invokeNoArgs(data, method));
    if (value.isBlank()) value = resolvePlaceholder(player, placeholder);
    if (!value.isBlank()) fields.add(label + "=" + compact(value));
  }

  private Object getPlayerData(Player player) {
    try {
      Plugin mmocore = Bukkit.getPluginManager().getPlugin("MMOCore");
      if (mmocore == null) return null;
      Class<?> dataClass = Class.forName(
          "net.Indyuce.mmocore.api.player.PlayerData", true, mmocore.getClass().getClassLoader());
      for (Method method : dataClass.getMethods()) {
        if (!method.getName().equals("get") || !Modifier.isStatic(method.getModifiers()) || method.getParameterCount() != 1) continue;
        Class<?> parameter = method.getParameterTypes()[0];
        Object argument = null;
        if (parameter.isInstance(player) || parameter.isAssignableFrom(player.getClass())) {
          argument = player;
        } else if (OfflinePlayer.class.isAssignableFrom(parameter)) {
          argument = player;
        } else if (parameter == java.util.UUID.class) {
          argument = player.getUniqueId();
        } else if (parameter == String.class) {
          argument = player.getName();
        }
        if (argument == null) continue;
        try {
          Object value = method.invoke(null, argument);
          if (value != null) return value;
        } catch (ReflectiveOperationException | IllegalArgumentException ignored) {
        }
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  private Map<String, String> discoverProfessions() {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    try {
      Plugin mmocore = Bukkit.getPluginManager().getPlugin("MMOCore");
      if (mmocore != null) {
        Object manager = invokeNoArgs(mmocore, "getProfessionManager");
        if (manager == null) manager = readField(mmocore, "professionManager");
        Object all = invokeNoArgs(manager, "getAll");
        if (all instanceof Collection<?> collection) {
          List<Object> sorted = new ArrayList<>(collection);
          sorted.sort(Comparator.comparing(value -> clean(asString(invokeNoArgs(value, "getId")))));
          for (Object profession : sorted) {
            String id = clean(asString(invokeNoArgs(profession, "getId")));
            if (id.isBlank()) continue;
            String name = clean(asString(invokeNoArgs(profession, "getName")));
            out.put(id, name.isBlank() ? id : name);
          }
        }
      }
    } catch (Throwable ignored) {
    }

    if (!out.isEmpty()) return out;
    Plugin mmocore = Bukkit.getPluginManager().getPlugin("MMOCore");
    if (mmocore == null) return out;
    for (String folderName : List.of("professions", "profession")) {
      File folder = new File(mmocore.getDataFolder(), folderName);
      File[] files = folder.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
      if (files == null) continue;
      for (File file : files) {
        String id = file.getName().substring(0, file.getName().length() - 4);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        String name = clean(yaml.getString("name", id));
        out.putIfAbsent(id, name.isBlank() ? id : name);
      }
    }
    return out;
  }

  private Map<String, String> discoverAttributeNames() {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    Plugin mmocore = Bukkit.getPluginManager().getPlugin("MMOCore");
    if (mmocore == null) return out;
    File file = new File(mmocore.getDataFolder(), "attributes.yml");
    if (!file.isFile()) return out;
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    for (String key : yaml.getKeys(false)) {
      ConfigurationSection section = yaml.getConfigurationSection(key);
      if (section == null) continue;
      String name = clean(section.getString("name", key));
      out.put(key, name.isBlank() ? key : name);
    }
    return out;
  }

  private List<String> discoverStats() {
    Plugin mmocore = Bukkit.getPluginManager().getPlugin("MMOCore");
    if (mmocore == null) return List.of();
    File file = new File(mmocore.getDataFolder(), "stats.yml");
    if (!file.isFile()) return List.of();
    YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
    List<String> ids = new ArrayList<>(yaml.getKeys(false));
    ids.sort(String.CASE_INSENSITIVE_ORDER);
    return ids;
  }

  private String resolvePlaceholder(Player player, String placeholder) {
    if (!plugin.getIntegrationsConfig().getBoolean("mmocore.placeholder-fallback", true)) return "";
    try {
      Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
      if (papi == null || !papi.isEnabled()) return "";
      Class<?> api = Class.forName("me.clip.placeholderapi.PlaceholderAPI", true, papi.getClass().getClassLoader());
      Method method = api.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
      Object value = method.invoke(null, player, placeholder);
      String resolved = clean(asString(value));
      if (resolved.equalsIgnoreCase(placeholder) || resolved.contains("%mmocore_")) return "";
      return resolved;
    } catch (Throwable ignored) {
      return "";
    }
  }

  private static Object readField(Object target, String name) {
    if (target == null) return null;
    Class<?> type = target.getClass();
    while (type != null) {
      try {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
      } catch (ReflectiveOperationException ignored) {
        type = type.getSuperclass();
      }
    }
    return null;
  }

  private static Object invokeNoArgs(Object target, String methodName) {
    return invoke(target, methodName, new Class<?>[0]);
  }

  private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
    if (target == null) return null;
    try {
      Method method = target.getClass().getMethod(methodName, parameterTypes);
      return method.invoke(target, args);
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }

  private static List<String> lowerList(List<String> values) {
    List<String> out = new ArrayList<>();
    if (values == null) return out;
    for (String value : values) {
      if (value != null && !value.isBlank()) out.add(value.trim().toLowerCase(Locale.ROOT));
    }
    return out;
  }

  private static String asString(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static String formatNumber(Object value) {
    if (value == null) return "";
    if (value instanceof Number number) {
      double d = number.doubleValue();
      if (Math.rint(d) == d) return Long.toString(Math.round(d));
      return String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
    return asString(value);
  }

  private static String clean(String value) {
    if (value == null) return "";
    return value
        .replaceAll("(?i)§[0-9A-FK-ORX]", "")
        .replaceAll("(?i)&[0-9A-FK-ORX]", "")
        .replaceAll("\\s+", " ")
        .trim();
  }

  private static String compact(String value) {
    String cleaned = clean(value);
    if (cleaned.indexOf(' ') >= 0 || cleaned.indexOf(',') >= 0 || cleaned.indexOf('|') >= 0) {
      return '"' + cleaned.replace("\"", "'") + '"';
    }
    return cleaned;
  }
}
