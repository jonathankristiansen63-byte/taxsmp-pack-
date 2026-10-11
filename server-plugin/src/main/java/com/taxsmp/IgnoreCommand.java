package com.taxsmp;

import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class IgnoreCommand implements CommandExecutor {
   private final TaxSMP plugin;

   public IgnoreCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (args.length != 1) {
            player.sendMessage(ChatColor.RED + "Usage: /ignore <player>");
            return true;
         }

         Player target = Bukkit.getPlayerExact(args[0]);
         UUID targetUUID;
         String targetName;
         if (target != null) {
            targetUUID = target.getUniqueId();
            targetName = target.getName();
         } else {
            OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(args[0]);
            if (offline == null || !offline.hasPlayedBefore()) {
               player.sendMessage(ChatColor.RED + "Player not found.");
               return true;
            }

            targetUUID = offline.getUniqueId();
            targetName = offline.getName();
         }

         if (targetUUID.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "You cannot ignore yourself.");
            return true;
         }

         IgnoreManager ignoreManager = this.plugin.getIgnoreManager();
         if (ignoreManager.isIgnoring(player.getUniqueId(), targetUUID)) {
            ignoreManager.unignore(player.getUniqueId(), targetUUID);
            player.sendMessage(ChatColor.GREEN + "You are no longer ignoring " + targetName + ".");
         } else {
            ignoreManager.ignore(player.getUniqueId(), targetUUID);
            player.sendMessage(ChatColor.GREEN + "You are now ignoring " + targetName + ".");
         }

         return true;
      } else {
         sender.sendMessage(ChatColor.RED + "Only players can use this command.");
         return true;
      }
   }
}
