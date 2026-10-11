package com.taxsmp;

import com.taxsmp.storage.Db;
import com.taxsmp.storage.SnapshotManager;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class SimpleEconomy {
   private static final long REFRESH_SECONDS = 3L;
   private static final long LOCAL_WRITE_GRACE_MILLIS = 3000L;
   private final JavaPlugin plugin;
   private final Db db;
   private final File dataFile;
   private FileConfiguration cfg;
   private final ConcurrentHashMap<UUID, Double> cache = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, Long> lastLocalChange = new ConcurrentHashMap<>();

   public SimpleEconomy(JavaPlugin plugin, Db db) {
      this.plugin = plugin;
      this.db = db;
      this.dataFile = new File(plugin.getDataFolder(), "balances.yml");
      if (!this.dataFile.exists()) {
         try {
            plugin.getDataFolder().mkdirs();
            this.dataFile.createNewFile();
         } catch (IOException var7) {
         }
      }

      this.cfg = YamlConfiguration.loadConfiguration(this.dataFile);
      if (this.usingDb()) {
         if (db.isHealthy()) {
            this.db.async(this::initDb);
         }

         db.onReconnect(this::reloadFromDb);
         Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> this.refreshOnlineBalances(), 3L, 3L, TimeUnit.SECONDS);
      } else {
         for (String key : this.cfg.getKeys(false)) {
            try {
               this.cache.put(UUID.fromString(key), this.cfg.getDouble(key));
            } catch (IllegalArgumentException var6) {
            }
         }
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
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_balances (uuid VARCHAR(36) PRIMARY KEY, balance DOUBLE NOT NULL)");
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_balances table: " + e.getMessage());
      }
   }

   private void initDb() {
      this.createTable();
      this.migrateLegacyFile();

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT uuid, balance FROM taxsmp_balances");
         ResultSet rs = ps.executeQuery();
      ) {
         while (rs.next()) {
            try {
               this.cache.put(UUID.fromString(rs.getString("uuid")), rs.getDouble("balance"));
            } catch (IllegalArgumentException var9) {
            }
         }

         this.plugin.getLogger().info("Loaded " + this.cache.size() + " balances from MySQL.");
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not load balances from MySQL: " + e.getMessage());
      }
   }

   private boolean tableHasRows() {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_balances LIMIT 1");
         ResultSet rs = ps.executeQuery();
      ) {
         return rs.next();
      } catch (SQLException e) {
         return true;
      }
   }

   private void migrateLegacyFile() {
      Set<String> keys = this.cfg.getKeys(false);
      if (!keys.isEmpty()) {
         if (this.tableHasRows()) {
            this.plugin.getLogger().info("Balances already in MySQL; skipping local import on this server.");
         } else {
            int migrated = 0;

            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement(
                  "INSERT INTO taxsmp_balances (uuid, balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE balance = GREATEST(balance, VALUES(balance))"
               );
            ) {
               for (String key : keys) {
                  try {
                     UUID u = UUID.fromString(key);
                     ps.setString(1, u.toString());
                     ps.setDouble(2, this.cfg.getDouble(key));
                     ps.addBatch();
                     migrated++;
                  } catch (IllegalArgumentException var10) {
                  }
               }

               ps.executeBatch();
            } catch (SQLException e) {
               this.plugin.getLogger().severe("Balance migration failed: " + e.getMessage());
               return;
            }

            if (migrated > 0) {
               this.plugin
                  .getLogger()
                  .info("Merged " + migrated + " balances from balances.yml into MySQL (kept the higher value where rows already existed).");
               File backup = new File(this.plugin.getDataFolder(), "balances.yml.imported");
               if (!backup.exists()) {
                  this.dataFile.renameTo(backup);
               }
            }
         }
      }
   }

   public double balanceOf(UUID id) {
      return id == null ? 0.0 : this.cache.getOrDefault(id, 0.0);
   }

   public double getBalance(OfflinePlayer p) {
      return this.cache.getOrDefault(p.getUniqueId(), 0.0);
   }

   public void depositPlayer(OfflinePlayer p, double amount) {
      if (!(amount <= 0.0)) {
         UUID uuid = p.getUniqueId();
         this.lastLocalChange.put(uuid, System.currentTimeMillis());
         double newBal = this.cache.merge(uuid, amount, Double::sum);
         if (this.usingDb()) {
            this.saveDelta(uuid, amount);
         } else {
            this.cache.put(uuid, newBal);
            this.save(uuid, newBal);
         }
      }
   }

   public boolean withdrawPlayer(OfflinePlayer p, double amount) {
      UUID uuid = p.getUniqueId();
      double current = this.getBalance(p);
      if (current < amount) {
         return false;
      }

      double newBal = current - amount;
      this.lastLocalChange.put(uuid, System.currentTimeMillis());
      this.cache.put(uuid, newBal);
      if (this.usingDb()) {
         this.saveDelta(uuid, -amount);
      } else {
         this.save(uuid, newBal);
      }

      return true;
   }

   public Set<UUID> getKnownPlayers() {
      return this.cache.keySet();
   }

   public void loadFromDb(UUID uuid) {
      if (this.usingDb()) {
         this.db.async(() -> {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT balance FROM taxsmp_balances WHERE uuid = ?");
            ) {
               ps.setString(1, uuid.toString());

               try (ResultSet rs = ps.executeQuery()) {
                  if (rs.next()) {
                     this.cache.put(uuid, rs.getDouble("balance"));
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh balance for " + uuid + ": " + e.getMessage());
            }
         });
      }
   }

   private void refreshOnlineBalances() {
      if (this.usingDb() && this.db.isHealthy()) {
         long now = System.currentTimeMillis();
         this.lastLocalChange.entrySet().removeIf(e -> now - e.getValue() > 60000L);
         List<UUID> ids = new ArrayList<>();

         for (OfflinePlayer p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();
            Long changed = this.lastLocalChange.get(uuid);
            if ((changed == null || now - changed >= 3000L) && !this.db.hasPendingFor(uuid)) {
               ids.add(uuid);
            }
         }

         if (!ids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
            Map<UUID, Double> fresh = new HashMap<>();

            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT uuid, balance FROM taxsmp_balances WHERE uuid IN (" + placeholders + ")");
            ) {
               for (int i = 0; i < ids.size(); i++) {
                  ps.setString(i + 1, ids.get(i).toString());
               }

               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     try {
                        fresh.put(UUID.fromString(rs.getString("uuid")), rs.getDouble("balance"));
                     } catch (IllegalArgumentException var14) {
                     }
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh online balances: " + e.getMessage());
               return;
            }

            long after = System.currentTimeMillis();

            for (Entry<UUID, Double> entry : fresh.entrySet()) {
               Long changed = this.lastLocalChange.get(entry.getKey());
               if ((changed == null || after - changed >= 3000L) && !this.db.hasPendingFor(entry.getKey())) {
                  this.cache.put(entry.getKey(), entry.getValue());
               }
            }
         }
      }
   }

   private void save(UUID uuid, double amount) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> this.db
                  .writeIdempotent(
                     uuid,
                     "INSERT INTO taxsmp_balances (uuid, balance) VALUES (?, ?) ON DUPLICATE KEY UPDATE balance = VALUES(balance)",
                     uuid.toString(),
                     amount
                  )
            );
      } else {
         Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
            synchronized (this.dataFile) {
               this.cfg.set(uuid.toString(), amount);

               try {
                  this.cfg.save(this.dataFile);
               } catch (IOException var8) {
               }
            }
         });
      }
   }

   private void saveDelta(UUID uuid, double delta) {
      if (!this.usingDb()) {
         this.save(uuid, this.getBalance(Bukkit.getOfflinePlayer(uuid)));
      } else {
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> this.db
                  .writeDelta(
                     uuid,
                     "INSERT INTO taxsmp_balances (uuid, balance) VALUES (?, GREATEST(0, ?)) ON DUPLICATE KEY UPDATE balance = GREATEST(0, balance + ?)",
                     uuid.toString(),
                     delta,
                     delta
                  )
            );
      }
   }

   public void reloadFromDb() {
      if (this.usingDb()) {
         this.createTable();
         Map<UUID, Double> fresh = new HashMap<>();

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT uuid, balance FROM taxsmp_balances");
            ResultSet rs = ps.executeQuery();
         ) {
            while (rs.next()) {
               try {
                  fresh.put(UUID.fromString(rs.getString("uuid")), rs.getDouble("balance"));
               } catch (IllegalArgumentException var10) {
               }
            }
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Could not reload balances after reconnect: " + e.getMessage());
            return;
         }

         for (Entry<UUID, Double> e : fresh.entrySet()) {
            if (!this.db.hasPendingFor(e.getKey())) {
               this.cache.put(e.getKey(), e.getValue());
            }
         }

         this.plugin.getLogger().info("Reloaded " + fresh.size() + " balances from MySQL after reconnect.");
      }
   }

   public SnapshotManager.Section snapshotSection() {
      return new SnapshotManager.Section() {
         @Override
         public String sectionName() {
            return "balances";
         }

         @Override
         public void writeSnapshot(ConfigurationSection out) {
            for (Entry<UUID, Double> e : SimpleEconomy.this.cache.entrySet()) {
               out.set(e.getKey().toString(), e.getValue());
            }
         }

         @Override
         public void readSnapshot(ConfigurationSection in) {
            int n = 0;

            for (String key : in.getKeys(false)) {
               try {
                  SimpleEconomy.this.cache.put(UUID.fromString(key), in.getDouble(key));
                  n++;
               } catch (IllegalArgumentException var6) {
               }
            }

            SimpleEconomy.this.plugin.getLogger().warning("Restored " + n + " balances from local snapshot.");
         }
      };
   }
}
