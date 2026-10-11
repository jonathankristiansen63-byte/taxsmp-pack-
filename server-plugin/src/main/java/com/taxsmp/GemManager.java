package com.taxsmp;

import com.taxsmp.storage.Db;
import com.taxsmp.storage.SnapshotManager;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

public class GemManager {
   private final TaxSMP plugin;
   private final Db db;
   private final ConcurrentHashMap<UUID, Integer> cache = new ConcurrentHashMap<>();
   private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
   private ScheduledTask dbRefreshTask;

   public GemManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         if (this.db.isHealthy()) {
            this.db.async(this::initDb);
         }

         this.db.onReconnect(this::reloadFromDb);
         this.dbRefreshTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> this.refreshOnlineFromDb(), 5L, 15L, TimeUnit.SECONDS);
      }
   }

   private boolean tableHasRows() {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_gems LIMIT 1");
         ResultSet rs = ps.executeQuery();
      ) {
         return rs.next();
      } catch (SQLException e) {
         return true;
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private void createTable() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_gems (uuid VARCHAR(36) PRIMARY KEY, gems INT NOT NULL)");
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_gems table: " + e.getMessage());
      }
   }

   private void initDb() {
      this.createTable();
      ConfigurationSection players = this.plugin.getConfig().getConfigurationSection("players");
      if (players != null && !this.tableHasRows()) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
               "INSERT INTO taxsmp_gems (uuid, gems) VALUES (?, ?) ON DUPLICATE KEY UPDATE gems = GREATEST(gems, VALUES(gems))"
            );
         ) {
            int n = 0;

            for (String key : players.getKeys(false)) {
               int g = this.plugin.getConfig().getInt("players." + key + ".gems", 0);
               if (g > 0) {
                  try {
                     ps.setString(1, UUID.fromString(key).toString());
                     ps.setInt(2, g);
                     ps.addBatch();
                     n++;
                  } catch (IllegalArgumentException var15) {
                  }
               }
            }

            if (n > 0) {
               ps.executeBatch();
               this.plugin.getLogger().info("Merged " + n + " gem balances from config into MySQL.");
            }
         } catch (SQLException e) {
            this.plugin.getLogger().severe("Gem migration failed: " + e.getMessage());
         }
      }

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT uuid, gems FROM taxsmp_gems");
         ResultSet rs = ps.executeQuery();
      ) {
         while (rs.next()) {
            try {
               this.cache.put(UUID.fromString(rs.getString("uuid")), rs.getInt("gems"));
            } catch (IllegalArgumentException var12) {
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not load gems from MySQL: " + e.getMessage());
      }
   }

   private void refreshOnlineFromDb() {
      if (this.usingDb() && this.db.isHealthy()) {
         List<UUID> ids = new ArrayList<>();

         for (Player player : Bukkit.getOnlinePlayers()) {
            if (!this.db.hasPendingFor(player.getUniqueId())) {
               ids.add(player.getUniqueId());
            }
         }

         if (!ids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT uuid, gems FROM taxsmp_gems WHERE uuid IN (" + placeholders + ")");
            ) {
               for (int i = 0; i < ids.size(); i++) {
                  ps.setString(i + 1, ids.get(i).toString());
               }

               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     try {
                        UUID id = UUID.fromString(rs.getString("uuid"));
                        if (!this.db.hasPendingFor(id)) {
                           this.cache.put(id, rs.getInt("gems"));
                        }
                     } catch (IllegalArgumentException var11) {
                     }
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh online gem balances: " + e.getMessage());
            }
         }
      }
   }

   public int getGems(Player player) {
      return this.getGems(player.getUniqueId());
   }

   public int getGems(OfflinePlayer player) {
      return this.getGems(player.getUniqueId());
   }

   public int getGems(UUID uuid) {
      if (this.usingDb()) {
         Integer cached = this.cache.get(uuid);
         if (cached != null) {
            return cached;
         }

         this.loadFromDb(uuid);
         return 0;
      } else {
         return this.plugin.getConfig().getInt("players." + uuid + ".gems", 0);
      }
   }

   public void setGems(OfflinePlayer player, int amount) {
      this.setGems(player.getUniqueId(), amount);
   }

   public void setGems(UUID uuid, int amount) {
      int val = Math.max(0, amount);
      if (this.usingDb()) {
         this.cache.put(uuid, val);
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> this.db
                  .writeIdempotent(uuid, "INSERT INTO taxsmp_gems (uuid, gems) VALUES (?, ?) ON DUPLICATE KEY UPDATE gems = VALUES(gems)", uuid.toString(), val)
            );
      } else {
         this.plugin.getConfig().set("players." + uuid + ".gems", val);
         this.plugin.saveConfig();
      }
   }

   public void addGems(OfflinePlayer player, int amount) {
      this.addGems(player.getUniqueId(), amount);
   }

   public int addGems(UUID uuid, int amount) {
      if (amount == 0) {
         return this.getGems(uuid);
      } else if (this.usingDb()) {
         int value = this.cache.merge(uuid, amount, (oldValue, delta) -> Math.max(0, oldValue + delta));
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> this.db
                  .writeDelta(
                     uuid,
                     "INSERT INTO taxsmp_gems (uuid, gems) VALUES (?, GREATEST(0, ?)) ON DUPLICATE KEY UPDATE gems = GREATEST(0, gems + ?)",
                     uuid.toString(),
                     amount,
                     amount
                  )
            );
         return value;
      } else {
         int value = Math.max(0, this.getGems(uuid) + amount);
         this.setGems(uuid, value);
         return value;
      }
   }

   public void loadFromDb(UUID uuid) {
      if (this.usingDb() && this.loading.add(uuid)) {
         this.db.async(() -> {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT gems FROM taxsmp_gems WHERE uuid = ?");
            ) {
               ps.setString(1, uuid.toString());

               try (ResultSet rs = ps.executeQuery()) {
                  if (rs.next()) {
                     this.cache.put(uuid, rs.getInt("gems"));
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh gems for " + uuid + ": " + e.getMessage());
            } finally {
               this.loading.remove(uuid);
            }
         });
      }
   }

   public void reloadFromDb() {
      if (this.usingDb()) {
         this.createTable();

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT uuid, gems FROM taxsmp_gems");
            ResultSet rs = ps.executeQuery();
         ) {
            int n = 0;

            while (rs.next()) {
               try {
                  UUID id = UUID.fromString(rs.getString("uuid"));
                  if (!this.db.hasPendingFor(id)) {
                     this.cache.put(id, rs.getInt("gems"));
                     n++;
                  }
               } catch (IllegalArgumentException var9) {
               }
            }

            this.plugin.getLogger().info("Reloaded " + n + " gem balances from MySQL after reconnect.");
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Could not reload gems after reconnect: " + e.getMessage());
         }
      }
   }

   public SnapshotManager.Section snapshotSection() {
      return new SnapshotManager.Section() {
         @Override
         public String sectionName() {
            return "gems";
         }

         @Override
         public void writeSnapshot(ConfigurationSection out) {
            for (Entry<UUID, Integer> e : GemManager.this.cache.entrySet()) {
               out.set(e.getKey().toString(), e.getValue());
            }
         }

         @Override
         public void readSnapshot(ConfigurationSection in) {
            int n = 0;

            for (String key : in.getKeys(false)) {
               try {
                  GemManager.this.cache.put(UUID.fromString(key), in.getInt(key));
                  n++;
               } catch (IllegalArgumentException var6) {
               }
            }

            GemManager.this.plugin.getLogger().warning("Restored " + n + " gem balances from local snapshot.");
         }
      };
   }
}
