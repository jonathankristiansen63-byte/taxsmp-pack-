package com.taxsmp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class TaxMoneyCommand implements CommandExecutor, TabCompleter {
   private static final String PERM = "taxsmp.taxmoney";
   private final TaxSMP plugin;

   public TaxMoneyCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p && !p.hasPermission("taxsmp.taxmoney") && !p.isOp()) {
         p.sendMessage("§c§l[!] §cYou do not have permission.");
         return true;
      } else {
         if (args.length < 3) {
            sender.sendMessage("§c§l[!] §cUsage: /taxmoney <add|set|take> <player> <amount>");
            sender.sendMessage("§7Example: /taxmoney add Notch 10k");
            return true;
         }

         String action = args[0].toLowerCase();
         if (!action.equals("add") && !action.equals("set") && !action.equals("take")) {
            sender.sendMessage("§c§l[!] §cUnknown action. Use add, set or take.");
            return true;
         }

         OfflinePlayer target = this.resolvePlayer(args[1]);
         if (target == null) {
            sender.sendMessage("§c§l[!] §cThat player doesn't exist.");
            return true;
         }

         double amount;
         try {
            amount = PriceManager.parseAmount(args[2]);
         } catch (NumberFormatException e) {
            sender.sendMessage("§c§l[!] §cInvalid amount format! Use numbers (1000) or suffixes (1k, 1m, 1b).");
            return true;
         }

         if (amount < 0.0) {
            sender.sendMessage("§c§l[!] §cAmount cannot be negative.");
            return true;
         }

         SimpleEconomy econ = this.plugin.getEconomy();
         if (econ == null) {
            sender.sendMessage("§c§l[!] §cEconomy is unavailable.");
            return true;
         }

         String targetName = this.displayName(target, args[1]);
         Player onlineTarget = target.isOnline() ? target.getPlayer() : null;
         switch (action) {
            case "add": {
               econ.depositPlayer(target, amount);
               String formatted = PriceManager.formatPrice(amount);
               sender.sendMessage("§a§l[✓] §aAdded §6$" + formatted + " §ato " + targetName + "'s balance.");
               String mint = "§x§5§5§F§F§B§8";
               if (onlineTarget != null) {
                  onlineTarget.sendMessage(mint + "+$" + formatted);
                  onlineTarget.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(mint + "+$" + formatted));
                  onlineTarget.playSound(onlineTarget.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
               } else if (this.plugin.getNetworkNotificationManager() != null) {
                  this.plugin.getNetworkNotificationManager().sendBalanceAlert(target.getUniqueId(), mint + "+$" + formatted);
               }
               break;
            }
            case "set": {
               double current = econ.getBalance(target);
               if (current > amount) {
                  econ.withdrawPlayer(target, current - amount);
               } else if (current < amount) {
                  econ.depositPlayer(target, amount - current);
               }

               String formatted = PriceManager.formatPrice(amount);
               sender.sendMessage("§a§l[✓] §aSet " + targetName + "'s balance to §6$" + formatted + "§a.");
               if (onlineTarget != null) {
                  onlineTarget.sendMessage("§eYour balance was set to §6$" + formatted);
                  onlineTarget.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§eBalance: §6$" + formatted));
               } else if (this.plugin.getNetworkNotificationManager() != null) {
                  this.plugin.getNetworkNotificationManager().sendBalanceAlert(target.getUniqueId(), "§eYour balance was set to §6$" + formatted);
               }
               break;
            }
            case "take": {
               double current = econ.getBalance(target);
               double taken = Math.min(current, amount);
               if (taken > 0.0) {
                  econ.withdrawPlayer(target, taken);
               }

               String formatted = PriceManager.formatPrice(taken);
               sender.sendMessage("§a§l[✓] §aTook §6$" + formatted + " §afrom " + targetName + "'s balance.");
               if (onlineTarget != null) {
                  onlineTarget.sendMessage("§c-$" + formatted);
                  onlineTarget.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§c-$" + formatted));
               } else if (this.plugin.getNetworkNotificationManager() != null) {
                  this.plugin.getNetworkNotificationManager().sendBalanceAlert(target.getUniqueId(), "§c-$" + formatted);
               }
            }
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
      if (sender instanceof Player p && !p.hasPermission("taxsmp.taxmoney") && !p.isOp()) {
         return new ArrayList<>();
      } else if (args.length == 1) {
         List<String> out = new ArrayList<>();

         for (String s : Arrays.asList("add", "set", "take")) {
            if (s.startsWith(args[0].toLowerCase())) {
               out.add(s);
            }
         }

         return out;
      } else {
         return args.length == 2 && this.plugin.getNetworkPlayerDirectory() != null
            ? this.plugin.getNetworkPlayerDirectory().completeNames(args[1])
            : new ArrayList<>();
      }
   }
}
