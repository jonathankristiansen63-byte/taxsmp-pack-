package com.taxsmp.ui;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class HomeIconCatalog {
   private static final String FALLBACK = "item/paper";
   private static Map<String, String> sprites;
   private static List<String> materials;

   private HomeIconCatalog() {
   }

   static String sprite(String materialName) {
      load();
      return materialName == null ? "item/paper" : sprites.getOrDefault(materialName.toUpperCase(Locale.ROOT), "item/paper");
   }

   static boolean isKnown(String materialName) {
      load();
      return materialName != null && sprites.containsKey(materialName.toUpperCase(Locale.ROOT));
   }

   static List<String> all() {
      load();
      return materials;
   }

   static List<String> search(String query) {
      load();
      if (query != null && !query.isBlank()) {
         String needle = query.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
         List<String> out = new ArrayList<>();

         for (String material : materials) {
            if (material.toLowerCase(Locale.ROOT).contains(needle)) {
               out.add(material);
            }
         }

         return out;
      } else {
         return materials;
      }
   }

   static String prettyName(String materialName) {
      StringBuilder out = new StringBuilder();

      for (String word : materialName.split("_")) {
         if (!word.isEmpty()) {
            if (!out.isEmpty()) {
               out.append(' ');
            }

            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
         }
      }

      return out.toString();
   }

   private static synchronized void load() {
      if (sprites == null) {
         Map<String, String> table = new LinkedHashMap<>();

         try (InputStream in = HomeIconCatalog.class.getClassLoader().getResourceAsStream("item-sprites.txt")) {
            if (in != null) {
               BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

               String line;
               while ((line = reader.readLine()) != null) {
                  if (!line.isBlank() && line.charAt(0) != '#') {
                     int space = line.indexOf(32);
                     if (space > 0) {
                        table.put(line.substring(0, space), line.substring(space + 1).trim());
                     }
                  }
               }
            }
         } catch (Exception var7) {
         }

         sprites = Map.copyOf(table);
         List<String> names = new ArrayList<>(table.keySet());
         names.sort((a, b) -> prettyName(a).compareToIgnoreCase(prettyName(b)));
         materials = List.copyOf(names);
      }
   }
}
