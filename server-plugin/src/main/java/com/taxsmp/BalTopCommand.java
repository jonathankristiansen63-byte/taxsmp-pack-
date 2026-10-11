package com.taxsmp;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BalTopCommand implements CommandExecutor {
   private final TaxSMP plugin;
   private final LeaderboardCommand leaderboard;

   public BalTopCommand(TaxSMP plugin, LeaderboardCommand leaderboard) {
      this.plugin = plugin;
      this.leaderboard = leaderboard;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p) {
         int page = 1;
         if (args.length >= 1) {
            try {
               page = Math.max(1, Integer.parseInt(args[0]));
            } catch (Exception var8) {
            }
         }

         this.leaderboard.openLeaderboard(p, StatsTracker.StatType.MONEY, page);
         return true;
      } else {
         sender.sendMessage("Only players can use /baltop");
         return true;
      }
   }
}
