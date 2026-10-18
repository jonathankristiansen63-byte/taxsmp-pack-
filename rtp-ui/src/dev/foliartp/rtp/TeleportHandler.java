package dev.foliartp.rtp;

import dev.foliartp.CombatHook;
import dev.foliartp.FoliaRTP;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

public class TeleportHandler {
   private static final long DEFAULT_COOLDOWN_MILLIS = 30000L;
   private static final long SHORT_COOLDOWN_MILLIS = 2000L;
   private static final int MAX_VERTICAL_DELTA = 3;
   private static final Map<UUID, ScheduledTask> pendingTeleports = new ConcurrentHashMap<>();
   private static final Map<UUID, Location> playerStartLocations = new ConcurrentHashMap<>();
   private static final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
   private static final Map<UUID, Runnable> cancelHooks = new ConcurrentHashMap<>();

   public static boolean hasPending(UUID uuid) {
      return pendingTeleports.containsKey(uuid);
   }

   public static void cancelTeleport(Player player) {
      cancelTeleport(player, "Teleport cancelled because you moved.");
   }

   public static void cancelTeleport(Player player, String reason) {
      UUID uuid = player.getUniqueId();
      ScheduledTask task = pendingTeleports.remove(uuid);
      if (task != null) {
         task.cancel();
         playerStartLocations.remove(uuid);
         runCancelHook(uuid);
         player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
         player.sendActionBar(Component.text(reason, NamedTextColor.RED));
      }
   }

   private static void runCancelHook(UUID uuid) {
      Runnable hook = cancelHooks.remove(uuid);
      if (hook != null) {
         hook.run();
      }
   }

   public static void checkMovement(Player player) {
      UUID uuid = player.getUniqueId();
      Location startLoc = playerStartLocations.get(uuid);
      if (startLoc != null) {
         Location current = player.getLocation();
         if (current.getBlockX() != startLoc.getBlockX()
            || current.getBlockZ() != startLoc.getBlockZ()
            || Math.abs(current.getBlockY() - startLoc.getBlockY()) > 3) {
            cancelTeleport(player);
         }
      }
   }

   public static void startTeleportCountdown(Player player, World world, TeleportHandler.AsyncLocationFinder locationFinder, FoliaRTP plugin) {
      startCountdown(player, plugin, "Teleporting in ", () -> performTeleport(player, world, locationFinder, plugin, true), null);
   }

   public static boolean startTransferCountdown(Player player, FoliaRTP plugin, Runnable onComplete, Runnable onCancel) {
      // Cross-server RTP: spin the player up first, then switch servers; the other server drops them in.
      return startCountdown(player, plugin, "Teleporting in ", () -> RtpEffects.launch(player, plugin, onComplete), onCancel);
   }

   private static boolean startCountdown(Player player, FoliaRTP plugin, String label, Runnable onComplete, Runnable onCancel) {
      UUID uuid = player.getUniqueId();
      if (CombatHook.isInCombat(player)) {
         player.sendMessage(Component.text("You cant do this in combat.", NamedTextColor.RED));
         return false;
      }

      long cooldownMillis = getCooldownMillis(player);
      long now = System.currentTimeMillis();
      long cooldownUntil = cooldowns.getOrDefault(uuid, 0L);
      if (cooldownUntil > now) {
         long secondsLeft = (cooldownUntil - now + 999L) / 1000L;
         player.sendMessage(Component.text("You can't rtp for another " + secondsLeft + "s.", NamedTextColor.RED));
         return false;
      }

      if (pendingTeleports.containsKey(uuid)) {
         player.sendMessage(Component.text("You already have a pending teleport!", NamedTextColor.RED));
         return false;
      }

      cooldowns.put(uuid, now + cooldownMillis);
      playerStartLocations.put(uuid, player.getLocation().clone());
      if (onCancel != null) {
         cancelHooks.put(uuid, onCancel);
      }

      plugin.getLogger().info("Starting 5s countdown for " + player.getName());
      AtomicInteger countdown = new AtomicInteger(0);
      ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, scheduledTask -> {
         if (!player.isOnline()) {
            scheduledTask.cancel();
            pendingTeleports.remove(uuid);
            playerStartLocations.remove(uuid);
            runCancelHook(uuid);
         } else if (CombatHook.isInCombat(player)) {
            scheduledTask.cancel();
            pendingTeleports.remove(uuid);
            playerStartLocations.remove(uuid);
            runCancelHook(uuid);
            player.sendMessage(Component.text("You cant do this in combat.", NamedTextColor.RED));
         } else {
            int remaining = countdown.getAndDecrement();
            if (remaining > 0) {
               player.sendActionBar(Component.text(label, NamedTextColor.GRAY).append(Component.text(remaining, NamedTextColor.YELLOW)));
               player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0F, 1.0F);
            } else {
               scheduledTask.cancel();
               pendingTeleports.remove(uuid);
               playerStartLocations.remove(uuid);
               cancelHooks.remove(uuid);
               plugin.getLogger().info("Countdown done for " + player.getName());
               onComplete.run();
            }
         }
      }, () -> {
         pendingTeleports.remove(uuid);
         playerStartLocations.remove(uuid);
         runCancelHook(uuid);
      }, 1L, 20L);
      pendingTeleports.put(uuid, task);
      return true;
   }

   public static void teleportNow(Player player, World world, TeleportHandler.AsyncLocationFinder locationFinder, FoliaRTP plugin) {
      performTeleport(player, world, locationFinder, plugin, false);
   }

   private static long getCooldownMillis(Player player) {
      return player.hasPermission("rtp.cooldown.short") ? 2000L : 30000L;
   }

   private static void performTeleport(Player player, World world, TeleportHandler.AsyncLocationFinder locationFinder, FoliaRTP plugin, boolean effects) {
      if (CombatHook.isInCombat(player)) {
         player.getScheduler().run(plugin, task -> player.sendMessage(Component.text("You cant do this in combat.", NamedTextColor.RED)), null);
      } else {
         locationFinder.find(
            world,
            0,
            safeLoc -> {
               if (safeLoc == null) {
                  plugin.getLogger().warning("Could not find safe location for " + player.getName() + " after max retries");
                  player.getScheduler()
                     .run(plugin, task -> player.sendMessage(Component.text("Could not find a safe location. Please try again.", NamedTextColor.RED)), null);
               } else {
                  plugin.getLogger()
                     .info(
                        "Found safe location for "
                           + player.getName()
                           + " at "
                           + safeLoc.getWorld().getName()
                           + " "
                           + safeLoc.getBlockX()
                           + " "
                           + safeLoc.getBlockY()
                           + " "
                           + safeLoc.getBlockZ()
                     );
                  player.getScheduler().run(plugin, task -> {
                     if (CombatHook.isInCombat(player)) {
                        player.sendMessage(Component.text("You cant do this in combat.", NamedTextColor.RED));
                     } else if (effects) {
                        RtpEffects.launch(player, plugin, () -> teleportTo(player, RtpEffects.dropPoint(safeLoc), true));
                     } else {
                        teleportTo(player, safeLoc, false);
                     }
                  }, null);
               }
            }
         );
      }
   }

   private static void teleportTo(Player player, Location target, boolean effects) {
      if (effects) {
         RtpEffects.expectArrival(player.getUniqueId());
      }
      player.teleportAsync(target).thenAccept(success -> {
         if (success) {
            if (effects) {
               RtpEffects.arrive(player);
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_TELEPORT, 1.0F, 1.0F);
            player.sendMessage(Component.text("You teleported to a random location.", NamedTextColor.GRAY));
         } else {
            player.sendMessage(Component.text("Teleport failed. Please try again.", NamedTextColor.RED));
         }
      });
   }

   @FunctionalInterface
   public interface AsyncLocationFinder {
      void find(World var1, int var2, Consumer<Location> var3);
   }
}
