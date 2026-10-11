package com.taxsmp;

import com.taxsmp.storage.Db;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class StatsTracker implements Listener {
   private final TaxSMP plugin;
   private final Db db;
   private final String serverId;
   private final File file;
   private FileConfiguration cfg;
   private final ConcurrentHashMap<UUID, Map<StatsTracker.StatType, Long>> cache = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, String> names = new ConcurrentHashMap<>();
   // With MySQL, playtime is counted here instead of by Minecraft: NA's and EU's own counters both
   // held the same time, so adding them up doubled it. Time up to `playtimeMark` is saved in taxsmp_playtime.
   private final ConcurrentHashMap<UUID, Long> playtimeMark = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, Long> unsavedPlaytime = new ConcurrentHashMap<>();
   private static final long NANOS_PER_TICK = 50_000_000L;
   private ScheduledTask snapshotTask;
   private static final StatsTracker.StatType[] DB_STATS = new StatsTracker.StatType[]{
      StatsTracker.StatType.DEATHS,
      StatsTracker.StatType.PLAYTIME,
      StatsTracker.StatType.BLOCKS_PLACED,
      StatsTracker.StatType.BLOCKS_BROKEN,
      StatsTracker.StatType.MOBS_KILLED,
      StatsTracker.StatType.PLAYER_KILLS
   };
   private static final String UPSERT_SQL = "INSERT INTO taxsmp_stats (uuid, name, deaths, playtime, blocks_placed, blocks_broken, mobs_killed, player_kills) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), deaths = GREATEST(deaths, VALUES(deaths)), playtime = GREATEST(playtime, VALUES(playtime)), blocks_placed = GREATEST(blocks_placed, VALUES(blocks_placed)), blocks_broken = GREATEST(blocks_broken, VALUES(blocks_broken)), mobs_killed = GREATEST(mobs_killed, VALUES(mobs_killed)), player_kills = GREATEST(player_kills, VALUES(player_kills))";
   private static final String UPSERT_SERVER_SQL = "INSERT INTO taxsmp_stats_by_server (uuid, server_id, name, deaths, playtime, blocks_placed, blocks_broken, mobs_killed, player_kills) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), deaths = GREATEST(deaths, VALUES(deaths)), playtime = GREATEST(playtime, VALUES(playtime)), blocks_placed = GREATEST(blocks_placed, VALUES(blocks_placed)), blocks_broken = GREATEST(blocks_broken, VALUES(blocks_broken)), mobs_killed = GREATEST(mobs_killed, VALUES(mobs_killed)), player_kills = GREATEST(player_kills, VALUES(player_kills))";

   public StatsTracker(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      this.serverId = normalizeServerId(plugin.getConfig().getString("region", "EU"));
      this.file = new File(plugin.getDataFolder(), "stats.yml");
      if (!plugin.getDataFolder().exists()) {
         plugin.getDataFolder().mkdirs();
      }

      if (!this.file.exists()) {
         try {
            this.file.createNewFile();
         } catch (IOException var3) {
         }
      }

      this.cfg = YamlConfiguration.loadConfiguration(this.file);
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(plugin, task -> this.initDb());
      } else {
         this.load();
      }

      long now = System.nanoTime();
      for (Player p : Bukkit.getOnlinePlayers()) {
         this.playtimeMark.put(p.getUniqueId(), now);
      }

      this.snapshotTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> this.snapshotAllOnline(), 30L, 60L, TimeUnit.SECONDS);
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private static String col(StatsTracker.StatType t) {
      return t.name().toLowerCase();
   }

   private void initDb() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_stats (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32), deaths BIGINT NOT NULL DEFAULT 0, playtime BIGINT NOT NULL DEFAULT 0, blocks_placed BIGINT NOT NULL DEFAULT 0, blocks_broken BIGINT NOT NULL DEFAULT 0, mobs_killed BIGINT NOT NULL DEFAULT 0, player_kills BIGINT NOT NULL DEFAULT 0)"
         );
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_stats_by_server (uuid VARCHAR(36) NOT NULL, server_id VARCHAR(32) NOT NULL, name VARCHAR(32), deaths BIGINT NOT NULL DEFAULT 0, playtime BIGINT NOT NULL DEFAULT 0, blocks_placed BIGINT NOT NULL DEFAULT 0, blocks_broken BIGINT NOT NULL DEFAULT 0, mobs_killed BIGINT NOT NULL DEFAULT 0, player_kills BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, server_id), INDEX idx_stats_server_uuid (uuid))"
         );
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_playtime (uuid VARCHAR(36) PRIMARY KEY, ticks BIGINT NOT NULL DEFAULT 0)");
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_meta (k VARCHAR(64) PRIMARY KEY, v VARCHAR(255))");
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_stats table: " + e.getMessage());
      }

      this.migrateStatsFile();
      this.migratePlaytime();
      this.refreshAggregateCache();
   }

   /**
    * One time for the whole network: start the new playtime counter from each player's biggest
    * single-server playtime, instead of NA + EU added together (which counted the same time twice).
    */
   private void migratePlaytime() {
      try (Connection conn = this.db.getConnection()) {
         conn.setAutoCommit(false);
         try (
            PreparedStatement claim = conn.prepareStatement("INSERT IGNORE INTO taxsmp_meta (k, v) VALUES ('playtime_v2', ?)");
            Statement st = conn.createStatement();
         ) {
            claim.setString(1, this.serverId);
            if (claim.executeUpdate() == 0) {
               conn.rollback();
               return;
            }

            int fixed = st.executeUpdate(
               "INSERT INTO taxsmp_playtime (uuid, ticks) SELECT uuid, MAX(playtime) FROM taxsmp_stats_by_server GROUP BY uuid ON DUPLICATE KEY UPDATE ticks = GREATEST(ticks, VALUES(ticks))"
            );
            // Players who only ever had a stats.yml number (from before MySQL) keep it.
            st.executeUpdate("INSERT IGNORE INTO taxsmp_playtime (uuid, ticks) SELECT uuid, playtime FROM taxsmp_stats");
            st.executeUpdate("UPDATE taxsmp_stats s JOIN taxsmp_playtime p ON p.uuid = s.uuid SET s.playtime = p.ticks");
            conn.commit();
            this.plugin.getLogger().info("Playtime fix: set " + fixed + " players to their biggest single-server playtime.");
         } catch (SQLException e) {
            conn.rollback();
            throw e;
         }
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Playtime fix failed, will try again next start: " + e.getMessage());
      }
   }

   /** Saves the playtime counted since the last save. {@code stop} ends counting (player left). */
   private void savePlaytime(UUID id, boolean stop) {
      long now = System.nanoTime();
      long[] taken = new long[1];
      if (stop) {
         Long mark = this.playtimeMark.remove(id);
         if (mark != null) {
            taken[0] = (now - mark) / NANOS_PER_TICK;
         }
      } else {
         this.playtimeMark.computeIfPresent(id, (k, mark) -> {
            taken[0] = (now - mark) / NANOS_PER_TICK;
            return mark + taken[0] * NANOS_PER_TICK;
         });
      }

      Long earlier = this.unsavedPlaytime.remove(id);
      long ticks = taken[0] + (earlier == null ? 0L : earlier);
      if (ticks > 0L && this.usingDb()) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
               "INSERT INTO taxsmp_playtime (uuid, ticks) VALUES (?, ?) ON DUPLICATE KEY UPDATE ticks = ticks + VALUES(ticks)"
            );
         ) {
            ps.setString(1, id.toString());
            ps.setLong(2, ticks);
            ps.executeUpdate();
            this.cache.computeIfPresent(id, (k, old) -> {
               Map<StatsTracker.StatType, Long> map = new HashMap<>(old);
               map.merge(StatsTracker.StatType.PLAYTIME, ticks, Long::sum);
               return map;
            });
         } catch (SQLException e) {
            this.unsavedPlaytime.merge(id, ticks, Long::sum);
            this.plugin.getLogger().warning("Could not save playtime for " + id + ", will retry: " + e.getMessage());
         }
      }
   }

   /** Playtime counted on this server that isn't in the database total yet. */
   private long pendingPlaytime(UUID id) {
      Long mark = this.playtimeMark.get(id);
      Long earlier = this.unsavedPlaytime.get(id);
      long ticks = mark == null ? 0L : (System.nanoTime() - mark) / NANOS_PER_TICK;
      return ticks + (earlier == null ? 0L : earlier);
   }

   private static String normalizeServerId(String value) {
      String s = value == null ? "server" : value.trim().toLowerCase(Locale.ROOT);
      return s.isEmpty() ? "server" : s;
   }

   private void refreshAggregateCache() {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT s.*, p.ticks AS counted_playtime FROM taxsmp_stats s LEFT JOIN taxsmp_playtime p ON p.uuid = s.uuid");
         ResultSet rs = ps.executeQuery();
      ) {
         while (rs.next()) {
            try {
               UUID id = UUID.fromString(rs.getString("uuid"));
               Map<StatsTracker.StatType, Long> map = new HashMap<>();

               for (StatsTracker.StatType t : DB_STATS) {
                  map.put(t, rs.getLong(col(t)));
               }

               long counted = rs.getLong("counted_playtime");
               if (!rs.wasNull()) {
                  map.put(StatsTracker.StatType.PLAYTIME, counted);
               }

               this.cache.put(id, map);
               String nm = rs.getString("name");
               if (nm != null) {
                  this.names.put(id, nm);
               }
            } catch (IllegalArgumentException var13) {
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not load stats from MySQL: " + e.getMessage());
      }
   }

   private boolean statsTableHasRows() {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_stats LIMIT 1");
         ResultSet rs = ps.executeQuery();
      ) {
         return rs.next();
      } catch (SQLException e) {
         return true;
      }
   }

   private void migrateStatsFile() {
      ConfigurationSection section = this.cfg.getConfigurationSection("players");
      if (section != null && !this.statsTableHasRows()) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
               "INSERT INTO taxsmp_stats (uuid, name, deaths, playtime, blocks_placed, blocks_broken, mobs_killed, player_kills) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), deaths = GREATEST(deaths, VALUES(deaths)), playtime = GREATEST(playtime, VALUES(playtime)), blocks_placed = GREATEST(blocks_placed, VALUES(blocks_placed)), blocks_broken = GREATEST(blocks_broken, VALUES(blocks_broken)), mobs_killed = GREATEST(mobs_killed, VALUES(mobs_killed)), player_kills = GREATEST(player_kills, VALUES(player_kills))"
            );
         ) {
            int n = 0;

            for (String key : section.getKeys(false)) {
               try {
                  UUID id = UUID.fromString(key);
                  ps.setString(1, id.toString());
                  ps.setString(2, this.cfg.getString("players." + key + ".name"));
                  ps.setLong(3, this.cfg.getLong("players." + key + ".deaths", 0L));
                  ps.setLong(4, this.cfg.getLong("players." + key + ".playtime", 0L));
                  ps.setLong(5, this.cfg.getLong("players." + key + ".blocks_placed", 0L));
                  ps.setLong(6, this.cfg.getLong("players." + key + ".blocks_broken", 0L));
                  ps.setLong(7, this.cfg.getLong("players." + key + ".mobs_killed", 0L));
                  ps.setLong(8, this.cfg.getLong("players." + key + ".player_kills", 0L));
                  ps.addBatch();
                  n++;
               } catch (IllegalArgumentException var10) {
               }
            }

            if (n > 0) {
               ps.executeBatch();
               this.plugin.getLogger().info("Merged " + n + " stat rows from stats.yml into MySQL.");
               File backup = new File(this.plugin.getDataFolder(), "stats.yml.imported");
               if (!backup.exists()) {
                  this.file.renameTo(backup);
               }
            }
         } catch (SQLException e) {
            this.plugin.getLogger().severe("Stats migration failed: " + e.getMessage());
         }
      }
   }

   public void shutdown() {
      if (this.snapshotTask != null) {
         this.snapshotTask.cancel();
      }

      this.snapshotAllOnline();
      // Async tasks don't run anymore while the plugin shuts down, so save playtime right here.
      for (UUID id : new ArrayList<>(this.playtimeMark.keySet())) {
         this.savePlaytime(id, true);
      }
   }

   private void load() {
      ConfigurationSection section = this.cfg.getConfigurationSection("players");
      if (section != null) {
         for (String key : section.getKeys(false)) {
            try {
               UUID id = UUID.fromString(key);
               Map<StatsTracker.StatType, Long> map = new HashMap<>();

               for (StatsTracker.StatType t : StatsTracker.StatType.values()) {
                  map.put(t, this.cfg.getLong("players." + key + "." + t.name().toLowerCase(), 0L));
               }

               this.cache.put(id, map);
               String nm = this.cfg.getString("players." + key + ".name");
               if (nm != null) {
                  this.names.put(id, nm);
               }
            } catch (Exception var10) {
            }
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      this.playtimeMark.put(e.getPlayer().getUniqueId(), System.nanoTime());
      this.snapshot(e.getPlayer());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      UUID id = e.getPlayer().getUniqueId();
      this.snapshot(e.getPlayer());
      Bukkit.getAsyncScheduler().runNow(this.plugin, task -> this.savePlaytime(id, true));
   }

   private void snapshotAllOnline() {
      for (Player p : Bukkit.getOnlinePlayers()) {
         try {
            p.getScheduler().run(this.plugin, task -> this.snapshot(p), null);
         } catch (Exception var4) {
         }
      }

      // While shutting down Folia refuses async tasks; shutdown() saves playtime itself.
      if (this.usingDb() && this.plugin.isEnabled()) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            for (UUID id : new ArrayList<>(this.playtimeMark.keySet())) {
               this.savePlaytime(id, false);
            }

            this.refreshAggregateCache();
         });
      }
   }

   public void snapshot(Player p) {
      if (p != null) {
         UUID id = p.getUniqueId();
         Map<StatsTracker.StatType, Long> localMap = new HashMap<>();
         double bal = this.plugin.getEconomy() != null ? this.plugin.getEconomy().getBalance(p) : 0.0;
         localMap.put(StatsTracker.StatType.MONEY, (long)Math.floor(bal));
         localMap.put(StatsTracker.StatType.DEATHS, (long)p.getStatistic(Statistic.DEATHS));
         localMap.put(StatsTracker.StatType.PLAYTIME, (long)p.getStatistic(Statistic.PLAY_ONE_MINUTE));
         localMap.put(StatsTracker.StatType.MOBS_KILLED, (long)p.getStatistic(Statistic.MOB_KILLS));
         localMap.put(StatsTracker.StatType.PLAYER_KILLS, (long)p.getStatistic(Statistic.PLAYER_KILLS));
         long placed = 0L;
         long broken = 0L;

         for (Material m : Material.values()) {
            if (m.isBlock()) {
               try {
                  broken += p.getStatistic(Statistic.MINE_BLOCK, m);
                  placed += p.getStatistic(Statistic.USE_ITEM, m);
               } catch (IllegalArgumentException var18) {
               }
            }
         }

         localMap.put(StatsTracker.StatType.BLOCKS_PLACED, placed);
         localMap.put(StatsTracker.StatType.BLOCKS_BROKEN, broken);
         Map<StatsTracker.StatType, Long> displayMap = new HashMap<>(localMap);
         Map<StatsTracker.StatType, Long> existing = this.cache.get(id);
         if (existing != null) {
            for (StatsTracker.StatType t : DB_STATS) {
               Long old = existing.get(t);
               Long cur = displayMap.get(t);
               if (old != null && (cur == null || old > cur)) {
                  displayMap.put(t, old);
               }
            }
         }

         if (this.usingDb()) {
            displayMap.put(StatsTracker.StatType.PLAYTIME, existing == null ? 0L : existing.getOrDefault(StatsTracker.StatType.PLAYTIME, 0L));
         }

         this.cache.put(id, displayMap);
         this.names.put(id, p.getName());
         this.persist(id, localMap);
      }
   }

   private void persist(UUID id, Map<StatsTracker.StatType, Long> snapshot) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> {
                  Map<StatsTracker.StatType, Long> map = snapshot;
                  if (map != null) {
                     boolean stored = this.db
                        .writeIdempotent(
                           id,
                           "INSERT INTO taxsmp_stats_by_server (uuid, server_id, name, deaths, playtime, blocks_placed, blocks_broken, mobs_killed, player_kills) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), deaths = GREATEST(deaths, VALUES(deaths)), playtime = GREATEST(playtime, VALUES(playtime)), blocks_placed = GREATEST(blocks_placed, VALUES(blocks_placed)), blocks_broken = GREATEST(blocks_broken, VALUES(blocks_broken)), mobs_killed = GREATEST(mobs_killed, VALUES(mobs_killed)), player_kills = GREATEST(player_kills, VALUES(player_kills))",
                           id.toString(),
                           this.serverId,
                           this.names.get(id),
                           map.getOrDefault(StatsTracker.StatType.DEATHS, 0L),
                           map.getOrDefault(StatsTracker.StatType.PLAYTIME, 0L),
                           map.getOrDefault(StatsTracker.StatType.BLOCKS_PLACED, 0L),
                           map.getOrDefault(StatsTracker.StatType.BLOCKS_BROKEN, 0L),
                           map.getOrDefault(StatsTracker.StatType.MOBS_KILLED, 0L),
                           map.getOrDefault(StatsTracker.StatType.PLAYER_KILLS, 0L)
                        );
                     if (stored) {
                        try (Connection conn = this.db.getConnection()) {
                           this.rebuildAggregateFor(conn, id);
                           this.loadAggregateFor(conn, id);
                        } catch (SQLException e) {
                           this.plugin.getLogger().warning("Could not rebuild stat totals for " + id + ": " + e.getMessage());
                        }
                     }
                  }
               }
            );
      } else {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            synchronized (this.file) {
               Map<StatsTracker.StatType, Long> map = this.cache.get(id);
               if (map != null) {
                  for (Entry<StatsTracker.StatType, Long> e : map.entrySet()) {
                     this.cfg.set("players." + id + "." + e.getKey().name().toLowerCase(), e.getValue());
                  }

                  String nm = this.names.get(id);
                  if (nm != null) {
                     this.cfg.set("players." + id + ".name", nm);
                  }

                  try {
                     this.cfg.save(this.file);
                  } catch (IOException var8) {
                  }
               }
            }
         });
      }
   }

   private void rebuildAggregateFor(Connection conn, UUID id) throws SQLException {
      try (PreparedStatement ensure = conn.prepareStatement("INSERT IGNORE INTO taxsmp_playtime (uuid, ticks) VALUES (?, 0)")) {
         ensure.setString(1, id.toString());
         ensure.executeUpdate();
      }

      try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO taxsmp_stats (uuid, name, deaths, playtime, blocks_placed, blocks_broken, mobs_killed, player_kills) SELECT s.uuid, MAX(s.name), SUM(s.deaths), COALESCE(MAX(p.ticks), 0), SUM(s.blocks_placed), SUM(s.blocks_broken), SUM(s.mobs_killed), SUM(s.player_kills) FROM taxsmp_stats_by_server s LEFT JOIN taxsmp_playtime p ON p.uuid = s.uuid WHERE s.uuid = ? GROUP BY s.uuid ON DUPLICATE KEY UPDATE name = VALUES(name), deaths = GREATEST(deaths, VALUES(deaths)), playtime = VALUES(playtime), blocks_placed = GREATEST(blocks_placed, VALUES(blocks_placed)), blocks_broken = GREATEST(blocks_broken, VALUES(blocks_broken)), mobs_killed = GREATEST(mobs_killed, VALUES(mobs_killed)), player_kills = GREATEST(player_kills, VALUES(player_kills))"
         )) {
         ps.setString(1, id.toString());
         ps.executeUpdate();
      }
   }

   private void loadAggregateFor(Connection conn, UUID id) throws SQLException {
      try (PreparedStatement ps = conn.prepareStatement("SELECT s.*, p.ticks AS counted_playtime FROM taxsmp_stats s LEFT JOIN taxsmp_playtime p ON p.uuid = s.uuid WHERE s.uuid = ?")) {
         ps.setString(1, id.toString());

         try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
               Map<StatsTracker.StatType, Long> map = new HashMap<>();

               for (StatsTracker.StatType t : DB_STATS) {
                  map.put(t, rs.getLong(col(t)));
               }

               long counted = rs.getLong("counted_playtime");
               if (!rs.wasNull()) {
                  map.put(StatsTracker.StatType.PLAYTIME, counted);
               }

               this.cache.put(id, map);
               String nm = rs.getString("name");
               if (nm != null) {
                  this.names.put(id, nm);
               }
            }
         }
      }
   }

   public long getStat(UUID id, StatsTracker.StatType t) {
      if (t == StatsTracker.StatType.MONEY && this.plugin.getEconomy() != null) {
         OfflinePlayer off = Bukkit.getOfflinePlayer(id);
         return (long)Math.floor(this.plugin.getEconomy().getBalance(off));
      } else {
         return this.cachedStat(id, t);
      }
   }

   public String getName(UUID id) {
      String nm = this.names.get(id);
      if (nm != null) {
         return nm;
      }

      OfflinePlayer off = Bukkit.getOfflinePlayer(id);
      return off.getName() != null ? off.getName() : id.toString().substring(0, 8);
   }

   public String cachedName(UUID id) {
      return id == null ? null : this.names.get(id);
   }

   public long cachedStat(UUID id, StatsTracker.StatType t) {
      Map<StatsTracker.StatType, Long> map = this.cache.get(id);
      long value = map == null ? 0L : map.getOrDefault(t, 0L);
      return t == StatsTracker.StatType.PLAYTIME && this.usingDb() ? value + this.pendingPlaytime(id) : value;
   }

   public Set<UUID> cachedIds() {
      return this.cache.keySet();
   }

   public List<UUID> getAllKnown() {
      Set<UUID> all = new HashSet<>(this.cache.keySet());
      if (this.plugin.getEconomy() != null) {
         all.addAll(this.plugin.getEconomy().getKnownPlayers());
      }

      File bal = new File(this.plugin.getDataFolder(), "balances.yml");
      if (bal.exists()) {
         YamlConfiguration bc = YamlConfiguration.loadConfiguration(bal);

         for (String key : bc.getKeys(false)) {
            try {
               all.add(UUID.fromString(key));
            } catch (Exception var7) {
            }
         }
      }

      return new ArrayList<>(all);
   }

   public List<Entry<UUID, Long>> getSorted(StatsTracker.StatType t) {
      List<Entry<UUID, Long>> list = new ArrayList<>();

      for (UUID id : this.getAllKnown()) {
         list.add(Map.entry(id, this.getStat(id, t)));
      }

      list.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
      return list;
   }

   public int getRank(UUID id, StatsTracker.StatType t) {
      List<Entry<UUID, Long>> list = this.getSorted(t);

      for (int i = 0; i < list.size(); i++) {
         if (list.get(i).getKey().equals(id)) {
            return i + 1;
         }
      }

      return -1;
   }

   public static String formatValue(StatsTracker.StatType t, long value) {
      switch (t) {
         case MONEY:
            return "$" + PriceManager.formatShort(value);
         case PLAYTIME:
            return PlaytimeFormatter.compact(value);
         default:
            return PriceManager.formatShort(value);
      }
   }

   public enum StatType {
      MONEY("ᴍᴏɴᴇʏ", "Money"),
      DEATHS("ᴅᴇᴀᴛʜꜱ", "Deaths"),
      PLAYTIME("ᴘʟᴀʏᴛɪᴍᴇ", "Playtime"),
      BLOCKS_PLACED("ʙʟᴏᴄᴋꜱ ᴘʟᴀᴄᴇᴅ", "Blocks placed"),
      BLOCKS_BROKEN("ʙʟᴏᴄᴋꜱ ʙʀᴏᴋᴇɴ", "Blocks broken"),
      MOBS_KILLED("ᴍᴏʙꜱ ᴋɪʟʟᴇᴅ", "Mobs killed"),
      PLAYER_KILLS("ᴘʟᴀʏᴇʀ ᴋɪʟʟꜱ", "Kills");

      public final String pretty;
      public final String loreLabel;

      StatType(String pretty, String loreLabel) {
         this.pretty = pretty;
         this.loreLabel = loreLabel;
      }
   }
}
