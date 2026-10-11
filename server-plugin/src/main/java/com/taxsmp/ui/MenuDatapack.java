package com.taxsmp.ui;

import com.taxsmp.TaxSMP;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.World;

public final class MenuDatapack {
   private static final String PACK_NAME = "taxsmp-menus";
   private static final String VERSION = "8";
   private static final int LAST_PRE_MINOR_FORMAT = 81;
   private static final int FALLBACK_FORMAT = 94;

   private MenuDatapack() {
   }

   public static boolean install(TaxSMP plugin) {
      Path root = packRoot();
      if (root == null) {
         plugin.getLogger().warning("Could not find the world folder - the pause-menu button was not installed.");
         return false;
      }

      Path stamp = root.resolve(".taxsmp-version");
      int format = dataPackFormat(plugin);
      String want = "10:" + format;

      try {
         if (Files.exists(stamp) && want.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim())) {
            return false;
         }

         write(root.resolve("pack.mcmeta"), packMeta(format));
         write(root.resolve("data/taxsmp/dialog/menu.json"), rootMenu());
         write(root.resolve("data/minecraft/tags/dialog/pause_screen_additions.json"), "{\"values\": [\"taxsmp:menu\"]}");
         write(root.resolve("data/minecraft/tags/dialog/quick_actions.json"), "{\"values\": [\"taxsmp:menu\"]}");
         write(stamp, want);
         plugin.getLogger().info("Installed the TaxSMP pause-menu data pack at " + root + ".");
         return true;
      } catch (IOException e) {
         plugin.getLogger().warning("Could not write the pause-menu data pack: " + e.getMessage());
         return false;
      }
   }

   public static boolean uninstall(TaxSMP plugin) {
      Path root = packRoot();
      if (root != null && Files.exists(root)) {
         try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
               try {
                  Files.deleteIfExists(path);
               } catch (IOException var2x) {
               }
            });
            plugin.getLogger().info("Removed the TaxSMP pause-menu data pack.");
            return true;
         } catch (IOException e) {
            plugin.getLogger().warning("Could not remove the pause-menu data pack: " + e.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   private static Path packRoot() {
      List<World> worlds = Bukkit.getWorlds();
      if (!worlds.isEmpty()) {
         return worlds.get(0).getWorldFolder().toPath().resolve("datapacks").resolve("taxsmp-menus");
      }

      try {
         Path container = Bukkit.getWorldContainer().toPath();
         return container.resolve(levelName(container)).resolve("datapacks").resolve("taxsmp-menus");
      } catch (Throwable ignored) {
         return null;
      }
   }

   private static String levelName(Path container) {
      for (Path candidate : new Path[]{Path.of("server.properties"), container.resolve("server.properties")}) {
         try {
            if (Files.isRegularFile(candidate)) {
               for (String rawLine : Files.readAllLines(candidate, StandardCharsets.UTF_8)) {
                  String line = rawLine.trim();
                  if (line.startsWith("level-name=")) {
                     String value = line.substring("level-name=".length()).trim();
                     if (!value.isEmpty()) {
                        return value;
                     }
                  }
               }
            }
         } catch (Exception var9) {
         }
      }

      return "world";
   }

   private static void write(Path path, String contents) throws IOException {
      Files.createDirectories(path.getParent());
      Files.writeString(path, contents, StandardCharsets.UTF_8);
   }

   private static String packMeta(int format) {
      String versionFields = format > 81 ? "\"min_format\": " + format + ",\n    \"max_format\": " + format : "\"pack_format\": " + format;
      return "{\n  \"pack\": {\n    \"description\": \"TaxSMP menus\",\n    %s\n  }\n}".formatted(versionFields);
   }

   private static int dataPackFormat(TaxSMP plugin) {
      for (ClassLoader loader : new ClassLoader[]{
         Bukkit.getServer().getClass().getClassLoader(), MenuDatapack.class.getClassLoader(), ClassLoader.getSystemClassLoader()
      }) {
         if (loader != null) {
            try (InputStream in = loader.getResourceAsStream("version.json")) {
               if (in != null) {
                  String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                  Matcher m = Pattern.compile("\"data_major\"\\s*:\\s*(\\d+)").matcher(json);
                  if (m.find()) {
                     return Integer.parseInt(m.group(1));
                  }
               }
            } catch (Exception | LinkageError var11) {
            }
         }
      }

      plugin.getLogger().warning("Could not read this server's data pack format; assuming 94. If the pause-menu button does not appear, say so.");
      return 94;
   }

   private static String rootMenu() {
      StringBuilder actions = new StringBuilder();

      for (MenuLayout.Entry entry : MenuLayout.ROOT) {
         if (!actions.isEmpty()) {
            actions.append(",\n    ");
         }

         String atlas = entry.icon().startsWith("block/") ? "blocks" : "items";
         actions.append("{\"label\": [{\"type\": \"object\", \"object\": \"atlas\", \"atlas\": \"minecraft:")
            .append(atlas)
            .append("\", \"sprite\": \"minecraft:")
            .append(escape(entry.icon()))
            .append("\"}, {\"text\": \" ")
            .append(escape(entry.label()))
            .append("\"}]")
            .append(", \"width\": 150, \"action\": {\"type\": \"minecraft:dynamic/custom\", \"id\": \"taxsmp:open/")
            .append(escape(entry.screen()))
            .append("\"}}");
      }

      return "{\n  \"type\": \"minecraft:multi_action\",\n  \"title\": \"TaxSMP\",\n  \"external_title\": \"TaxSMP\",\n  \"can_close_with_escape\": true,\n  \"pause\": false,\n  \"after_action\": \"none\",\n  \"columns\": 2,\n  \"actions\": [\n    %s\n  ]\n}"
         .formatted(actions);
   }

   private static String escape(String text) {
      return text.replace("\\", "\\\\").replace("\"", "\\\"");
   }
}
