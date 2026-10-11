package com.taxsmp.ui;

import com.taxsmp.TaxSMP;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class MenuClickListener implements Listener {
   private static final String NAMESPACE = "taxsmp";
   private static final String PREFIX = "open/";
   private final TaxSMP plugin;
   private final TaxMenus menus;

   public MenuClickListener(TaxSMP plugin, TaxMenus menus) {
      this.plugin = plugin;
      this.menus = menus;
   }

   @EventHandler
   public void onCustomClick(PlayerCustomClickEvent event) {
      Key id = event.getIdentifier();
      if ("taxsmp".equals(id.namespace()) && id.value().startsWith("open/")) {
         if (event.getCommonConnection() instanceof PlayerGameConnection connection) {
            Player player = connection.getPlayer();
            if (player != null) {
               if (!this.menus.enabledFor(player)) {
                  player.sendMessage(
                     this.menus.disabledOnServer()
                        ? "§cThe TaxSMP menu is switched off on this server."
                        : "§cThis menu needs Minecraft 1.21.11 or newer on Java. Use the commands instead."
                  );
               } else {
                  String screen = id.value().substring("open/".length());
                  player.getScheduler().run(this.plugin, task -> this.menus.openScreen(player, screen), null);
               }
            }
         }
      }
   }
}
