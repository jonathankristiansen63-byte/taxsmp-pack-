package com.taxsmp.ui;

import com.taxsmp.TaxSMP;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Container;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Gives the chicken tools from the separate ChickenSellAxe, ChickenAxe, ChickenPickaxe and
 * ChickenShovel plugins their TaxSMP resource pack look (item model tool/<name>), hides their
 * enchantment glint so the texture shows clean, and renames the pickaxe to the drill and the axe
 * to the tree chopper. Those plugins find their items by data tag, never by name or model, so this
 * doesn't change how the tools work. Bedrock gets the same models through the Geyser mappings.
 *
 * The chicken plugins (1.1.0+) already create their tools with this look, so this only fixes up older
 * tools. It does that when something happens to an item (join, pickup, switching the held item,
 * closing an inventory, opening a chest) instead of scanning every player's inventory once a second.
 */
public final class ChickenToolSkin implements Listener {
   private enum Tool {
      SELL_AXE(Material.NETHERITE_AXE, "chickensellaxe:chicken_sell_axe", "sell_axe", null, null),
      TREE_CHOPPER(Material.NETHERITE_AXE, "chickenaxe:is_chicken_axe", "tree_chopper", "ᴀxᴇ", "ᴛʀᴇᴇ ᴄʜᴏᴘᴘᴇʀ"),
      DRILL(Material.NETHERITE_PICKAXE, "chickenpickaxe:chicken_pickaxe", "drill", "ᴘɪᴄᴋᴀxᴇ", "ᴅʀɪʟʟ"),
      SHOVEL(Material.NETHERITE_SHOVEL, "chickenshovel:is_chicken_shovel", "shovel", null, null);

      final Material material;
      final NamespacedKey mark;
      final NamespacedKey model;
      final TextReplacementConfig rename;

      Tool(Material material, String mark, String model, String oldName, String newName) {
         this.material = material;
         this.mark = NamespacedKey.fromString(mark);
         this.model = new NamespacedKey("taxsmp", "tool/" + model);
         this.rename = oldName == null ? null
            : TextReplacementConfig.builder().matchLiteral(oldName).replacement(newName).once().build();
      }
   }

   private final TaxSMP plugin;

   public ChickenToolSkin(TaxSMP plugin) {
      this.plugin = plugin;
   }

   /** Applies the chicken tool look in place; returns true when the item was changed. */
   public static boolean apply(ItemStack item) {
      if (item == null || !item.hasItemMeta()) {
         return false;
      }

      Tool tool = null;
      ItemMeta meta = null;
      for (Tool t : Tool.values()) {
         if (item.getType() == t.material) {
            if (meta == null) {
               meta = item.getItemMeta();
            }
            if (meta.getPersistentDataContainer().has(t.mark)) {
               tool = t;
               break;
            }
         }
      }
      if (tool == null) {
         return false;
      }

      boolean changed = false;
      if (!tool.model.equals(meta.getItemModel())) {
         meta.setItemModel(tool.model);
         changed = true;
      }
      if (!meta.hasEnchantmentGlintOverride() || meta.getEnchantmentGlintOverride()) {
         meta.setEnchantmentGlintOverride(false);
         changed = true;
      }
      Component name = meta.displayName();
      if (tool.rename != null && name != null) {
         Component renamed = name.replaceText(tool.rename);
         if (!renamed.equals(name)) {
            meta.displayName(renamed);
            changed = true;
         }
      }
      if (changed) {
         item.setItemMeta(meta);
      }
      return changed;
   }

   private static void applyAll(Inventory inventory) {
      ItemStack[] contents = inventory.getContents();
      for (int i = 0; i < contents.length; i++) {
         if (apply(contents[i])) {
            inventory.setItem(i, contents[i]);
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      p.getScheduler().runDelayed(this.plugin, t -> applyAll(p.getInventory()), null, 20L);
   }

   // Tools given by command or dropped by someone else get fixed when they're picked up.
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onPickup(EntityPickupItemEvent e) {
      if (e.getEntity() instanceof Player) {
         Item drop = e.getItem();
         ItemStack stack = drop.getItemStack();
         if (apply(stack)) {
            drop.setItemStack(stack);
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHeld(PlayerItemHeldEvent e) {
      PlayerInventory inventory = e.getPlayer().getInventory();
      ItemStack held = inventory.getItem(e.getNewSlot());
      if (apply(held)) {
         inventory.setItem(e.getNewSlot(), held);
      }
   }

   @EventHandler
   public void onClose(InventoryCloseEvent e) {
      if (e.getPlayer() instanceof Player p) {
         applyAll(p.getInventory());
      }
   }

   // Only real block containers: menu items are copies the menus compare against.
   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onOpen(InventoryOpenEvent e) {
      Inventory top = e.getView().getTopInventory();
      if (top.getHolder(false) instanceof Container || top instanceof DoubleChestInventory) {
         applyAll(top);
      }
   }
}
