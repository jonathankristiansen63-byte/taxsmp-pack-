package com.taxsmp;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

public class PhantomBlocker implements Listener {
   private final TaxSMP plugin;
   private static final EnumSet<SpawnReason> AMBIENT_SPAWN_REASONS = EnumSet.of(
      SpawnReason.NATURAL,
      SpawnReason.CHUNK_GEN,
      SpawnReason.JOCKEY,
      SpawnReason.MOUNT,
      SpawnReason.PATROL,
      SpawnReason.RAID,
      SpawnReason.VILLAGE_INVASION,
      SpawnReason.NETHER_PORTAL,
      SpawnReason.TRAP,
      SpawnReason.LIGHTNING
   );
   private static final Set<EntityType> NEW_UNDEAD_TYPES = typesByName("PARCHED", "CAMEL_HUSK", "ZOMBIE_NAUTILUS");
   private static final Set<EntityType> EXTRA_SUPPRESSED_TYPES = typesByName("GLOW_SQUID", "PARCHED", "CAMEL_HUSK", "ZOMBIE_NAUTILUS");

   public PhantomBlocker(TaxSMP plugin) {
      this.plugin = plugin;
   }

   private static Set<EntityType> typesByName(String... names) {
      EnumSet<EntityType> found = EnumSet.noneOf(EntityType.class);

      for (String name : names) {
         try {
            found.add(EntityType.valueOf(name));
         } catch (IllegalArgumentException var7) {
         }
      }

      return found;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onPhantomSpawn(CreatureSpawnEvent event) {
      if (event.getEntityType() == EntityType.PHANTOM || event.getEntityType() == EntityType.BAT) {
         event.setCancelled(true);
      } else if (this.isAmbientSpawn(event.getSpawnReason(), event.getEntityType()) && this.isSuppressibleMob(event)) {
         World world = event.getLocation().getWorld();
         if (world != null) {
            Location loc = event.getLocation();
            Player nearest = null;
            double bestSq = Double.MAX_VALUE;

            try {
               for (Player p : world.getPlayers()) {
                  double dSq = p.getLocation().distanceSquared(loc);
                  if (dSq < bestSq) {
                     bestSq = dSq;
                     nearest = p;
                  }
               }
            } catch (Exception ignored) {
               return;
            }

            if (nearest != null && this.plugin.isMobSpawnsDisabledFor(nearest)) {
               event.setCancelled(true);
            }
         }
      }
   }

   private boolean isAmbientSpawn(SpawnReason reason, EntityType type) {
      return AMBIENT_SPAWN_REASONS.contains(reason) || reason == SpawnReason.DEFAULT && NEW_UNDEAD_TYPES.contains(type);
   }

   private boolean isSuppressibleMob(CreatureSpawnEvent event) {
      Entity entity = event.getEntity();
      if (entity instanceof Monster || entity instanceof Slime || entity instanceof Ghast) {
         return true;
      } else {
         return entity instanceof Enemy && !(entity instanceof EnderDragon) ? true : EXTRA_SUPPRESSED_TYPES.contains(event.getEntityType());
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onEntitySpawn(EntitySpawnEvent event) {
      if (event.getEntityType() == EntityType.PHANTOM || event.getEntityType() == EntityType.BAT) {
         event.setCancelled(true);
      }
   }
}
