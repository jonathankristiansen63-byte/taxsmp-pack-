package com.taxsmp;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

public class LeaderboardCommand implements CommandExecutor, Listener {
   static final String ACCENT = "§e";
   static final String GREY = "§7";
   static final String WHITE = "§f";
   static final String DARK = "§8";
   static final String MAIN_TITLE = "§8ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅꜱ";
   static final String PAGE_PREFIX = "§8ᴍᴏѕᴛ ";
   private final TaxSMP plugin;
   private final StatsTracker tracker;

   public LeaderboardCommand(TaxSMP plugin, StatsTracker tracker) {
      this.plugin = plugin;
      this.tracker = tracker;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!(sender instanceof Player p)) {
         sender.sendMessage("Only players can use this command.");
         return true;
      } else {
         boolean drawnMenus = this.plugin.getMenus() != null && this.plugin.getMenus().enabledFor(p);
         if (args.length >= 1) {
            StatsTracker.StatType t = this.parseType(args[0]);
            if (t != null) {
               if (drawnMenus) {
                  this.plugin.getMenus().openTop(p, t);
               } else {
                  this.openLeaderboard(p, t, 1);
               }

               return true;
            }
         }

         if (drawnMenus) {
            this.plugin.getMenus().openLeaderboards(p);
         } else {
            this.openMainMenu(p);
         }

         return true;
      }
   }

   public void openMainMenu(Player p) {
      Inventory inv = Bukkit.createInventory(null, 27, "§8ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅꜱ");
      inv.setItem(10, this.makeItem(Material.EMERALD, "§eᴍᴏɴᴇʏ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ", "§fClick to view the §eᴍᴏɴᴇʏ§f leaderboard"));
      inv.setItem(11, this.makeItem(Material.SKELETON_SKULL, "§eᴅᴇᴀᴛʜꜱ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ", "§fClick to view the §eᴅᴇᴀᴛʜꜱ§f leaderboard"));
      inv.setItem(12, this.makeItem(Material.CLOCK, "§eᴘʟᴀʏᴛɪᴍᴇ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ", "§fClick to view the §eᴘʟᴀʏᴛɪᴍᴇ§f leaderboard"));
      inv.setItem(13, this.makeItem(Material.STONE, "§eʙʟᴏᴄᴋꜱ ᴘʟᴀᴄᴇᴅ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ", "§fClick to view the §eʙʟᴏᴄᴋꜱ ᴘʟᴀᴄᴇᴅ§f leaderboard"));
      inv.setItem(14, this.makeItem(Material.COBBLESTONE, "§eʙʟᴏᴄᴋꜱ ʙʀᴏᴋᴇɴ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ", "§fClick to view the §eʙʟᴏᴄᴋꜱ ʙʀᴏᴋᴇɴ§f leaderboard"));
      inv.setItem(15, this.makeItem(Material.ZOMBIE_HEAD, "§eᴍᴏʙꜱ ᴋɪʟʟᴇᴅ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ", "§fClick to view the §eᴍᴏʙꜱ ᴋɪʟʟᴇᴅ§f leaderboard"));
      inv.setItem(16, this.makeItem(Material.DIAMOND_SWORD, "§eᴘʟᴀʏᴇʀ ᴋɪʟʟꜱ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ", "§fClick to view the §eᴘʟᴀʏᴇʀ ᴋɪʟʟꜱ§f leaderboard"));
      p.openInventory(inv);
   }

   public void openLeaderboard(Player viewer, StatsTracker.StatType type, int page) {
      Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
         List<Entry<UUID, Long>> sorted = this.tracker.getSorted(type);
         viewer.getScheduler().run(this.plugin, t -> this.renderPage(viewer, type, sorted, page), null);
      });
   }

   private void renderPage(Player viewer, StatsTracker.StatType type, List<Entry<UUID, Long>> list, int page) {
      int perPage = 45;
      int total = list.size();
      int maxPage = Math.max(1, (int)Math.ceil((double)total / perPage));
      if (page < 1) {
         page = 1;
      }

      if (page > maxPage) {
         page = maxPage;
      }

      String title = "§8ᴍᴏѕᴛ " + type.pretty + "§8 (Page " + page + "/" + maxPage + ")";
      Inventory inv = Bukkit.createInventory(null, 54, title);
      int start = (page - 1) * perPage;
      int end = Math.min(start + perPage, total);
      int slot = 0;

      for (int i = start; i < end; i++) {
         Entry<UUID, Long> e = list.get(i);
         ItemStack head = this.playerHead(e.getKey(), type, e.getValue(), i + 1);
         inv.setItem(slot++, head);
      }

      inv.setItem(45, this.makeItem(Material.ARROW, "§eʙᴀᴄᴋ", "§fClick to go to the previous page"));
      long selfValue = this.tracker.getStat(viewer.getUniqueId(), type);
      int selfRank = this.tracker.getRank(viewer.getUniqueId(), type);
      inv.setItem(48, this.playerHead(viewer.getUniqueId(), type, selfValue, selfRank));
      Material refreshMat = this.refreshMaterial(type);
      inv.setItem(49, this.makeItem(refreshMat, "§e" + "§8ᴍᴏѕᴛ ".replace("§e", "") + type.pretty, "§fClick to refresh"));
      ItemStack searchItem = new ItemStack(Material.OAK_SIGN);
      ItemMeta sm = searchItem.getItemMeta();
      if (sm != null) {
         sm.setDisplayName("§eѕᴇᴀʀᴄʜ");
         sm.setLore(Collections.singletonList("§7currently under work"));
         searchItem.setItemMeta(sm);
      }

      inv.setItem(50, searchItem);
      inv.setItem(53, this.makeItem(Material.ARROW, "§eɴᴇxᴛ", "§fClick to go to the next page"));
      viewer.openInventory(inv);
   }

   private ItemStack playerHead(UUID id, StatsTracker.StatType type, long value, int rank) {
      OfflinePlayer off = Bukkit.getOfflinePlayer(id);
      ItemStack head = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta sm = (SkullMeta)head.getItemMeta();
      if (sm != null) {
         try {
            HeadProfiles.setOwner(sm, off);
         } catch (Exception var10) {
         }

         String name = off.getName() != null ? off.getName() : this.tracker.getName(id);
         sm.setDisplayName("§e" + name);
         sm.setLore(Collections.singletonList("§f" + type.loreLabel + ": §7" + StatsTracker.formatValue(type, value) + " §e(#" + (rank > 0 ? rank : "?") + ")"));
         HeadProfiles.setItemMeta(head, sm);
      }

      return head;
   }

   private Material refreshMaterial(StatsTracker.StatType type) {
      switch (type) {
         case MONEY:
            return Material.EMERALD;
         case DEATHS:
            return Material.SKELETON_SKULL;
         case PLAYTIME:
            return Material.CLOCK;
         case BLOCKS_PLACED:
            return Material.STONE;
         case BLOCKS_BROKEN:
            return Material.COBBLESTONE;
         case MOBS_KILLED:
            return Material.ZOMBIE_HEAD;
         case PLAYER_KILLS:
            return Material.DIAMOND_SWORD;
         default:
            return Material.PAPER;
      }
   }

   private ItemStack makeItem(Material m, String name, String lore) {
      ItemStack it = new ItemStack(m);
      ItemMeta im = it.getItemMeta();
      if (im != null) {
         im.setDisplayName(name);
         im.setLore(Collections.singletonList(lore));
         it.setItemMeta(im);
      }

      return it;
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onInventoryClick(InventoryClickEvent e) {
      String title = e.getView().getOriginalTitle();
      if (isLeaderboardTitle(title)) {
         e.setCancelled(true);
         if (e.getWhoClicked() instanceof Player p && e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getTopInventory())) {
            ClickType ct = e.getClick();
            if (ct != ClickType.DROP
               && ct != ClickType.CONTROL_DROP
               && ct != ClickType.WINDOW_BORDER_LEFT
               && ct != ClickType.WINDOW_BORDER_RIGHT
               && ct != ClickType.NUMBER_KEY
               && ct != ClickType.SWAP_OFFHAND) {
               if (title.equals("§8ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅꜱ")) {
                  this.handleMainMenuClick(p, e.getRawSlot());
               } else {
                  StatsTracker.StatType type = parseTypeFromTitle(title);
                  if (type != null) {
                     int currentPage = parsePageFromTitle(title);
                     int slot = e.getRawSlot();
                     switch (slot) {
                        case 45:
                           this.openLeaderboard(p, type, Math.max(1, currentPage - 1));
                           return;
                        case 46:
                        case 47:
                        case 51:
                        case 52:
                        default:
                           if (slot >= 0 && slot < 45) {
                              ItemStack clicked = e.getCurrentItem();
                              if (clicked == null || clicked.getType() != Material.PLAYER_HEAD) {
                                 return;
                              }

                              ItemMeta im = clicked.getItemMeta();
                              if (im == null || !im.hasDisplayName()) {
                                 return;
                              }

                              String stripped = ChatColor.stripColor(im.getDisplayName());
                              if (stripped != null && !stripped.isEmpty()) {
                                 this.runStatsOn(p, stripped);
                              }
                           }

                           return;
                        case 48:
                           this.runStatsOn(p, p.getName());
                           return;
                        case 49:
                           this.openLeaderboard(p, type, currentPage);
                           return;
                        case 50:
                           return;
                        case 53:
                           this.openLeaderboard(p, type, currentPage + 1);
                     }
                  }
               }
            }
         }
      }
   }

   private void runStatsOn(Player p, String target) {
      p.closeInventory();
      p.getScheduler().run(this.plugin, task -> Bukkit.dispatchCommand(p, "stats " + target), null);
   }

   private void handleMainMenuClick(Player p, int slot) {
      switch (slot) {
         case 10:
            this.openLeaderboard(p, StatsTracker.StatType.MONEY, 1);
            break;
         case 11:
            this.openLeaderboard(p, StatsTracker.StatType.DEATHS, 1);
            break;
         case 12:
            this.openLeaderboard(p, StatsTracker.StatType.PLAYTIME, 1);
            break;
         case 13:
            this.openLeaderboard(p, StatsTracker.StatType.BLOCKS_PLACED, 1);
            break;
         case 14:
            this.openLeaderboard(p, StatsTracker.StatType.BLOCKS_BROKEN, 1);
            break;
         case 15:
            this.openLeaderboard(p, StatsTracker.StatType.MOBS_KILLED, 1);
            break;
         case 16:
            this.openLeaderboard(p, StatsTracker.StatType.PLAYER_KILLS, 1);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onInventoryDrag(InventoryDragEvent e) {
      if (isLeaderboardTitle(e.getView().getOriginalTitle())) {
         e.setCancelled(true);
      }
   }

   public static boolean isLeaderboardTitle(String title) {
      return title == null ? false : title.equals("§8ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅꜱ") || title.startsWith("§8ᴍᴏѕᴛ ");
   }

   static int parsePageFromTitle(String title) {
      try {
         int idx = title.indexOf("Page ");
         if (idx < 0) {
            return 1;
         }

         String after = title.substring(idx + 5);
         int slash = after.indexOf(47);
         return slash < 0 ? 1 : Math.max(1, Integer.parseInt(after.substring(0, slash).trim()));
      } catch (Exception var4) {
         return 1;
      }
   }

   static StatsTracker.StatType parseTypeFromTitle(String title) {
      for (StatsTracker.StatType t : StatsTracker.StatType.values()) {
         if (title.contains(t.pretty)) {
            return t;
         }
      }

      return null;
   }

   private StatsTracker.StatType parseType(String s) {
      if (s == null) {
         return null;
      }

      String k = s.toLowerCase(Locale.ROOT);
      switch (k) {
         case "money":
         case "bal":
         case "balance":
         case "baltop":
            return StatsTracker.StatType.MONEY;
         case "deaths":
         case "death":
            return StatsTracker.StatType.DEATHS;
         case "playtime":
         case "time":
            return StatsTracker.StatType.PLAYTIME;
         case "placed":
         case "blocksplaced":
         case "blocks_placed":
            return StatsTracker.StatType.BLOCKS_PLACED;
         case "broken":
         case "blocksbroken":
         case "blocks_broken":
            return StatsTracker.StatType.BLOCKS_BROKEN;
         case "mobs":
         case "mobskilled":
         case "mobs_killed":
            return StatsTracker.StatType.MOBS_KILLED;
         case "kills":
         case "playerkills":
         case "player_kills":
            return StatsTracker.StatType.PLAYER_KILLS;
         default:
            return null;
      }
   }
}
