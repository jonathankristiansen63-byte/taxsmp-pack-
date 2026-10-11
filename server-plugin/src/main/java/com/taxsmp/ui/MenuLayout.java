package com.taxsmp.ui;

import java.util.List;

final class MenuLayout {
   static final List<MenuLayout.Entry> ROOT = List.of(
      new MenuLayout.Entry("Homes", "Your homes and warps", "homes", "item/oak_door"),
      new MenuLayout.Entry("Team", "Your team, members and team home", "team", "item/iron_helmet"),
      new MenuLayout.Entry("Auction", "Browse the auction house", "auction", "item/gold_ingot"),
      new MenuLayout.Entry("Orders", "Place and fill buy orders", "orders", "item/writable_book"),
      new MenuLayout.Entry("Sell", "Sell the items you are holding", "sell", "item/emerald"),
      new MenuLayout.Entry("Sell Multiplier", "View and claim your sell multipliers", "sellmulti", "item/experience_bottle"),
      new MenuLayout.Entry("Shop", "Buy from the server shop", "shop", "item/villager_spawn_egg"),
      new MenuLayout.Entry("Pay", "Send money to another player", "pay", "item/gold_nugget"),
      new MenuLayout.Entry("Teleport", "Ask to teleport to a player", "teleport", "item/ender_pearl"),
      new MenuLayout.Entry("RTP", "Teleport somewhere random", "rtp", "item/ender_eye"),
      new MenuLayout.Entry("RTP Queue", "Join the random teleport queue", "rtpqueue", "item/chorus_fruit"),
      new MenuLayout.Entry("Stats", "Look up a player's stats", "stats", "item/book"),
      new MenuLayout.Entry("Leaderboards", "See who is on top", "leaderboards", "item/nether_star"),
      new MenuLayout.Entry("Settings", "Your personal settings", "settings", "item/comparator")
   );

   static List<String> screens() {
      return ROOT.stream().map(MenuLayout.Entry::screen).toList();
   }

   private MenuLayout() {
   }

   record Entry(String label, String tooltip, String screen, String icon) {
   }
}
