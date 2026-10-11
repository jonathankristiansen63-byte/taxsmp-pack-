package com.taxsmp;

import java.io.File;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

public final class VaultBridge {
   public static final int MAX_VAULTS = 10;
   private final TaxSMP plugin;

   public VaultBridge(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean isAvailable() {
      return Bukkit.getPluginManager().getPlugin("PlayerVaults") != null;
   }

   private File vaultFile(Player player) {
      File pluginsDir = this.plugin.getDataFolder().getParentFile();
      return new File(pluginsDir, "PlayerVaults" + File.separator + "vaults" + File.separator + player.getUniqueId() + ".yml");
   }

   public int grantedVaults(Player player) {
      File file = this.vaultFile(player);
      return !file.exists() ? 0 : clamp(YamlConfiguration.loadConfiguration(file).getInt("granted", 0));
   }

   public int permissionVaults(Player player) {
      int max = 0;

      for (int i = 1; i <= 10; i++) {
         if (player.hasPermission("playervaults.max." + i)) {
            max = i;
         }
      }

      return max;
   }

   public int effectiveVaults(Player player) {
      return clamp(Math.max(this.permissionVaults(player), this.grantedVaults(player)));
   }

   public int grantNextVault(Player player) {
      int current = this.effectiveVaults(player);
      if (current >= 10) {
         return -1;
      }

      int target = current + 1;
      String name = player.getName();
      Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pv set " + name + " " + target));
      return target;
   }

   private static int clamp(int value) {
      return Math.max(0, Math.min(10, value));
   }
}
