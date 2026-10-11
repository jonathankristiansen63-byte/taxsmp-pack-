package com.taxsmp.ui;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class HomesBridge {
   private static final int FALLBACK_SLOTS = 3;
   private static Boolean available;
   private static Object service;
   private static Method getMaxHomes;
   private static Method getHomeName;
   private static Method getStoredHome;
   private static Method setHomeName;
   private static Method deleteHome;
   private static Method teleportToHome;
   private static Method setHomeHere;
   private static Method getHomeIcon;
   private static Method setHomeIcon;

   private HomesBridge() {
   }

   static int totalSlots() {
      if (hook()) {
         try {
            return service.getClass().getField("MAX_HOME_SLOTS").getInt(null);
         } catch (Throwable var1) {
         }
      }

      return 10;
   }

   static int maxHomes(Player player) {
      if (!hook()) {
         return 3;
      }

      try {
         return getMaxHomes.invoke(service, player) instanceof Integer max && max > 0 ? max : 3;
      } catch (Throwable ignored) {
         return 3;
      }
   }

   static String homeName(Player player, int slot) {
      if (!hook()) {
         return null;
      }

      try {
         if (!(getStoredHome.invoke(service, player, slot) instanceof Optional<?> stored && !stored.isEmpty())) {
            return null;
         } else {
            return getHomeName.invoke(service, player, slot) instanceof Optional<?> optional
                  && optional.isPresent()
                  && optional.get() instanceof String text
                  && !text.isBlank()
               ? text
               : "Home " + slot;
         }
      } catch (Throwable ignored) {
         return null;
      }
   }

   static boolean rename(Player player, int slot, String name) {
      if (hook() && setHomeName != null) {
         try {
            setHomeName.invoke(service, player, slot, name);
            return true;
         } catch (Throwable ignored) {
            return false;
         }
      } else {
         return false;
      }
   }

   static boolean delete(Player player, int slot) {
      if (hook() && deleteHome != null) {
         try {
            deleteHome.invoke(service, player, slot);
            return true;
         } catch (Throwable ignored) {
            return false;
         }
      } else {
         return false;
      }
   }

   static boolean teleport(Player player, int slot) {
      if (hook() && teleportToHome != null) {
         try {
            return Boolean.TRUE.equals(teleportToHome.invoke(service, player, slot));
         } catch (Throwable ignored) {
            return false;
         }
      } else {
         return false;
      }
   }

   static boolean setHome(Player player, int slot) {
      if (hook() && setHomeHere != null) {
         try {
            return Boolean.TRUE.equals(setHomeHere.invoke(service, player, slot));
         } catch (Throwable ignored) {
            return false;
         }
      } else {
         return false;
      }
   }

   static String icon(Player player, int slot) {
      if (hook() && getHomeIcon != null) {
         try {
            return getHomeIcon.invoke(service, player, slot) instanceof Optional<?> stored
                  && stored.isPresent()
                  && stored.get() instanceof String name
                  && !name.isBlank()
               ? name
               : null;
         } catch (Throwable ignored) {
            return null;
         }
      } else {
         return null;
      }
   }

   static boolean setIcon(Player player, int slot, String materialName) {
      if (hook() && setHomeIcon != null) {
         try {
            setHomeIcon.invoke(service, player, slot, materialName);
            return true;
         } catch (Throwable ignored) {
            return false;
         }
      } else {
         return false;
      }
   }

   private static synchronized boolean hook() {
      if (Boolean.TRUE.equals(available)) {
         return true;
      }

      try {
         Plugin homes = Bukkit.getPluginManager().getPlugin("Homes");
         if (homes != null && homes.isEnabled()) {
            Field field = homes.getClass().getDeclaredField("homesService");
            field.setAccessible(true);
            service = field.get(homes);
            if (service == null) {
               return false;
            }

            getMaxHomes = service.getClass().getMethod("getMaxHomes", Player.class);
            getHomeName = service.getClass().getMethod("getHomeName", Player.class, int.class);
            getStoredHome = service.getClass().getMethod("getStoredHome", Player.class, int.class);
            setHomeName = service.getClass().getMethod("setHomeName", Player.class, int.class, String.class);

            try {
               deleteHome = service.getClass().getMethod("deleteHome", Player.class, int.class);
            } catch (NoSuchMethodException noDelete) {
               deleteHome = null;
            }

            teleportToHome = optional("teleportToHome", Player.class, int.class);
            setHomeHere = optional("setHomeHere", Player.class, int.class);
            getHomeIcon = optional("getHomeIcon", Player.class, int.class);
            setHomeIcon = optional("setHomeIcon", Player.class, int.class, String.class);
            available = Boolean.TRUE;
            return true;
         } else {
            return false;
         }
      } catch (Throwable ignored) {
         return false;
      }
   }

   private static Method optional(String name, Class<?>... parameters) {
      try {
         return service.getClass().getMethod(name, parameters);
      } catch (NoSuchMethodException olderHomes) {
         return null;
      }
   }
}
