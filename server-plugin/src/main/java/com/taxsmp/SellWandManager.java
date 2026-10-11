package com.taxsmp;

import java.util.ArrayList;
import java.util.List;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class SellWandManager implements Listener, CommandExecutor {
   private final TaxSMP plugin;
   private final NamespacedKey markKey;
   private final NamespacedKey tierKey;
   private final NamespacedKey usesKey;
   private static final String SUBTITLE = "§8ɢᴇᴍ ꜱʜᴏᴘ";
   private static final double PAYOUT_CAP = 1.0E10;

   public SellWandManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.markKey = new NamespacedKey(plugin, "sell_wand");
      this.tierKey = new NamespacedKey(plugin, "sell_wand_tier");
      this.usesKey = new NamespacedKey(plugin, "sell_wand_uses");
   }

   public ItemStack createWand(SellWandManager.Tier tier) {
      return this.createWand(tier, tier.maxUses);
   }

   private ItemStack createWand(SellWandManager.Tier tier, int usesLeft) {
      ItemStack item = new ItemStack(Material.BLAZE_ROD);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§cSell Wand [Tier " + tier.tier + "]");
         meta.setLore(this.buildWandLore(tier, usesLeft));
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP});

         try {
            meta.setMaxStackSize(1);
         } catch (Throwable var6) {
         }

         PersistentDataContainer pdc = meta.getPersistentDataContainer();
         pdc.set(this.markKey, PersistentDataType.BYTE, (byte)1);
         pdc.set(this.tierKey, PersistentDataType.INTEGER, tier.tier);
         pdc.set(this.usesKey, PersistentDataType.INTEGER, usesLeft);
         item.setItemMeta(meta);
      }

      return item;
   }

   private List<String> buildWandLore(SellWandManager.Tier tier, int usesLeft) {
      List<String> lore = new ArrayList<>();
      lore.add("§8ɢᴇᴍ ꜱʜᴏᴘ");
      lore.add("");
      lore.add("§eInformation:");
      lore.add("§fInstantly sell all items inside");
      lore.add("§fchest using your Sellwand.");
      lore.add("");
      lore.add("§eExtra Details:");
      lore.add("§b» Multiplier: " + tier.multiplierLabel);
      lore.add("§a» Uses: " + usesLeft + "/" + tier.maxUses);
      return lore;
   }

   public boolean isSellWand(ItemStack item) {
      if (item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         if (meta == null) {
            return false;
         }

         Byte b = (Byte)meta.getPersistentDataContainer().get(this.markKey, PersistentDataType.BYTE);
         return b != null && b == 1;
      } else {
         return false;
      }
   }

   private SellWandManager.Tier getTier(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return null;
      }

      Integer t = (Integer)meta.getPersistentDataContainer().get(this.tierKey, PersistentDataType.INTEGER);
      return t == null ? null : SellWandManager.Tier.fromNumber(t);
   }

   private int getUses(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return 0;
      }

      Integer u = (Integer)meta.getPersistentDataContainer().get(this.usesKey, PersistentDataType.INTEGER);
      return u == null ? 0 : u;
   }

   @EventHandler
   public void onInteract(PlayerInteractEvent e) {
      if (e.getHand() == EquipmentSlot.HAND && this.isSellWand(e.getPlayer().getInventory().getItemInMainHand())) {
         e.setCancelled(true);
         this.removeWands(e.getPlayer());
      }
   }

   private double sellContainer(Inventory inv) {
      double total = 0.0;
      ItemStack[] contents = inv.getContents();

      for (int i = 0; i < contents.length; i++) {
         ItemStack item = contents[i];
         if (item != null && item.getType() != Material.AIR) {
            if (item.getItemMeta() instanceof BlockStateMeta bmeta && bmeta.getBlockState() instanceof ShulkerBox sb) {
               double shulkerTotal = this.getShulkerBoxPrice(item.getType());

               for (ItemStack inner : sb.getInventory().getContents()) {
                  if (inner != null) {
                     double iprice = PriceManager.getPrice(inner.getType());
                     if (iprice > 0.0) {
                        shulkerTotal += iprice * inner.getAmount();
                     }
                  }
               }

               if (shulkerTotal > 0.0) {
                  total += shulkerTotal * item.getAmount();
                  inv.setItem(i, null);
               }
            } else {
               double price = PriceManager.getPrice(item.getType());
               if (price > 0.0) {
                  total += price * item.getAmount();
                  inv.setItem(i, null);
               }
            }
         }
      }

      return total;
   }

   private double getShulkerBoxPrice(Material material) {
      double exactPrice = PriceManager.getPrice(material);
      return exactPrice > 0.0 ? exactPrice : PriceManager.getPrice(Material.SHULKER_BOX);
   }

   private void processPayout(Player p, double payout) {
      this.plugin.getEconomy().depositPlayer(p, payout);
      String mint = "§x§5§5§F§F§B§8";
      String payoutText = mint + "+$" + PriceManager.formatPrice(payout);
      p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(payoutText));
      p.sendMessage(payoutText);
      p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
      this.plugin.addSellEarnings(p.getUniqueId(), payout);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      sender.sendMessage("§cSell wands have been removed from the server.");
      return true;
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      this.removeWands(e.getPlayer());
   }

   private void removeWands(Player p) {
      if (p.isOnline()) {
         int removed = 0;
         Inventory[] inventories = new Inventory[]{p.getInventory(), p.getEnderChest()};

         for (Inventory inv : inventories) {
            ItemStack[] contents = inv.getContents();

            for (int i = 0; i < contents.length; i++) {
               if (this.isSellWand(contents[i])) {
                  removed += contents[i].getAmount();
                  inv.setItem(i, null);
               }
            }
         }

         if (removed > 0) {
            p.sendMessage(
               "§cSell wands have been removed from the server, so " + (removed == 1 ? "your sell wand was" : removed + " sell wands were") + " taken away."
            );
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
         }
      }
   }

   public enum Tier {
      TIER1(1, 1.0, "1x", 200, 200),
      TIER2(2, 1.5, "1.5x", 150, 350),
      TIER3(3, 2.0, "2x", 100, 500);

      final int tier;
      final double multiplier;
      final String multiplierLabel;
      final int maxUses;
      final int gemCost;

      Tier(int tier, double multiplier, String multiplierLabel, int maxUses, int gemCost) {
         this.tier = tier;
         this.multiplier = multiplier;
         this.multiplierLabel = multiplierLabel;
         this.maxUses = maxUses;
         this.gemCost = gemCost;
      }

      static SellWandManager.Tier fromNumber(int n) {
         for (SellWandManager.Tier t : values()) {
            if (t.tier == n) {
               return t;
            }
         }

         return null;
      }
   }
}
