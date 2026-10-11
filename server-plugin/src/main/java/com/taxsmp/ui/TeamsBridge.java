package com.taxsmp.ui;

import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class TeamsBridge {
   private TeamsBridge() {
   }

   static boolean openTeamHome(Player player, Consumer<Player> back) {
      Plugin teams = teams();
      if (teams == null) {
         return false;
      }

      try {
         return Boolean.TRUE.equals(teams.getClass().getMethod("openTeamHomeMenu", Player.class, Consumer.class).invoke(teams, player, back));
      } catch (Throwable ignored) {
         return false;
      }
   }

   static boolean openTeam(Player player) {
      Plugin teams = teams();
      if (teams == null) {
         return false;
      }

      try {
         teams.getClass().getMethod("openTeamMenu", Player.class).invoke(teams, player);
         return true;
      } catch (Throwable ignored) {
         return false;
      }
   }

   private static Plugin teams() {
      Plugin teams = Bukkit.getPluginManager().getPlugin("TaxTeams");
      return teams != null && teams.isEnabled() ? teams : null;
   }
}
