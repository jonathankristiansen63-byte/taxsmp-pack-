package com.taxsmp;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.audience.Audience;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class ChatListener implements Listener {
   private final TaxSMP plugin;

   public ChatListener(TaxSMP plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onChat(AsyncChatEvent event) {
      UUID senderUUID = event.getPlayer().getUniqueId();
      Set<Audience> toRemove = new HashSet<>();

      for (Audience viewer : event.viewers()) {
         if (viewer instanceof Player p && !p.getUniqueId().equals(senderUUID)) {
            if (!this.plugin.isPublicChatEnabledFor(p)) {
               toRemove.add(viewer);
            }

            if (this.plugin.isTeamChatEnabledFor(p)) {
               toRemove.add(viewer);
            }

            if (this.plugin.getIgnoreManager().isIgnoring(p.getUniqueId(), senderUUID)) {
               toRemove.add(viewer);
            }
         }
      }

      event.viewers().removeAll(toRemove);
   }
}
