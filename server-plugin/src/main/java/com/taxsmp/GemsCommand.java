package com.taxsmp;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class GemsCommand implements CommandExecutor, TabCompleter {
   private final TaxSMP plugin;

   public GemsCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      GemManager gems = this.plugin.getGemManager();
      if (gems == null) {
         sender.sendMessage("§cGem system is currently offline.");
         return true;
      }

      if (args.length == 0) {
         if (sender instanceof Player player) {
            sender.sendMessage("§b" + player.getName() + " §fhas §b" + gems.getGems(player) + " gems§f.");
            return true;
         } else {
            sender.sendMessage("§cConsole must use /gems <player>.");
            return true;
         }
      } else {
         String sub = args[0].toLowerCase();
         if (sub.equals("add")) {
            return this.handleAdd(sender, gems, args);
         } else if (sub.equals("pay")) {
            return this.handlePay(sender, gems, args);
         } else {
            OfflinePlayer target = this.resolvePlayer(args[0]);
            if (target == null) {
               sender.sendMessage("§cPlayer not found.");
               return true;
            } else {
               sender.sendMessage("§b" + this.displayName(target, args[0]) + " §fhas §b" + gems.getGems(target) + " gems§f.");
               return true;
            }
         }
      }
   }

   private boolean handleAdd(CommandSender sender, GemManager gems, String[] args) {
      if (!sender.isOp() && !sender.hasPermission("taxsmp.gems.add")) {
         sender.sendMessage("§cYou do not have permission to add gems.");
         return true;
      }

      if (args.length != 3) {
         sender.sendMessage("§cUsage: /gems add <player> <amount>");
         return true;
      }

      OfflinePlayer target = this.resolvePlayer(args[1]);
      if (target == null) {
         sender.sendMessage("§cPlayer not found.");
         return true;
      }

      int amount;
      try {
         amount = Integer.parseInt(args[2]);
      } catch (NumberFormatException ex) {
         sender.sendMessage("§cAmount must be a whole number.");
         return true;
      }

      if (amount <= 0) {
         sender.sendMessage("§cAmount must be positive.");
         return true;
      }

      int newBalance = gems.addGems(target.getUniqueId(), amount);
      String targetName = this.displayName(target, args[1]);
      sender.sendMessage("§aAdded §b" + amount + " gems §ato §b" + targetName + "§a. New balance: §b" + newBalance + "§a.");
      if (target.isOnline() && target.getPlayer() != null) {
         target.getPlayer().sendMessage("§aYou received §b" + amount + " gems§a! Balance: §b" + newBalance);
      } else if (this.plugin.getNetworkNotificationManager() != null) {
         this.plugin.getNetworkNotificationManager().sendGemsAlert(target.getUniqueId(), "§aYou received §b" + amount + " gems§a! Balance: §b" + newBalance);
      }

      return true;
   }

   private boolean handlePay(CommandSender sender, GemManager gems, String[] args) {
      if (!(sender instanceof Player payer)) {
         sender.sendMessage("§cOnly players can pay gems.");
         return true;
      } else {
         if (args.length != 3) {
            sender.sendMessage("§cUsage: /gems pay <player> <amount>");
            return true;
         }

         OfflinePlayer target = this.resolvePlayer(args[1]);
         if (target == null) {
            sender.sendMessage("§cPlayer not found.");
            return true;
         }

         if (target.getUniqueId().equals(payer.getUniqueId())) {
            sender.sendMessage("§cYou can't pay gems to yourself.");
            return true;
         }

         int amount;
         try {
            amount = Integer.parseInt(args[2]);
         } catch (NumberFormatException ex) {
            sender.sendMessage("§cAmount must be a whole number.");
            return true;
         }

         if (amount <= 0) {
            sender.sendMessage("§cAmount must be positive.");
            return true;
         }

         int balance = gems.getGems(payer);
         if (balance < amount) {
            sender.sendMessage("§cYou only have §b" + balance + " gems§c.");
            return true;
         }

         gems.setGems(payer.getUniqueId(), balance - amount);
         gems.addGems(target.getUniqueId(), amount);
         payer.sendMessage("§aYou paid §b" + amount + " gems §ato §b" + this.displayName(target, args[1]) + "§a.");
         if (target.isOnline() && target.getPlayer() != null) {
            target.getPlayer().sendMessage("§b" + payer.getName() + " §apaid you §b" + amount + " gems§a!");
         } else if (this.plugin.getNetworkNotificationManager() != null) {
            this.plugin.getNetworkNotificationManager().sendGemsAlert(target.getUniqueId(), "§b" + payer.getName() + " §apaid you §b" + amount + " gems§a!");
         }

         return true;
      }
   }

   private OfflinePlayer resolvePlayer(String name) {
      if (this.plugin.getNetworkPlayerDirectory() != null) {
         OfflinePlayer resolved = this.plugin.getNetworkPlayerDirectory().resolveOfflinePlayer(name);
         if (resolved != null) {
            return resolved;
         }
      }

      OfflinePlayer target = Bukkit.getOfflinePlayer(name);
      return !target.hasPlayedBefore() && !target.isOnline() ? null : target;
   }

   private String displayName(OfflinePlayer player, String fallback) {
      String name = player.getName();
      if (name != null && !name.isBlank()) {
         return name;
      }

      NetworkPlayerDirectory.KnownPlayer known = this.plugin.getNetworkPlayerDirectory() == null
         ? null
         : this.plugin.getNetworkPlayerDirectory().find(fallback);
      return known == null ? fallback : known.name();
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1) {
         List<String> out = new ArrayList<>();
         String lower = args[0].toLowerCase();

         for (String sub : List.of("pay", "add")) {
            if (sub.startsWith(lower)) {
               out.add(sub);
            }
         }

         if (this.plugin.getNetworkPlayerDirectory() != null) {
            out.addAll(this.plugin.getNetworkPlayerDirectory().completeNames(args[0]));
         }

         return out;
      } else {
         return args.length == 2 && (args[0].equalsIgnoreCase("pay") || args[0].equalsIgnoreCase("add")) && this.plugin.getNetworkPlayerDirectory() != null
            ? this.plugin.getNetworkPlayerDirectory().completeNames(args[1])
            : List.of();
      }
   }
}
