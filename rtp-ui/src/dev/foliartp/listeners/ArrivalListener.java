package dev.foliartp.listeners;

import dev.foliartp.FoliaRTP;
import dev.foliartp.db.RedisStore;
import dev.foliartp.rtp.RtpEffects;
import dev.foliartp.rtp.SafeLocationFinder;
import dev.foliartp.rtp.TeleportHandler;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

public class ArrivalListener implements Listener {
   private static final long MAX_AGE_MILLIS = 120000L;
   private static final long FIND_TIMEOUT_SECONDS = 6L;
   private static final int JOIN_RETRY_ATTEMPTS = 6;
   private static final long PRECOMPUTE_POLL_MILLIS = 1000L;
   private static final int PRECOMPUTE_ROUNDS = 3;
   private final FoliaRTP plugin;
   private final Map<UUID, Location> readyLocations = new ConcurrentHashMap<>();
   private final Map<UUID, Long> readyTimestamps = new ConcurrentHashMap<>();
   private final Map<UUID, CompletableFuture<Location>> inFlight = new ConcurrentHashMap<>();
   private final Map<UUID, String> fallbackDimensions = new ConcurrentHashMap<>();
   private final Set<UUID> completedArrivals = ConcurrentHashMap.newKeySet();

   public ArrivalListener(FoliaRTP plugin) {
      this.plugin = plugin;
      RtpEffects.register(plugin);
      plugin.getServer().getAsyncScheduler().runAtFixedRate(plugin, task -> this.precomputePendingArrivals(), 1000L, 1000L, TimeUnit.MILLISECONDS);
   }

   public void announcePendingArrival(UUID uuid, String dimension) {
      this.plugin.getLogger().info("Incoming cross-server RTP announced for " + uuid + " (dimension=" + dimension + "); finding a location now.");
      this.beginPrecompute(uuid, dimension);
   }

   private void precomputePendingArrivals() {
      RedisStore store = this.plugin.getStore();
      if (store != null) {
         long now = System.currentTimeMillis();
         this.readyTimestamps.entrySet().removeIf(e -> {
            if (now - e.getValue() > 120000L) {
               this.readyLocations.remove(e.getKey());
               return true;
            } else {
               return false;
            }
         });
         Map<UUID, String> pending = store.listPending(this.plugin.getThisRegion(), 120000L);

         for (Entry<UUID, String> entry : pending.entrySet()) {
            this.beginPrecompute(entry.getKey(), entry.getValue());
         }
      }
   }

   private void beginPrecompute(UUID uuid, String dimension) {
      if (!this.readyLocations.containsKey(uuid) && !this.inFlight.containsKey(uuid) && this.plugin.getServer().getPlayer(uuid) == null) {
         World world = this.plugin.getWorldForDimension(dimension);
         if (world == null) {
            dimension = "overworld";
            world = this.plugin.getOverworld();
            if (world == null) {
               return;
            }
         }

         CompletableFuture<Location> future = new CompletableFuture<>();
         if (this.inFlight.putIfAbsent(uuid, future) == null) {
            this.findWithRetries(world, finderFor(dimension), 0, uuid, future);
         }
      }
   }

   private void findWithRetries(World world, TeleportHandler.AsyncLocationFinder finder, int round, UUID uuid, CompletableFuture<Location> future) {
      finder.find(world, 0, loc -> {
         if (loc == null && round + 1 < 3) {
            this.findWithRetries(world, finder, round + 1, uuid, future);
         } else {
            if (loc != null) {
               this.readyLocations.put(uuid, loc);
               this.readyTimestamps.put(uuid, System.currentTimeMillis());
            }

            this.inFlight.remove(uuid);
            future.complete(loc);
         }
      });
   }

   @EventHandler
   public void onPreLogin(AsyncPlayerPreLoginEvent event) {
      UUID uuid = event.getUniqueId();
      String dimension = this.consumePendingArrival(uuid);
      if (dimension != null) {
         this.plugin.getLogger().info("Consumed pending cross-server RTP (dimension=" + dimension + ") for " + event.getName() + " during pre-login.");
         if (!this.readyLocations.containsKey(uuid)) {
            CompletableFuture<Location> running = this.inFlight.get(uuid);
            if (running != null) {
               try {
                  Location loc = running.get(6L, TimeUnit.SECONDS);
                  if (loc == null) {
                     this.fallbackDimensions.put(uuid, dimension);
                  }
               } catch (Exception e) {
                  this.fallbackDimensions.put(uuid, dimension);
               }
            } else {
               World world = this.plugin.getWorldForDimension(dimension);
               if (world == null) {
                  this.plugin.getLogger().warning("No " + dimension + " world on this server; falling back to overworld RTP for " + event.getName() + ".");
                  dimension = "overworld";
                  world = this.plugin.getOverworld();
                  if (world == null) {
                     return;
                  }
               }

               CompletableFuture<Location> future = new CompletableFuture<>();
               finderFor(dimension).find(world, 0, future::complete);

               try {
                  Location loc = future.get(6L, TimeUnit.SECONDS);
                  if (loc != null) {
                     this.readyLocations.put(uuid, loc);
                  } else {
                     this.fallbackDimensions.put(uuid, dimension);
                  }
               } catch (Exception e) {
                  this.fallbackDimensions.put(uuid, dimension);
               }
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onSpawnLocation(PlayerSpawnLocationEvent event) {
      this.readyTimestamps.remove(event.getPlayer().getUniqueId());
      Location loc = this.readyLocations.remove(event.getPlayer().getUniqueId());
      if (loc != null) {
         this.plugin.getLogger().info("Spawning " + event.getPlayer().getName() + " directly at cross-server RTP location.");
         event.setSpawnLocation(loc);
         this.completedArrivals.add(event.getPlayer().getUniqueId());
      }
   }

   // Paper 1.21.6+ no longer fires PlayerSpawnLocationEvent, so the handler above never ran and every
   // cross-server RTP loaded the old spot first, then teleported (two full chunk loads). This places the
   // player straight at the pre-computed RTP location while they join; onJoin still falls back if it's missing.
   @EventHandler(priority = EventPriority.HIGH)
   public void onAsyncSpawnLocation(io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent event) {
      UUID uuid = event.getConnection().getProfile().getId();
      if (uuid == null) {
         return;
      }

      Location loc = this.readyLocations.remove(uuid);
      if (loc != null) {
         this.readyTimestamps.remove(uuid);
         this.plugin.getLogger().info("Spawning " + event.getConnection().getProfile().getName() + " directly at cross-server RTP location.");
         RtpEffects.expectArrival(uuid);
         event.setSpawnLocation(loc);
         this.completedArrivals.add(uuid);
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      UUID uuid = player.getUniqueId();
      if (this.completedArrivals.remove(uuid)) {
         player.getScheduler().run(this.plugin, t -> {
            if (player.isOnline()) {
               RtpEffects.arrive(player);
            }
         }, null);
         player.getScheduler().runDelayed(this.plugin, t -> {
            if (player.isOnline()) {
               player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_TELEPORT, 1.0F, 1.0F);
               player.sendMessage(Component.text("You teleported to a random location.", NamedTextColor.GRAY));
            }
         }, null, 10L);
      } else {
         Location leftover = this.readyLocations.remove(uuid);
         if (leftover != null) {
            this.plugin.getLogger().info("Spawn placement missed for " + player.getName() + "; teleporting to pre-computed RTP location after join.");
            RtpEffects.expectArrival(uuid);
            player.getScheduler().runDelayed(this.plugin, t -> {
               if (player.isOnline()) {
                  player.teleportAsync(leftover).thenAccept(success -> {
                     if (success) {
                        RtpEffects.arrive(player);
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_TELEPORT, 1.0F, 1.0F);
                        player.sendMessage(Component.text("You teleported to a random location.", NamedTextColor.GRAY));
                     }
                  });
               }
            }, null, 1L);
         } else {
            String fallbackDimension = this.fallbackDimensions.remove(uuid);
            if (fallbackDimension == null) {
               this.retryPendingAfterJoin(player, 0);
            } else {
               this.teleportAfterJoin(player, fallbackDimension, "Pre-spawn RTP not ready for ");
            }
         }
      }
   }

   private String consumePendingArrival(UUID uuid) {
      RedisStore store = this.plugin.getStore();
      return store == null ? null : store.consumePending(uuid, this.plugin.getThisRegion(), 120000L);
   }

   private void retryPendingAfterJoin(Player player, int attempt) {
      this.plugin.getServer().getAsyncScheduler().runDelayed(this.plugin, task -> {
         if (player.isOnline()) {
            String dimension = this.consumePendingArrival(player.getUniqueId());
            player.getScheduler().run(this.plugin, entityTask -> {
               if (player.isOnline()) {
                  if (dimension != null) {
                     this.teleportAfterJoin(player, dimension, "Consumed pending cross-server RTP after join for ");
                  } else {
                     if (attempt + 1 < 6) {
                        this.retryPendingAfterJoin(player, attempt + 1);
                     }
                  }
               }
            }, null);
         }
      }, 1L, TimeUnit.SECONDS);
   }

   private void teleportAfterJoin(Player player, String dimension, String logPrefix) {
      World world = this.plugin.getWorldForDimension(dimension);
      if (world == null) {
         world = this.plugin.getOverworld();
         dimension = "overworld";
         if (world == null) {
            return;
         }
      }

      World targetWorld = world;
      TeleportHandler.AsyncLocationFinder finder = finderFor(dimension);
      this.plugin.getLogger().info(logPrefix + player.getName() + "; teleporting after join (dimension=" + dimension + ").");
      player.getScheduler().runDelayed(this.plugin, t -> {
         if (player.isOnline()) {
            TeleportHandler.teleportNow(player, targetWorld, finder, this.plugin);
         }
      }, null, 1L);
   }

   private static TeleportHandler.AsyncLocationFinder finderFor(String dimension) {
      return switch (dimension) {
         case "nether" -> SafeLocationFinder::findSafeNetherAsync;
         case "end" -> SafeLocationFinder::findSafeEndAsync;
         default -> SafeLocationFinder::findSafeOverworldAsync;
      };
   }
}
