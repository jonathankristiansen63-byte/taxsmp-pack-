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

public class EggsCommand implements CommandExecutor, TabCompleter {
   private final TaxSMP plugin;

   public EggsCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      EggManager eggManager = this.plugin.getEggManager();
      if (eggManager == null) {
         sender.sendMessage("§cEgg system is currently offline.");
         return true;
      }

      if (args.length == 0) {
         if (sender instanceof Player player) {
            int var9 = eggManager.getEggs(player);
            sender.sendMessage("§d" + player.getName() + " §fhas §d" + var9 + " eggs§f.");
            return true;
         } else {
            sender.sendMessage("§cConsole must use /eggs <player>.");
            return true;
         }
      } else if (args[0].equalsIgnoreCase("add")) {
         return this.handleAdd(sender, eggManager, args);
      } else if (!sender.isOp() && !sender.hasPermission("taxsmp.eggs.others")) {
         sender.sendMessage("§cYou do not have permission to check other players' eggs.");
         return true;
      } else {
         OfflinePlayer target = this.resolvePlayer(args[0]);
         if (target == null) {
            sender.sendMessage("§cPlayer not found.");
            return true;
         } else {
            int eggs = eggManager.getEggs(target);
            sender.sendMessage("§d" + this.displayName(target, args[0]) + " §fhas §d" + eggs + " eggs§f.");
            return true;
         }
      }
   }

   private boolean handleAdd(CommandSender sender, EggManager eggManager, String[] args) {
      if (!sender.isOp() && !sender.hasPermission("taxsmp.seteggs")) {
         sender.sendMessage("§cYou do not have permission to add eggs.");
         return true;
      }

      if (args.length != 3) {
         sender.sendMessage("§cUsage: /eggs add <player> <amount>");
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

      int newBalance = eggManager.addEggs(target.getUniqueId(), amount);
      String targetName = this.displayName(target, args[1]);
      sender.sendMessage("§aAdded §d" + amount + " eggs §ato §d" + targetName + "§a. New balance: §d" + newBalance + "§a.");
      if (target.isOnline() && target.getPlayer() != null) {
         target.getPlayer().sendMessage("§aYou received §d" + amount + " eggs§a! Balance: §d" + newBalance);
      } else if (this.plugin.getNetworkNotificationManager() != null) {
         this.plugin.getNetworkNotificationManager().sendEggsAlert(target.getUniqueId(), "§aYou received §d" + amount + " eggs§a! Balance: §d" + newBalance);
      }

      return true;
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
         if ("add".startsWith(args[0].toLowerCase())) {
            out.add("add");
         }

         if (this.plugin.getNetworkPlayerDirectory() != null) {
            out.addAll(this.plugin.getNetworkPlayerDirectory().completeNames(args[0]));
         }

         return out;
      } else {
         return args.length == 2 && args[0].equalsIgnoreCase("add") && this.plugin.getNetworkPlayerDirectory() != null
            ? this.plugin.getNetworkPlayerDirectory().completeNames(args[1])
            : List.of();
      }
   }
}
