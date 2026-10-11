package com.taxsmp;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Material;

public class PriceManager {
   private static final Map<Material, Double> sellPrices = new EnumMap<>(Material.class);
   private static final Map<Material, Double> shopPrices = new EnumMap<>(Material.class);

   public static double getPrice(Material m) {
      return getSellPrice(m);
   }

   public static double getSellPrice(Material m) {
      if (m == null) {
         return 0.0;
      }

      double val = sellPrices.getOrDefault(m, 0.0);
      return Math.min(val, 1.0E9);
   }

   public static void setPrice(Material m, double price) {
      setSellPrice(m, price);
   }

   public static void setSellPrice(Material m, double price) {
      if (m != null) {
         double safe = Math.max(0.0, Math.min(price, 1.0E9));
         sellPrices.put(m, safe);
      }
   }

   public static double getShopPrice(Material m) {
      if (m == null) {
         return 0.0;
      }

      double val = shopPrices.getOrDefault(m, 0.0);
      return Math.min(val, 1.0E9);
   }

   public static Map<Material, Double> getShopPrices() {
      return new EnumMap<>(shopPrices);
   }

   public static void setShopPrice(Material m, double price) {
      if (m != null) {
         double safe = Math.max(0.0, Math.min(price, 1.0E9));
         shopPrices.put(m, safe);
      }
   }

   public static String formatPrice(double amt) {
      if (amt >= 9.99995E11) {
         return String.format("%.2fT", amt / 1.0E12);
      } else if (amt >= 9.99995E8) {
         return String.format("%.2fB", amt / 1.0E9);
      } else if (amt >= 999995.0) {
         return String.format("%.2fM", amt / 1000000.0);
      } else if (amt >= 999.995) {
         return String.format("%.2fK", amt / 1000.0);
      } else {
         return amt == (long)amt ? String.format("%.0f", amt) : String.format("%.2f", amt);
      }
   }

   public static String formatShort(double amt) {
      if (amt >= 9.99995E11) {
         return trimZeros(amt / 1.0E12) + "T";
      } else if (amt >= 9.99995E8) {
         return trimZeros(amt / 1.0E9) + "B";
      } else if (amt >= 999995.0) {
         return trimZeros(amt / 1000000.0) + "M";
      } else {
         return amt >= 999.995 ? trimZeros(amt / 1000.0) + "k" : trimZeros(amt);
      }
   }

   private static String trimZeros(double v) {
      String s = String.format("%.2f", v);
      if (s.indexOf(46) >= 0) {
         s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
      }

      return s;
   }

   public static double getBroPrice(Material m) {
      return getShopPrice(m);
   }

   public static double parseAmount(String s) throws NumberFormatException {
      if (s == null) {
         throw new NumberFormatException("null");
      }

      s = s.trim().toLowerCase().replace(",", "");
      double mult = 1.0;
      if (s.endsWith("k")) {
         mult = 1000.0;
         s = s.substring(0, s.length() - 1);
      } else if (s.endsWith("m")) {
         mult = 1000000.0;
         s = s.substring(0, s.length() - 1);
      } else if (s.endsWith("b")) {
         mult = 1.0E9;
         s = s.substring(0, s.length() - 1);
      }

      double amt = Double.parseDouble(s) * mult;
      return Math.min(amt, 1.0E10);
   }

   static {
      shopPrices.put(Material.OBSIDIAN, 100.0);
      shopPrices.put(Material.COBWEB, 300.0);
      shopPrices.put(Material.WATER_BUCKET, 1500.0);
      shopPrices.put(Material.LAVA_BUCKET, 1500.0);
      shopPrices.put(Material.COBBLESTONE, 155.0);
      shopPrices.put(Material.END_CRYSTAL, 250.0);
      shopPrices.put(Material.RESPAWN_ANCHOR, 500.0);
      shopPrices.put(Material.GLOWSTONE, 50.0);
      shopPrices.put(Material.TOTEM_OF_UNDYING, 50000.0);
      shopPrices.put(Material.ENDER_PEARL, 1000.0);
      shopPrices.put(Material.GOLDEN_APPLE, 300.0);
      shopPrices.put(Material.EXPERIENCE_BOTTLE, 200.0);
      shopPrices.put(Material.TIPPED_ARROW, 500.0);
      shopPrices.put(Material.ENDER_CHEST, 1250.0);
      shopPrices.put(Material.END_STONE, 8.0);
      shopPrices.put(Material.DRAGON_BREATH, 750.0);
      shopPrices.put(Material.END_ROD, 50.0);
      shopPrices.put(Material.CHORUS_FRUIT, 75.0);
      shopPrices.put(Material.POPPED_CHORUS_FRUIT, 22.0);
      shopPrices.put(Material.SHULKER_SHELL, 400.0);
      shopPrices.put(Material.SHULKER_BOX, 1000.0);
      shopPrices.put(Material.BLAZE_ROD, 125.0);
      shopPrices.put(Material.NETHER_WART, 75.0);
      shopPrices.put(Material.GLOWSTONE_DUST, 10.0);
      shopPrices.put(Material.MAGMA_CREAM, 75.0);
      shopPrices.put(Material.GHAST_TEAR, 325.0);
      shopPrices.put(Material.QUARTZ, 25.0);
      shopPrices.put(Material.SOUL_SAND, 40.0);
      shopPrices.put(Material.MAGMA_BLOCK, 25.0);
      shopPrices.put(Material.CRYING_OBSIDIAN, 125.0);
      shopPrices.put(Material.REDSTONE_TORCH, 250.0);
      shopPrices.put(Material.REDSTONE, 225.0);
      shopPrices.put(Material.REDSTONE_BLOCK, 2500.0);
      shopPrices.put(Material.DISPENSER, 1250.0);
      shopPrices.put(Material.OBSERVER, 2500.0);
      shopPrices.put(Material.REPEATER, 2500.0);
      shopPrices.put(Material.COMPARATOR, 1250.0);
      shopPrices.put(Material.PISTON, 2500.0);
      shopPrices.put(Material.STICKY_PISTON, 5000.0);
      shopPrices.put(Material.SLIME_BLOCK, 2500.0);
      shopPrices.put(Material.DROPPER, 1250.0);
      shopPrices.put(Material.TARGET, 1250.0);
      shopPrices.put(Material.NOTE_BLOCK, 1250.0);
      shopPrices.put(Material.REDSTONE_LAMP, 2500.0);
      shopPrices.put(Material.POTATO, 75.0);
      shopPrices.put(Material.SWEET_BERRIES, 45.0);
      shopPrices.put(Material.MELON_SLICE, 30.0);
      shopPrices.put(Material.CARROT, 75.0);
      shopPrices.put(Material.APPLE, 20.0);
      shopPrices.put(Material.COOKED_CHICKEN, 45.0);
      shopPrices.put(Material.COOKED_BEEF, 30.0);
      shopPrices.put(Material.GOLDEN_CARROT, 110.0);
   }
}
