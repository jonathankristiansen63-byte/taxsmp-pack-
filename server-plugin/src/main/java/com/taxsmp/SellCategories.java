package com.taxsmp;

import org.bukkit.Material;

public final class SellCategories {
   private SellCategories() {
   }

   public static SellMultiManager.Category categoryOf(Material var0) {
      String var1 = var0.name();
      if (var1.endsWith("SHULKER_BOX")
         || var1.equals("BAMBOO_BLOCK")
         || var1.equals("STRIPPED_BAMBOO_BLOCK")
         || var1.equals("BONE_BLOCK")
         || var1.equals("DRIED_KELP_BLOCK")) {
         return SellMultiManager.Category.BLOCKS;
      } else {
         return !var1.equals("CLOSED_EYEBLOSSOM") && !var1.equals("OPEN_EYEBLOSSOM") && !var1.equals("WILDFLOWERS")
            ? SellMultiManager.categoryOf$taxOrig(var0)
            : SellMultiManager.Category.NATURAL;
      }
   }
}
