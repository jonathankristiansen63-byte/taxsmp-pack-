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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class EggManager implements Listener {
   private static final String AFK_WORLD = "afk";
   private static final int COUNTDOWN_SECONDS = 60;
   private static final int AFK_EGGS_PER_CYCLE = 1;
   private static final int ANYWHERE_EGGS_PER_CYCLE = 1;
   private final TaxSMP plugin;
   private final Db db;
   private final Map<UUID, Integer> cache = new ConcurrentHashMap<>();
   private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Integer> afkCountdowns = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> anywhereCountdowns = new ConcurrentHashMap<>();
   private ScheduledTask globalTask;
   private ScheduledTask dbRefreshTask;

   public EggManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         if (this.db.isHealthy()) {
            this.db.async(this::initDb);
         }

         this.db.onReconnect(this::reloadFromDb);
         this.dbRefreshTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> this.refreshOnlineFromDb(), 5L, 15L, TimeUnit.SECONDS);
      }

      this.startGlobalTask();
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private void createTable() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_eggs (uuid VARCHAR(36) PRIMARY KEY, eggs INT NOT NULL)");
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_eggs table: " + e.getMessage());
      }
   }

   private void initDb() {
      this.createTable();
      ConfigurationSection players = this.plugin.getConfig().getConfigurationSection("players");
      if (players != null && !this.tableHasRows()) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
               "INSERT INTO taxsmp_eggs (uuid, eggs) VALUES (?, ?) ON DUPLICATE KEY UPDATE eggs = GREATEST(eggs, VALUES(eggs))"
            );
         ) {
            int migrated = 0;

            for (String key : players.getKeys(false)) {
               int eggs = this.plugin.getConfig().getInt("players." + key + ".eggs", 0);
               if (eggs > 0) {
                  try {
                     ps.setString(1, UUID.fromString(key).toString());
                     ps.setInt(2, eggs);
                     ps.addBatch();
                     migrated++;
                  } catch (IllegalArgumentException var15) {
                  }
               }
            }

            if (migrated > 0) {
               ps.executeBatch();
               this.plugin.getLogger().info("Merged " + migrated + " egg balances from config into MySQL.");
            }
         } catch (SQLException e) {
            this.plugin.getLogger().severe("Egg migration failed: " + e.getMessage());
         }
      }

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT uuid, eggs FROM taxsmp_eggs");
         ResultSet rs = ps.executeQuery();
      ) {
         while (rs.next()) {
            try {
               this.cache.put(UUID.fromString(rs.getString("uuid")), rs.getInt("eggs"));
            } catch (IllegalArgumentException var12) {
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not load eggs from MySQL: " + e.getMessage());
      }
   }

   private boolean tableHasRows() {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_eggs LIMIT 1");
         ResultSet rs = ps.executeQuery();
      ) {
         return rs.next();
      } catch (SQLException e) {
         return true;
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
               PreparedStatement ps = conn.prepareStatement("SELECT uuid, eggs FROM taxsmp_eggs WHERE uuid IN (" + placeholders + ")");
            ) {
               for (int i = 0; i < ids.size(); i++) {
                  ps.setString(i + 1, ids.get(i).toString());
               }

               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     try {
                        UUID id = UUID.fromString(rs.getString("uuid"));
                        if (!this.db.hasPendingFor(id)) {
                           this.cache.put(id, rs.getInt("eggs"));
                        }
                     } catch (IllegalArgumentException var11) {
                     }
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh online egg balances: " + e.getMessage());
            }
         }
      }
   }

   private void startGlobalTask() {
      this.globalTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this.plugin, task -> {
         for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            if (player.getWorld().getName().equalsIgnoreCase("afk")) {
               this.tickCountdown(player, this.afkCountdowns, true, 1);
            } else {
               this.afkCountdowns.remove(uuid);
               if (this.canEarnEggsAnywhere(player)) {
                  this.tickCountdown(player, this.anywhereCountdowns, false, 1);
               } else {
                  this.anywhereCountdowns.remove(uuid);
               }
            }
         }
      }, 1L, 20L);
   }

   private void tickCountdown(Player player, Map<UUID, Integer> countdowns, boolean showCountdown, int reward) {
      UUID uuid = player.getUniqueId();
      Integer remaining = countdowns.get(uuid);
      if (remaining == null) {
         countdowns.put(uuid, 60);
         this.plugin.getLogger().info("[EggManager] Started countdown for " + player.getName());
      } else if (remaining == 0) {
         int eggs = this.addEggs(player.getUniqueId(), reward);
         this.plugin.getLogger().info("[EggManager] " + player.getName() + " earned " + reward + (reward == 1 ? " egg! Total: " : " eggs! Total: ") + eggs);
         if (showCountdown) {
            this.sendActionBar(player, ChatColor.RED + "+" + reward + (reward == 1 ? " Egg" : " Eggs"));
         }

         countdowns.put(uuid, 60);
      } else {
         if (showCountdown) {
            this.sendActionBar(player, ChatColor.GRAY + "Next egg in " + ChatColor.RED + remaining + "s");
         }

         countdowns.put(uuid, remaining - 1);
      }
   }

   private boolean canEarnEggsAnywhere(Player player) {
      return player.hasPermission("eggsanywhere") || player.hasPermission("taxsmp.eggsanywhere");
   }

   private void sendActionBar(Player player, String message) {
      try {
         player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(message));
      } catch (Exception var4) {
      }
   }

   public int getEggs(Player player) {
      return this.getEggs(player.getUniqueId());
   }

   public int getEggs(OfflinePlayer player) {
      return this.getEggs(player.getUniqueId());
   }

   public int getEggs(UUID uuid) {
      if (this.usingDb()) {
         Integer cached = this.cache.get(uuid);
         if (cached != null) {
            return cached;
         }

         this.loadFromDb(uuid);
         return 0;
      } else {
         return this.plugin.getConfig().getInt("players." + uuid + ".eggs", 0);
      }
   }

   public void setEggs(Player player, int amount) {
      this.setEggs(player.getUniqueId(), amount);
   }

   public void setEggs(OfflinePlayer player, int amount) {
      this.setEggs(player.getUniqueId(), amount);
   }

   public void setEggs(UUID uuid, int amount) {
      int value = Math.max(0, amount);
      if (this.usingDb()) {
         this.cache.put(uuid, value);
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> this.db
                  .writeIdempotent(
                     uuid, "INSERT INTO taxsmp_eggs (uuid, eggs) VALUES (?, ?) ON DUPLICATE KEY UPDATE eggs = VALUES(eggs)", uuid.toString(), value
                  )
            );
      } else {
         this.plugin.getConfig().set("players." + uuid + ".eggs", value);
         this.plugin.saveConfig();
      }
   }

   public int addEggs(UUID uuid, int amount) {
      if (amount == 0) {
         return this.getEggs(uuid);
      } else if (this.usingDb()) {
         int value = this.cache.merge(uuid, amount, (oldValue, delta) -> Math.max(0, oldValue + delta));
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> this.db
                  .writeDelta(
                     uuid,
                     "INSERT INTO taxsmp_eggs (uuid, eggs) VALUES (?, GREATEST(0, ?)) ON DUPLICATE KEY UPDATE eggs = GREATEST(0, eggs + ?)",
                     uuid.toString(),
                     amount,
                     amount
                  )
            );
         return value;
      } else {
         int value = Math.max(0, this.getEggs(uuid) + amount);
         this.setEggs(uuid, value);
         return value;
      }
   }

   public void loadFromDb(UUID uuid) {
      if (this.usingDb() && this.loading.add(uuid)) {
         this.db.async(() -> {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT eggs FROM taxsmp_eggs WHERE uuid = ?");
            ) {
               ps.setString(1, uuid.toString());

               try (ResultSet rs = ps.executeQuery()) {
                  if (rs.next()) {
                     this.cache.put(uuid, rs.getInt("eggs"));
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh eggs for " + uuid + ": " + e.getMessage());
            } finally {
               this.loading.remove(uuid);
            }
         });
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      this.loadFromDb(event.getPlayer().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      UUID uuid = event.getPlayer().getUniqueId();
      this.afkCountdowns.remove(uuid);
      this.anywhereCountdowns.remove(uuid);
   }

   public void reloadFromDb() {
      if (this.usingDb()) {
         this.createTable();

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT uuid, eggs FROM taxsmp_eggs");
            ResultSet rs = ps.executeQuery();
         ) {
            int n = 0;

            while (rs.next()) {
               try {
                  UUID id = UUID.fromString(rs.getString("uuid"));
                  if (!this.db.hasPendingFor(id)) {
                     this.cache.put(id, rs.getInt("eggs"));
                     n++;
                  }
               } catch (IllegalArgumentException var9) {
               }
            }

            this.plugin.getLogger().info("Reloaded " + n + " egg balances from MySQL after reconnect.");
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Could not reload eggs after reconnect: " + e.getMessage());
         }
      }
   }

   public SnapshotManager.Section snapshotSection() {
      return new SnapshotManager.Section() {
         @Override
         public String sectionName() {
            return "eggs";
         }

         @Override
         public void writeSnapshot(ConfigurationSection out) {
            for (Entry<UUID, Integer> e : EggManager.this.cache.entrySet()) {
               out.set(e.getKey().toString(), e.getValue());
            }
         }

         @Override
         public void readSnapshot(ConfigurationSection in) {
            int n = 0;

            for (String key : in.getKeys(false)) {
               try {
                  EggManager.this.cache.put(UUID.fromString(key), in.getInt(key));
                  n++;
               } catch (IllegalArgumentException var6) {
               }
            }

            EggManager.this.plugin.getLogger().warning("Restored " + n + " egg balances from local snapshot.");
         }
      };
   }
}
