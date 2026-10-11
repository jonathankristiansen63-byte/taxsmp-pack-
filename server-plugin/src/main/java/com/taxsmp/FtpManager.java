package com.taxsmp;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.taxsmp.storage.Db;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class FtpManager implements CommandExecutor, TabCompleter, Listener {
   private static final String PROXY_CHANNEL = "BungeeCord";
   private static final long DEFAULT_TIMEOUT_SECONDS = 60L;
   private static final long ARRIVAL_RETRY_TICKS = 20L;
   private final TaxSMP plugin;
   private final Db db;
   private final String serverId;

   public FtpManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      this.serverId = plugin.getNetworkPlayerDirectory() == null ? "server" : plugin.getNetworkPlayerDirectory().serverId();
      plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(plugin, task -> this.createTable());
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private long timeoutMillis() {
      return TimeUnit.SECONDS.toMillis(Math.max(5L, this.plugin.getConfig().getLong("ftp.timeout-seconds", 60L)));
   }

   private String proxyNameFor(String server) {
      return this.plugin.getConfig().getString("ftp.server-names." + server, server);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player player) {
         if (!player.hasPermission("taxsmp.ftp")) {
            player.sendMessage("§cYou do not have permission to use this command.");
            return true;
         }

         if (args.length < 1) {
            player.sendMessage("§cUsage: /ftp <player>");
            return true;
         }

         Player local = Bukkit.getPlayerExact(args[0]);
         if (local != null && local.isOnline()) {
            if (local.getUniqueId().equals(player.getUniqueId())) {
               player.sendMessage("§cYou are already there.");
               return true;
            } else {
               this.teleportTo(player, local);
               return true;
            }
         } else {
            NetworkPlayerDirectory directory = this.plugin.getNetworkPlayerDirectory();
            NetworkPlayerDirectory.KnownPlayer known = directory == null ? null : directory.find(args[0]);
            if (known == null) {
               player.sendMessage("§cThat player doesn't exist or has never joined.");
               return true;
            } else if (known.uuid().equals(player.getUniqueId())) {
               player.sendMessage("§cYou are already there.");
               return true;
            } else if (known.online() && !known.server().equalsIgnoreCase(this.serverId)) {
               this.sendAcross(player, known);
               return true;
            } else {
               player.sendMessage("§c" + known.name() + " is not online.");
               return true;
            }
         }
      } else {
         sender.sendMessage("§cOnly players can teleport.");
         return true;
      }
   }

   private void teleportTo(Player traveller, Player target) {
      String targetName = target.getName();
      ScheduledTask reading = target.getScheduler()
         .run(
            this.plugin,
            task -> {
               Location destination = target.getLocation();
               ScheduledTask going = traveller.getScheduler()
                  .run(
                     this.plugin,
                     inner -> traveller.teleportAsync(destination).thenAccept(moved -> this.report(traveller, targetName, Boolean.TRUE.equals(moved))),
                     null
                  );
               if (going == null) {
                  this.plugin.getLogger().fine("Teleport dropped: " + traveller.getName() + " left before arriving.");
               }
            },
            null
         );
      if (reading == null) {
         this.report(traveller, targetName, false);
      }
   }

   private void report(Player traveller, String targetName, boolean moved) {
      traveller.getScheduler()
         .run(
            this.plugin,
            task -> traveller.sendMessage(moved ? "§7Teleported to §f" + targetName + "§7." : "§c" + targetName + " is no longer reachable."),
            null
         );
   }

   private void sendAcross(Player player, NetworkPlayerDirectory.KnownPlayer known) {
      if (!this.usingDb()) {
         player.sendMessage("§c" + known.name() + " is on another server, and cross-server teleport needs MySQL.");
      } else {
         String proxyName = this.proxyNameFor(known.server());
         player.sendMessage("§7Sending you to §f" + known.server().toUpperCase(Locale.ROOT) + "§7...");
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            boolean written = this.writePending(player.getUniqueId(), known);
            player.getScheduler().run(this.plugin, inner -> {
               if (player.isOnline()) {
                  if (!written) {
                     player.sendMessage("§cCould not set up the teleport. Try again in a moment.");
                  } else {
                     this.connect(player, proxyName);
                  }
               }
            }, null);
         });
      }
   }

   private void connect(Player player, String proxyName) {
      ByteArrayDataOutput out = ByteStreams.newDataOutput();
      out.writeUTF("Connect");
      out.writeUTF(proxyName);
      player.sendPluginMessage(this.plugin, "BungeeCord", out.toByteArray());
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent event) {
      if (this.usingDb()) {
         Player player = event.getPlayer();
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            FtpManager.Pending pending = this.takePending(player.getUniqueId());
            if (pending != null) {
               this.finishArrival(player, pending, true);
            }
         });
      }
   }

   private void finishArrival(Player player, FtpManager.Pending pending, boolean mayRetry) {
      Player target = Bukkit.getPlayer(pending.target());
      if (target != null && target.isOnline()) {
         this.teleportTo(player, target);
      } else if (mayRetry) {
         player.getScheduler().runDelayed(this.plugin, task -> this.finishArrival(player, pending, false), null, 20L);
      } else {
         player.getScheduler().run(this.plugin, task -> player.sendMessage("§c" + pending.targetName() + " left before you got there."), null);
      }
   }

   private void createTable() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_ftp_pending (player VARCHAR(36) NOT NULL PRIMARY KEY,target VARCHAR(36) NOT NULL,target_name VARCHAR(32),created_at BIGINT NOT NULL)"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_ftp_pending table: " + e.getMessage());
      }
   }

   private boolean writePending(UUID player, NetworkPlayerDirectory.KnownPlayer known) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO taxsmp_ftp_pending (player, target, target_name, created_at) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE target = VALUES(target), target_name = VALUES(target_name), created_at = VALUES(created_at)"
         );
      ) {
         ps.setString(1, player.toString());
         ps.setString(2, known.uuid().toString());
         ps.setString(3, known.name());
         ps.setLong(4, System.currentTimeMillis());
         ps.executeUpdate();
         return true;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not queue a cross-server teleport for " + player + ": " + e.getMessage());
         return false;
      }
   }

   private FtpManager.Pending takePending(UUID player) {
      try (Connection conn = this.db.getConnection()) {
         FtpManager.Pending pending = null;

         try (PreparedStatement ps = conn.prepareStatement("SELECT target, target_name, created_at FROM taxsmp_ftp_pending WHERE player = ?")) {
            ps.setString(1, player.toString());

            try (ResultSet rs = ps.executeQuery()) {
               if (rs.next()) {
                  boolean fresh = System.currentTimeMillis() - rs.getLong("created_at") <= this.timeoutMillis();
                  if (fresh) {
                     pending = new FtpManager.Pending(UUID.fromString(rs.getString("target")), rs.getString("target_name"));
                  }
               }
            }
         }

         try (PreparedStatement del = conn.prepareStatement("DELETE FROM taxsmp_ftp_pending WHERE player = ? OR created_at < ?")) {
            del.setString(1, player.toString());
            del.setLong(2, System.currentTimeMillis() - this.timeoutMillis());
            del.executeUpdate();
         }

         return pending;
      } catch (SQLException | IllegalArgumentException e) {
         this.plugin.getLogger().warning("Could not read a pending teleport for " + player + ": " + e.getMessage());
         return null;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      return args.length == 1 && sender.hasPermission("taxsmp.ftp") && this.plugin.getNetworkPlayerDirectory() != null
         ? this.plugin.getNetworkPlayerDirectory().completeNames(args[0])
         : List.of();
   }

   private record Pending(UUID target, String targetName) {
   }
}
