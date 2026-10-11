package me.kirug.signgui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

public class SignGUI {
   private static Plugin plugin;
   private String[] lines = new String[]{"", "", "", ""};
   private BiConsumer<Player, String> handler;
   private int handlerLine = 0;

   private SignGUI() {
   }

   public static void init(Plugin plugin) {
      SignGUI.plugin = plugin;
   }

   public static SignGUI.Builder builder() {
      return new SignGUI.Builder();
   }

   public void open(Player player) {
      if (plugin == null) {
         player.sendMessage("§cSignGUI not initialized");
      } else {
         try {
            this.openSignEditor(player);
         } catch (Exception e) {
            player.sendMessage("§cFailed to open sign UI");
            e.printStackTrace();
         }
      }
   }

   private void openSignEditor(Player player) throws Exception {
      Location loc = player.getLocation();
      int x = loc.getBlockX();
      int y = loc.getWorld().getMinHeight();
      int z = loc.getBlockZ();
      Location signLoc = new Location(loc.getWorld(), x, y, z);
      BlockData originalBlock = signLoc.getBlock().getBlockData();
      player.sendBlockChange(signLoc, Material.OAK_SIGN.createBlockData());
      player.sendSignChange(signLoc, this.lines);
      Object channel = getPlayerChannel(player);
      String handlerName = "signgui_" + System.nanoTime();
      boolean[] done = new boolean[]{false};
      Runnable cleanup = () -> {
         try {
            player.sendBlockChange(signLoc, originalBlock);
         } catch (Throwable var4x) {
         }
      };
      ClassLoader cl = SignGUI.class.getClassLoader();
      Object handler = Proxy.newProxyInstance(cl, new Class[]{Class.forName("io.netty.channel.ChannelInboundHandler")}, (proxy, method, args) -> {
         if ("channelRead".equals(method.getName()) && args != null && args.length == 2) {
            Object msg = args[1];
            if (msg != null && msg.getClass().getName().endsWith("ServerboundSignUpdatePacket")) {
               try {
                  int[] p = extractBlockPos(msg);
                  if (p != null && p[0] == x && p[1] == y && p[2] == z) {
                     String[] resultLines = extractSignLines(msg);
                     removeChannelHandler(channel, handlerName);
                     player.getScheduler().run(plugin, task -> {
                        done[0] = true;
                        cleanup.run();
                        String text = resultLines[this.handlerLine];
                        if (this.handler != null) {
                           this.handler.accept(player, text);
                        }
                     }, null);
                     return null;
                  }
               } catch (Throwable var19) {
               }
            }

            Object ctx = args[0];
            Method fire = ctx.getClass().getMethod("fireChannelRead", Object.class);
            fire.invoke(ctx, args[1]);
            return null;
         } else {
            if (args != null && args.length > 0) {
               Object ctx = args[0];
               String mname = method.getName();

               try {
                  String fireName = "fire" + Character.toUpperCase(mname.charAt(0)) + mname.substring(1);

                  for (Method m : ctx.getClass().getMethods()) {
                     if (m.getName().equals(fireName)) {
                        return m.invoke(ctx, Arrays.copyOfRange(args, 1, args.length));
                     }
                  }
               } catch (Throwable var20) {
               }
            }

            return null;
         }
      });
      addChannelHandler(channel, handlerName, handler);
      player.getScheduler().runDelayed(plugin, task -> {
         try {
            sendOpenSignEditor(player, x, y, z);
         } catch (Throwable t) {
            removeChannelHandler(channel, handlerName);
            cleanup.run();
         }
      }, null, 1L);
      player.getScheduler().runDelayed(plugin, task -> {
         if (!done[0]) {
            removeChannelHandler(channel, handlerName);
            cleanup.run();
         }
      }, null, 600L);
   }

   private static Object getHandle(Player p) throws Exception {
      Method m = p.getClass().getMethod("getHandle");
      return m.invoke(p);
   }

   private static Object getPlayerConnection(Player p) throws Exception {
      Object handle = getHandle(p);
      Field f = handle.getClass().getField("connection");
      return f.get(handle);
   }

   private static Object getPlayerChannel(Player p) throws Exception {
      Object listener = getPlayerConnection(p);
      Field f = listener.getClass().getField("connection");
      Object network = f.get(listener);
      Field channelField = network.getClass().getDeclaredField("channel");
      channelField.setAccessible(true);
      return channelField.get(network);
   }

   private static void addChannelHandler(Object channel, String name, Object handler) throws Exception {
      Class<?> cChannel = Class.forName("io.netty.channel.Channel");
      Class<?> cPipeline = Class.forName("io.netty.channel.ChannelPipeline");
      Class<?> cHandler = Class.forName("io.netty.channel.ChannelHandler");
      Method mPipeline = cChannel.getMethod("pipeline");
      Object pipe = mPipeline.invoke(channel);
      Method addLast = cPipeline.getMethod("addLast", String.class, cHandler);
      addLast.invoke(pipe, name, handler);
   }

   private static void removeChannelHandler(Object channel, String name) {
      try {
         Class<?> cChannel = Class.forName("io.netty.channel.Channel");
         Method mPipeline = cChannel.getMethod("pipeline");
         Object pipe = mPipeline.invoke(channel);
         Method remove = pipe.getClass().getMethod("remove", String.class);
         remove.invoke(pipe, name);
      } catch (Throwable var6) {
      }
   }

   private static void sendOpenSignEditor(Player p, int x, int y, int z) throws Exception {
      Class<?> cBlockPos = Class.forName("net.minecraft.core.BlockPos");
      Constructor<?> posCtor = cBlockPos.getConstructor(int.class, int.class, int.class);
      Object pos = posCtor.newInstance(x, y, z);
      Class<?> cPacket = Class.forName("net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket");

      Object packet;
      try {
         Constructor<?> ctor = cPacket.getConstructor(cBlockPos, boolean.class);
         packet = ctor.newInstance(pos, true);
      } catch (NoSuchMethodException ex) {
         Constructor<?> ctor = cPacket.getConstructor(cBlockPos);
         packet = ctor.newInstance(pos);
      }

      Object connectionListener = getPlayerConnection(p);

      for (Method m : connectionListener.getClass().getMethods()) {
         if (m.getName().equals("send") && m.getParameterCount() == 1) {
            m.invoke(connectionListener, packet);
            break;
         }
      }
   }

   private static int[] extractBlockPos(Object packet) {
      try {
         Object pos;
         try {
            Method m = packet.getClass().getMethod("pos");
            pos = m.invoke(packet);
         } catch (NoSuchMethodException ignored) {
            Field f = packet.getClass().getDeclaredField("pos");
            f.setAccessible(true);
            pos = f.get(packet);
         }

         if (pos == null) {
            return null;
         }

         int y;
         int z;
         int x;
         try {
            x = (Integer)pos.getClass().getMethod("getX").invoke(pos);
            y = (Integer)pos.getClass().getMethod("getY").invoke(pos);
            z = (Integer)pos.getClass().getMethod("getZ").invoke(pos);
         } catch (NoSuchMethodException e) {
            x = (Integer)pos.getClass().getMethod("x").invoke(pos);
            y = (Integer)pos.getClass().getMethod("y").invoke(pos);
            z = (Integer)pos.getClass().getMethod("z").invoke(pos);
         }

         return new int[]{x, y, z};
      } catch (Throwable t) {
         return null;
      }
   }

   private static String[] extractSignLines(Object packet) {
      try {
         Object linesObj;
         try {
            Method m = packet.getClass().getMethod("lines");
            linesObj = m.invoke(packet);
         } catch (NoSuchMethodException ignored) {
            try {
               Method m = packet.getClass().getMethod("getLines");
               linesObj = m.invoke(packet);
            } catch (NoSuchMethodException ignored2) {
               linesObj = null;

               for (Field f : packet.getClass().getDeclaredFields()) {
                  f.setAccessible(true);
                  Class<?> t = f.getType();
                  if (List.class.isAssignableFrom(t) || t.isArray()) {
                     linesObj = f.get(packet);
                     break;
                  }
               }
            }
         }

         List<String> list = new ArrayList<>(4);
         if (linesObj instanceof List) {
            for (Object o : (List)linesObj) {
               list.add(o == null ? "" : String.valueOf(o));
            }
         } else if (linesObj instanceof String[]) {
            for (String s : (String[])linesObj) {
               list.add(s == null ? "" : s);
            }
         }

         while (list.size() < 4) {
            list.add("");
         }

         if (list.size() > 4) {
            list = list.subList(0, 4);
         }

         return list.toArray(new String[0]);
      } catch (Throwable t) {
         return new String[]{"", "", "", ""};
      }
   }

   public static class Builder {
      private final SignGUI gui = new SignGUI();

      public SignGUI.Builder setLines(String... lines) {
         for (int i = 0; i < Math.min(lines.length, 4); i++) {
            this.gui.lines[i] = lines[i] != null ? lines[i] : "";
         }

         return this;
      }

      public SignGUI.Builder setHandler(int line, BiConsumer<Player, String> handler) {
         this.gui.handlerLine = line;
         this.gui.handler = handler;
         return this;
      }

      public SignGUI build() {
         return this.gui;
      }
   }
}
