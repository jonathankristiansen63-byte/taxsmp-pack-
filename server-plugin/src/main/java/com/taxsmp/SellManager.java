package com.taxsmp;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class SellManager implements CommandExecutor, Listener {
   private final TaxSMP plugin;
   private final Map<UUID, SellManager.SellSession> sessions = new ConcurrentHashMap<>();
   private static final String SELL_TITLE = "§0Drop Items to Sell";
   private static final String CONFIRM_TITLE = "§0Confirm Sell";
   private static final double PAYOUT_CAP = 1.0E10;
   private static final int SELL_SLOTS = 36;
   private static final int SELL_SIZE = 45;
   private static final int SELL_BUTTON_SLOT = 44;
   private static final String MINT = "§x§5§5§F§F§B§8";

   public SellManager(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (sender instanceof Player p) {
         Inventory sellInv = Bukkit.createInventory(null, 45, "§0Drop Items to Sell");
         this.drawButtonRow(sellInv, 0.0);
         p.openInventory(sellInv);
         return true;
      } else {
         return true;
      }
   }

   private void drawButtonRow(Inventory inventory, double total) {
      for (int slot = 36; slot < 45; slot++) {
         inventory.setItem(slot, null);
      }

      inventory.setItem(44, named(Material.LIME_STAINED_GLASS_PANE, "§x§5§5§F§F§B§8$" + PriceManager.formatPrice(total), "§7Click to sell items"));
   }

   private static ItemStack named(Material material, String name, String loreLine) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(name);
         if (loreLine != null) {
            meta.setLore(List.of(loreLine));
         }

         item.setItemMeta(meta);
      }

      return item;
   }

   private double appraise(Player player, Inventory inventory) {
      SellMultiManager sellMulti = this.plugin.getSellMultiManager();
      Map<SellMultiManager.Category, Double> ignored = new EnumMap<>(SellMultiManager.Category.class);
      double total = 0.0;

      for (int slot = 0; slot < 36; slot++) {
         ItemStack item = inventory.getItem(slot);
         if (item != null && item.getType() != Material.AIR) {
            double value = sellMulti.appraiseStack(player.getUniqueId(), item, ignored);
            if (value > 0.0) {
               total += value;
            }
         }
      }

      return total;
   }

   private void sellContents(Player player, Inventory inventory) {
      SellMultiManager sellMulti = this.plugin.getSellMultiManager();
      Map<SellMultiManager.Category, Double> credit = new EnumMap<>(SellMultiManager.Category.class);
      List<ItemStack> toReturn = new ArrayList<>();
      List<ItemStack> sold = new ArrayList<>();
      List<Double> soldValues = new ArrayList<>();
      double total = 0.0;

      for (int slot = 0; slot < 36; slot++) {
         ItemStack item = inventory.getItem(slot);
         if (item != null && item.getType() != Material.AIR) {
            double value = sellMulti.appraiseStack(player.getUniqueId(), item, credit);
            if (value <= 0.0) {
               toReturn.add(item);
            } else {
               total += value;
               sold.add(item.clone());
               soldValues.add(value);
            }
         }

         inventory.setItem(slot, null);
      }

      if (total > 0.0) {
         this.processPayout(player, total);
         sellMulti.creditProgress(player, credit, Math.min(total, 1.0E10) / total);
         if (this.plugin.getEconomy() != null) {
            SellHistory.recordSell(player.getUniqueId(), sold, soldValues, Math.min(total, 1.0E10));
         }
      }

      this.giveBack(player, toReturn);
   }

   private void giveBack(Player player, List<ItemStack> items) {
      if (!items.isEmpty()) {
         Map<Integer, ItemStack> leftovers = player.getInventory().addItem(items.toArray(new ItemStack[0]));
         if (!leftovers.isEmpty()) {
            leftovers.values().forEach(it -> player.getWorld().dropItemNaturally(player.getLocation(), it));
         }

         player.sendMessage("§eSome items were not sellable and have been returned.");
      }
   }

   private ItemStack addToItemArea(Inventory inventory, ItemStack stack) {
      ItemStack remaining = stack.clone();

      for (int slot = 0; slot < 36 && remaining.getAmount() > 0; slot++) {
         ItemStack existing = inventory.getItem(slot);
         if (existing != null && existing.isSimilar(remaining)) {
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space > 0) {
               int moved = Math.min(space, remaining.getAmount());
               existing.setAmount(existing.getAmount() + moved);
               inventory.setItem(slot, existing);
               remaining.setAmount(remaining.getAmount() - moved);
            }
         }
      }

      for (int slot = 0; slot < 36 && remaining.getAmount() > 0; slot++) {
         ItemStack existing = inventory.getItem(slot);
         if (existing == null || existing.getType() == Material.AIR) {
            int moved = Math.min(remaining.getMaxStackSize(), remaining.getAmount());
            ItemStack placed = remaining.clone();
            placed.setAmount(moved);
            inventory.setItem(slot, placed);
            remaining.setAmount(remaining.getAmount() - moved);
         }
      }

      return remaining.getAmount() > 0 ? remaining : null;
   }

   private void refreshLater(Player player, Inventory inventory) {
      player.getScheduler().runDelayed(this.plugin, task -> {
         if (player.getOpenInventory().getOriginalTitle().equals("§0Drop Items to Sell")) {
            this.drawButtonRow(inventory, this.appraise(player, inventory));
         }
      }, null, 1L);
   }

   @EventHandler
   public void onSellClose(InventoryCloseEvent e) {
      if (e.getView().getOriginalTitle().equals("§0Drop Items to Sell") && e.getPlayer() instanceof Player p) {
         this.sellContents(p, e.getInventory());
         e.getInventory().clear();
      }
   }

   private void processPayout(Player p, double total) {
      double payout = Math.min(total, 1.0E10);
      if (this.plugin.getEconomy() != null) {
         this.plugin.getEconomy().depositPlayer(p, payout);
         String payoutText = "§x§5§5§F§F§B§8+$" + PriceManager.formatPrice(payout);
         p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(payoutText));
         p.sendMessage(payoutText);
         p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
         this.plugin.addPlayerTotal(p.getUniqueId(), "sell_made", payout);
         if (total > 1.0E10) {
            p.sendMessage("§ePayout capped at $10,000,000,000");
         }
      } else {
         p.sendMessage("§cEconomy not available.");
      }
   }

   @EventHandler
   public void onSellDrag(InventoryDragEvent e) {
      if (e.getView().getOriginalTitle().equals("§0Drop Items to Sell") && e.getWhoClicked() instanceof Player p) {
         for (int raw : e.getRawSlots()) {
            if (raw >= 36 && raw < 45) {
               e.setCancelled(true);
               return;
            }
         }

         this.refreshLater(p, e.getView().getTopInventory());
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent e) {
      if (e.getWhoClicked() instanceof Player p) {
         if (e.getView().getOriginalTitle().equals("§0Drop Items to Sell")) {
            Inventory top = e.getView().getTopInventory();
            int raw = e.getRawSlot();
            if (raw >= 36 && raw < 45) {
               e.setCancelled(true);
               if (raw == 44) {
                  this.sellContents(p, top);
                  this.drawButtonRow(top, 0.0);
               }

               return;
            }

            if (e.getClick().isShiftClick() && raw >= 45) {
               e.setCancelled(true);
               ItemStack moving = e.getCurrentItem();
               if (moving != null && moving.getType() != Material.AIR) {
                  e.setCurrentItem(this.addToItemArea(top, moving));
                  this.drawButtonRow(top, this.appraise(p, top));
               }

               return;
            }

            this.refreshLater(p, top);
            return;
         }

         if (e.getView().getOriginalTitle().equals("§0Confirm Sell")) {
            e.setCancelled(true);
            UUID id = p.getUniqueId();
            SellManager.SellSession sess = this.sessions.get(id);
            if (sess == null) {
               p.closeInventory();
            } else {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null) {
                  if (clicked.getType() == Material.GREEN_SHULKER_BOX) {
                     this.processPayout(p, sess.total);
                     this.sessions.remove(id);
                     p.closeInventory();
                  } else if (clicked.getType() == Material.RED_SHULKER_BOX) {
                     sess.items.forEach(it -> p.getInventory().addItem(new ItemStack[]{it}));
                     p.sendMessage("§cSell cancelled. Items returned.");
                     this.sessions.remove(id);
                     p.closeInventory();
                  }
               }
            }
         }
      }
   }

   private static class SellSession {
      public final List<ItemStack> items;
      public final double total;

      public SellSession(List<ItemStack> items, double total) {
         this.items = items;
         this.total = total;
      }
   }
}
