package com.taxsmp;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class EconomyListener implements Listener {
   private final TaxSMP plugin;

   public EconomyListener(TaxSMP plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      UUID uuid = player.getUniqueId();
      if (this.plugin.getEconomy() != null) {
         this.plugin.getEconomy().loadFromDb(uuid);
      }

      if (this.plugin.getGemManager() != null) {
         this.plugin.getGemManager().loadFromDb(uuid);
      }

      if (this.plugin.getIgnoreManager() != null) {
         this.plugin.getIgnoreManager().loadFromDb(uuid);
      }

      if (this.plugin.getOfflinePayManager() != null) {
         this.plugin.getOfflinePayManager().consumePending(uuid, total -> {
            if (total != null && !(total <= 0.0) && player.isOnline()) {
               String mint = "§x§5§5§F§F§B§8";
               String msg = "§7You got paid " + mint + "$" + PriceManager.formatShort(total) + " §7while you were away.";
               player.getScheduler().run(this.plugin, t -> player.sendMessage(msg), null);
            }
         });
      }
   }
}
