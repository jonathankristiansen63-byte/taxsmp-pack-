package com.taxsmp;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class KeyallManager implements Listener {
   private static final int KEYALL_SECONDS = 3600;
   private final TaxSMP plugin;
   private ScheduledTask task;

   public KeyallManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.startTask();
   }

   private void startTask() {
      this.task = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this.plugin, task -> {
         for (Player player : Bukkit.getOnlinePlayers()) {
            this.tickPlayer(player);
         }
      }, 20L, 20L);
   }

   private void tickPlayer(Player player) {
      if (player != null && player.isOnline()) {
         int remaining = this.getRemainingSeconds(player);
         if (remaining <= 0) {
            this.giveRandomKey(player);
            this.setRemainingSeconds(player.getUniqueId(), 3600);
         } else {
            this.setRemainingSeconds(player.getUniqueId(), remaining - 1);
         }
      }
   }

   private void giveRandomKey(Player player) {
      String key = this.rollKey();
      String command = "givekey " + key + " " + player.getName() + " 1";
      Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
   }

   private String rollKey() {
      double roll = ThreadLocalRandom.current().nextDouble(100.0);
      if (roll < 98.9901) {
         return "Common";
      } else if (roll < 99.59004) {
         return "crimson";
      } else if (roll < 99.89001) {
         return "Spawner";
      } else {
         return roll < 99.99 ? "chicken" : "Tax";
      }
   }

   public int getRemainingSeconds(Player player) {
      return this.getRemainingSeconds(player.getUniqueId());
   }

   public int getRemainingSeconds(UUID uuid) {
      return this.plugin.getConfig().getInt(this.path(uuid), 3600);
   }

   private void setRemainingSeconds(UUID uuid, int seconds) {
      this.plugin.getConfig().set(this.path(uuid), Math.max(0, seconds));
   }

   private String path(UUID uuid) {
      return "players." + uuid + ".keyall_remaining";
   }

   public String formatRemaining(Player player) {
      int seconds = Math.max(0, this.getRemainingSeconds(player));
      int minutes = seconds / 60;
      int secs = seconds % 60;
      if (seconds >= 3600) {
         return "60m";
      } else {
         return secs == 0 ? minutes + "m" : minutes + "m " + secs + "s";
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      UUID uuid = event.getPlayer().getUniqueId();
      if (!this.plugin.getConfig().contains(this.path(uuid))) {
         this.setRemainingSeconds(uuid, 3600);
         this.plugin.saveConfig();
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.plugin.saveConfig();
   }

   public void shutdown() {
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }

      this.plugin.saveConfig();
   }
}
