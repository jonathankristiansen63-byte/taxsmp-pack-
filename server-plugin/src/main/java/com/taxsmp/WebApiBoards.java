package com.taxsmp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;

final class WebApiBoards {
   static final StatsTracker.StatType[] TYPES = StatsTracker.StatType.values();
   private static final Comparator<WebApiBoards.Row> ORDER = (a, b) -> {
      int byValue = Long.compare(b.value, a.value);
      return byValue != 0 ? byValue : a.idText.compareTo(b.idText);
   };

   private WebApiBoards() {
   }

   static String key(String name) {
      return name.toLowerCase(Locale.ROOT).replace(' ', '_');
   }

   static WebApiBoards.Snapshot build(TaxSMP plugin, WebApiPresence presence) {
      StatsTracker tracker = plugin.getStatsTracker();
      SimpleEconomy economy = plugin.getEconomy();
      NetworkPlayerDirectory directory = plugin.getNetworkPlayerDirectory();
      Set<UUID> ids = new HashSet<>();
      Map<UUID, String> names = new HashMap<>();
      if (tracker != null) {
         ids.addAll(tracker.cachedIds());
         if (economy != null) {
            ids.addAll(economy.getKnownPlayers());
         }

         for (UUID id : ids) {
            String name = tracker.cachedName(id);
            if (name == null && directory != null) {
               NetworkPlayerDirectory.KnownPlayer known = directory.cached(id);
               if (known != null) {
                  name = known.name();
               }
            }

            if (name != null) {
               names.put(id, name);
            }
         }
      }

      for (Entry<UUID, String> joined : presence.joinedNames().entrySet()) {
         ids.add(joined.getKey());
         names.put(joined.getKey(), joined.getValue());
      }

      Map<StatsTracker.StatType, WebApiBoards.Board> boards = new EnumMap<>(StatsTracker.StatType.class);
      Set<UUID> ranked = new HashSet<>();

      for (StatsTracker.StatType type : TYPES) {
         List<WebApiBoards.Row> rows = new ArrayList<>();
         if (tracker != null) {
            for (UUID id : ids) {
               long value = type == StatsTracker.StatType.MONEY
                  ? (economy == null ? 0L : (long)Math.floor(economy.balanceOf(id)))
                  : tracker.cachedStat(id, type);
               if (value > 0L) {
                  rows.add(new WebApiBoards.Row(id, value));
                  ranked.add(id);
               }
            }
         }

         rows.sort(ORDER);
         boards.put(type, new WebApiBoards.Board(rows.toArray(new WebApiBoards.Row[0])));
      }

      Map<String, UUID> byKey = new HashMap<>();

      for (Entry<UUID, String> entry : names.entrySet()) {
         String key = key(entry.getValue());
         UUID current = byKey.get(key);
         if (current == null || moreRecent(presence, entry.getKey(), current)) {
            byKey.put(key, entry.getKey());
         }
      }

      return new WebApiBoards.Snapshot(System.currentTimeMillis(), boards, ids, names, byKey, ranked.size());
   }

   private static boolean moreRecent(WebApiPresence presence, UUID candidate, UUID current) {
      long a = presence.recency(candidate);
      long b = presence.recency(current);
      return a != b ? a > b : candidate.toString().compareTo(current.toString()) < 0;
   }

   static final class Board {
      final WebApiBoards.Row[] rows;
      private final Map<UUID, Integer> index;

      Board(WebApiBoards.Row[] rows) {
         this.rows = rows;
         this.index = new HashMap<>(rows.length * 2);

         for (int i = 0; i < rows.length; i++) {
            this.index.put(rows[i].id, i);
         }
      }

      int rankOf(UUID id) {
         Integer i = this.index.get(id);
         return i == null ? 0 : i + 1;
      }
   }

   static final class Row {
      final UUID id;
      final String idText;
      final long value;

      Row(UUID id, long value) {
         this.id = id;
         this.idText = id.toString();
         this.value = value;
      }
   }

   static final class Snapshot {
      final long updated;
      final Map<StatsTracker.StatType, WebApiBoards.Board> boards;
      final Set<UUID> ids;
      final Map<UUID, String> names;
      final Map<String, UUID> byKey;
      final int total;

      Snapshot(long updated, Map<StatsTracker.StatType, WebApiBoards.Board> boards, Set<UUID> ids, Map<UUID, String> names, Map<String, UUID> byKey, int total) {
         this.updated = updated;
         this.boards = boards;
         this.ids = ids;
         this.names = names;
         this.byKey = byKey;
         this.total = total;
      }
   }
}
