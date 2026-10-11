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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class NetworkPlayerDirectory implements Listener {
   private static final long STALE_AFTER_MILLIS = 45000L;
   private final TaxSMP plugin;
   private final Db db;
   private final String serverId;
   private final Map<String, NetworkPlayerDirectory.KnownPlayer> byLowerName = new ConcurrentHashMap<>();
   private final Map<UUID, NetworkPlayerDirectory.KnownPlayer> byUuid = new ConcurrentHashMap<>();
   private ScheduledTask task;

   public NetworkPlayerDirectory(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      this.serverId = resolveServerId(plugin);
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(plugin, task -> {
            try {
               this.ensureTable();
               this.updateLocalPlayers();
               this.refreshCache();
            } catch (SQLException ex) {
               plugin.getLogger().warning("Could not initialize network player directory: " + ex.getMessage());
            }
         });
         this.task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
            try {
               this.updateLocalPlayers();
               this.refreshCache();
            } catch (SQLException ex) {
               plugin.getLogger().warning("Could not refresh network player directory: " + ex.getMessage());
            }
         }, 5L, 10L, TimeUnit.SECONDS);
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   public String serverId() {
      return this.serverId;
   }

   public NetworkPlayerDirectory.KnownPlayer find(String name) {
      if (name != null && !name.isBlank()) {
         Player local = Bukkit.getPlayerExact(name);
         if (local != null && local.isOnline()) {
            return new NetworkPlayerDirectory.KnownPlayer(local.getUniqueId(), local.getName(), this.serverId, true);
         }

         NetworkPlayerDirectory.KnownPlayer known = this.byLowerName.get(name.toLowerCase(Locale.ROOT));
         if (known != null) {
            return known;
         }

         OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
         return cached != null && cached.getName() != null
            ? new NetworkPlayerDirectory.KnownPlayer(cached.getUniqueId(), cached.getName(), this.serverId, cached.isOnline())
            : null;
      } else {
         return null;
      }
   }

   public NetworkPlayerDirectory.KnownPlayer find(UUID uuid) {
      if (uuid == null) {
         return null;
      }

      Player local = Bukkit.getPlayer(uuid);
      return local != null && local.isOnline()
         ? new NetworkPlayerDirectory.KnownPlayer(local.getUniqueId(), local.getName(), this.serverId, true)
         : this.byUuid.get(uuid);
   }

   public NetworkPlayerDirectory.KnownPlayer cached(UUID uuid) {
      return uuid == null ? null : this.byUuid.get(uuid);
   }

   public OfflinePlayer resolveOfflinePlayer(String name) {
      NetworkPlayerDirectory.KnownPlayer known = this.find(name);
      return known != null ? Bukkit.getOfflinePlayer(known.uuid()) : Bukkit.getOfflinePlayerIfCached(name);
   }

   public List<String> completeNames(String prefix) {
      String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
      LinkedHashSet<String> names = new LinkedHashSet<>();

      for (Player player : Bukkit.getOnlinePlayers()) {
         if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
            names.add(player.getName());
         }
      }

      for (NetworkPlayerDirectory.KnownPlayer known : this.byLowerName.values()) {
         if (known.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
            names.add(known.name());
         }
      }

      return new ArrayList<>(names);
   }

   public List<String> completeOnlineNames(String prefix) {
      String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
      LinkedHashSet<String> names = new LinkedHashSet<>();

      for (Player player : Bukkit.getOnlinePlayers()) {
         if (player.getName().toLowerCase(Locale.ROOT).startsWith(lower)) {
            names.add(player.getName());
         }
      }

      for (NetworkPlayerDirectory.KnownPlayer known : this.byLowerName.values()) {
         if (known.online() && known.name().toLowerCase(Locale.ROOT).startsWith(lower)) {
            names.add(known.name());
         }
      }

      return new ArrayList<>(names);
   }

   private void ensureTable() throws SQLException {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_network_players (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL, lower_name VARCHAR(32) NOT NULL, server_id VARCHAR(32) NOT NULL, online TINYINT(1) NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL, INDEX idx_network_players_name (lower_name), INDEX idx_network_players_online (online, updated_at))"
         );
      }
   }

   private void refreshCache() throws SQLException {
      if (this.usingDb()) {
         long minUpdated = System.currentTimeMillis() - 45000L;
         Map<String, NetworkPlayerDirectory.KnownPlayer> fresh = new HashMap<>();

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT uuid, name, server_id, online FROM taxsmp_network_players WHERE updated_at >= ? OR online = 0");
         ) {
            ps.setLong(1, minUpdated);

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  NetworkPlayerDirectory.KnownPlayer known = new NetworkPlayerDirectory.KnownPlayer(
                     UUID.fromString(rs.getString("uuid")),
                     rs.getString("name"),
                     rs.getString("server_id"),
                     rs.getBoolean("online") && rs.getString("server_id") != null
                  );
                  fresh.put(known.name().toLowerCase(Locale.ROOT), known);
               }
            }
         }

         this.byLowerName.clear();
         this.byLowerName.putAll(fresh);
         this.byUuid.clear();

         for (NetworkPlayerDirectory.KnownPlayer known : fresh.values()) {
            this.byUuid.put(known.uuid(), known);
         }
      }
   }

   private void updateLocalPlayers() throws SQLException {
      if (this.usingDb()) {
         long now = System.currentTimeMillis();

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
               "INSERT INTO taxsmp_network_players (uuid, name, lower_name, server_id, online, updated_at) VALUES (?, ?, ?, ?, 1, ?) ON DUPLICATE KEY UPDATE name = VALUES(name), lower_name = VALUES(lower_name), server_id = VALUES(server_id), online = 1, updated_at = VALUES(updated_at)"
            );
         ) {
            for (Player player : Bukkit.getOnlinePlayers()) {
               ps.setString(1, player.getUniqueId().toString());
               ps.setString(2, player.getName());
               ps.setString(3, player.getName().toLowerCase(Locale.ROOT));
               ps.setString(4, this.serverId);
               ps.setLong(5, now);
               ps.addBatch();
            }

            ps.executeBatch();
         }
      }
   }

   private void markOffline(UUID uuid) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> {
            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("UPDATE taxsmp_network_players SET online = 0, updated_at = ? WHERE uuid = ? AND server_id = ?");
            ) {
               ps.setLong(1, System.currentTimeMillis());
               ps.setString(2, uuid.toString());
               ps.setString(3, this.serverId);
               ps.executeUpdate();
            } catch (SQLException var11) {
            }
         }, 1L, TimeUnit.SECONDS);
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try {
               this.updateLocalPlayers();
               this.refreshCache();
            } catch (SQLException var3) {
            }
         });
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.markOffline(event.getPlayer().getUniqueId());
   }

   public void shutdown() {
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }
   }

   private static String resolveServerId(TaxSMP plugin) {
      String configured = plugin.getConfig().getString("network-server-id", plugin.getConfig().getString("server-id", ""));
      return configured != null && !configured.isBlank() ? normalize(configured) : normalize(plugin.getConfig().getString("region", "server"));
   }

   private static String normalize(String value) {
      String server = value == null ? "server" : value.trim().toLowerCase(Locale.ROOT);
      server = server.replaceAll("[^a-z0-9_-]", "-");
      return server.isBlank() ? "server" : server;
   }

   public record KnownPlayer(UUID uuid, String name, String server, boolean online) {
   }
}
