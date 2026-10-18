package com.taxsmp.limboredirect;

import com.google.inject.Inject;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.slf4j.Logger;

/**
 * Sends players to limbo when the server they are on shuts down, whichever server that is.
 *
 * <p>Velocity picks the fallback from the try list ["eu", "limbo", "na"], so a player whose EU
 * server closed landed in limbo, but a player whose NA server closed was sent to EU instead - in
 * the middle of a restart that usually meant a second kick and a disconnect. With the redirect in
 * place both land in limbo, and VelocityAutoReconnect / Limbo Handler bring them back to the server
 * they were on once it answers again.
 */
public final class LimboRedirect {
   private static final String LIMBO = "limbo";
   private static final String SHUTDOWN_KEY = "multiplayer.disconnect.server_shutdown";

   private final ProxyServer proxy;
   private final Logger logger;

   @Inject
   public LimboRedirect(ProxyServer proxy, Logger logger) {
      this.proxy = proxy;
      this.logger = logger;
   }

   // EARLY so the reconnect plugins, which run at the default order, already see the limbo redirect.
   @Subscribe(order = PostOrder.EARLY)
   public void onKicked(KickedFromServerEvent event) {
      if (event.kickedDuringServerConnect() || event.kickedDuringLogin()) {
         return;
      }

      String from = event.getServer().getServerInfo().getName();
      if (LIMBO.equalsIgnoreCase(from) || !isShutdown(event.getServerKickReason().orElse(null))) {
         return;
      }

      Optional<RegisteredServer> limbo = this.proxy.getServer(LIMBO);
      if (limbo.isEmpty()) {
         return;
      }

      event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo.get()));
      this.logger.info("{} was on {} when it closed; holding them in limbo until it is back.", event.getPlayer().getUsername(), from);
   }

   private static boolean isShutdown(Component reason) {
      if (reason == null) {
         return false;
      }

      if (reason instanceof TranslatableComponent translatable && SHUTDOWN_KEY.equals(translatable.key())) {
         return true;
      }

      return PlainTextComponentSerializer.plainText().serialize(reason).contains("Server closed");
   }
}
