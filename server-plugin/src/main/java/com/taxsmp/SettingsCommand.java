package com.taxsmp;

import java.util.Arrays;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public class SettingsCommand implements CommandExecutor {
   private static final String TITLE = ChatColor.GRAY + "Settings";
   private final TaxSMP plugin;

   public SettingsCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (this.plugin.getMenus() != null && this.plugin.getMenus().enabledFor(player)) {
            this.plugin.getMenus().openSettings(player, null);
            return true;
         } else {
            this.openSettingsGui(player);
            return true;
         }
      } else {
         sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
         return true;
      }
   }

   private void openSettingsGui(Player player) {
      Inventory inv = Bukkit.createInventory(null, 36, TITLE);
      inv.setItem(0, this.settingItem(player, Material.OAK_SIGN, "public chat", "public_chat"));
      inv.setItem(1, this.settingItem(player, Material.DARK_OAK_SIGN, "private messages", "private_messages"));
      inv.setItem(2, this.settingItem(player, Material.WARPED_SIGN, "chat server messages", "chat_server_messages"));
      inv.setItem(3, this.settingItem(player, Material.CRIMSON_SIGN, "hotbar server messages", "hotbar_server_messages"));
      inv.setItem(4, this.settingItem(player, Material.CHERRY_SIGN, "pay alerts", "pay_alerts"));
      inv.setItem(5, this.settingItem(player, Material.BAMBOO_SIGN, "bounty alerts", "bounty_alerts"));
      inv.setItem(6, this.settingItem(player, Material.ACACIA_SIGN, "auction alerts", "auction_alerts"));
      inv.setItem(9, this.settingItem(player, Material.END_CRYSTAL, "fast crystal", "fast_crystals"));
      inv.setItem(13, this.quickAuctionBuyItem(player));
      inv.setItem(14, this.settingItem(player, Material.CHAINMAIL_HELMET, "chainmail on respawn", "chainmail_on_respawn"));
      inv.setItem(15, this.settingItem(player, Material.ZOMBIE_HEAD, "mob spawns", "mob_spawns"));
      inv.setItem(18, this.settingItem(player, Material.LECTERN, "scoreboard", "scoreboard"));
      inv.setItem(19, this.settingItem(player, Material.FEATHER, "tpa confirm menus", "tpa_confirm_menus"));
      inv.setItem(20, this.settingItem(player, Material.MUSIC_DISC_CAT, "sound notifications", "sound_notifications"));
      inv.setItem(27, this.settingItem(player, Material.ENDER_PEARL, "tpa requests", "tpa_requests"));
      inv.setItem(28, this.settingItem(player, Material.ENDER_EYE, "tpahere requests", "tpa_here_requests"));
      inv.setItem(29, this.settingItem(player, Material.EMERALD, "payments", "payments"));
      inv.setItem(30, this.settingItem(player, Material.LIME_CANDLE, "worth display", "worth_display"));
      inv.setItem(31, this.settingItem(player, Material.BELL, "team chat", "team_chat"));
      player.getScheduler().run(this.plugin, task -> player.openInventory(inv), null);
   }

   private ItemStack settingItem(Player player, Material material, String name, String key) {
      return this.createGuiItem(material, ChatColor.YELLOW + this.toSmallCaps(name), this.statusLine(this.plugin.getPlayerSetting(player, key)));
   }

   private ItemStack quickAuctionBuyItem(Player player) {
      boolean enabled = this.plugin.getPlayerSetting(player, "quick_auction_buy") && this.plugin.hasQuickAuctionBuyPermission(player);
      return !this.plugin.hasQuickAuctionBuyPermission(player)
         ? this.createGuiItem(
            Material.GOLD_INGOT, ChatColor.YELLOW + this.toSmallCaps("quick auction buy"), ChatColor.RED + "Requires a Rank! /store", this.statusLine(enabled)
         )
         : this.createGuiItem(
            Material.GOLD_INGOT,
            ChatColor.YELLOW + this.toSmallCaps("quick auction buy"),
            ChatColor.GRAY + "Toggle instant purchasing.",
            this.statusLine(enabled)
         );
   }

   private ItemStack createGuiItem(Material material, String name, String... loreLines) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(name);
         if (loreLines.length > 0) {
            meta.setLore(Arrays.asList(loreLines));
         }

         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         item.setItemMeta(meta);
      }

      return item;
   }

   private String statusLine(boolean on) {
      return ChatColor.GRAY + "Currently: " + (on ? "" + ChatColor.GREEN + ChatColor.BOLD + "ON" : "" + ChatColor.RED + ChatColor.BOLD + "OFF");
   }

   private String toSmallCaps(String text) {
      StringBuilder out = new StringBuilder();

      for (char c : text.toCharArray()) {
         out.append(switch (c) {
            case 'A', 'a' -> 'ᴀ';
            case 'B', 'b' -> 'ʙ';
            case 'C', 'c' -> 'ᴄ';
            case 'D', 'd' -> 'ᴅ';
            case 'E', 'e' -> 'ᴇ';
            case 'F', 'f' -> 'ғ';
            case 'G', 'g' -> 'ɢ';
            case 'H', 'h' -> 'ʜ';
            case 'I', 'i' -> 'ɪ';
            case 'J', 'j' -> 'ᴊ';
            case 'K', 'k' -> 'ᴋ';
            case 'L', 'l' -> 'ʟ';
            case 'M', 'm' -> 'ᴍ';
            case 'N', 'n' -> 'ɴ';
            case 'O', 'o' -> 'ᴏ';
            case 'P', 'p' -> 'ᴘ';
            case 'Q', 'q' -> 'ǫ';
            case 'R', 'r' -> 'ʀ';
            case 'S', 's' -> 'ꜱ';
            case 'T', 't' -> 'ᴛ';
            case 'U', 'u' -> 'ᴜ';
            case 'V', 'v' -> 'ᴠ';
            case 'W', 'w' -> 'ᴡ';
            case 'X', 'x' -> 'x';
            case 'Y', 'y' -> 'ʏ';
            case 'Z', 'z' -> 'ᴢ';
            default -> c;
         });
      }

      return out.toString();
   }
}
