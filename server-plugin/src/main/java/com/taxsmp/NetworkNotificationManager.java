package com.taxsmp;

import com.taxsmp.storage.Db;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class NetworkNotificationManager implements Listener {
   private static final int MAX_PER_POLL = 20;
   private final TaxSMP plugin;
   private final Db db;
   private ScheduledTask task;

   public NetworkNotificationManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         this.db.async(this::initDb);
         this.task = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> this.pollOnlinePlayers(), 3L, 2L, TimeUnit.SECONDS);
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
            "CREATE TABLE IF NOT EXISTS taxsmp_notifications (id BIGINT AUTO_INCREMENT PRIMARY KEY, uuid VARCHAR(36) NOT NULL, channel VARCHAR(32) NOT NULL DEFAULT 'generic', sender_uuid VARCHAR(36), sender_name VARCHAR(32), message TEXT NOT NULL, sound VARCHAR(96), created_at BIGINT NOT NULL, INDEX idx_notify_uuid (uuid, id))"
         );

         try {
            st.executeUpdate("ALTER TABLE taxsmp_notifications ADD COLUMN channel VARCHAR(32) NOT NULL DEFAULT 'generic'");
         } catch (SQLException var9) {
         }

         try {
            st.executeUpdate("ALTER TABLE taxsmp_notifications ADD COLUMN sender_uuid VARCHAR(36)");
         } catch (SQLException var8) {
         }

         try {
            st.executeUpdate("ALTER TABLE taxsmp_notifications ADD COLUMN sender_name VARCHAR(32)");
         } catch (SQLException var7) {
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not create notification table: " + e.getMessage());
      }
   }

   public boolean sendAuctionAlert(UUID uuid, String message) {
      return this.send(uuid, message, Sound.ENTITY_EXPERIENCE_ORB_PICKUP.name(), "auction");
   }

   public boolean sendPayAlert(UUID uuid, String message) {
      return this.send(uuid, message, null, "pay");
   }

   public boolean sendBalanceAlert(UUID uuid, String message) {
      return this.send(uuid, message, Sound.ENTITY_PLAYER_LEVELUP.name(), "balance");
   }

   public boolean sendGemsAlert(UUID uuid, String message) {
      return this.send(uuid, message, Sound.ENTITY_PLAYER_LEVELUP.name(), "gems");
   }

   public boolean sendEggsAlert(UUID uuid, String message) {
      return this.send(uuid, message, Sound.ENTITY_PLAYER_LEVELUP.name(), "eggs");
   }

   public boolean sendPrivateMessage(UUID recipient, UUID senderUuid, String senderName, String message) {
      return this.send(recipient, message, Sound.ENTITY_CHICKEN_EGG.name(), "msg", senderUuid, senderName);
   }

   public boolean send(UUID uuid, String message, String sound) {
      return this.send(uuid, message, sound, "generic");
   }

   public boolean send(UUID uuid, String message, String sound, String channel) {
      return this.send(uuid, message, sound, channel, null, null);
   }

   public boolean send(UUID uuid, String message, String sound, String channel, UUID senderUuid, String senderName) {
      if (uuid != null && message != null && !message.isBlank()) {
         Player local = Bukkit.getPlayer(uuid);
         if (local != null && local.isOnline()) {
            local.getScheduler().run(this.plugin, task -> this.deliver(local, message, sound, channel, senderUuid, senderName), null);
            return true;
         }

         if (!this.usingDb()) {
            return false;
         }

         this.db
            .async(
               () -> {
                  try (
                     Connection conn = this.db.getConnection();
                     PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO taxsmp_notifications (uuid, channel, sender_uuid, sender_name, message, sound, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                     );
                  ) {
                     ps.setString(1, uuid.toString());
                     ps.setString(2, channel != null && !channel.isBlank() ? channel : "generic");
                     ps.setString(3, senderUuid == null ? null : senderUuid.toString());
                     ps.setString(4, senderName);
                     ps.setString(5, message);
                     ps.setString(6, sound);
                     ps.setLong(7, System.currentTimeMillis());
                     ps.executeUpdate();
                  } catch (SQLException e) {
                     this.plugin.getLogger().warning("Could not queue network notification for " + uuid + ": " + e.getMessage());
                  }
               }
            );
         return true;
      } else {
         return false;
      }
   }

   private void pollOnlinePlayers() {
      if (this.usingDb()) {
         Map<UUID, Player> online = new HashMap<>();

         for (Player player : Bukkit.getOnlinePlayers()) {
            if (player != null && player.isOnline()) {
               online.put(player.getUniqueId(), player);
            }
         }

         if (!online.isEmpty()) {
            this.db.async(() -> {
               Map<UUID, List<NetworkNotificationManager.QueuedNotification>> notesByPlayer = this.claimNotifications(online.keySet());
               if (!notesByPlayer.isEmpty()) {
                  for (Entry<UUID, List<NetworkNotificationManager.QueuedNotification>> entry : notesByPlayer.entrySet()) {
                     Player playerx = online.get(entry.getKey());
                     if (playerx != null) {
                        List<NetworkNotificationManager.QueuedNotification> notes = entry.getValue();
                        playerx.getScheduler().run(this.plugin, t -> {
                           if (playerx.isOnline()) {
                              for (NetworkNotificationManager.QueuedNotification note : notes) {
                                 this.deliver(playerx, note.message, note.sound, note.channel, note.senderUuid, note.senderName);
                              }
                           }
                        }, null);
                     }
                  }
               }
            });
         }
      }
   }

   private Map<UUID, List<NetworkNotificationManager.QueuedNotification>> claimNotifications(Collection<UUID> uuids) {
      Map<UUID, List<NetworkNotificationManager.QueuedNotification>> notes = new HashMap<>();
      if (uuids.isEmpty()) {
         return notes;
      }

      Connection conn = null;

      try {
         conn = this.db.getConnection();
         conn.setAutoCommit(false);
         List<Long> ids = new ArrayList<>();
         String placeholders = String.join(",", Collections.nCopies(uuids.size(), "?"));

         try (PreparedStatement ps = conn.prepareStatement(
               "SELECT id, uuid, channel, sender_uuid, sender_name, message, sound FROM taxsmp_notifications WHERE uuid IN ("
                  + placeholders
                  + ") ORDER BY id LIMIT ? FOR UPDATE"
            )) {
            int i = 1;

            for (UUID uuid : uuids) {
               ps.setString(i++, uuid.toString());
            }

            ps.setInt(i, Math.max(20, uuids.size() * 20));

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  long id = rs.getLong("id");
                  UUID uuid = UUID.fromString(rs.getString("uuid"));
                  String senderRaw = rs.getString("sender_uuid");
                  UUID senderUuid = senderRaw != null && !senderRaw.isBlank() ? UUID.fromString(senderRaw) : null;
                  ids.add(id);
                  notes.computeIfAbsent(uuid, ignored -> new ArrayList<>())
                     .add(
                        new NetworkNotificationManager.QueuedNotification(
                           id, rs.getString("channel"), senderUuid, rs.getString("sender_name"), rs.getString("message"), rs.getString("sound")
                        )
                     );
               }
            }
         }

         if (!ids.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_notifications WHERE id = ?")) {
               for (Long id : ids) {
                  ps.setLong(1, id);
                  ps.addBatch();
               }

               ps.executeBatch();
            }
         }

         conn.commit();
      } catch (SQLException e) {
         notes.clear();
         this.plugin.getLogger().warning("Could not claim network notifications: " + e.getMessage());
         if (conn != null) {
            try {
               conn.rollback();
            } catch (SQLException var33) {
            }
         }
      } finally {
         if (conn != null) {
            try {
               conn.setAutoCommit(true);
            } catch (SQLException var32) {
            }

            try {
               conn.close();
            } catch (SQLException var31) {
            }
         }
      }

      return notes;
   }

   private void deliver(Player player, String message, String soundName, String channel, UUID senderUuid, String senderName) {
      String normalized = channel == null ? "generic" : channel.toLowerCase(Locale.ROOT);
      this.refreshSyncedCache(player, normalized);
      if ((!"auction".equals(normalized) || this.plugin.isAuctionAlertsEnabledFor(player))
         && (!"pay".equals(normalized) || this.plugin.isPayAlertsEnabledFor(player))) {
         if ("msg".equals(normalized)) {
            if (!this.plugin.isPrivateMessagesEnabledFor(player)) {
               return;
            }

            if (senderUuid != null && this.plugin.getIgnoreManager() != null && this.plugin.getIgnoreManager().isIgnoring(player.getUniqueId(), senderUuid)) {
               return;
            }

            if (senderUuid != null && this.plugin.getReplyCommand() != null) {
               this.plugin.getReplyCommand().setLastMessaged(player.getUniqueId(), senderUuid);
            }
         }

         player.sendMessage(message);
         if (soundName != null && !soundName.isBlank()) {
            try {
               Sound sound = Sound.valueOf(soundName);
               player.playSound(player.getLocation(), sound, 1.0F, 1.0F);
            } catch (IllegalArgumentException var9) {
            }
         }
      }
   }

   private void refreshSyncedCache(Player player, String channel) {
      UUID uuid = player.getUniqueId();
      if (("pay".equals(channel) || "balance".equals(channel)) && this.plugin.getEconomy() != null) {
         this.plugin.getEconomy().loadFromDb(uuid);
         player.getScheduler().runDelayed(this.plugin, task -> {
            if (player.isOnline()) {
               this.plugin.getEconomy().loadFromDb(uuid);
            }
         }, null, 40L);
      } else if ("gems".equals(channel) && this.plugin.getGemManager() != null) {
         this.plugin.getGemManager().loadFromDb(uuid);
         player.getScheduler().runDelayed(this.plugin, task -> {
            if (player.isOnline()) {
               this.plugin.getGemManager().loadFromDb(uuid);
            }
         }, null, 40L);
      } else if ("eggs".equals(channel) && this.plugin.getEggManager() != null) {
         this.plugin.getEggManager().loadFromDb(uuid);
         player.getScheduler().runDelayed(this.plugin, task -> {
            if (player.isOnline()) {
               this.plugin.getEggManager().loadFromDb(uuid);
            }
         }, null, 40L);
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      event.getPlayer().getScheduler().runDelayed(this.plugin, task -> this.pollOnlinePlayers(), null, 40L);
   }

   public void shutdown() {
      if (this.task != null) {
         try {
            this.task.cancel();
         } catch (Throwable var2) {
         }
      }
   }

   private record QueuedNotification(long id, String channel, UUID senderUuid, String senderName, String message, String sound) {
   }
}
