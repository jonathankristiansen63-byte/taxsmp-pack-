package com.taxsmp;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

public class SetEggsCommand implements CommandExecutor, TabCompleter {
   private final TaxSMP plugin;

   public SetEggsCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.isOp() && !sender.hasPermission("taxsmp.seteggs")) {
         sender.sendMessage("§cYou do not have permission to use this command.");
         return true;
      }

      if (args.length != 2) {
         sender.sendMessage("§cUsage: /seteggs <player> <amount>");
         return true;
      }

      EggManager eggManager = this.plugin.getEggManager();
      if (eggManager == null) {
         sender.sendMessage("§cEgg system is currently offline.");
         return true;
      }

      OfflinePlayer target = this.resolvePlayer(args[0]);
      if (target == null) {
         sender.sendMessage("§cPlayer not found.");
         return true;
      }

      int amount;
      try {
         amount = Integer.parseInt(args[1]);
      } catch (NumberFormatException ex) {
         sender.sendMessage("§cAmount must be a whole number.");
         return true;
      }

      if (amount < 0) {
         sender.sendMessage("§cAmount cannot be negative.");
         return true;
      }

      eggManager.setEggs(target, amount);
      sender.sendMessage("§aSet §d" + this.displayName(target, args[0]) + "§a's eggs to §d" + amount + "§a.");
      if (target.isOnline() && target.getPlayer() != null) {
         target.getPlayer().sendMessage("§aYour egg balance was set to §d" + amount + "§a.");
      } else if (this.plugin.getNetworkNotificationManager() != null) {
         this.plugin.getNetworkNotificationManager().sendEggsAlert(target.getUniqueId(), "§aYour egg balance was set to §d" + amount + "§a.");
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
      return args.length == 1 && this.plugin.getNetworkPlayerDirectory() != null ? this.plugin.getNetworkPlayerDirectory().completeNames(args[0]) : List.of();
   }
}
