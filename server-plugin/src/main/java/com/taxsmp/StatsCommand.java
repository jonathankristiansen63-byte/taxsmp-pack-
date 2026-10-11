package com.taxsmp;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class StatsCommand implements CommandExecutor, Listener {
   private static final String ACCENT = "§e";
   private static final String GREY = "§7";
   private static final String DARK = "§8";
   private static final String TITLE_PREFIX = "§8";
   private static final String TITLE_SELF = "§8ᴍʏ ѕᴛᴀᴛѕ";
   private static final String TITLE_OTHER_SUFFIX = "§8 ѕᴛᴀᴛѕ";
   private final TaxSMP plugin;

   public StatsCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      boolean isSelf = false;
      OfflinePlayer target;
      if (args.length >= 1) {
         target = Bukkit.getOfflinePlayerIfCached(args[0]);
         if (target == null) {
            target = Bukkit.getOfflinePlayer(args[0]);
         }
      } else {
         if (!(sender instanceof Player p)) {
            sender.sendMessage("§cUsage: /stats [player]");
            return true;
         }

         target = p;
         isSelf = true;
      }

      if (target != null && (target.hasPlayedBefore() || target.isOnline())) {
         if (sender instanceof Player viewer && this.plugin.getMenus() != null && this.plugin.getMenus().enabledFor(viewer)) {
            this.plugin.getMenus().openStatsFor(viewer, target.getName() != null ? target.getName() : viewer.getName());
            return true;
         } else {
            this.openStats(sender, target, isSelf || sender instanceof Player viewer2 && viewer2.getUniqueId().equals(target.getUniqueId()));
            return true;
         }
      } else {
         sender.sendMessage("§cPlayer not found.");
         return true;
      }
   }

   public void openChestStats(Player viewer, OfflinePlayer target) {
      this.openStats(viewer, target, viewer.getUniqueId().equals(target.getUniqueId()));
   }

   private void openStats(CommandSender sender, OfflinePlayer target, boolean isSelf) {
      String title = isSelf ? "§8ᴍʏ ѕᴛᴀᴛѕ" : "§8" + target.getName() + "§8 ѕᴛᴀᴛѕ";
      Inventory inv = Bukkit.createInventory(null, 36, title);
      long money = 0L;
      long deaths = 0L;
      long playtime = 0L;
      long mobs = 0L;
      long kills = 0L;
      long blocksBroken = 0L;
      long blocksPlaced = 0L;
      double bal = this.plugin.getEconomy() != null ? this.plugin.getEconomy().getBalance(target) : 0.0;
      money = (long)Math.floor(bal);
      Player online = target.isOnline() ? target.getPlayer() : null;
      if (online != null) {
         deaths = online.getStatistic(Statistic.DEATHS);
         playtime = this.plugin.getStatsTracker() != null
            ? this.plugin.getStatsTracker().getStat(target.getUniqueId(), StatsTracker.StatType.PLAYTIME)
            : online.getStatistic(Statistic.PLAY_ONE_MINUTE);
         mobs = online.getStatistic(Statistic.MOB_KILLS);
         kills = online.getStatistic(Statistic.PLAYER_KILLS);

         for (Material m : Material.values()) {
            if (m.isBlock()) {
               try {
                  blocksBroken += online.getStatistic(Statistic.MINE_BLOCK, m);
                  blocksPlaced += online.getStatistic(Statistic.USE_ITEM, m);
               } catch (IllegalArgumentException var30) {
               }
            }
         }
      } else if (this.plugin.getStatsTracker() != null) {
         StatsTracker st = this.plugin.getStatsTracker();
         UUID id = target.getUniqueId();
         deaths = st.getStat(id, StatsTracker.StatType.DEATHS);
         playtime = st.getStat(id, StatsTracker.StatType.PLAYTIME);
         mobs = st.getStat(id, StatsTracker.StatType.MOBS_KILLED);
         kills = st.getStat(id, StatsTracker.StatType.PLAYER_KILLS);
         blocksPlaced = st.getStat(id, StatsTracker.StatType.BLOCKS_PLACED);
         blocksBroken = st.getStat(id, StatsTracker.StatType.BLOCKS_BROKEN);
      }

      String uuid = target.getUniqueId().toString();
      int eggs = this.plugin.getEggManager() != null
         ? this.plugin.getEggManager().getEggs(target.getUniqueId())
         : this.plugin.getConfig().getInt("players." + uuid + ".eggs", 0);
      double moneySpent = this.plugin.getPlayerTotal(target.getUniqueId(), "shop_spent");
      double moneyMade = this.plugin.getPlayerTotal(target.getUniqueId(), "sell_made");
      inv.setItem(10, this.makeItem(Material.EMERALD, "§eᴍᴏɴᴇʏ", "§7$" + PriceManager.formatShort(money)));
      inv.setItem(11, this.makeItem(Material.EGG, "§eᴇɢɢꜱ", "§7" + eggs));
      inv.setItem(12, this.makeItem(Material.DIAMOND_SWORD, "§eᴋɪʟʟꜱ", "§7" + PriceManager.formatShort(kills)));
      inv.setItem(13, this.makeItem(Material.SKELETON_SKULL, "§eᴅᴇᴀᴛʜꜱ", "§7" + PriceManager.formatShort(deaths)));
      inv.setItem(14, this.makeItem(Material.CLOCK, "§eᴘʟᴀʏᴛɪᴍᴇ", "§7" + PlaytimeFormatter.compact(playtime)));
      inv.setItem(15, this.makeItem(Material.STONE, "§eʙʟᴏᴄᴋꜱ ᴘʟᴀᴄᴇᴅ", "§7" + PriceManager.formatShort(blocksPlaced)));
      inv.setItem(16, this.makeItem(Material.COBBLESTONE, "§eʙʟᴏᴄᴋꜱ ʙʀᴏᴋᴇɴ", "§7" + PriceManager.formatShort(blocksBroken)));
      inv.setItem(19, this.makeItem(Material.ZOMBIE_HEAD, "§eᴍᴏʙꜱ ᴋɪʟʟᴇᴅ", "§7" + PriceManager.formatShort(mobs)));
      inv.setItem(20, this.makeItem(Material.GOLD_NUGGET, "§eᴍᴏɴᴇʏ ꜱᴘᴇɴᴛ ᴏɴ ꜱʜᴏᴘ", "§7$" + PriceManager.formatShort(moneySpent)));
      inv.setItem(21, this.makeItem(Material.IRON_NUGGET, "§eᴍᴏɴᴇʏ ᴍᴀᴅᴇ ᴏɴ /ꜱᴇʟʟ", "§7$" + PriceManager.formatShort(moneyMade)));
      if (sender instanceof Player p) {
         p.openInventory(inv);
      } else {
         sender.sendMessage("Stats for " + target.getName() + ": $" + PriceManager.formatPrice(bal));
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent e) {
      String title = e.getView().getOriginalTitle();
      if (title.equals("§8ᴍʏ ѕᴛᴀᴛѕ") || title.endsWith("§8 ѕᴛᴀᴛѕ")) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent e) {
      String title = e.getView().getOriginalTitle();
      if (title.equals("§8ᴍʏ ѕᴛᴀᴛѕ") || title.endsWith("§8 ѕᴛᴀᴛѕ")) {
         e.setCancelled(true);
      }
   }

   private ItemStack makeItem(Material mat, String name, String value) {
      ItemStack it = new ItemStack(mat);
      ItemMeta im = it.getItemMeta();
      if (im != null) {
         im.setDisplayName(name);
         List<String> lore = new ArrayList<>();
         lore.add(value);
         im.setLore(lore);
         it.setItemMeta(im);
      }

      return it;
   }
}
