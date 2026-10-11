package com.taxsmp;

import com.taxsmp.storage.Db;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

public class NetworkTotalsManager {
   public static final String SHOP_SPENT = "shop_spent";
   public static final String SELL_MADE = "sell_made";
   public static final String AH_SPENT = "ah_spent";
   public static final String AH_MADE = "ah_made";
   private static final Map<String, String> LEGACY_ROOTS = Map.of(
      "shop_spent", "shop.total", "sell_made", "sell.total", "ah_spent", "ah.spent", "ah_made", "ah.made"
   );
   private static final long FLUSH_SECONDS = 5L;
   private final TaxSMP plugin;
   private final Db db;
   private final ConcurrentHashMap<String, Double> cache = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<String, Double> pending = new ConcurrentHashMap<>();
   private ScheduledTask flushTask;

   public NetworkTotalsManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         this.db.async(this::initDb);
         this.flushTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> this.db.async(this::flushPending), 5L, 5L, TimeUnit.SECONDS);
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
            "CREATE TABLE IF NOT EXISTS taxsmp_totals (uuid VARCHAR(36) NOT NULL, total_key VARCHAR(64) NOT NULL, amount DOUBLE NOT NULL, PRIMARY KEY (uuid, total_key))"
         );
         if (!this.hasRows(conn)) {
            this.migrateLegacyConfig(conn);
         }

         this.loadAll(conn);
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not initialize shared totals: " + e.getMessage());
      }
   }

   private boolean hasRows(Connection conn) throws SQLException {
      try (
         PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_totals LIMIT 1");
         ResultSet rs = ps.executeQuery();
      ) {
         return rs.next();
      }
   }

   private void migrateLegacyConfig(Connection conn) throws SQLException {
      int migrated = 0;

      try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO taxsmp_totals (uuid, total_key, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = GREATEST(amount, VALUES(amount))"
         )) {
         for (Entry<String, String> entry : LEGACY_ROOTS.entrySet()) {
            ConfigurationSection section = this.plugin.getConfig().getConfigurationSection(entry.getValue());
            if (section != null) {
               for (String rawUuid : section.getKeys(false)) {
                  double amount = this.plugin.getConfig().getDouble(entry.getValue() + "." + rawUuid, 0.0);
                  if (!(amount <= 0.0)) {
                     try {
                        UUID.fromString(rawUuid);
                     } catch (IllegalArgumentException ignored) {
                        continue;
                     }

                     ps.setString(1, rawUuid);
                     ps.setString(2, entry.getKey());
                     ps.setDouble(3, amount);
                     ps.addBatch();
                     migrated++;
                  }
               }
            }
         }

         if (migrated > 0) {
            ps.executeBatch();
            this.plugin.getLogger().info("Merged " + migrated + " stat total rows into MySQL.");
         }
      }
   }

   private void loadAll(Connection conn) throws SQLException {
      try (
         PreparedStatement ps = conn.prepareStatement("SELECT uuid, total_key, amount FROM taxsmp_totals");
         ResultSet rs = ps.executeQuery();
      ) {
         while (rs.next()) {
            this.cache.put(cacheKey(rs.getString("uuid"), rs.getString("total_key")), rs.getDouble("amount"));
         }
      }
   }

   public boolean add(UUID uuid, String totalKey, double amount) {
      if (this.usingDb() && uuid != null && totalKey != null && !totalKey.isBlank() && !(amount <= 0.0)) {
         String id = uuid.toString();
         String normalizedKey = normalizeKey(totalKey);
         String key = cacheKey(id, normalizedKey);
         this.cache.merge(key, amount, Double::sum);
         this.pending.merge(key, amount, Double::sum);
         return true;
      } else {
         return false;
      }
   }

   private void flushPending() {
      if (!this.pending.isEmpty() && this.usingDb()) {
         Map<String, Double> batch = new HashMap<>();

         for (String key : new ArrayList<>(this.pending.keySet())) {
            Double amount = this.pending.remove(key);
            if (amount != null && amount > 0.0) {
               batch.put(key, amount);
            }
         }

         if (!batch.isEmpty()) {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement(
                  "INSERT INTO taxsmp_totals (uuid, total_key, amount) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)"
               );
            ) {
               for (Entry<String, Double> e : batch.entrySet()) {
                  int split = e.getKey().indexOf(124);
                  if (split > 0) {
                     ps.setString(1, e.getKey().substring(0, split));
                     ps.setString(2, e.getKey().substring(split + 1));
                     ps.setDouble(3, e.getValue());
                     ps.addBatch();
                  }
               }

               ps.executeBatch();
            } catch (SQLException e) {
               for (Entry<String, Double> entry : batch.entrySet()) {
                  this.pending.merge(entry.getKey(), entry.getValue(), Double::sum);
               }

               this.plugin.getLogger().warning("Could not flush " + batch.size() + " shared total(s), will retry: " + e.getMessage());
            }
         }
      }
   }

   public void shutdown() {
      if (this.flushTask != null) {
         this.flushTask.cancel();
         this.flushTask = null;
      }

      this.flushPending();
   }

   public double get(UUID uuid, String totalKey, double fallback) {
      return this.usingDb() && uuid != null && totalKey != null && !totalKey.isBlank()
         ? this.cache.getOrDefault(cacheKey(uuid.toString(), normalizeKey(totalKey)), fallback)
         : fallback;
   }

   private static String normalizeKey(String totalKey) {
      return totalKey.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
   }

   private static String cacheKey(String uuid, String totalKey) {
      return uuid + "|" + totalKey;
   }
}
