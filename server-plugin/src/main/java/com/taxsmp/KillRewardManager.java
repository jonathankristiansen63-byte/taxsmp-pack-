package com.taxsmp;

import com.taxsmp.storage.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class KillRewardManager implements Listener {
   private static final String AFK_WORLD = "afk";
   private static final int EGGS_PER_KILL = 10;
   private static final long COOLDOWN_MS = TimeUnit.HOURS.toMillis(12L);
   private final TaxSMP plugin;
   private final Db db;
   private final Map<String, Long> lastReward = new ConcurrentHashMap<>();

   public KillRewardManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(plugin, task -> this.initDb());
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private void initDb() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_kill_rewards (killer VARCHAR(36) NOT NULL, victim VARCHAR(36) NOT NULL, last_reward BIGINT NOT NULL, PRIMARY KEY (killer, victim))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_kill_rewards table: " + e.getMessage());
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player victim = event.getEntity();
      if (!victim.getWorld().getName().equalsIgnoreCase("afk")) {
         CombatManager combat = this.plugin.getCombatManager();
         UUID killerId = combat == null ? null : combat.resolveKiller(victim);
         if (killerId == null) {
            Player direct = victim.getKiller();
            killerId = direct == null ? null : direct.getUniqueId();
         }

         if (killerId != null && !killerId.equals(victim.getUniqueId())) {
            Player killer = Bukkit.getPlayer(killerId);
            if (killer != null && killer.isOnline() && !killer.getWorld().getName().equalsIgnoreCase("afk")) {
               this.award(killer, victim.getUniqueId());
            }
         }
      }
   }

   private void award(Player killer, UUID victimId) {
      UUID killerId = killer.getUniqueId();
      String pair = killerId + ":" + victimId;
      long now = System.currentTimeMillis();
      Long cached = this.lastReward.get(pair);
      if (cached == null || now - cached >= COOLDOWN_MS) {
         if (!this.usingDb()) {
            this.lastReward.put(pair, now);
            this.payOut(killer);
         } else {
            Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
               if (this.claim(killerId, victimId, now)) {
                  this.lastReward.put(pair, now);
                  killer.getScheduler().run(this.plugin, t -> {
                     if (killer.isOnline()) {
                        this.payOut(killer);
                     }
                  }, null);
               }
            });
         }
      }
   }

   private boolean claim(UUID killerId, UUID victimId, long now) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO taxsmp_kill_rewards (killer, victim, last_reward) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE last_reward = IF(? - last_reward >= ?, ?, last_reward)"
         );
      ) {
         ps.setString(1, killerId.toString());
         ps.setString(2, victimId.toString());
         ps.setLong(3, now);
         ps.setLong(4, now);
         ps.setLong(5, COOLDOWN_MS);
         ps.setLong(6, now);
         return ps.executeUpdate() > 0;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not record kill reward for " + killerId + ": " + e.getMessage());
         return false;
      }
   }

   private void payOut(Player killer) {
      EggManager eggs = this.plugin.getEggManager();
      if (eggs != null) {
         eggs.addEggs(killer.getUniqueId(), 10);
         String message = ChatColor.RED + "+10 Eggs";
         killer.sendMessage(message);

         try {
            killer.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
         } catch (Exception var5) {
         }
      }
   }
}
