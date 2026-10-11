package com.taxsmp;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class CombatHook {
   private CombatHook() {
   }

   public static boolean isHookAvailable() {
      return getCombatManager() != null;
   }

   public static boolean isInCombat(Player player) {
      CombatManager manager = getCombatManager();
      return manager != null && manager.isInCombat(player);
   }

   public static int getRemainingSeconds(Player player) {
      CombatManager manager = getCombatManager();
      return manager == null ? 0 : manager.getRemainingSeconds(player);
   }

   private static CombatManager getCombatManager() {
      Plugin plugin = Bukkit.getPluginManager().getPlugin("TaxSMP");
      return !(plugin instanceof TaxSMP) ? null : ((TaxSMP)plugin).getCombatManager();
   }
}
