package com.taxsmp;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;

final class WebApiPresence implements Listener {
   private static final long FLUSH_SECONDS = 60L;
   private static final long HOOK_RETRY_MILLIS = 30000L;
   private final TaxSMP plugin;
   private final Runnable onChange;
   private final ConcurrentHashMap<UUID, WebApiPresence.Online> online = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, Long> lastSeen = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<UUID, String> joinedNames = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<String, UUID> joinedByKey = new ConcurrentHashMap<>();
   private final Object fileLock = new Object();
   private final AtomicBoolean dirty = new AtomicBoolean();
   private volatile boolean hideVanished = true;
   private volatile boolean running;
   private volatile int maxPlayers;
   private volatile File worldFolder;
   private volatile ScheduledTask flushTask;
   private volatile Method vanishApi;
   private volatile Object essentials;
   private volatile Method userIsVanished;
   private volatile Method essentialsGetUser;
   private volatile long lastHookAttempt;

   WebApiPresence(TaxSMP plugin, Runnable onChange) {
      this.plugin = plugin;
      this.onChange = onChange;
   }

   void start(boolean hideVanished) {
      this.hideVanished = hideVanished;
      this.running = true;
      this.loadLastSeen();
      this.maxPlayers = Bukkit.getMaxPlayers();
      this.resolveWorldFolder();
      this.plugin.getServer().getPluginManager().registerEvents(this, this.plugin);

      for (Player player : Bukkit.getOnlinePlayers()) {
         this.track(player, true);
      }

      this.flushTask = Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin, task -> this.flush(), 60L, 60L, TimeUnit.SECONDS);
   }

   void stop() {
      this.running = false;
      HandlerList.unregisterAll(this);
      ScheduledTask task = this.flushTask;
      if (task != null) {
         task.cancel();
         this.flushTask = null;
      }

      for (WebApiPresence.Online entry : this.online.values()) {
         this.cancelPoll(entry);
      }

      this.online.clear();
      this.flush();
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent event) {
      if (this.running) {
         if (this.worldFolder == null) {
            this.resolveWorldFolder();
         }

         this.track(event.getPlayer(), false);
         this.maxPlayers = Bukkit.getMaxPlayers();
         this.changed();
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onQuit(PlayerQuitEvent event) {
      if (this.running) {
         Player player = event.getPlayer();
         UUID id = player.getUniqueId();
         WebApiPresence.Online gone = this.online.get(id);
         if (gone != null && gone.player == player) {
            this.online.remove(id, gone);
            this.cancelPoll(gone);
         }

         this.lastSeen.put(id, System.currentTimeMillis());
         this.dirty.set(true);
         this.changed();
      }
   }

   private void changed() {
      try {
         this.onChange.run();
      } catch (RuntimeException var2) {
      }
   }

   private void track(Player player, boolean seeded) {
      UUID id = player.getUniqueId();
      String name = player.getName();
      WebApiPresence.Online entry = new WebApiPresence.Online(player, id, name, id.getMostSignificantBits() == 0L);
      entry.vanished = this.hideVanished && (seeded || this.isVanished(player));
      WebApiPresence.Online previous = this.online.put(id, entry);
      if (previous != null) {
         this.cancelPoll(previous);
      }

      String oldName = this.joinedNames.put(id, name);
      if (oldName != null && !oldName.equals(name)) {
         this.joinedByKey.remove(WebApiBoards.key(oldName), id);
      }

      this.joinedByKey.put(WebApiBoards.key(name), id);
      if (this.hideVanished) {
         try {
            entry.poll = player.getScheduler().runAtFixedRate(this.plugin, task -> this.poll(entry, task), null, 1L, 20L);
         } catch (RuntimeException ignored) {
            entry.vanished = false;
         }
      }
   }

   private void poll(WebApiPresence.Online entry, ScheduledTask task) {
      if (this.running && this.online.get(entry.id) == entry) {
         entry.vanished = this.isVanished(entry.player);
      } else {
         task.cancel();
      }
   }

   private void cancelPoll(WebApiPresence.Online entry) {
      ScheduledTask task = entry.poll;
      if (task != null) {
         task.cancel();
      }
   }

   private boolean isVanished(Player player) {
      try {
         for (MetadataValue value : player.getMetadata("vanished")) {
            if (value.asBoolean()) {
               return true;
            }
         }
      } catch (RuntimeException var7) {
      }

      this.resolveHooks();
      Method api = this.vanishApi;
      if (api != null) {
         try {
            if (Boolean.TRUE.equals(api.invoke(null, player))) {
               return true;
            }
         } catch (ReflectiveOperationException | RuntimeException var6) {
         }
      }

      Method getUser = this.essentialsGetUser;
      if (getUser != null) {
         try {
            Object user = getUser.invoke(this.essentials, player);
            if (user != null && Boolean.TRUE.equals(this.userIsVanished.invoke(user))) {
               return true;
            }
         } catch (ReflectiveOperationException | RuntimeException var5) {
         }
      }

      return false;
   }

   private void resolveHooks() {
      if (this.vanishApi == null || this.essentialsGetUser == null) {
         long now = System.currentTimeMillis();
         if (now - this.lastHookAttempt >= 30000L) {
            this.lastHookAttempt = now;

            try {
               if (this.vanishApi == null) {
                  for (String pluginName : new String[]{"PremiumVanish", "SuperVanish"}) {
                     Plugin other = Bukkit.getPluginManager().getPlugin(pluginName);
                     if (other != null && other.isEnabled()) {
                        try {
                           Class<?> api = Class.forName("de.myzelyam.api.vanish.VanishAPI", true, other.getClass().getClassLoader());
                           this.vanishApi = api.getMethod("isInvisible", Player.class);
                           break;
                        } catch (ReflectiveOperationException | LinkageError var11) {
                        }
                     }
                  }
               }

               if (this.essentialsGetUser == null) {
                  Plugin ess = Bukkit.getPluginManager().getPlugin("Essentials");
                  if (ess != null && ess.isEnabled()) {
                     try {
                        Method getUser;
                        try {
                           getUser = ess.getClass().getMethod("getUser", Player.class);
                        } catch (NoSuchMethodException e) {
                           getUser = ess.getClass().getMethod("getUser", Object.class);
                        }

                        this.userIsVanished = getUser.getReturnType().getMethod("isVanished");
                        this.essentials = ess;
                        this.essentialsGetUser = getUser;
                     } catch (ReflectiveOperationException | LinkageError var10) {
                     }
                  }
               }
            } catch (RuntimeException var12) {
            }
         }
      }
   }

   boolean isVisiblyOnline(UUID id) {
      WebApiPresence.Online entry = this.online.get(id);
      return entry != null && !entry.vanished;
   }

   List<WebApiPresence.Online> visibleOnline() {
      List<WebApiPresence.Online> out = new ArrayList<>();

      for (WebApiPresence.Online entry : this.online.values()) {
         if (!entry.vanished) {
            out.add(entry);
         }
      }

      return out;
   }

   int maxPlayers() {
      return this.maxPlayers;
   }

   Map<UUID, String> joinedNames() {
      return this.joinedNames;
   }

   String joinedName(UUID id) {
      return this.joinedNames.get(id);
   }

   UUID joinedByKey(String key) {
      return this.joinedByKey.get(key);
   }

   long recency(UUID id) {
      if (this.online.containsKey(id)) {
         return Long.MAX_VALUE;
      }

      Long seen = this.lastSeen.get(id);
      return seen == null ? 0L : seen;
   }

   Long lastSeenFor(UUID id, long now) {
      WebApiPresence.Online entry = this.online.get(id);
      if (entry != null && !entry.vanished) {
         return now;
      } else {
         Long recorded = this.lastSeen.get(id);
         if (recorded != null) {
            return recorded;
         } else {
            return entry != null ? null : this.playerDataModified(id);
         }
      }
   }

   private Long playerDataModified(UUID id) {
      File world = this.worldFolder;
      if (world == null) {
         return null;
      }

      String file = id + ".dat";

      for (File candidate : new File[]{new File(world, "playerdata/" + file), new File(world, "players/data/" + file)}) {
         long modified = candidate.lastModified();
         if (modified > 0L) {
            return modified;
         }
      }

      return null;
   }

   private void resolveWorldFolder() {
      try {
         List<World> worlds = Bukkit.getWorlds();
         if (!worlds.isEmpty()) {
            this.worldFolder = worlds.get(0).getWorldFolder();
         }
      } catch (RuntimeException var2) {
      }
   }

   private File lastSeenFile() {
      return new File(this.plugin.getDataFolder(), "webapi-lastseen.txt");
   }

   private void loadLastSeen() {
      File file = this.lastSeenFile();
      if (file.isFile()) {
         try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
               int eq = line.indexOf(61);
               if (eq > 0) {
                  try {
                     this.lastSeen.merge(UUID.fromString(line.substring(0, eq).trim()), Long.parseLong(line.substring(eq + 1).trim()), Math::max);
                  } catch (IllegalArgumentException var6) {
                  }
               }
            }
         } catch (IOException e) {
            this.plugin.getLogger().warning("[WebApi] Could not read webapi-lastseen.txt: " + e.getMessage());
         }
      }
   }

   void flush() {
      if (this.dirty.getAndSet(false)) {
         synchronized (this.fileLock) {
            try {
               StringBuilder out = new StringBuilder(this.lastSeen.size() * 56);

               for (Entry<UUID, Long> entry : this.lastSeen.entrySet()) {
                  out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
               }

               Path target = this.lastSeenFile().toPath();
               Path temp = target.resolveSibling(target.getFileName() + ".tmp");
               Files.writeString(temp, out, StandardCharsets.UTF_8);

               try {
                  Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
               } catch (AtomicMoveNotSupportedException e) {
                  Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
               }
            } catch (IOException e) {
               this.dirty.set(true);
               this.plugin.getLogger().warning("[WebApi] Could not save webapi-lastseen.txt: " + e.getMessage());
            }
         }
      }
   }

   static final class Online {
      final Player player;
      final UUID id;
      final String name;
      final boolean bedrock;
      volatile boolean vanished;
      volatile ScheduledTask poll;

      Online(Player player, UUID id, String name, boolean bedrock) {
         this.player = player;
         this.id = id;
         this.name = name;
         this.bedrock = bedrock;
      }
   }
}
