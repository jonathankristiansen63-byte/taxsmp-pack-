package com.taxsmp;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SpawnHook {
   private static final String SPAWN_PLUGIN_NAME = "SpawnPlugin";

   private SpawnHook() {
   }

   public static Location spawnFor(World world) {
      Location fromPlugin = fromSpawnPlugin(world);
      if (fromPlugin != null) {
         return fromPlugin;
      } else {
         return world == null ? null : world.getSpawnLocation();
      }
   }

   public static Location spawnFor(Player player) {
      return player == null ? null : spawnFor(player.getWorld());
   }

   private static Location fromSpawnPlugin(World world) {
      Plugin plugin = Bukkit.getPluginManager().getPlugin("SpawnPlugin");
      if (plugin != null && plugin.isEnabled()) {
         try {
            Object manager = plugin.getClass().getMethod("getSpawnManager").invoke(plugin);
            if (manager == null) {
               return null;
            }

            Method resolve = manager.getClass().getMethod("resolveSpawn", World.class);
            Object result = resolve.invoke(manager, world);
            return result instanceof Location ? (Location)result : null;
         } catch (ReflectiveOperationException ignored) {
            return null;
         }
      } else {
         return null;
      }
   }
}
