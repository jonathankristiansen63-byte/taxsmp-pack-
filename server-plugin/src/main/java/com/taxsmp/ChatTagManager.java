package com.taxsmp;

import com.taxsmp.storage.Db;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class ChatTagManager implements Listener {
   static final String OWNED_KEY = "taxsmp_tags";
   static final String ACTIVE_KEY = "taxsmp_tag";
   private final TaxSMP plugin;
   private final Db db;
   private final ConcurrentHashMap<UUID, Set<String>> ownedCache = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, String> activeCache = new ConcurrentHashMap<>();
   private final Set<UUID> loading = ConcurrentHashMap.newKeySet();
   private ScheduledTask dbRefreshTask;

   public ChatTagManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         this.db.async(this::initDb);
         this.dbRefreshTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> this.refreshOnlineFromDb(), 5L, 15L, TimeUnit.SECONDS);
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
         st.executeUpdate("CREATE TABLE IF NOT EXISTS taxsmp_chat_tags (uuid VARCHAR(36) PRIMARY KEY, owned TEXT NOT NULL, active VARCHAR(32))");

         try (
            PreparedStatement ps = conn.prepareStatement("SELECT uuid, owned, active FROM taxsmp_chat_tags");
            ResultSet rs = ps.executeQuery();
         ) {
            while (rs.next()) {
               UUID uuid = UUID.fromString(rs.getString("uuid"));
               Set<String> owned = parseOwned(rs.getString("owned"));
               String active = normalizeTagId(rs.getString("active"));
               this.ownedCache.put(uuid, owned);
               if (active != null) {
                  this.activeCache.put(uuid, active);
               }
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not initialize TaxSMP chat tag sync: " + e.getMessage());
      }
   }

   private void refreshOnlineFromDb() {
      if (this.usingDb()) {
         List<UUID> ids = new ArrayList<>();

         for (Player player : Bukkit.getOnlinePlayers()) {
            ids.add(player.getUniqueId());
         }

         if (!ids.isEmpty()) {
            String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));

            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement("SELECT uuid, owned, active FROM taxsmp_chat_tags WHERE uuid IN (" + placeholders + ")");
            ) {
               for (int i = 0; i < ids.size(); i++) {
                  ps.setString(i + 1, ids.get(i).toString());
               }

               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     try {
                        UUID uuid = UUID.fromString(rs.getString("uuid"));
                        Set<String> owned = parseOwned(rs.getString("owned"));
                        String active = normalizeTagId(rs.getString("active"));
                        if (this.cacheChanged(uuid, owned, active)) {
                           this.cache(uuid, owned, active);
                           this.mirrorLuckPerms(uuid, owned, active);
                        }
                     } catch (IllegalArgumentException var12) {
                     }
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not refresh online chat tags: " + e.getMessage());
            }
         }
      }
   }

   private User user(UUID uuid) {
      LuckPerms lp = this.plugin.getLuckPerms();
      return lp == null ? null : lp.getUserManager().getUser(uuid);
   }

   private String readMeta(User u, String key) {
      if (u == null) {
         return null;
      }

      for (MetaNode mn : u.getNodes(NodeType.META)) {
         if (mn.getMetaKey().equals(key)) {
            return mn.getMetaValue();
         }
      }

      return null;
   }

   public Set<String> getOwnedIds(UUID uuid) {
      if (this.usingDb()) {
         Set<String> cached = this.ownedCache.get(uuid);
         if (cached != null) {
            return new LinkedHashSet<>(cached);
         }

         this.loadFromDbAsync(uuid);
      }

      return parseOwned(this.readMeta(this.user(uuid), "taxsmp_tags"));
   }

   public boolean owns(UUID uuid, ChatTag tag) {
      return tag != null && this.getOwnedIds(uuid).contains(tag.id());
   }

   public ChatTag getActive(UUID uuid) {
      if (this.usingDb()) {
         String active = this.activeCache.get(uuid);
         if (active != null) {
            return ChatTag.fromId(active);
         }

         this.loadFromDbAsync(uuid);
      }

      return ChatTag.fromId(this.readMeta(this.user(uuid), "taxsmp_tag"));
   }

   public String getActiveDisplay(UUID uuid) {
      ChatTag t = this.getActive(uuid);
      return t == null ? "" : t.display();
   }

   private void clearKey(User u, String key) {
      u.data().clear(n -> n instanceof MetaNode mn && mn.getMetaKey().equals(key));
   }

   private void save(User u) {
      LuckPerms lp = this.plugin.getLuckPerms();
      if (lp != null) {
         lp.getUserManager().saveUser(u);
      }
   }

   public boolean grantAndEquip(UUID uuid, ChatTag tag) {
      if (uuid != null && tag != null) {
         Set<String> owned = this.getOwnedIds(uuid);
         owned.add(tag.id());
         String active = tag.id();
         this.cache(uuid, owned, active);
         this.mirrorLuckPerms(uuid, owned, active);
         if (this.usingDb()) {
            this.saveDbAsync(uuid, owned, active);
            return true;
         } else {
            return this.user(uuid) != null;
         }
      } else {
         return false;
      }
   }

   public boolean setActive(UUID uuid, ChatTag tag) {
      if (uuid == null) {
         return false;
      } else {
         Set<String> owned = this.getOwnedIds(uuid);
         String active = tag == null ? null : tag.id();
         this.cache(uuid, owned, active);
         this.mirrorLuckPerms(uuid, owned, active);
         if (this.usingDb()) {
            this.saveDbAsync(uuid, owned, active);
            return true;
         } else {
            return this.user(uuid) != null;
         }
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      if (this.usingDb()) {
         UUID uuid = event.getPlayer().getUniqueId();
         this.loadFromDbAsync(uuid);
      }
   }

   private void loadFromDbAsync(UUID uuid) {
      if (this.loading.add(uuid)) {
         this.db.async(() -> {
            try {
               this.loadFromDbDirect(uuid);
            } finally {
               this.loading.remove(uuid);
            }
         });
      }
   }

   private void loadFromDbDirect(UUID uuid) {
      if (!this.loadFromDbNow(uuid)) {
         Set<String> legacyOwned = parseOwned(this.readMeta(this.user(uuid), "taxsmp_tags"));
         String legacyActive = normalizeTagId(this.readMeta(this.user(uuid), "taxsmp_tag"));
         if (!legacyOwned.isEmpty() || legacyActive != null) {
            if (legacyActive != null) {
               legacyOwned.add(legacyActive);
            }

            this.cache(uuid, legacyOwned, legacyActive);
            this.saveDbAsync(uuid, legacyOwned, legacyActive);
         }
      }
   }

   private boolean loadFromDbNow(UUID uuid) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT owned, active FROM taxsmp_chat_tags WHERE uuid = ?");
      ) {
         ps.setString(1, uuid.toString());

         try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
               Set<String> owned = parseOwned(rs.getString("owned"));
               String active = normalizeTagId(rs.getString("active"));
               if (this.cacheChanged(uuid, owned, active)) {
                  this.cache(uuid, owned, active);
                  this.mirrorLuckPerms(uuid, owned, active);
               }

               return true;
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not load TaxSMP chat tags for " + uuid + ": " + e.getMessage());
      }

      return false;
   }

   private void saveDbAsync(UUID uuid, Set<String> owned, String active) {
      Bukkit.getAsyncScheduler()
         .runNow(
            this.plugin,
            task -> {
               try (
                  Connection conn = this.db.getConnection();
                  PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO taxsmp_chat_tags (uuid, owned, active) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE owned = VALUES(owned), active = VALUES(active)"
                  );
               ) {
                  ps.setString(1, uuid.toString());
                  ps.setString(2, String.join(",", owned));
                  ps.setString(3, active);
                  ps.executeUpdate();
               } catch (SQLException e) {
                  this.plugin.getLogger().warning("Could not save TaxSMP chat tags for " + uuid + ": " + e.getMessage());
               }
            }
         );
   }

   private void cache(UUID uuid, Set<String> owned, String active) {
      this.ownedCache.put(uuid, new LinkedHashSet<>(owned));
      if (active != null && !active.isBlank()) {
         this.activeCache.put(uuid, active);
      } else {
         this.activeCache.remove(uuid);
      }
   }

   private boolean cacheChanged(UUID uuid, Set<String> owned, String active) {
      Set<String> oldOwned = this.ownedCache.get(uuid);
      String oldActive = this.activeCache.get(uuid);
      return oldOwned == null || !oldOwned.equals(owned) || (oldActive == null ? active != null : !oldActive.equals(active));
   }

   private void mirrorLuckPerms(UUID uuid, Set<String> owned, String active) {
      User u = this.user(uuid);
      if (u != null) {
         this.clearKey(u, "taxsmp_tags");
         if (!owned.isEmpty()) {
            u.data().add(MetaNode.builder("taxsmp_tags", String.join(",", owned)).build());
         }

         this.clearKey(u, "taxsmp_tag");
         if (active != null && !active.isBlank()) {
            u.data().add(MetaNode.builder("taxsmp_tag", active).build());
         }

         this.save(u);
      }
   }

   private static Set<String> parseOwned(String csv) {
      Set<String> out = new LinkedHashSet<>();
      if (csv != null && !csv.isBlank()) {
         for (String s : csv.split(",")) {
            String normalized = normalizeTagId(s);
            if (normalized != null) {
               out.add(normalized);
            }
         }

         return out;
      } else {
         return out;
      }
   }

   private static String normalizeTagId(String value) {
      if (value != null && !value.isBlank()) {
         ChatTag tag = ChatTag.fromId(value.trim());
         return tag == null ? null : tag.id();
      } else {
         return null;
      }
   }
}
