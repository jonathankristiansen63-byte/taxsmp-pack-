package com.taxsmp;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeadProfiles implements Listener {
   private static final String TEXTURES = "textures";
   private static final int MAX_ENTRIES = 50000;
   private static final Map<UUID, ProfileProperty> SKINS = new ConcurrentHashMap<>();
   private static final AtomicBoolean STARTED = new AtomicBoolean();
   private static final ThreadLocal<Object[]> PENDING = new ThreadLocal<>();
   private static volatile File cacheFile;
   private static volatile boolean dirty;

   private HeadProfiles() {
   }

   public static boolean setOwner(SkullMeta var0, OfflinePlayer var1) {
      PENDING.remove();

      try {
         PlayerProfile var2 = texturedProfile(var1);
         if (var2 != null) {
            var0.setPlayerProfile(var2);
            return true;
         }
      } catch (Throwable var3) {
      }

      PENDING.set(new Object[]{var0, var1});
      return true;
   }

   public static boolean setItemMeta(ItemStack var0, ItemMeta var1) {
      boolean var2 = var0.setItemMeta(var1);
      Object[] var3 = PENDING.get();
      if (var3 != null && var3[0] == var1) {
         PENDING.remove();
         applyUuidOnly(var0, (OfflinePlayer)var3[1]);
      }

      return var2;
   }

   public static ItemStack menuHead(OfflinePlayer var0) {
      ItemStack var1 = new ItemStack(Material.PLAYER_HEAD);
      if (var1.getItemMeta() instanceof SkullMeta var2) {
         PlayerProfile var9 = null;

         try {
            var9 = texturedProfile(var0);
         } catch (Throwable var8) {
         }

         if (var9 == null && !Bukkit.isPrimaryThread()) {
            try {
               PlayerProfile var4 = var0.getPlayerProfile();
               if (!var4.hasTextures()) {
                  var4 = (PlayerProfile)var4.update().get(10L, TimeUnit.SECONDS);
               }

               if (var4 != null && var4.hasTextures()) {
                  remember(var4);
                  var9 = var4;
               }
            } catch (Throwable var7) {
            }
         }

         boolean var10 = false;
         if (var9 != null) {
            try {
               var2.setPlayerProfile(var9);
               var10 = true;
            } catch (Throwable var6) {
            }
         }

         var1.setItemMeta(var2);
         if (!var10) {
            applyUuidOnly(var1, var0);
         }
      }

      return var1;
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent var1) {
      try {
         remember(var1.getPlayer().getPlayerProfile());
      } catch (Throwable var3) {
      }
   }

   private static PlayerProfile texturedProfile(OfflinePlayer var0) {
      start();
      UUID var1 = var0.getUniqueId();
      if (var1 == null) {
         return null;
      }

      Player var2 = Bukkit.getPlayer(var1);
      if (var2 != null) {
         PlayerProfile var3 = var2.getPlayerProfile();
         if (var3 != null && var3.hasTextures()) {
            remember(var3);
            return var3;
         }
      }

      ProfileProperty var5 = SKINS.get(var1);
      if (var5 == null) {
         return null;
      }

      PlayerProfile var4 = Bukkit.createProfile(var1, safeName(var0.getName()));
      var4.setProperty(var5);
      return var4;
   }

   private static void applyUuidOnly(ItemStack var0, OfflinePlayer var1) {
      try {
         var0.setData(DataComponentTypes.PROFILE, (ResolvableProfile)ResolvableProfile.resolvableProfile().uuid(var1.getUniqueId()).build());
      } catch (Throwable var3) {
      }
   }

   private static void remember(PlayerProfile var0) {
      if (var0 != null && var0.getId() != null) {
         for (ProfileProperty var2 : var0.getProperties()) {
            if ("textures".equals(var2.getName()) && var2.getValue() != null) {
               ProfileProperty var3 = var2.getSignature() == null
                  ? new ProfileProperty("textures", var2.getValue())
                  : new ProfileProperty("textures", var2.getValue(), var2.getSignature());
               if (SKINS.size() < 50000 || SKINS.containsKey(var0.getId())) {
                  ProfileProperty var4 = SKINS.put(var0.getId(), var3);
                  if (!var3.equals(var4)) {
                     dirty = true;
                  }
               }

               return;
            }
         }
      }
   }

   private static String safeName(String var0) {
      return var0 != null && !var0.isEmpty() && var0.length() <= 16 ? var0 : null;
   }

   private static void start() {
      if (STARTED.compareAndSet(false, true)) {
         try {
            TaxSMP var0 = (TaxSMP)JavaPlugin.getPlugin(TaxSMP.class);
            cacheFile = new File(var0.getDataFolder(), "head-skins.cache");
            Bukkit.getPluginManager().registerEvents(new HeadProfiles(), var0);
            Bukkit.getAsyncScheduler().runNow(var0, var0x -> load());
            Bukkit.getAsyncScheduler().runAtFixedRate(var0, var0x -> saveIfDirty(), 60L, 60L, TimeUnit.SECONDS);

            for (Player var2 : Bukkit.getOnlinePlayers()) {
               try {
                  remember(var2.getPlayerProfile());
               } catch (Throwable var4) {
               }
            }
         } catch (Throwable var5) {
         }
      }
   }

   private static void load() {
      File var0 = cacheFile;
      if (var0 != null && var0.isFile()) {
         String var2;
         try (BufferedReader var1 = Files.newBufferedReader(var0.toPath(), StandardCharsets.UTF_8)) {
            while ((var2 = var1.readLine()) != null) {
               String[] var3 = var2.split("\t");
               if (var3.length >= 2) {
                  try {
                     UUID var4 = UUID.fromString(var3[0]);
                     ProfileProperty var5 = var3.length >= 3 && !var3[2].isEmpty()
                        ? new ProfileProperty("textures", var3[1], var3[2])
                        : new ProfileProperty("textures", var3[1]);
                     SKINS.putIfAbsent(var4, var5);
                  } catch (IllegalArgumentException var7) {
                  }
               }
            }
         } catch (Throwable var9) {
         }
      }
   }

   private static void saveIfDirty() {
      File var0 = cacheFile;
      if (dirty && var0 != null) {
         dirty = false;
         File var1 = new File(var0.getParentFile(), var0.getName() + ".tmp");

         try {
            var0.getParentFile().mkdirs();

            try (BufferedWriter var2 = Files.newBufferedWriter(var1.toPath(), StandardCharsets.UTF_8)) {
               for (Entry var4 : SKINS.entrySet()) {
                  ProfileProperty var5 = (ProfileProperty)var4.getValue();
                  var2.write(((UUID)var4.getKey()).toString());
                  var2.write(9);
                  var2.write(var5.getValue());
                  var2.write(9);
                  var2.write(var5.getSignature() == null ? "" : var5.getSignature());
                  var2.write(10);
               }
            }

            try {
               Files.move(var1.toPath(), var0.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException var7) {
               Files.move(var1.toPath(), var0.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (Throwable var9) {
            dirty = true;
         }
      }
   }
}
