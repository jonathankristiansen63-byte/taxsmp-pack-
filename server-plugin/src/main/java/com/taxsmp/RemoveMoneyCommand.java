package com.taxsmp;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class RemoveMoneyCommand implements CommandExecutor {
   private final TaxSMP plugin;

   public RemoveMoneyCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p && !p.hasPermission("taxsmp.removemoney")) {
         p.sendMessage("§c§l[!] §cYou do not have permission.");
         return true;
      } else {
         if (args.length < 1) {
            sender.sendMessage("§c§l[!] §cUsage: /removemoney <player>");
            sender.sendMessage("§7This will completely remove all money from the target player.");
            return true;
         }

         Player target = Bukkit.getPlayer(args[0]);
         if (target != null && target.isOnline()) {
            try {
               SimpleEconomy econ = this.plugin.getEconomy();
               if (econ == null) {
                  sender.sendMessage("§c§l[!] §cEconomy is unavailable.");
                  return true;
               }

               double currentBalance = econ.getBalance(target);
               econ.withdrawPlayer(target, currentBalance);
               sender.sendMessage("§a§l[✓] §aRemoved §6$" + PriceManager.formatPrice(currentBalance) + " §afrom " + target.getName() + "'s balance.");
               target.sendMessage("§c§l[!] §cAn OP has removed all your money! Your balance is now: §6$0");
            } catch (Exception e) {
               sender.sendMessage("§c§l[!] §cAn internal error occurred.");
               e.printStackTrace();
            }

            return true;
         } else {
            sender.sendMessage("§c§l[!] §cThat player is currently offline or doesn't exist.");
            return true;
         }
      }
   }
}
