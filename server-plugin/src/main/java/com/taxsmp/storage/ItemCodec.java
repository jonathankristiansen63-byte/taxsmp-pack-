package com.taxsmp.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

public final class ItemCodec {
   private ItemCodec() {
   }

   public static String encode(ItemStack item) {
      if (item != null && !item.getType().isAir()) {
         YamlConfiguration c = new YamlConfiguration();
         c.set("i", item);
         return c.saveToString();
      } else {
         return null;
      }
   }

   public static ItemStack decode(String s) {
      if (s != null && !s.isEmpty()) {
         try {
            YamlConfiguration c = new YamlConfiguration();
            c.loadFromString(s);
            return c.getItemStack("i");
         } catch (Exception e) {
            return null;
         }
      } else {
         return null;
      }
   }
}
