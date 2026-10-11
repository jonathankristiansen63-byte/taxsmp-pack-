package com.taxsmp;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BalCommand implements CommandExecutor {
   private final TaxSMP plugin;

   public BalCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (args.length == 0) {
         if (sender instanceof Player p) {
            double bal = this.plugin.getEconomy().getBalance(p);
            p.sendMessage("§aYour balance: §f$" + PriceManager.formatShort(bal));
            return true;
         } else {
            sender.sendMessage("Usage: /bal <player>");
            return true;
         }
      } else {
         String targetName = args[0];
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);
            double balx = this.plugin.getEconomy().getBalance(target);
            sender.sendMessage("§a" + targetName + " has: §f$" + PriceManager.formatShort(balx));
         });
         return true;
      }
   }
}
