package dev.foliartp.rtp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * RTP launch: the player looks straight up and is shot a few blocks into the air with a riptide spin
 * (everyone sees it), then is teleported straight onto their RTP spot. The riptide pose follows the
 * player's pitch, so looking straight up is what makes the spin vertical instead of sideways. Fall
 * damage is cancelled for a short while in case the teleport doesn't happen mid-air.
 */
public final class RtpEffects implements Listener {
   /** About the top of the arc for LAUNCH_SPEED. */
   private static final long LAUNCH_TICKS = 10L;
   /** Roughly 5-6 blocks up. */
   private static final double LAUNCH_SPEED = 1.0;
   private static final int LAUNCH_SPIN_TICKS = 14;
   private static final long PROTECT_MILLIS = 15000L;
   private static final RtpEffects INSTANCE = new RtpEffects();
   private static final AtomicBoolean REGISTERED = new AtomicBoolean();
   private static final Map<UUID, Long> protectedUntil = new ConcurrentHashMap<>();

   private RtpEffects() {
   }

   public static void register(Plugin plugin) {
      if (REGISTERED.compareAndSet(false, true)) {
         plugin.getServer().getPluginManager().registerEvents(INSTANCE, plugin);
      }
   }

   /** Spins the player straight up (runs on the player's thread), then runs next on the player's thread. */
   public static void launch(Player player, Plugin plugin, Runnable next) {
      protect(player.getUniqueId());
      Location lookUp = player.getLocation();
      lookUp.setPitch(-90.0F);
      // Same spot, only the view turned straight up; the velocity is set after because a teleport clears it.
      player.teleportAsync(lookUp, PlayerTeleportEvent.TeleportCause.PLUGIN).whenComplete((ok, error) -> player.getScheduler().run(plugin, task -> {
         if (player.isOnline()) {
            liftOff(player, plugin, next);
         }
      }, null));
   }

   private static void liftOff(Player player, Plugin plugin, Runnable next) {
      spin(player, LAUNCH_SPIN_TICKS);
      player.setVelocity(new Vector(0.0, LAUNCH_SPEED, 0.0));
      Location at = player.getLocation();
      player.getWorld().playSound(at, Sound.ITEM_TRIDENT_RIPTIDE_3, 1.0F, 1.0F);
      player.getWorld().spawnParticle(Particle.CLOUD, at, 20, 0.4, 0.1, 0.4, 0.05);
      player.getScheduler().runDelayed(plugin, task -> {
         if (player.isOnline()) {
            next.run();
         }
      }, null, LAUNCH_TICKS);
   }

   /** Called before the player arrives (also off the main thread). */
   public static void expectArrival(UUID uuid) {
      protect(uuid);
   }

   /** Runs once the player is at the spot: a puff where they land. */
   public static void arrive(Player player) {
      protect(player.getUniqueId());
      Location at = player.getLocation();
      player.getWorld().spawnParticle(Particle.CLOUD, at, 20, 0.4, 0.1, 0.4, 0.05);
   }

   private static void spin(Player player, int ticks) {
      try {
         player.startRiptideAttack(ticks, 0.0F, new ItemStack(Material.TRIDENT));
      } catch (Throwable ignored) {
         // Only the look; the teleport works without it.
      }
   }

   private static void protect(UUID uuid) {
      protectedUntil.put(uuid, System.currentTimeMillis() + PROTECT_MILLIS);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onFall(EntityDamageEvent event) {
      if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
         return;
      }
      Long until = protectedUntil.remove(player.getUniqueId());
      if (until != null && until >= System.currentTimeMillis()) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      protectedUntil.remove(event.getPlayer().getUniqueId());
   }
}
