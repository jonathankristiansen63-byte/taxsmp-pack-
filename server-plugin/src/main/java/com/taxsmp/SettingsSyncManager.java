package com.taxsmp;

import com.taxsmp.storage.Db;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class SettingsSyncManager implements Listener {
   private static final long CACHE_TTL_MILLIS = 3000L;
   private final TaxSMP plugin;
   private final Db db;
   private final Map<String, SettingsSyncManager.CachedSetting> remoteCache = new ConcurrentHashMap<>();

   public SettingsSyncManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(plugin, t -> this.initDb());
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
            "CREATE TABLE IF NOT EXISTS taxsmp_player_settings (player_uuid VARCHAR(36) NOT NULL, setting_key VARCHAR(48) NOT NULL, value TINYINT(1) NOT NULL, updated_at BIGINT NOT NULL, PRIMARY KEY (player_uuid, setting_key))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not create player settings table: " + e.getMessage());
      }
   }

   public void push(UUID uuid, String key, boolean value) {
      if (this.usingDb() && uuid != null && key != null) {
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               t -> {
                  try (
                     Connection conn = this.db.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO taxsmp_player_settings (player_uuid, setting_key, value, updated_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE value = VALUES(value), updated_at = VALUES(updated_at)"
                     );
                  ) {
                     ps.setString(1, uuid.toString());
                     ps.setString(2, key);
                     ps.setBoolean(3, value);
                     ps.setLong(4, System.currentTimeMillis());
                     ps.executeUpdate();
                  } catch (SQLException e) {
                     this.plugin.getLogger().warning("Could not sync setting " + key + " for " + uuid + ": " + e.getMessage());
                  }
               }
            );
         this.remoteCache.put(cacheKey(uuid, key), new SettingsSyncManager.CachedSetting(value, System.currentTimeMillis()));
      }
   }

   public void fetchSetting(UUID uuid, String key, boolean fallback, Consumer<Boolean> callback) {
      if (uuid != null && key != null && this.usingDb()) {
         String cacheKey = cacheKey(uuid, key);
         SettingsSyncManager.CachedSetting cached = this.remoteCache.get(cacheKey);
         if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < 3000L) {
            callback.accept(cached.value());
         } else {
            Bukkit.getAsyncScheduler().runNow(this.plugin, t -> {
               boolean value = fallback;

               try (
                  Connection conn = this.db.getConnection();
                  PreparedStatement ps = conn.prepareStatement("SELECT value FROM taxsmp_player_settings WHERE player_uuid = ? AND setting_key = ?");
               ) {
                  ps.setString(1, uuid.toString());
                  ps.setString(2, key);

                  try (ResultSet rs = ps.executeQuery()) {
                     if (rs.next()) {
                        value = rs.getBoolean("value");
                     }
                  }

                  this.remoteCache.put(cacheKey, new SettingsSyncManager.CachedSetting(value, System.currentTimeMillis()));
               } catch (SQLException e) {
                  this.plugin.getLogger().warning("Could not read synced setting " + key + " for " + uuid + ": " + e.getMessage());
               }

               callback.accept(value);
            });
         }
      } else {
         callback.accept(fallback);
      }
   }

   private static String cacheKey(UUID uuid, String key) {
      return uuid + ":" + key;
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      if (this.usingDb()) {
         Player player = event.getPlayer();
         UUID uuid = player.getUniqueId();
         Bukkit.getAsyncScheduler().runNow(this.plugin, t -> {
            Map<String, Boolean> settings = new HashMap<>();

            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT setting_key, value FROM taxsmp_player_settings WHERE player_uuid = ?");
            ) {
               ps.setString(1, uuid.toString());

               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     settings.put(rs.getString("setting_key"), rs.getBoolean("value"));
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not load synced settings for " + uuid + ": " + e.getMessage());
               return;
            }

            if (!settings.isEmpty()) {
               Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
                  if (player.isOnline()) {
                     this.applySettings(player, settings);
                  }
               });
            }
         });
      }
   }

   private void applySettings(Player player, Map<String, Boolean> settings) {
      boolean changed = false;

      for (Entry<String, Boolean> entry : settings.entrySet()) {
         String path = "players." + player.getUniqueId() + "." + entry.getKey();
         if (!this.plugin.getConfig().contains(path) || this.plugin.getConfig().getBoolean(path) != entry.getValue()) {
            this.plugin.getConfig().set(path, entry.getValue());
            changed = true;
         }
      }

      if (changed) {
         this.plugin.requestConfigSave();
      }

      for (Entry<String, Boolean> entry : settings.entrySet()) {
         this.plugin.applySettingSideEffects(player, entry.getKey(), entry.getValue());
      }
   }

   private record CachedSetting(boolean value, long fetchedAt) {
   }
}
