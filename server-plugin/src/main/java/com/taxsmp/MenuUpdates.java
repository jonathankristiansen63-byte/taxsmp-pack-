package com.taxsmp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MenuUpdates {
   private static final String SMALL = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
   private static final Map<String, String> TITLES = Collections.synchronizedMap(new LinkedHashMap<>());
   private static final String MINT = "§x§5§5§F§F§B§8";

   private MenuUpdates() {
   }

   public static double validPrice(double var0) {
      return Double.isFinite(var0) && var0 >= 1.0 ? var0 : 0.0;
   }

   public static void reopenOrders(Object var0, Object var1) {
      try {
         Object var2 = call(var1, "getUniqueId");
         Field var3 = var0.getClass().getDeclaredField("viewerPage");
         Field var4 = var0.getClass().getDeclaredField("viewerSearchQuery");
         var3.setAccessible(true);
         var4.setAccessible(true);
         int var6 = ((Map)var3.get(var0)).get(var2) instanceof Integer var7 ? var7 : 1;
         String var10 = (String)((Map)var4.get(var0)).get(var2);
         Method var8 = var0.getClass().getMethod("openOrders", Class.forName("org.bukkit.entity.Player"), int.class, String.class);
         var8.invoke(var0, var1, var6, var10);
      } catch (ReflectiveOperationException var9) {
         throw new IllegalStateException(var9);
      }
   }

   public static void refreshOrders(Object var0, Object var1) {
      Object var2 = call(var1, "getOpenInventory");
      String var3 = internalTitle((String)call(var2, "getOriginalTitle"));
      if (plain(var3).startsWith("orders - ")) {
         reopenOrders(var0, var1);
      }
   }

   public static String plain(String var0) {
      StringBuilder var1 = new StringBuilder();

      for (char var5 : var0.replaceAll("§.", "").toCharArray()) {
         int var6 = "ᴀʙᴄᴅᴇғɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ".indexOf(var5);
         var1.append(var6 < 0 ? var5 : (char)(97 + var6));
      }

      return var1.toString();
   }

   public static String titleCase(String var0) {
      StringBuilder var1 = new StringBuilder();
      boolean var2 = true;

      for (char var6 : var0.toLowerCase(Locale.ROOT).toCharArray()) {
         var1.append(var2 ? Character.toUpperCase(var6) : var6);
         var2 = Character.isWhitespace(var6);
      }

      return var1.toString();
   }

   public static String displayTitle(String var0) {
      String var1 = plain(var0);
      String var2;
      if (var1.startsWith("orders - page ")) {
         var2 = "Orders (Page " + var1.substring(14) + ")";
      } else if (var1.startsWith("orders - ")) {
         var2 = "Orders (" + titleCase(var1.substring(9)) + ")";
      } else if (var1.startsWith("auction house - page ")) {
         var2 = "Auction (Page " + var1.substring(21) + ")";
      } else if (var1.startsWith("auction house - ")) {
         var2 = "Auction (" + titleCase(var1.substring(16)) + ")";
      } else {
         var2 = switch (var1) {
            case "your orders" -> "Orders -> Your Orders";
            case "new order" -> "Orders -> New Order";
            case "select item" -> "Orders -> Select An Item";
            case "pick enchantments" -> "Orders -> Pick Enchantments";
            case "deliver items" -> "Orders -> Deliver Items";
            case "confirm delivery" -> "Orders -> Confirm Delivery";
            case "edit order" -> "Orders -> Edit Order";
            case "collect items" -> "Orders -> Collect Items";
            case "sell order items" -> "Orders -> Sell Items";
            case "cancel order" -> "Orders -> Cancel Order";
            case "auction -> your items" -> "Auction -> Your Items";
            case "confirm ah buy" -> "Auction -> Confirm Purchase";
            case "confirm ah sell" -> "Auction -> Confirm Listing";
            default -> var1.startsWith("transactions (page ") ? "Auction -> Transactions (Page " + var1.substring(19) : var0;
         };
      }

      if (!var2.equals(var0)) {
         TITLES.put(var2, var0);
      }

      return var2;
   }

   public static String internalTitle(String var0) {
      return TITLES.getOrDefault(var0, var0);
   }

   public static String displayTitle(String internal, String display) {
      TITLES.put(display, internal);
      return display;
   }

   private static Object call(Object var0, String var1, Class<?>[] var2, Object... var3) {
      try {
         Method var4 = var0.getClass().getMethod(var1, var2);
         var4.setAccessible(true);
         return var4.invoke(var0, var3);
      } catch (ReflectiveOperationException var5) {
         throw new IllegalStateException("Menu hook: " + var1, var5);
      }
   }

   private static Object call(Object var0, String var1) {
      return call(var0, var1, new Class[0]);
   }

   private static void lore(Object var0, List<String> var1) {
      Object var2 = call(var0, "getItemMeta");
      if (var2 != null) {
         call(var2, "setLore", new Class[]{List.class}, var1);
         setMeta(var0, var2);
      }
   }

   private static void setMeta(Object var0, Object var1) {
      try {
         call(var0, "setItemMeta", new Class[]{Class.forName("org.bukkit.inventory.meta.ItemMeta")}, var1);
      } catch (ClassNotFoundException var3) {
         throw new IllegalStateException(var3);
      }
   }

   private static Object item(Object var0, int var1) {
      return call(var0, "getItem", new Class[]{int.class}, var1);
   }

   private static void clear(Object var0, int var1) {
      call(var0, "clear", new Class[]{int.class}, var1);
   }

   private static void name(Object var0, String var1) {
      Object var2 = call(var0, "getItemMeta");
      if (var2 != null) {
         call(var2, "setDisplayName", new Class[]{String.class}, var1);
         setMeta(var0, var2);
      }
   }

   public static Object prepare(Object var0, String var1) {
      if (var1.equals("openMaterialsList")) {
         for (int var2 = 0; var2 < 45; var2++) {
            Object var3 = item(var0, var2);
            if (var3 != null) {
               String var4 = ((Enum)call(var3, "getType")).name();
               name(var3, "§f" + titleCase(var4.replace('_', ' ')));
               lore(var3, Collections.emptyList());
            }
         }

         clear(var0, 46);

         for (int var11 = 45; var11 < 54; var11++) {
            Object var14 = item(var0, var11);
            if (var14 != null && ((Enum)call(var14, "getType")).name().equals("GRAY_STAINED_GLASS_PANE")) {
               clear(var0, var11);
            }
         }
      }

      if (var1.equals("openNewOrder")) {
         Object var12 = item(var0, 12);
         if (var12 != null) {
            lore(var12, List.of("§7(" + titleCase(((Enum)call(var12, "getType")).name().replace('_', ' ')) + ")"));
         }

         for (int var6 : new int[]{13, 14}) {
            Object var7 = item(var0, var6);
            if (var7 != null) {
               Object var8 = call(var7, "getItemMeta");
               List var9 = (List)call(var8, "getLore");
               if (var9 != null && var9.size() > 1) {
                  lore(var7, List.of(var6 == 13 ? "§fClick To Select Amount" : "§fClick To Select Price", ((String)var9.get(1)).replace("§8", "§7")));
               }
            }
         }
      }

      if (var1.startsWith("open") && (var1.contains("Confirm") || var1.equals("openNewOrder") || var1.equals("openEnchanter"))) {
         int var13 = (Integer)call(var0, "getSize");

         for (int var16 = 0; var16 < var13; var16++) {
            Object var18 = item(var0, var16);
            if (var18 != null) {
               String var19 = ((Enum)call(var18, "getType")).name();
               if (var19.equals("LIME_STAINED_GLASS_PANE") || var19.equals("GREEN_STAINED_GLASS_PANE")) {
                  name(var18, "§x§5§5§F§F§B§8ᴄᴏɴꜰɪʀᴍ");
                  Object var20 = call(var18, "getItemMeta");
                  List<String> var21 = (List<String>)call(var20, "getLore");
                  ArrayList var22 = new ArrayList();
                  var22.add("§fClick To Confirm");
                  if (var21 != null) {
                     for (String var10 : var21) {
                        if (!plain(var10).toLowerCase(Locale.ROOT).startsWith("click")) {
                           var22.add(var10.replace("§8", "§7"));
                        }
                     }
                  }

                  lore(var18, var22);
               } else if (var19.equals("RED_STAINED_GLASS_PANE")) {
                  name(var18, "§cᴄᴀɴᴄᴇʟ");
                  lore(var18, List.of("§fClick to return"));
               }
            }
         }
      }

      return var0;
   }

   public static void pruneTransactions(Object var0) {
      try {
         Field var1 = var0.getClass().getDeclaredField("recentTransactions");
         var1.setAccessible(true);
         Map var2 = (Map)var1.get(var0);
         long var3 = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3L).toInstant().toEpochMilli();

         for (Object var6 : var2.values()) {
            List var7 = (List)var6;
            var7.removeIf(var2x -> {
               try {
                  Field var3x = var2x.getClass().getDeclaredField("timestamp");
                  var3x.setAccessible(true);
                  return var3x.getLong(var2x) < var3;
               } catch (ReflectiveOperationException var4) {
                  throw new IllegalStateException(var4);
               }
            });
         }
      } catch (ReflectiveOperationException var8) {
         throw new IllegalStateException(var8);
      }
   }
}
