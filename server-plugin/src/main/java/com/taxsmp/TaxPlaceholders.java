package com.taxsmp;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map.Entry;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public class TaxPlaceholders extends PlaceholderExpansion {
   private final TaxSMP plugin;
   private final TopCache tops;

   public TaxPlaceholders(TaxSMP plugin) {
      this.plugin = plugin;
      this.tops = new TopCache(plugin);
   }

   public String getIdentifier() {
      return "taxsmp";
   }

   public String getAuthor() {
      return "TaxSMP";
   }

   public String getVersion() {
      return "1.0";
   }

   public boolean persist() {
      return true;
   }

   public String onRequest(OfflinePlayer offlinePlayer, String params) {
      if (offlinePlayer != null && params != null) {
         String key = params.toLowerCase();
         if (key.startsWith("top_")) {
            return this.top(key.substring(4));
         }

         UUID id = offlinePlayer.getUniqueId();
         Player online = offlinePlayer.getPlayer();
         StatsTracker stats = this.plugin.getStatsTracker();
         switch (params.toLowerCase()) {
            case "chattag":
            case "tag":
               ChatTagManager tags = this.plugin.getChatTagManager();
               return tags == null ? "" : tags.getActiveDisplay(id);
            case "money":
            case "balance":
               return PriceManager.formatShort(this.balance(offlinePlayer));
            case "money_raw":
            case "balance_raw":
               return String.format(Locale.ROOT, "%.2f", this.balance(offlinePlayer));
            case "eggs":
               return String.valueOf(this.plugin.getEggManager() == null ? 0 : this.plugin.getEggManager().getEggs(id));
            case "gems":
               return String.valueOf(this.plugin.getGemManager() == null ? 0 : this.plugin.getGemManager().getGems(id));
            case "kills":
               return stat(stats, id, StatsTracker.StatType.PLAYER_KILLS);
            case "deaths":
               return stat(stats, id, StatsTracker.StatType.DEATHS);
            case "mobs_killed":
               return stat(stats, id, StatsTracker.StatType.MOBS_KILLED);
            case "blocks_placed":
               return stat(stats, id, StatsTracker.StatType.BLOCKS_PLACED);
            case "blocks_broken":
               return stat(stats, id, StatsTracker.StatType.BLOCKS_BROKEN);
            case "playtime":
               return stats == null ? "" : PlaytimeFormatter.compact(stats.getStat(id, StatsTracker.StatType.PLAYTIME));
            case "playtime_ticks":
               return stats == null ? "0" : String.valueOf(stats.getStat(id, StatsTracker.StatType.PLAYTIME));
            case "playtime_minutes":
               return stats == null ? "0" : String.valueOf(stats.getStat(id, StatsTracker.StatType.PLAYTIME) / 20L / 60L);
            case "sell_earned":
               return PriceManager.formatShort(this.plugin.getPlayerTotal(id, "sell_made"));
            case "sell_earned_raw":
               return String.format(Locale.ROOT, "%.2f", this.plugin.getPlayerTotal(id, "sell_made"));
            case "shop_spent":
               return PriceManager.formatShort(this.plugin.getPlayerTotal(id, "shop_spent"));
            case "shop_spent_raw":
               return String.format(Locale.ROOT, "%.2f", this.plugin.getPlayerTotal(id, "shop_spent"));
            case "ping":
               return online == null ? "0" : String.valueOf(online.getPing());
            case "players":
               ScoreboardManager network = this.plugin.getScoreboardManager();
               return network == null ? "0" : String.valueOf(network.networkOnlineCount());
            case "team":
               ScoreboardManager teams = this.plugin.getScoreboardManager();
               return teams != null && online != null ? teams.getCleanTeamName(online) : "";
            case "bedrock":
               ScoreboardManager platform = this.plugin.getScoreboardManager();
               return platform != null && online != null && platform.isBedrockPlayer(online) ? "true" : "false";
            default:
               return "";
         }
      } else {
         return "";
      }
   }

   private String top(String query) {
      boolean wantsName = query.contains("_name_");
      int cut = wantsName ? query.indexOf("_name_") : query.lastIndexOf(95);
      if (cut < 1) {
         return "";
      }

      String[] parts = new String[]{query.substring(0, cut), query.substring(cut + (wantsName ? "_name_".length() : 1))};
      if (parts.length != 2) {
         return "";
      }

      StatsTracker.StatType type = statType(parts[0]);
      if (type == null) {
         return "";
      }

      int rank;
      try {
         rank = Integer.parseInt(parts[1].trim());
      } catch (NumberFormatException notANumber) {
         return "";
      }

      List<Entry<UUID, Long>> list = this.tops.get(type);
      if (rank >= 1 && rank <= list.size()) {
         Entry<UUID, Long> entry = list.get(rank - 1);
         if (!wantsName) {
            return StatsTracker.formatValue(type, entry.getValue());
         }

         StatsTracker tracker = this.plugin.getStatsTracker();
         return tracker == null ? "" : tracker.getName(entry.getKey());
      } else {
         return "";
      }
   }

   private static StatsTracker.StatType statType(String name) {
      return switch (name) {
         case "money", "balance" -> StatsTracker.StatType.MONEY;
         case "kills" -> StatsTracker.StatType.PLAYER_KILLS;
         case "deaths" -> StatsTracker.StatType.DEATHS;
         case "playtime" -> StatsTracker.StatType.PLAYTIME;
         case "blocks_placed" -> StatsTracker.StatType.BLOCKS_PLACED;
         case "blocks_broken" -> StatsTracker.StatType.BLOCKS_BROKEN;
         case "mobs_killed" -> StatsTracker.StatType.MOBS_KILLED;
         default -> null;
      };
   }

   private double balance(OfflinePlayer player) {
      return this.plugin.getEconomy() == null ? 0.0 : this.plugin.getEconomy().getBalance(player);
   }

   private static String stat(StatsTracker stats, UUID id, StatsTracker.StatType type) {
      return stats == null ? "0" : String.valueOf(stats.getStat(id, type));
   }
}
