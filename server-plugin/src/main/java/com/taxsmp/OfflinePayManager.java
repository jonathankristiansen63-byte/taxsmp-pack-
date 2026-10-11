package com.taxsmp;

import com.taxsmp.storage.Db;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class OfflinePayManager {
   private final TaxSMP plugin;
   private final Db db;
   private final File file;

   public OfflinePayManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      this.file = new File(plugin.getDataFolder(), "pending_pays.yml");
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
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_pending_pays (uuid VARCHAR(36) PRIMARY KEY, amount DOUBLE NOT NULL)");
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_pending_pays table: " + e.getMessage());
      }
   }

   public void addPending(UUID recipient, double amount) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> {
                  try (
                     Connection conn = this.db.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO taxsmp_pending_pays (uuid, amount) VALUES (?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)"
                     );
                  ) {
                     ps.setString(1, recipient.toString());
                     ps.setDouble(2, amount);
                     ps.executeUpdate();
                  } catch (SQLException e) {
                     this.plugin.getLogger().warning("Could not store pending pay for " + recipient + ": " + e.getMessage());
                  }
               }
            );
      } else {
         Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
            synchronized (this.file) {
               FileConfiguration cfg = YamlConfiguration.loadConfiguration(this.file);
               double total = cfg.getDouble(recipient.toString(), 0.0) + amount;
               cfg.set(recipient.toString(), total);

               try {
                  cfg.save(this.file);
               } catch (IOException var11) {
               }
            }
         });
      }
   }

   public void consumePending(UUID recipient, Consumer<Double> callback) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            double total = 0.0;

            try (Connection conn = this.db.getConnection()) {
               try (PreparedStatement ps = conn.prepareStatement("SELECT amount FROM taxsmp_pending_pays WHERE uuid = ?")) {
                  ps.setString(1, recipient.toString());

                  try (ResultSet rs = ps.executeQuery()) {
                     if (rs.next()) {
                        total = rs.getDouble("amount");
                     }
                  }
               }

               if (total > 0.0) {
                  try (PreparedStatement del = conn.prepareStatement("DELETE FROM taxsmp_pending_pays WHERE uuid = ?")) {
                     del.setString(1, recipient.toString());
                     del.executeUpdate();
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not read pending pay for " + recipient + ": " + e.getMessage());
               return;
            }

            callback.accept(total);
         });
      } else {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            double total;
            synchronized (this.file) {
               FileConfiguration cfg = YamlConfiguration.loadConfiguration(this.file);
               total = cfg.getDouble(recipient.toString(), 0.0);
               if (total > 0.0) {
                  cfg.set(recipient.toString(), null);

                  try {
                     cfg.save(this.file);
                  } catch (IOException var10) {
                  }
               }
            }

            callback.accept(total);
         });
      }
   }
}
