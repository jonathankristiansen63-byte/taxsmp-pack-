package com.taxsmp;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class ResetBlockStatsCommand implements CommandExecutor, Listener {
   private final TaxSMP plugin;

   public ResetBlockStatsCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (sender instanceof Player p && !p.hasPermission("taxsmp.resetblockstats")) {
         p.sendMessage("§cYou do not have permission.");
         return true;
      } else {
         sender.sendMessage("§eResetting block statistics for online players now; will also reset future joiners.");
         this.plugin.getConfig().set("reset-block-stats.pending", true);
         this.plugin.saveConfig();

         for (Player p : Bukkit.getOnlinePlayers()) {
            this.resetPlayerStats(p);
            this.plugin.getConfig().set("reset-block-stats.done." + p.getUniqueId().toString(), true);
         }

         this.plugin.saveConfig();
         sender.sendMessage("§aReset complete for online players.");
         return true;
      }
   }

   private void resetPlayerStats(Player p) {
      for (Material m : Material.values()) {
         if (!m.isLegacy()) {
            try {
               if (m.isBlock()) {
                  p.setStatistic(Statistic.MINE_BLOCK, m, 0);
               }
            } catch (Exception var8) {
            }

            try {
               p.setStatistic(Statistic.USE_ITEM, m, 0);
            } catch (Exception var7) {
            }
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      boolean pending = this.plugin.getConfig().getBoolean("reset-block-stats.pending", false);
      if (pending) {
         String key = "reset-block-stats.done." + p.getUniqueId().toString();
         boolean done = this.plugin.getConfig().getBoolean(key, false);
         if (!done) {
            this.resetPlayerStats(p);
            this.plugin.getConfig().set(key, true);
            this.plugin.saveConfig();
         }
      }
   }
}
