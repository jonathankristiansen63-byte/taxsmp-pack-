package dev.foliartp.rtp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * RTP launch and landing: the player is shot up with a riptide spin (everyone sees it), then dropped in
 * from above the world's build limit over their RTP spot. Arriving above the build limit also makes the
 * client skip the "Loading terrain" screen after a server switch. Fall damage from that drop is cancelled.
 * The nether has a bedrock roof, so nether spots are used as they are.
 */
public final class RtpEffects implements Listener {
   private static final long LAUNCH_TICKS = 12L;
   private static final double LAUNCH_SPEED = 2.0;
   private static final int LAUNCH_SPIN_TICKS = 20;
   private static final int ARRIVAL_SPIN_TICKS = 30;
   private static final long PROTECT_MILLIS = 45000L;
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

   /** Spins the player up (runs on the player's thread), then runs next on the player's thread. */
   public static void launch(Player player, Plugin plugin, Runnable next) {
      protect(player.getUniqueId());
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

   /** Where the player arrives: above the build limit over the spot, except in the nether. */
   public static Location dropPoint(Location ground) {
      World world = ground.getWorld();
      if (world == null || world.getEnvironment() == World.Environment.NETHER) {
         return ground;
      }
      Location sky = ground.clone();
      sky.setY(world.getMaxHeight() + 4);
      return sky;
   }

   /** Called before the player arrives (also off the main thread): no fall damage for the drop. */
   public static void expectArrival(UUID uuid) {
      protect(uuid);
   }

   /** Runs on the player's thread once they're in the world: spin while falling in. */
   public static void arrive(Player player) {
      protect(player.getUniqueId());
      spin(player, ARRIVAL_SPIN_TICKS);
      player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_2, 1.0F, 1.0F);
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
         Location at = player.getLocation();
         player.getWorld().spawnParticle(Particle.CLOUD, at, 30, 0.5, 0.1, 0.5, 0.05);
         player.getWorld().playSound(at, Sound.ITEM_TRIDENT_HIT_GROUND, 1.0F, 1.0F);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      protectedUntil.remove(event.getPlayer().getUniqueId());
   }
}
