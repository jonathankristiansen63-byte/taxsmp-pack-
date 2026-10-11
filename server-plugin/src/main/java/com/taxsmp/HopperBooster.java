package com.taxsmp;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class HopperBooster implements Listener {
   private static final int TARGET_AMOUNT = 12;
   private static final String WORTH_PREFIX = "Worth:";

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onHopperMove(InventoryMoveItemEvent event) {
      if (event.getInitiator().getType() == InventoryType.HOPPER) {
         ItemStack moving = event.getItem();
         if (moving != null && !moving.getType().isAir()) {
            Material type = moving.getType();
            int maxStack = type.getMaxStackSize();
            int target = Math.min(12, maxStack);
            ItemStack movingClean = moving.clone();
            this.removeWorthLine(movingClean);
            Inventory source = event.getSource();
            Inventory destination = event.getDestination();
            int sourceTotal = 0;

            for (ItemStack stack : source.getContents()) {
               if (stack != null && stack.getType() == type) {
                  ItemStack clean = stack.clone();
                  this.removeWorthLine(clean);
                  if (clean.isSimilar(movingClean)) {
                     sourceTotal += stack.getAmount();
                     if (sourceTotal >= target) {
                        break;
                     }
                  }
               }
            }

            if (sourceTotal != 0) {
               int toMove = Math.min(target, sourceTotal);
               int remaining = toMove;
               int placed = 0;

               for (int i = 0; i < destination.getSize() && remaining > 0; i++) {
                  ItemStack slot = destination.getItem(i);
                  if (slot != null && slot.getType() == type && slot.getAmount() < maxStack) {
                     ItemStack clean = slot.clone();
                     this.removeWorthLine(clean);
                     if (clean.isSimilar(movingClean)) {
                        int space = maxStack - slot.getAmount();
                        int add = Math.min(space, remaining);
                        slot.setAmount(slot.getAmount() + add);
                        destination.setItem(i, slot);
                        remaining -= add;
                        placed += add;
                     }
                  }
               }

               while (remaining > 0) {
                  int emptySlot = destination.firstEmpty();
                  if (emptySlot == -1) {
                     break;
                  }

                  int add = Math.min(maxStack, remaining);
                  ItemStack newStack = movingClean.clone();
                  newStack.setAmount(add);
                  destination.setItem(emptySlot, newStack);
                  remaining -= add;
                  placed += add;
               }

               if (placed != 0) {
                  int toRemove = placed;

                  for (int i = 0; i < source.getSize() && toRemove > 0; i++) {
                     ItemStack slot = source.getItem(i);
                     if (slot != null && slot.getType() == type) {
                        ItemStack clean = slot.clone();
                        this.removeWorthLine(clean);
                        if (clean.isSimilar(movingClean)) {
                           int take = Math.min(slot.getAmount(), toRemove);
                           if (take == slot.getAmount()) {
                              source.setItem(i, null);
                           } else {
                              slot.setAmount(slot.getAmount() - take);
                              source.setItem(i, slot);
                           }

                           toRemove -= take;
                        }
                     }
                  }

                  event.setCancelled(true);
               }
            }
         }
      }
   }

   private void removeWorthLine(ItemStack item) {
      if (item != null && !item.getType().isAir()) {
         ItemMeta meta = item.getItemMeta();
         if (meta != null && meta.hasLore() && meta.getLore() != null) {
            List<String> lore = new ArrayList<>(meta.getLore());
            lore.removeIf(line -> {
               String stripped = ChatColor.stripColor(line);
               return stripped != null && stripped.startsWith("Worth:");
            });
            meta.setLore(lore.isEmpty() ? null : lore);
            item.setItemMeta(meta);
         }
      }
   }
}
