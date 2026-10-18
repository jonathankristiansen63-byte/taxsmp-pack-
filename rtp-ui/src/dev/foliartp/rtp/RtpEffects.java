package dev.foliartp.rtp;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The one place RTP goes through for an effect. There is no animation: the owner asked for plain,
 * instant teleports (the riptide launch turned the camera straight up). Add a look here, not at the
 * call sites, if one is wanted again.
 */
public final class RtpEffects {
   private RtpEffects() {
   }

   public static void register(Plugin plugin) {
   }

   /** Runs the teleport straight away. */
   public static void launch(Player player, Plugin plugin, Runnable next) {
      next.run();
   }

   public static void expectArrival(UUID uuid) {
   }

   /** Nothing on arrival. */
   public static void arrive(Player player) {
   }
}
