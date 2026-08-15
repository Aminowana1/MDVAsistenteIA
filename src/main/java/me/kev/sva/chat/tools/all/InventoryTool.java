package me.kev.sva.chat.tools.all;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.message.PlayerChatMessage;
import me.kev.sva.chat.tools.ContextTargetResolver;
import me.kev.sva.chat.tools.ToolKind;
import me.kev.sva.chat.tools.ToolManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Local one-call inventory context. */
public final class InventoryTool extends Tool {
  private static final List<String> INVENTORY_TERMS = List.of(
      "inventario", "invetnario", "invetario", "inventaro", "inventorio", "inventory",
      "items", "item", "objeto", "objetos", "lleva", "llevo", "trae", "tiene encima",
      "que lleva", "que llevo", "que trae", "equipado", "equipo", "lore");

  private static final List<String> ARMOR_TERMS = List.of(
      "armadura", "armor", "casco", "pechera", "coraza", "peto", "pantalones", "grebas",
      "botas", "helmet", "chestplate", "leggings", "boots", "llevo puesto", "lleva puesto");

  private static final List<String> HAND_TERMS = List.of(
      "mano", "mainhand", "main hand", "mano principal", "offhand", "mano secundaria",
      "sostengo", "sosteniendo", "agarro", "agarrado");

  private static final List<String> ENCHANT_TERMS = List.of(
      "encantamiento", "encantamientos", "enchant", "enchants",
      "enchantment", "enchantments");

  /** Natural item-inspection phrases that usually refer to the held object. */
  private static final List<String> HELD_REFERENCE_TERMS = List.of(
      "mi espada", "mi arco", "mi daga", "mi baston", "mi cetro", "mi arma", "mi pico",
      "mi hacha", "mi azada", "mi escudo", "mi mazo", "mi libro", "mi objeto", "mi item",
      "que tiene mi", "que tiene su", "que es esto", "esto que es", "que tengo aqui");

  public InventoryTool(ServerAssistantPlugin plugin) {
    super(plugin, "inventory");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.CONTEXT;
  }

  @Override
  public String usage() {
    return "Local trusted inventory, held-item, enchantment and equipment data for an online player.";
  }

  @Override
  public boolean shouldPrefetch(String normalizedSceneText, List<ChatMessage> currentSceneMessages) {
    return matchesInventoryIntent(normalizedSceneText == null ? "" : normalizedSceneText);
  }

  @Override
  public String buildLocalContext(
      List<String> involvedPlayerNames,
      String normalizedSceneText,
      List<ChatMessage> currentSceneMessages) {

    int maxPlayers = Math.max(plugin.getConfig().getInt("tools.inventory.max-players", 2), 1);
    int maxItems = Math.max(plugin.getConfig().getInt("tools.inventory.max-items", 18), 1);

    // In a group scene, resolve this tool from the inventory-related player lines,
    // not from whichever unrelated player happened to speak last. This lets e.g.
    // Aminowana ask location while Kroattan asks about his sword in the same 4s scene.
    List<ChatMessage> queryMessages = selectRelevantMessages(currentSceneMessages);
    String queryText = normalizedText(queryMessages);
    if (queryText.isBlank()) queryText = normalizedSceneText == null ? "" : normalizedSceneText;

    List<String> targets = ContextTargetResolver.resolve(
        involvedPlayerNames, queryText, queryMessages, maxPlayers);

    InventoryView view = detectView(queryText);
    boolean enchantQuery = containsAnyTokenOrPhrase(queryText, ENCHANT_TERMS);
    List<String> rows = new ArrayList<>();
    for (String name : targets) {
      if (rows.size() >= maxPlayers) break;
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) continue;
      rows.add(compactInventory(player, maxItems, view, enchantQuery));
    }
    return String.join("\n", rows);
  }

  @Override
  public String buildLocalContext(List<String> involvedPlayerNames) {
    int maxPlayers = Math.max(plugin.getConfig().getInt("tools.inventory.max-players", 2), 1);
    int maxItems = Math.max(plugin.getConfig().getInt("tools.inventory.max-items", 18), 1);
    List<String> rows = new ArrayList<>();
    for (String name : involvedPlayerNames) {
      if (rows.size() >= maxPlayers) break;
      Player player = Bukkit.getPlayerExact(name);
      if (player == null) continue;
      rows.add(compactInventory(player, maxItems, InventoryView.GENERAL, true));
    }
    return String.join("\n", rows);
  }

  @Override
  public String execute(String arguments) {
    String playerName = arguments == null ? "" : arguments.trim();
    if (playerName.isBlank() || playerName.contains(" ")) {
      return "Usage: inventory <player>";
    }
    Player player = Bukkit.getPlayerExact(playerName);
    if (player == null) {
      return "Player '" + playerName + "' is not online.";
    }
    return compactInventory(
        player,
        Math.max(plugin.getConfig().getInt("tools.inventory.max-items", 18), 1),
        InventoryView.GENERAL,
        true);
  }

  private List<ChatMessage> selectRelevantMessages(List<ChatMessage> currentSceneMessages) {
    if (currentSceneMessages == null || currentSceneMessages.isEmpty()) return List.of();
    List<ChatMessage> relevant = new ArrayList<>();
    for (ChatMessage message : currentSceneMessages) {
      if (!(message instanceof PlayerChatMessage) || message.content == null) continue;
      String normalized = ToolManager.normalize(message.content);
      if (matchesInventoryIntent(normalized)) relevant.add(message);
    }
    return relevant.isEmpty() ? currentSceneMessages : List.copyOf(relevant);
  }

  private boolean matchesInventoryIntent(String text) {
    return containsAnyTokenOrPhrase(text, INVENTORY_TERMS)
        || containsAnyTokenOrPhrase(text, ARMOR_TERMS)
        || containsAnyTokenOrPhrase(text, HAND_TERMS)
        || containsAnyTokenOrPhrase(text, ENCHANT_TERMS)
        || containsAnyPhrase(text, HELD_REFERENCE_TERMS);
  }

  private String normalizedText(List<ChatMessage> messages) {
    StringBuilder out = new StringBuilder();
    if (messages != null) {
      for (ChatMessage message : messages) {
        if (message != null && message.content != null) out.append(message.content).append(' ');
      }
    }
    return ToolManager.normalize(out.toString());
  }

  private InventoryView detectView(String normalizedSceneText) {
    String text = normalizedSceneText == null ? "" : normalizedSceneText;
    if (containsAnyTokenOrPhrase(text, HAND_TERMS) || containsAnyPhrase(text, HELD_REFERENCE_TERMS)) {
      return InventoryView.HELD;
    }
    if (containsAnyTokenOrPhrase(text, ARMOR_TERMS)) return InventoryView.ARMOR;
    return InventoryView.GENERAL;
  }

  private String compactInventory(Player player, int maxItems, InventoryView view, boolean enchantQuery) {
    PlayerInventory inventory = player.getInventory();
    StringBuilder out = new StringBuilder("INVENTORY player=").append(player.getName())
        .append(" requested=").append(view.name().toLowerCase());

    if (view == InventoryView.HELD || view == InventoryView.GENERAL) {
      ItemStack main = inventory.getItemInMainHand();
      out.append(" | mainhand=").append(formatItem(main))
          .append(" | offhand=").append(formatItem(inventory.getItemInOffHand()));
      if (plugin.getConfig().getBoolean("tools.inventory.include-held-item-details", true)) {
        appendHeldItemDetails(out, main, enchantQuery);
      } else if (enchantQuery) {
        appendEnchantments(out, "mainhand_enchants", main, true);
      }
    }

    if (view == InventoryView.ARMOR || view == InventoryView.GENERAL) {
      appendArmor(out, "armor_helmet", inventory.getHelmet(), enchantQuery);
      appendArmor(out, "armor_chestplate", inventory.getChestplate(), enchantQuery);
      appendArmor(out, "armor_leggings", inventory.getLeggings(), enchantQuery);
      appendArmor(out, "armor_boots", inventory.getBoots(), enchantQuery);
    }

    if (view == InventoryView.GENERAL) {
      Map<String, Integer> items = new LinkedHashMap<>();
      for (int slot = 0; slot < 36; slot++) {
        ItemStack item = inventory.getItem(slot);
        if (isEmpty(item)) continue;
        items.merge(getItemName(item), item.getAmount(), Integer::sum);
      }

      out.append(" | items=");
      if (items.isEmpty()) {
        out.append("empty");
      } else {
        int used = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
          if (used++ >= maxItems) {
            out.append("; …");
            break;
          }
          if (used > 1) out.append("; ");
          out.append(entry.getKey()).append('x').append(entry.getValue());
        }
      }
    }

    return out.toString();
  }

  private void appendArmor(StringBuilder out, String key, ItemStack item, boolean enchantQuery) {
    out.append(" | ").append(key).append('=').append(formatItem(item));
    if (enchantQuery) appendEnchantments(out, key + "_enchants", item, true);
  }

  private void appendHeldItemDetails(StringBuilder out, ItemStack item, boolean enchantQuery) {
    if (isEmpty(item)) {
      if (enchantQuery) out.append(" | mainhand_enchants=none");
      return;
    }
    if (enchantQuery) appendEnchantments(out, "mainhand_enchants", item, true);
    if (!item.hasItemMeta()) return;

    var meta = item.getItemMeta();
    List<Component> lore = meta.lore();
    if (lore == null || lore.isEmpty()) return;

    int maxLoreLines = Math.max(plugin.getConfig().getInt("tools.inventory.max-held-lore-lines", 4), 0);
    if (maxLoreLines == 0) return;

    List<String> lines = new ArrayList<>();
    for (Component line : lore) {
      if (lines.size() >= maxLoreLines) break;
      String plain = PlainTextComponentSerializer.plainText().serialize(line).trim();
      if (!plain.isBlank()) {
        plain = plain.replace(';', ',').replace('|', '/');
        if (plain.length() > 180) plain = plain.substring(0, 180).trim();
        lines.add(plain);
      }
    }
    if (!lines.isEmpty()) {
      out.append(" | mainhand_lore=").append(String.join(" / ", lines));
    }
  }

  private void appendEnchantments(
      StringBuilder out,
      String key,
      ItemStack item,
      boolean explicitNone) {
    out.append(" | ").append(key).append('=');
    if (isEmpty(item) || !item.hasItemMeta()) {
      out.append(explicitNone ? "none" : "");
      return;
    }

    var meta = item.getItemMeta();
    // MMOItems and resource-pack items often carry a hidden Bukkit enchant only to
    // create the glint. Treat HIDE_ENCHANTS as not player-visible so Isolda does
    // not expose implementation-only Luck of the Sea/other dummy enchants.
    if (meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS)) {
      out.append(explicitNone ? "none_visible" : "");
      return;
    }
    if (meta.getEnchants().isEmpty()) {
      out.append(explicitNone ? "none" : "");
      return;
    }

    int index = 0;
    for (Map.Entry<Enchantment, Integer> entry : meta.getEnchants().entrySet()) {
      if (index++ > 0) out.append(',');
      String name;
      try {
        name = entry.getKey().getKey().getKey();
      } catch (Throwable ignored) {
        name = entry.getKey().toString();
      }
      out.append(name).append(':').append(entry.getValue());
    }
  }

  private static boolean containsAnyTokenOrPhrase(String text, List<String> terms) {
    for (String rawTerm : terms) {
      String term = ToolManager.normalize(rawTerm);
      if (term.isBlank()) continue;
      if (term.contains(" ")) {
        if ((" " + text + " ").contains(" " + term + " ")) return true;
      } else if (ContextTargetResolver.containsWholeToken(text, term)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsAnyPhrase(String text, List<String> terms) {
    String normalized = ToolManager.normalize(text);
    for (String raw : terms) {
      String term = ToolManager.normalize(raw);
      if (!term.isBlank() && normalized.contains(term)) return true;
    }
    return false;
  }

  private String formatItem(ItemStack item) {
    if (isEmpty(item)) return "empty";
    return getItemName(item) + "x" + item.getAmount();
  }

  private String getItemName(ItemStack item) {
    if (item.hasItemMeta()) {
      Component displayName = item.getItemMeta().displayName();
      if (displayName != null) {
        String plain = PlainTextComponentSerializer.plainText().serialize(displayName).trim();
        if (!plain.isBlank()) return plain.replace(';', ',');
      }
    }
    return item.getType().getKey().toString().replace("minecraft:", "");
  }

  private boolean isEmpty(ItemStack item) {
    return item == null || item.getType().isAir();
  }

  private enum InventoryView {
    HELD,
    ARMOR,
    GENERAL
  }
}
