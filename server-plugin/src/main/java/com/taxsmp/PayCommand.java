package com.taxsmp;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class PayCommand implements CommandExecutor, TabCompleter {
   private final TaxSMP plugin;

   public PayCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         boolean globalPayments = this.plugin.getConfig().getBoolean("settings.payments", true);
         if (!globalPayments) {
            player.sendMessage("§cPayments are currently disabled on this server.");
            return true;
         }

         if (args.length < 2) {
            if (this.plugin.getMenus() != null && this.plugin.getMenus().enabledFor(player)) {
               this.plugin.getMenus().openPay(player);
               return true;
            } else {
               player.sendMessage("§c§l[!] §cUsage: /pay <player> <amount>");
               player.sendMessage("§7Example: /pay Notch 10k or /pay Notch 1.5m");
               return true;
            }
         } else {
            Player target = Bukkit.getPlayer(args[0]);
            NetworkPlayerDirectory.KnownPlayer known = this.plugin.getNetworkPlayerDirectory() == null
               ? null
               : this.plugin.getNetworkPlayerDirectory().find(args[0]);
            OfflinePlayer recipient = (OfflinePlayer)(target != null
               ? target
               : (known != null ? Bukkit.getOfflinePlayer(known.uuid()) : Bukkit.getOfflinePlayerIfCached(args[0])));
            if (recipient == null) {
               player.sendMessage("§c§l[!] §cThat player doesn't exist or has never joined.");
               return true;
            }

            if (recipient.getUniqueId().equals(player.getUniqueId())) {
               player.sendMessage("§c§l[!] §cYou cannot pay yourself, you weirdo.");
               return true;
            }

            double amount;
            try {
               amount = PriceManager.parseAmount(args[1]);
            } catch (NumberFormatException e) {
               player.sendMessage("§c§l[!] §cInvalid amount format! Use numbers (1000) or suffixes (1k, 1m, 1b).");
               return true;
            }

            if (amount < 0.01) {
               player.sendMessage("§c§l[!] §cThe minimum payment amount is $0.01.");
               return true;
            } else {
               SimpleEconomy econ = this.plugin.getEconomy();
               if (econ == null) {
                  player.sendMessage("§c§l[!] §cEconomy is unavailable.");
                  return true;
               } else {
                  double playerBalance = econ.getBalance(player);
                  if (playerBalance < amount) {
                     player.sendMessage("§c§l[!] §cYou don't have enough money! Too bad.");
                     player.sendMessage("§7Your Balance: §f$" + PriceManager.formatShort(playerBalance));
                     return true;
                  } else {
                     boolean localAnswer = this.plugin.isPaymentsEnabledFor(recipient.getUniqueId());
                     SettingsSyncManager sync = this.plugin.getSettingsSyncManager();
                     if (target == null && sync != null) {
                        double finalAmount = amount;
                        sync.fetchSetting(recipient.getUniqueId(), "payments", localAnswer, allowed -> player.getScheduler().run(this.plugin, t -> {
                           if (player.isOnline()) {
                              if (!Boolean.TRUE.equals(allowed)) {
                                 this.denyDisabled(player);
                              } else {
                                 this.completePayment(player, recipient, null, known, finalAmount, args[0]);
                              }
                           }
                        }, null));
                        return true;
                     } else if (!localAnswer) {
                        this.denyDisabled(player);
                        return true;
                     } else {
                        this.completePayment(player, recipient, target, known, amount, args[0]);
                        return true;
                     }
                  }
               }
            }
         }
      } else {
         if (sender != null) {
            sender.sendMessage("§cOnly players can use the economy system!");
         }

         return true;
      }
   }

   private void denyDisabled(Player player) {
      player.sendMessage("§cThis user disabled payments.");
      player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
   }

   private void completePayment(
      Player player, OfflinePlayer recipient, Player target, NetworkPlayerDirectory.KnownPlayer known, double amount, String typedName
   ) {
      try {
         SimpleEconomy econ = this.plugin.getEconomy();
         if (econ == null) {
            player.sendMessage("§c§l[!] §cEconomy is unavailable.");
            return;
         }

         if (!econ.withdrawPlayer(player, amount)) {
            player.sendMessage("§c§l[!] §cYou don't have enough money! Too bad.");
            player.sendMessage("§7Your Balance: §f$" + PriceManager.formatShort(econ.getBalance(player)));
            return;
         }

         econ.depositPlayer(recipient, amount);
         String formattedAmount = PriceManager.formatShort(amount);
         String mint = "§x§5§5§F§F§B§8";
         String targetName = recipient.getName() != null ? recipient.getName() : (known != null ? known.name() : typedName);
         player.sendMessage("§7You paid §e" + targetName + " " + mint + "$" + formattedAmount);
         if (target != null) {
            target.sendMessage("§e" + player.getName() + " §7paid you " + mint + "$" + formattedAmount);
         } else if (known == null
            || !known.online()
            || this.plugin.getNetworkNotificationManager() == null
            || !this.plugin
               .getNetworkNotificationManager()
               .sendPayAlert(recipient.getUniqueId(), "§e" + player.getName() + " §7paid you " + mint + "$" + formattedAmount)) {
            this.plugin.getOfflinePayManager().addPending(recipient.getUniqueId(), amount);
         }
      } catch (Exception e) {
         player.sendMessage("§c§l[!] §cAn internal error occurred during the transaction.");
         e.printStackTrace();
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      return args.length == 1 && this.plugin.getNetworkPlayerDirectory() != null
         ? this.plugin.getNetworkPlayerDirectory().completeOnlineNames(args[0])
         : List.of();
   }
}
