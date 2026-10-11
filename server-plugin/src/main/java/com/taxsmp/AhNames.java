package com.taxsmp;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class AhNames {
   private static final long HIT_TTL_MILLIS = 900000L;
   private static final long MISS_TTL_MILLIS = 300000L;
   private static final ConcurrentHashMap<UUID, AhNames.Entry> CACHE = new ConcurrentHashMap<>();

   private AhNames() {
   }

   static String name(UUID id) {
      if (id == null) {
         return null;
      }

      long now = System.currentTimeMillis();
      AhNames.Entry cached = CACHE.get(id);
      if (cached != null && cached.expiresAt() > now) {
         return cached.name();
      }

      String name = null;

      try {
         Player online = Bukkit.getPlayer(id);
         if (online != null) {
            name = online.getName();
         }

         if (name == null) {
            name = Bukkit.getOfflinePlayer(id).getName();
         }
      } catch (Throwable t) {
         return cached != null ? cached.name() : null;
      }

      CACHE.put(id, new AhNames.Entry(name, now + (name == null ? 300000L : 900000L)));
      return name;
   }

   private record Entry(String name, long expiresAt) {
   }
}
