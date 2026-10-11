package com.taxsmp;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;

public final class TopCache {
   private static final long REFRESH_MILLIS = 60000L;
   private final TaxSMP plugin;
   private final ConcurrentHashMap<StatsTracker.StatType, List<Entry<UUID, Long>>> lists = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<StatsTracker.StatType, Long> refreshedAt = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<StatsTracker.StatType, Boolean> refreshing = new ConcurrentHashMap<>();

   public TopCache(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public List<Entry<UUID, Long>> get(StatsTracker.StatType type) {
      List<Entry<UUID, Long>> current = this.lists.getOrDefault(type, Collections.emptyList());
      long age = System.currentTimeMillis() - this.refreshedAt.getOrDefault(type, 0L);
      if (age > 60000L && this.refreshing.putIfAbsent(type, Boolean.TRUE) == null) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            try {
               StatsTracker tracker = this.plugin.getStatsTracker();
               if (tracker != null) {
                  this.lists.put(type, tracker.getSorted(type));
                  this.refreshedAt.put(type, System.currentTimeMillis());
               }
            } catch (Exception var7) {
            } finally {
               this.refreshing.remove(type);
            }
         });
      }

      return current;
   }
}
