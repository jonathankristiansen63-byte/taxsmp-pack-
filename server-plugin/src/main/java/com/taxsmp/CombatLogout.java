package com.taxsmp;

import com.taxsmp.storage.Db;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

public class CombatLogout implements Listener {
   private static final long FLAG_TTL_MS = 604800000L;
   private static final long PURGE_INTERVAL_MS = 3600000L;
   private final TaxSMP plugin;
   private final Db db;
   private final Set<UUID> localFlags = ConcurrentHashMap.newKeySet();
   private final Set<UUID> arriving = ConcurrentHashMap.newKeySet();
   private volatile long lastPurge = 0L;

   public CombatLogout(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         this.initDb();
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
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_combat_logouts (uuid CHAR(36) NOT NULL PRIMARY KEY, created_at BIGINT NOT NULL)");
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_combat_logouts table: " + e.getMessage());
      }
   }

   public boolean punish(Player player) {
      if (player != null && !player.isDead()) {
         try {
            player.setHealth(0.0);
         } catch (Throwable t) {
            this.plugin.getLogger().warning("Could not kill combat logger " + player.getName() + ": " + t.getMessage());
            return false;
         }

         this.leaveCleanSaveState(player);
         this.flag(player.getUniqueId());
         return true;
      } else {
         return false;
      }
   }

   private void leaveCleanSaveState(Player player) {
      try {
         for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
         }

         player.setFireTicks(0);
         player.setFreezeTicks(0);
         player.setFallDistance(0.0F);
         player.setVelocity(new Vector(0, 0, 0));
         player.setRemainingAir(player.getMaximumAir());
         player.setFoodLevel(20);
         player.setSaturation(5.0F);
         player.setExhaustion(0.0F);
         player.setHealth(maxHealth(player));
      } catch (Throwable t) {
         this.plugin.getLogger().warning("Could not reset state for combat logger " + player.getName() + ": " + t.getMessage());
      }
   }

   private static double maxHealth(Player player) {
      try {
         AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
         if (attribute != null && attribute.getValue() > 0.0) {
            return attribute.getValue();
         }
      } catch (Throwable var2) {
      }

      return 20.0;
   }

   private void flag(UUID uuid) {
      this.localFlags.add(uuid);
      if (this.usingDb()) {
         long now = System.currentTimeMillis();
         this.db
            .writeIdempotent(
               uuid,
               "INSERT INTO taxsmp_combat_logouts (uuid, created_at) VALUES (?, ?) ON DUPLICATE KEY UPDATE created_at = VALUES(created_at)",
               uuid.toString(),
               now
            );
      }
   }

   @EventHandler
   public void onPreLogin(AsyncPlayerPreLoginEvent event) {
      UUID uuid = event.getUniqueId();
      if (this.localFlags.contains(uuid) || this.isFlaggedInDb(uuid)) {
         this.arriving.add(uuid);
      }

      this.purgeExpired();
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
      UUID uuid = event.getConnection().getProfile().getId();
      if (uuid != null && this.arriving.contains(uuid)) {
         Location current = event.getSpawnLocation();
         Location spawn = SpawnHook.spawnFor(current == null ? null : current.getWorld());
         if (spawn != null) {
            event.setSpawnLocation(spawn);
         }
      }
   }

   @EventHandler(priority = EventPriority.NORMAL)
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      UUID uuid = player.getUniqueId();
      if (this.arriving.remove(uuid) || this.localFlags.contains(uuid)) {
         this.clearFlag(uuid);
         player.getScheduler().run(this.plugin, task -> {
            if (player.isOnline()) {
               Location spawn = SpawnHook.spawnFor(player);
               if (spawn != null && !isAt(player.getLocation(), spawn)) {
                  player.teleportAsync(spawn);
               }

               if (player.getHealth() <= 0.0) {
                  player.setHealth(maxHealth(player));
               }

               player.sendMessage("§cYou logged out in combat, so you died. §7You have been returned to spawn.");
            }
         }, null);
      }
   }

   private static boolean isAt(Location where, Location target) {
      return where != null && where.getWorld() != null && where.getWorld().equals(target.getWorld()) && where.distanceSquared(target) <= 4.0;
   }

   private boolean isFlaggedInDb(UUID uuid) {
      if (this.usingDb() && this.db.isHealthy()) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_combat_logouts WHERE uuid = ?");
         ) {
            ps.setString(1, uuid.toString());

            try (ResultSet rs = ps.executeQuery()) {
               return rs.next();
            }
         } catch (SQLException e) {
            return false;
         }
      } else {
         return false;
      }
   }

   private void clearFlag(UUID uuid) {
      this.localFlags.remove(uuid);
      if (this.usingDb()) {
         this.db.writeIdempotent(uuid, "DELETE FROM taxsmp_combat_logouts WHERE uuid = ?", uuid.toString());
      }
   }

   private void purgeExpired() {
      long now = System.currentTimeMillis();
      if (now - this.lastPurge >= 3600000L && this.usingDb()) {
         this.lastPurge = now;
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_combat_logouts WHERE created_at < ?");
            ) {
               ps.setLong(1, now - 604800000L);
               ps.executeUpdate();
            } catch (SQLException var12) {
            }
         });
      }
   }
}
