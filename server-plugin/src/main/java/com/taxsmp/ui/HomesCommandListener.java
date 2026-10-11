package com.taxsmp.ui;

import com.taxsmp.TaxSMP;
import java.util.Locale;
import java.util.Set;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

public final class HomesCommandListener implements Listener {
   private static final Set<String> LABELS = Set.of("home", "homes", "homes:home", "homes:homes", "minecraft:home", "minecraft:homes");
   private static final String PERMISSION = "homes.use";
   private final TaxSMP plugin;
   private final TaxMenus menus;

   public HomesCommandListener(TaxSMP plugin, TaxMenus menus) {
      this.plugin = plugin;
      this.menus = menus;
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onCommand(PlayerCommandPreprocessEvent event) {
      if (this.plugin.getConfig().getBoolean("menus.homes.take-over-command", true)) {
         String[] parts = event.getMessage().trim().split("\\s+");
         if (parts.length == 1) {
            String label = parts[0].toLowerCase(Locale.ROOT);
            if (label.startsWith("/") && LABELS.contains(label.substring(1))) {
               Player player = event.getPlayer();
               if (player.hasPermission("homes.use") && this.menus.enabledFor(player)) {
                  event.setCancelled(true);
                  player.getScheduler().run(this.plugin, task -> this.menus.openHomes(player), null);
               }
            }
         }
      }
   }
}
