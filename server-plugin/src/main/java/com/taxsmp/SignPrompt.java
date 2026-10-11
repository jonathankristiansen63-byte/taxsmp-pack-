package com.taxsmp;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType.Play.Client;
import com.comphenix.protocol.PacketType.Play.Server;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public final class SignPrompt {
   private static Plugin plugin;
   private static ProtocolManager protocol;
   private static boolean registered = false;
   private static final Map<UUID, SignPrompt.Pending> pending = new ConcurrentHashMap<>();

   private SignPrompt() {
   }

   public static void init(Plugin pl) {
      plugin = pl;

      try {
         protocol = ProtocolLibrary.getProtocolManager();
         register();
      } catch (Throwable t) {
         protocol = null;
         if (plugin != null) {
            plugin.getLogger().warning("ProtocolLib not available; sign input disabled: " + t.getMessage());
         }
      }
   }

   public static boolean isAvailable() {
      return protocol != null;
   }

   private static void register() {
      if (!registered && protocol != null) {
         registered = true;
         protocol.addPacketListener(new PacketAdapter(plugin, ListenerPriority.LOWEST, Client.UPDATE_SIGN) {
            public void onPacketReceiving(PacketEvent event) {
               Player player = event.getPlayer();
               if (player != null) {
                  SignPrompt.Pending p = SignPrompt.pending.remove(player.getUniqueId());
                  if (p != null) {
                     event.setCancelled(true);
                     String input = "";

                     try {
                        String[] lines = (String[])event.getPacket().getStringArrays().read(0);
                        if (lines != null && lines.length > 0 && lines[0] != null) {
                           input = lines[0].trim();
                        }
                     } catch (Throwable var6) {
                     }

                     String result = input;
                     player.getScheduler().run(this.plugin, task -> {
                        try {
                           player.sendBlockChange(p.loc, p.original);
                        } catch (Throwable var5x) {
                        }

                        if (p.callback != null) {
                           p.callback.accept(result);
                        }
                     }, null);
                  }
               }
            }
         });
      }
   }

   public static void open(Player player, String[] promptLines, Consumer<String> callback) {
      if (protocol == null) {
         init(plugin);
      }

      if (protocol == null) {
         player.sendMessage("§cSign input is unavailable (ProtocolLib missing).");
      } else {
         Location loc = player.getLocation().clone();
         int y = Math.max(player.getWorld().getMinHeight(), loc.getBlockY() - 4);
         loc = new Location(player.getWorld(), loc.getBlockX(), y, loc.getBlockZ());
         BlockData original = loc.getBlock().getBlockData();
         String[] four = new String[]{"", "", "", ""};
         if (promptLines != null) {
            for (int i = 0; i < 4 && i < promptLines.length; i++) {
               four[i] = promptLines[i] == null ? "" : promptLines[i];
            }
         }

         pending.put(player.getUniqueId(), new SignPrompt.Pending(loc, original, callback));
         player.sendBlockChange(loc, Material.OAK_SIGN.createBlockData());
         player.sendSignChange(loc, four);

         try {
            PacketContainer packet = protocol.createPacket(Server.OPEN_SIGN_EDITOR);
            packet.getBlockPositionModifier().write(0, new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));

            try {
               packet.getBooleans().write(0, true);
            } catch (Throwable var10) {
            }

            protocol.sendServerPacket(player, packet);
         } catch (Throwable t) {
            pending.remove(player.getUniqueId());

            try {
               player.sendBlockChange(loc, original);
            } catch (Throwable var9) {
            }

            player.sendMessage("§cCould not open the sign.");
         }
      }
   }

   private static final class Pending {
      final Location loc;
      final BlockData original;
      final Consumer<String> callback;

      Pending(Location loc, BlockData original, Consumer<String> callback) {
         this.loc = loc;
         this.original = original;
         this.callback = callback;
      }
   }
}
