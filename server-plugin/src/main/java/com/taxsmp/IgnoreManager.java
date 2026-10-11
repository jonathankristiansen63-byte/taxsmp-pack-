package com.taxsmp;

import com.taxsmp.storage.Db;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class IgnoreManager {
   private final TaxSMP plugin;
   private final Db db;
   private final File file;
   private final Map<UUID, Set<UUID>> ignoredMap = new ConcurrentHashMap<>();

   public IgnoreManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      this.file = new File(plugin.getDataFolder(), "ignores.yml");
      if (this.usingDb()) {
         this.db.async(this::initDb);
      } else {
         this.loadFile();
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
            "CREATE TABLE IF NOT EXISTS taxsmp_ignores (ignorer VARCHAR(36) NOT NULL, ignored VARCHAR(36) NOT NULL, PRIMARY KEY (ignorer, ignored))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_ignores table: " + e.getMessage());
      }

      if (this.file.exists()) {
         FileConfiguration cfg = YamlConfiguration.loadConfiguration(this.file);
         if (cfg.contains("ignores")) {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO taxsmp_ignores (ignorer, ignored) VALUES (?, ?)");
            ) {
               int n = 0;

               for (String key : cfg.getConfigurationSection("ignores").getKeys(false)) {
                  for (String s : cfg.getStringList("ignores." + key)) {
                     try {
                        ps.setString(1, UUID.fromString(key).toString());
                        ps.setString(2, UUID.fromString(s).toString());
                        ps.addBatch();
                        n++;
                     } catch (IllegalArgumentException var16) {
                     }
                  }
               }

               if (n > 0) {
                  ps.executeBatch();
                  this.plugin.getLogger().info("Merged " + n + " ignore entries from ignores.yml into MySQL.");
                  File backup = new File(this.plugin.getDataFolder(), "ignores.yml.imported");
                  if (!backup.exists()) {
                     this.file.renameTo(backup);
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().severe("Ignore migration failed: " + e.getMessage());
            }
         }
      }

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT ignorer, ignored FROM taxsmp_ignores");
         ResultSet rs = ps.executeQuery();
      ) {
         while (rs.next()) {
            try {
               UUID a = UUID.fromString(rs.getString("ignorer"));
               UUID b = UUID.fromString(rs.getString("ignored"));
               this.ignoredMap.computeIfAbsent(a, k -> ConcurrentHashMap.newKeySet()).add(b);
            } catch (IllegalArgumentException var13) {
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not load ignores from MySQL: " + e.getMessage());
      }
   }

   public void ignore(UUID ignorer, UUID ignored) {
      this.ignoredMap.computeIfAbsent(ignorer, k -> ConcurrentHashMap.newKeySet()).add(ignored);
      if (this.usingDb()) {
         this.db.async(() -> {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO taxsmp_ignores (ignorer, ignored) VALUES (?, ?)");
            ) {
               ps.setString(1, ignorer.toString());
               ps.setString(2, ignored.toString());
               ps.executeUpdate();
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not save ignore: " + e.getMessage());
            }
         });
      } else {
         this.saveFile();
      }
   }

   public void unignore(UUID ignorer, UUID ignored) {
      Set<UUID> set = this.ignoredMap.get(ignorer);
      if (set != null) {
         set.remove(ignored);
         if (set.isEmpty()) {
            this.ignoredMap.remove(ignorer);
         }
      }

      if (this.usingDb()) {
         this.db.async(() -> {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_ignores WHERE ignorer = ? AND ignored = ?");
            ) {
               ps.setString(1, ignorer.toString());
               ps.setString(2, ignored.toString());
               ps.executeUpdate();
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not remove ignore: " + e.getMessage());
            }
         });
      } else {
         this.saveFile();
      }
   }

   public boolean isIgnoring(UUID ignorer, UUID ignored) {
      Set<UUID> set = this.ignoredMap.get(ignorer);
      return set != null && set.contains(ignored);
   }

   public void loadFromDb(UUID ignorer) {
      if (this.usingDb()) {
         this.db.async(() -> {
            Set<UUID> set = ConcurrentHashMap.newKeySet();

            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT ignored FROM taxsmp_ignores WHERE ignorer = ?");
            ) {
               ps.setString(1, ignorer.toString());

               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     try {
                        set.add(UUID.fromString(rs.getString("ignored")));
                     } catch (IllegalArgumentException var11) {
                     }
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh ignores for " + ignorer + ": " + e.getMessage());
               return;
            }

            if (set.isEmpty()) {
               this.ignoredMap.remove(ignorer);
            } else {
               this.ignoredMap.put(ignorer, set);
            }
         });
      }
   }

   private void loadFile() {
      if (this.file.exists()) {
         FileConfiguration cfg = YamlConfiguration.loadConfiguration(this.file);
         if (cfg.contains("ignores")) {
            for (String key : cfg.getConfigurationSection("ignores").getKeys(false)) {
               UUID ignorer = UUID.fromString(key);
               List<String> list = cfg.getStringList("ignores." + key);
               Set<UUID> set = ConcurrentHashMap.newKeySet();

               for (String s : list) {
                  set.add(UUID.fromString(s));
               }

               this.ignoredMap.put(ignorer, set);
            }
         }
      }
   }

   private void saveFile() {
      FileConfiguration cfg = new YamlConfiguration();

      for (Entry<UUID, Set<UUID>> entry : this.ignoredMap.entrySet()) {
         List<String> list = new ArrayList<>();

         for (UUID uuid : entry.getValue()) {
            list.add(uuid.toString());
         }

         cfg.set("ignores." + entry.getKey().toString(), list);
      }

      try {
         cfg.save(this.file);
      } catch (IOException e) {
         e.printStackTrace();
      }
   }
}
