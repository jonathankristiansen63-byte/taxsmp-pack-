package com.taxsmp;

import java.util.Collections;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class SettingsListener implements Listener {
   private final TaxSMP plugin;

   public SettingsListener(TaxSMP plugin) {
      this.plugin = plugin;
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getView() != null) {
         String title = ChatColor.stripColor(event.getView().getOriginalTitle());
         if (title != null && title.equalsIgnoreCase("SETTINGS")) {
            event.setCancelled(true);
            if (event.getRawSlot() < event.getView().getTopInventory().getSize()
               && event.getClick() != ClickType.DOUBLE_CLICK
               && !event.isShiftClick()
               && event.getWhoClicked() instanceof Player player) {
               String key = this.keyForSlot(event.getRawSlot());
               if (key != null) {
                  ItemStack clicked = event.getCurrentItem();
                  if (clicked != null && clicked.hasItemMeta()) {
                     if (key.equals("quick_auction_buy") && !this.plugin.hasQuickAuctionBuyPermission(player)) {
                        player.sendMessage(ChatColor.RED + "You need a rank to toggle this! Check /store.");
                        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8F, 1.0F);
                     } else {
                        String path = "players." + player.getUniqueId() + "." + key;
                        boolean next = !this.plugin.getPlayerSetting(player, key);
                        this.plugin.getConfig().set(path, next);
                        this.plugin.requestConfigSave();
                        this.plugin.applySettingSideEffects(player, key, next);
                        TaxSMP.fireSettingsHooks(player.getUniqueId(), key, next);
                        if (this.plugin.getSettingsSyncManager() != null) {
                           this.plugin.getSettingsSyncManager().push(player.getUniqueId(), key, next);
                        }

                        ItemMeta meta = clicked.getItemMeta();
                        meta.setLore(Collections.singletonList(this.statusLine(next)));
                        clicked.setItemMeta(meta);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5F, 1.0F);
                     }
                  }
               }
            }
         }
      }
   }

   private String keyForSlot(int slot) {
      return switch (slot) {
         case 0 -> "public_chat";
         case 1 -> "private_messages";
         case 2 -> "chat_server_messages";
         case 3 -> "hotbar_server_messages";
         case 4 -> "pay_alerts";
         case 5 -> "bounty_alerts";
         case 6 -> "auction_alerts";
         default -> null;
         case 9 -> "fast_crystals";
         case 13 -> "quick_auction_buy";
         case 14 -> "chainmail_on_respawn";
         case 15 -> "mob_spawns";
         case 18 -> "scoreboard";
         case 19 -> "tpa_confirm_menus";
         case 20 -> "sound_notifications";
         case 27 -> "tpa_requests";
         case 28 -> "tpa_here_requests";
         case 29 -> "payments";
         case 30 -> "worth_display";
         case 31 -> "team_chat";
      };
   }

   private String statusLine(boolean on) {
      return ChatColor.GRAY + "Currently: " + (on ? "" + ChatColor.GREEN + ChatColor.BOLD + "ON" : "" + ChatColor.RED + ChatColor.BOLD + "OFF");
   }
}
