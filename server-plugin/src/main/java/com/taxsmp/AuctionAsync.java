package com.taxsmp;

import com.taxsmp.storage.Db;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

public final class AuctionAsync {
   private AuctionAsync() {
   }

   public static void addReturn(AuctionManager var0, TaxSMP var1, Db var2, UUID var3, ItemStack var4, long var5) {
      boolean var7 = var2 != null && var2.isEnabled();
      if (var7 && Bukkit.isPrimaryThread()) {
         ItemStack var8 = var4 == null ? null : var4.clone();

         try {
            Bukkit.getAsyncScheduler().runNow(var1, var5x -> var0.addReturn$taxOrig(var3, var8, var5));
            return;
         } catch (Throwable var10) {
         }
      }

      var0.addReturn$taxOrig(var3, var4, var5);
   }
}
