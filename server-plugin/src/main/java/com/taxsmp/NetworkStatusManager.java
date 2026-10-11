package com.taxsmp;

import com.taxsmp.storage.Db;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class NetworkStatusManager implements Listener {
   private static final long STALE_AFTER_MILLIS = 90000L;
   private static final long TOTAL_TRUSTED_FOR_MILLIS = 120000L;
   private final TaxSMP plugin;
   private final Db db;
   private final String serverId;
   private volatile int networkOnline = 0;
   private volatile int remoteOnline = 0;
   private volatile long remoteReadAt = 0L;
   private ScheduledTask task;

   public NetworkStatusManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      this.serverId = resolveServerId(plugin);
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            this.initDb();
            this.updateNow();
         });
         this.task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> this.updateNow(), 2L, 10L, TimeUnit.SECONDS);
      } else {
         this.networkOnline = Bukkit.getOnlinePlayers().size();
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private static String normalize(String value) {
      String s = value == null ? "server" : value.trim().toLowerCase(Locale.ROOT);
      s = s.replaceAll("[^a-z0-9_-]", "-");
      if (s.isEmpty()) {
         s = "server";
      }

      return s.length() > 32 ? s.substring(0, 32) : s;
   }

   private static String resolveServerId(TaxSMP plugin) {
      String configured = plugin.getConfig().getString("network-server-id", plugin.getConfig().getString("server-id", ""));
      if (configured != null && !configured.isBlank()) {
         return normalize(configured);
      }

      String region = normalize(plugin.getConfig().getString("region", "server"));
      int port = Bukkit.getPort();
      String path = plugin.getDataFolder().getAbsolutePath().toLowerCase(Locale.ROOT);
      String hash = Integer.toHexString(path.hashCode());
      return normalize(installId(plugin) + "-" + region + "-" + port + "-" + hash);
   }

   private static String installId(TaxSMP plugin) {
      File file = new File(plugin.getDataFolder(), "server-id");

      try {
         if (file.isFile()) {
            String existing = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
            if (!existing.isEmpty()) {
               return existing;
            }
         }

         String fresh = Integer.toHexString(new Random().nextInt(16777216) | 1048576);
         plugin.getDataFolder().mkdirs();
         Files.write(file.toPath(), fresh.getBytes(StandardCharsets.UTF_8));
         return fresh;
      } catch (IOException e) {
         plugin.getLogger().warning("Could not persist a server id (" + e.getMessage() + "); falling back to the host name.");

         try {
            return InetAddress.getLocalHost().getHostName();
         } catch (Exception ignored) {
            return "local";
         }
      }
   }

   private void initDb() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_server_status (server_id VARCHAR(32) PRIMARY KEY, online INT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL)"
         );
         st.executeUpdate("DELETE FROM taxsmp_server_status WHERE updated_at < " + (System.currentTimeMillis() - 86400000L));
         this.plugin.getLogger().info("Network player count: this server reports as '" + this.serverId + "' (must differ from the other server's id).");
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not create server status table: " + e.getMessage());
      }
   }

   private void updateNow() {
      int localOnline = Bukkit.getOnlinePlayers().size();
      if (!this.usingDb()) {
         this.networkOnline = localOnline;
      } else {
         long now = System.currentTimeMillis();

         try (Connection conn = this.db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                  "INSERT INTO taxsmp_server_status (server_id, online, updated_at) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE online = VALUES(online), updated_at = VALUES(updated_at)"
               )) {
               ps.setString(1, this.serverId);
               ps.setInt(2, localOnline);
               ps.setLong(3, now);
               ps.executeUpdate();
            }

            try (PreparedStatement ps = conn.prepareStatement(
                  "SELECT COALESCE(SUM(online), 0) AS total FROM taxsmp_server_status WHERE server_id <> ? AND updated_at >= ?"
               )) {
               ps.setString(1, this.serverId);
               ps.setLong(2, now - 90000L);

               try (ResultSet rs = ps.executeQuery()) {
                  if (rs.next()) {
                     this.remoteOnline = Math.max(0, rs.getInt("total"));
                     this.remoteReadAt = now;
                  }
               }
            }
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Could not update network player count: " + e.getMessage());
         }

         this.networkOnline = Bukkit.getOnlinePlayers().size() + this.trustedRemote();
      }
   }

   private int trustedRemote() {
      if (this.remoteReadAt == 0L) {
         return 0;
      } else {
         return System.currentTimeMillis() - this.remoteReadAt > 120000L ? 0 : this.remoteOnline;
      }
   }

   public int getNetworkOnline() {
      return Bukkit.getOnlinePlayers().size() + this.trustedRemote();
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Bukkit.getAsyncScheduler().runNow(this.plugin, t -> this.updateNow());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      Bukkit.getAsyncScheduler().runDelayed(this.plugin, t -> this.updateNow(), 1L, TimeUnit.SECONDS);
   }

   public void shutdown() {
      if (this.task != null) {
         try {
            this.task.cancel();
         } catch (Throwable var7) {
         }
      }

      if (this.usingDb()) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement(
               "INSERT INTO taxsmp_server_status (server_id, online, updated_at) VALUES (?, 0, ?) ON DUPLICATE KEY UPDATE online = 0, updated_at = VALUES(updated_at)"
            );
         ) {
            ps.setString(1, this.serverId);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Could not mark server offline in player count table: " + e.getMessage());
         }
      }
   }
}
