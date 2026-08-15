package me.kev.sva.chat.tools.all;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import me.kev.sva.ServerAssistantPlugin;
import me.kev.sva.chat.message.ChatMessage;
import me.kev.sva.chat.tools.ToolKind;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** Local one-call inventory context. */
public final class InventoryTool extends Tool {
  private static final List<String> INTENT_TERMS = List.of(
      "inventario", "invetnario", "invetario", "inventaro", "inventorio", "inventory",
      "items", "item", "objeto", "objetos", "lleva", "tiene encima", "que lleva", "que trae",
      "armadura", "armor", "equipado", "equipo", "offhand", "mano secundaria", "mano principal",
      "en la mano", "tengo en mano", "tengo en la mano", "sostengo", "sosteniendo", "agarro", "agarrado");

  public InventoryTool(ServerAssistantPlugin plugin) {
    super(plugin, "inventory");
  }

  @Override
  public ToolKind kind() {
    return ToolKind.CONTEXT;
  }

  @Override
  public String usage() {
    return "Local trusted inventory/equipment data for an online player.";
  }

  @Override
  public boolean shouldPrefetch(String normalizedSceneText, List<ChatMessage> currentSceneMessages) {
    for (String term : INTENT_TERMS) {
      if (normalizedSceneText.contains(term)) return true;
    }
    return false;
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
      rows.add(compactInventory(player, maxItems));
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
    return compactInventory(player, Math.max(plugin.getConfig().getInt("tools.inventory.max-items", 18), 1));
  }

  private String compactInventory(Player player, int maxItems) {
    PlayerInventory inventory = player.getInventory();
    Map<String, Integer> items = new LinkedHashMap<>();
    for (int slot = 0; slot < 36; slot++) {
      ItemStack item = inventory.getItem(slot);
      if (isEmpty(item)) continue;
      items.merge(getItemName(item), item.getAmount(), Integer::sum);
    }

    StringBuilder out = new StringBuilder("INVENTORY ").append(player.getName())
        .append(" mainhand=").append(formatItem(inventory.getItemInMainHand()))
        .append(" | armor=")
        .append(formatItem(inventory.getHelmet())).append(',')
        .append(formatItem(inventory.getChestplate())).append(',')
        .append(formatItem(inventory.getLeggings())).append(',')
        .append(formatItem(inventory.getBoots()))
        .append(" | offhand=").append(formatItem(inventory.getItemInOffHand()));

    if (plugin.getConfig().getBoolean("tools.inventory.include-held-item-details", true)) {
      appendHeldItemDetails(out, inventory.getItemInMainHand());
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
    return out.toString();
  }

  private void appendHeldItemDetails(StringBuilder out, ItemStack item) {
    if (isEmpty(item) || !item.hasItemMeta()) return;
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
}
