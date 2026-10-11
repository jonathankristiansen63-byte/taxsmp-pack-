package com.taxsmp.bounty;

import com.taxsmp.NetworkPlayerDirectory;
import com.taxsmp.PriceManager;
import com.taxsmp.SimpleEconomy;
import com.taxsmp.TaxSMP;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class BountyCommand implements CommandExecutor, TabCompleter {
   private static final String RED = BountyManager.RED;
   private static final String RED_BOLD = BountyManager.RED_BOLD;
   private static final String MINT = "§x§5§5§F§F§B§8";
   private static final List<String> SUBCOMMANDS = List.of("add", "top", "list", "poster", "board", "map", "remove", "cancel", "help");
   private final TaxSMP plugin;
   private final BountyManager bounties;
   private final BountyMapService maps;

   public BountyCommand(TaxSMP plugin, BountyManager bounties, BountyMapService maps) {
      this.plugin = plugin;
      this.bounties = bounties;
      this.maps = maps;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (args.length == 0) {
         if (sender instanceof Player player) {
            this.maps.giveBoard(player);
         } else {
            this.showTop(sender);
         }

         return true;
      } else {
         String sub = args[0].toLowerCase();
         switch (sub) {
            case "add":
            case "set":
            case "place":
               return this.handleAdd(sender, args);
            case "top":
            case "list":
               this.showTop(sender);
               return true;
            case "board":
            case "map":
               if (sender instanceof Player player) {
                  this.maps.giveBoard(player);
               } else {
                  sender.sendMessage("§cOnly players can hold a map.");
               }

               return true;
            case "poster":
               return this.handlePoster(sender, args);
            case "remove":
            case "cancel":
               return this.handleRemove(sender, args);
            case "help":
            case "?":
               this.showHelp(sender);
               return true;
            default:
               return this.showPlayer(sender, args[0]);
         }
      }
   }

   private boolean showPlayer(CommandSender sender, String rawName) {
      BountyCommand.Target target = this.resolve(rawName);
      if (target == null) {
         sender.sendMessage(RED + "That player doesn't exist or has never joined.");
         return true;
      }

      UUID id = target.uuid();
      String name = target.name();
      double amount = this.bounties.total(id);
      if (amount <= 0.0) {
         sender.sendMessage("§f" + name + " §7has no bounty on their head.");
         return true;
      }

      sender.sendMessage("§f" + name + " §8- §x§5§5§F§F§B§8$" + PriceManager.formatShort(amount));
      if (sender instanceof Player player) {
         player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
         if (this.plugin.getConfig().getBoolean("bounty.poster-on-lookup", true)) {
            this.maps.givePoster(player, id, name);
         }
      }

      return true;
   }

   private void showTop(CommandSender sender) {
      List<BountyManager.Entry> top = this.bounties.top(this.bounties.topSize());
      sender.sendMessage(RED_BOLD + "--------- MOST WANTED ---------");
      if (top.isEmpty()) {
         sender.sendMessage("§7Nobody has a bounty. How peaceful.");
      } else {
         for (int i = 0; i < top.size(); i++) {
            BountyManager.Entry entry = top.get(i);
            sender.sendMessage("§7" + (i + 1) + ". " + RED_BOLD + entry.name() + " §8- §x§5§5§F§F§B§8$" + PriceManager.formatShort(entry.amount()));
         }
      }

      sender.sendMessage(RED_BOLD + "-------------------------------");
   }

   private void showHelp(CommandSender sender) {
      sender.sendMessage(RED_BOLD + "Bounties");
      sender.sendMessage("§7/bounty §8- §fthe most wanted board, as a map");
      sender.sendMessage("§7/bounty <player> §8- §fwhat they are worth");
      sender.sendMessage("§7/bounty add <player> <amount> §8- §fput money on their head");
      sender.sendMessage("§7/bounty poster <player> §8- §ftheir wanted poster, as a map");
      sender.sendMessage("§7/bounty top §8- §fthe top " + this.bounties.topSize());
      if (sender.hasPermission("taxsmp.bounty.admin")) {
         sender.sendMessage("§7/bounty remove <player> §8- §fcancel and refund");
         sender.sendMessage("§7/setbountymap [width] [height] §8- §fhang a floating board");
      }
   }

   private boolean handleAdd(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (!player.hasPermission("taxsmp.bounty.place")) {
            player.sendMessage(RED + "You do not have permission to place bounties.");
            return true;
         }

         if (args.length < 3) {
            player.sendMessage(RED + "Usage: /bounty add <player> <amount>");
            player.sendMessage("§7Example: §f/bounty add " + player.getName() + " 10k");
            return true;
         }

         BountyCommand.Target target = this.resolve(args[1]);
         if (target == null) {
            player.sendMessage(RED + "That player doesn't exist or has never joined.");
            return true;
         }

         UUID targetId = target.uuid();
         if (targetId.equals(player.getUniqueId()) && !this.bounties.allowSelfBounty()) {
            player.sendMessage(RED + "You cannot put a bounty on yourself.");
            return true;
         }

         double amount;
         try {
            amount = PriceManager.parseAmount(args[2]);
         } catch (NumberFormatException e) {
            player.sendMessage(RED + "Invalid amount. Use numbers (1000) or suffixes (1k, 1m, 1b).");
            return true;
         }

         double minimum = this.bounties.minimumBounty();
         if (amount < minimum) {
            player.sendMessage(RED + "The smallest bounty you can place is $" + PriceManager.formatShort(minimum) + ".");
            return true;
         } else {
            double maximum = this.bounties.maximumBounty();
            if (maximum > 0.0 && this.bounties.total(targetId) + amount > maximum) {
               player.sendMessage(RED + "A bounty cannot go above $" + PriceManager.formatShort(maximum) + ".");
               return true;
            } else {
               SimpleEconomy econ = this.plugin.getEconomy();
               if (econ == null) {
                  player.sendMessage(RED + "Economy is unavailable.");
                  return true;
               } else if (!econ.withdrawPlayer(player, amount)) {
                  player.sendMessage(RED + "You don't have $" + PriceManager.formatShort(amount) + ".");
                  player.sendMessage("§7Your balance: §f$" + PriceManager.formatShort(econ.getBalance(player)));
                  return true;
               } else {
                  String targetName = target.name();
                  this.bounties.rememberName(player.getUniqueId(), player.getName());
                  this.bounties.add(targetId, targetName, player.getUniqueId(), amount);
                  String formatted = PriceManager.formatShort(amount);
                  player.sendMessage("§7You added a §x§5§5§F§F§B§8$" + formatted + " §7bounty to §f" + targetName + "§7's head.");
                  player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 0.8F);
                  this.bounties.alert(targetId, "§f" + player.getName() + " §7added a §x§5§5§F§F§B§8$" + formatted + " §7bounty to your head.");
                  return true;
               }
            }
         }
      } else {
         sender.sendMessage("§cOnly players can place bounties.");
         return true;
      }
   }

   private boolean handlePoster(CommandSender sender, String[] args) {
      if (sender instanceof Player player) {
         if (args.length < 2) {
            player.sendMessage(RED + "Usage: /bounty poster <player>");
            return true;
         } else {
            BountyCommand.Target target = this.resolve(args[1]);
            if (target == null) {
               player.sendMessage(RED + "That player doesn't exist or has never joined.");
               return true;
            } else {
               this.maps.givePoster(player, target.uuid(), target.name());
               return true;
            }
         }
      } else {
         sender.sendMessage("§cOnly players can hold a map.");
         return true;
      }
   }

   private boolean handleRemove(CommandSender sender, String[] args) {
      if (!sender.hasPermission("taxsmp.bounty.admin")) {
         sender.sendMessage(RED + "You do not have permission to do that.");
         return true;
      } else if (args.length < 2) {
         sender.sendMessage(RED + "Usage: /bounty remove <player>");
         return true;
      } else {
         BountyCommand.Target target = this.resolve(args[1]);
         if (target == null) {
            sender.sendMessage(RED + "That player doesn't exist or has never joined.");
            return true;
         } else {
            UUID id = target.uuid();
            if (this.bounties.total(id) <= 0.0) {
               sender.sendMessage(RED + "There is no bounty on them.");
               return true;
            } else {
               String name = target.name();
               this.bounties
                  .refund(
                     id,
                     refunded -> {
                        if (refunded <= 0.0) {
                           this.reply(sender, RED + "That bounty had already been claimed.");
                        } else {
                           this.reply(
                              sender,
                              "§7Cancelled the §x§5§5§F§F§B§8$"
                                 + PriceManager.formatShort(refunded)
                                 + " §7bounty on §f"
                                 + name
                                 + " §7and refunded everyone who paid into it."
                           );
                           this.bounties.alert(id, "§7The bounty on your head was cancelled.");
                        }
                     }
                  );
               return true;
            }
         }
      }
   }

   private void reply(CommandSender sender, String message) {
      if (sender instanceof Player player) {
         player.getScheduler().run(this.plugin, task -> player.sendMessage(message), null);
      } else {
         sender.sendMessage(message);
      }
   }

   private BountyCommand.Target resolve(String name) {
      Player online = Bukkit.getPlayerExact(name);
      if (online != null) {
         return new BountyCommand.Target(online.getUniqueId(), online.getName());
      }

      NetworkPlayerDirectory directory = this.plugin.getNetworkPlayerDirectory();
      if (directory != null) {
         NetworkPlayerDirectory.KnownPlayer known = directory.find(name);
         if (known != null) {
            this.bounties.rememberName(known.uuid(), known.name());
            return new BountyCommand.Target(known.uuid(), known.name());
         }
      }

      OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
      if (cached != null) {
         String cachedName = cached.getName();
         return new BountyCommand.Target(cached.getUniqueId(), cachedName != null ? cachedName : name);
      } else {
         return null;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1) {
         List<String> out = new ArrayList<>();
         String prefix = args[0].toLowerCase();

         for (String sub : SUBCOMMANDS) {
            if (sub.startsWith(prefix)) {
               out.add(sub);
            }
         }

         for (BountyManager.Entry entry : this.bounties.top(this.bounties.topSize())) {
            if (entry.name().toLowerCase().startsWith(prefix)) {
               out.add(entry.name());
            }
         }

         if (this.plugin.getNetworkPlayerDirectory() != null) {
            out.addAll(this.plugin.getNetworkPlayerDirectory().completeOnlineNames(args[0]));
         }

         return out;
      } else {
         if (args.length == 2 && this.plugin.getNetworkPlayerDirectory() != null) {
            String sub = args[0].toLowerCase();
            if (SUBCOMMANDS.contains(sub) && !sub.equals("top") && !sub.equals("list") && !sub.equals("help")) {
               return this.plugin.getNetworkPlayerDirectory().completeOnlineNames(args[1]);
            }
         }

         return args.length != 3 || !args[0].equalsIgnoreCase("add") && !args[0].equalsIgnoreCase("place")
            ? List.of()
            : List.of("100", "1k", "10k", "100k", "1m");
      }
   }

   private record Target(UUID uuid, String name) {
   }
}
