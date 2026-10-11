package com.taxsmp.bounty;

import com.taxsmp.TaxSMP;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class SetBountyMapCommand implements CommandExecutor, TabCompleter {
   private final TaxSMP plugin;
   private final BountyMapService maps;

   public SetBountyMapCommand(TaxSMP plugin, BountyMapService maps) {
      this.plugin = plugin;
      this.maps = maps;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (!player.hasPermission("taxsmp.setbountymap")) {
            player.sendMessage("§cYou do not have permission to do that.");
            return true;
         }

         if (args.length > 0) {
            String sub = args[0].toLowerCase();
            if (sub.equals("remove") || sub.equals("delete")) {
               if (this.maps.removeNearestBoard(player, 32)) {
                  player.sendMessage("§7Took down the nearest bounty board.");
               } else {
                  player.sendMessage("§cThere is no bounty board within 32 blocks of you.");
               }

               return true;
            }

            if (sub.equals("removeall") || sub.equals("clear")) {
               int removed = this.maps.removeAllBoards();
               player.sendMessage("§7Took down §f" + removed + " §7bounty board(s).");
               return true;
            }

            if (sub.equals("list")) {
               player.sendMessage("§7There are §f" + this.maps.boardCount() + " §7bounty board(s) placed.");
               return true;
            }
         }

         int width = this.plugin.getConfig().getInt("bounty.board.width", 3);
         int height = this.plugin.getConfig().getInt("bounty.board.height", 3);
         if (args.length >= 1) {
            try {
               width = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
               player.sendMessage("§cUsage: /setbountymap [width] [height] | remove | removeall | list");
               return true;
            }
         }

         if (args.length >= 2) {
            try {
               height = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
               player.sendMessage("§cUsage: /setbountymap [width] [height] | remove | removeall | list");
               return true;
            }
         }

         player.sendMessage("§7Hanging the bounty board...");
         this.maps.placeBoard(player, width, height, message -> player.getScheduler().run(this.plugin, t -> player.sendMessage(message), null));
         return true;
      } else {
         sender.sendMessage("§cOnly players can place a bounty board.");
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1) {
         return List.of("3", "2", "4", "5", "remove", "removeall", "list");
      } else {
         return args.length == 2 ? List.of("3", "2", "4", "5") : List.of();
      }
   }
}
