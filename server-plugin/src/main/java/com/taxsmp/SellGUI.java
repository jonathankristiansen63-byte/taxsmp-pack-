package com.taxsmp;

import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

public class SellGUI implements Runnable {
   private final TaxSMP plugin;

   public SellGUI(TaxSMP plugin) {
      this.plugin = plugin;
   }

   @Override
   public void run() {
      for (Player p : Bukkit.getOnlinePlayers()) {
         p.getScheduler().run(this.plugin, task -> {
            Scoreboard b = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective obj = b.registerNewObjective("TaxSMP", "dummy", "§6§lTax§e§lSMP");
            obj.setDisplaySlot(DisplaySlot.SIDEBAR);
            String group = "MEMBER";
            if (this.plugin.getLuckPerms() != null) {
               User user = this.plugin.getLuckPerms().getUserManager().getUser(p.getUniqueId());
               if (user != null) {
                  group = user.getPrimaryGroup().equalsIgnoreCase("default") ? "MEMBER" : user.getPrimaryGroup().toUpperCase();
               }
            }

            double money = 0.0;
            if (this.plugin.getEconomy() != null) {
               money = this.plugin.getEconomy().getBalance(p);
            }

            obj.getScore("§f ").setScore(7);
            obj.getScore("RANK: §l" + group).setScore(6);
            obj.getScore("§c⚔ Kills: §l" + p.getStatistic(Statistic.PLAYER_KILLS)).setScore(5);
            obj.getScore("§6\ud83d\udc80 Deaths: §l" + p.getStatistic(Statistic.DEATHS)).setScore(4);
            obj.getScore("§a$ Money: §l" + (int)money).setScore(3);
            obj.getScore("§b⌛ Playtime: §l" + PlaytimeFormatter.compact(this.plugin.getStatsTracker() != null ? this.plugin.getStatsTracker().getStat(p.getUniqueId(), StatsTracker.StatType.PLAYTIME) : p.getStatistic(Statistic.PLAY_ONE_MINUTE))).setScore(2);
            p.setScoreboard(b);
         }, null);
      }
   }
}
