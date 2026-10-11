package com.taxsmp.bounty;

import com.taxsmp.TaxSMP;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

public class SkinCache {
   private static final long DISK_TTL_MILLIS = TimeUnit.DAYS.toMillis(7L);
   private static final int CONNECT_TIMEOUT_MILLIS = 8000;
   private static final int READ_TIMEOUT_MILLIS = 8000;
   private static final long FAILURE_BACKOFF_MILLIS = TimeUnit.MINUTES.toMillis(10L);
   private final TaxSMP plugin;
   private final File cacheDir;
   private final Map<UUID, BufferedImage> heads = new ConcurrentHashMap<>();
   private final Map<UUID, Long> failedAt = new ConcurrentHashMap<>();
   private final Set<UUID> fetching = ConcurrentHashMap.newKeySet();
   private final BufferedImage placeholder = buildPlaceholder();
   private volatile Runnable refreshHook;

   public SkinCache(TaxSMP plugin) {
      this.plugin = plugin;
      this.cacheDir = new File(plugin.getDataFolder(), "bountyheads");
      if (!this.cacheDir.exists()) {
         this.cacheDir.mkdirs();
      }
   }

   public void setRefreshHook(Runnable hook) {
      this.refreshHook = hook;
   }

   public BufferedImage placeholder() {
      return this.placeholder;
   }

   public boolean hasHead(UUID uuid) {
      return uuid != null && (isBedrock(uuid) || this.heads.containsKey(uuid));
   }

   public static boolean isBedrock(UUID uuid) {
      return uuid != null && uuid.getMostSignificantBits() == 0L;
   }

   public BufferedImage head(UUID uuid, String name) {
      if (uuid != null && !isBedrock(uuid)) {
         BufferedImage cached = this.heads.get(uuid);
         if (cached != null) {
            return cached;
         }

         this.request(uuid, name);
         return this.placeholder;
      } else {
         return this.placeholder;
      }
   }

   public void request(UUID uuid, String name) {
      if (uuid != null && !isBedrock(uuid) && !this.heads.containsKey(uuid)) {
         Long failed = this.failedAt.get(uuid);
         if (failed == null || System.currentTimeMillis() - failed >= FAILURE_BACKOFF_MILLIS) {
            if (this.fetching.add(uuid)) {
               Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
                  try {
                     BufferedImage head = this.loadFromDisk(uuid);
                     if (head == null) {
                        head = this.download(uuid, name);
                        if (head != null) {
                           this.saveToDisk(uuid, head);
                        }
                     }

                     if (head != null) {
                        this.heads.put(uuid, head);
                        this.failedAt.remove(uuid);
                        Runnable hook = this.refreshHook;
                        if (hook != null) {
                           hook.run();
                        }
                     } else {
                        this.failedAt.put(uuid, System.currentTimeMillis());
                     }
                  } catch (Throwable t) {
                     this.failedAt.put(uuid, System.currentTimeMillis());
                  } finally {
                     this.fetching.remove(uuid);
                  }
               });
            }
         }
      }
   }

   private BufferedImage download(UUID uuid, String name) {
      URL skinUrl = this.resolveSkinUrl(uuid, name);
      if (skinUrl == null) {
         return null;
      }

      try {
         HttpURLConnection conn = (HttpURLConnection)skinUrl.openConnection();
         conn.setConnectTimeout(8000);
         conn.setReadTimeout(8000);
         conn.setRequestProperty("User-Agent", "TaxSMP-Bounties");

         try (InputStream in = conn.getInputStream()) {
            BufferedImage skin = ImageIO.read(in);
            return skin == null ? null : cropHead(skin);
         }
      } catch (Exception e) {
         this.plugin.getLogger().fine("Could not download skin for " + uuid + ": " + e.getMessage());
         return null;
      }
   }

   private URL resolveSkinUrl(UUID uuid, String name) {
      Player online = Bukkit.getPlayer(uuid);
      if (online != null) {
         try {
            PlayerTextures textures = online.getPlayerProfile().getTextures();
            if (textures != null && textures.getSkin() != null) {
               return textures.getSkin();
            }
         } catch (Throwable var7) {
         }
      }

      try {
         PlayerProfile profile = name != null && !name.isBlank() ? Bukkit.createPlayerProfile(uuid, name) : Bukkit.createPlayerProfile(uuid);
         PlayerProfile updated = (PlayerProfile)profile.update().get(15L, TimeUnit.SECONDS);
         PlayerTextures textures = updated == null ? null : updated.getTextures();
         return textures == null ? null : textures.getSkin();
      } catch (Exception e) {
         this.plugin.getLogger().fine("Could not resolve skin profile for " + uuid + ": " + e.getMessage());
         return null;
      }
   }

   private static BufferedImage cropHead(BufferedImage skin) {
      BufferedImage head = new BufferedImage(8, 8, 2);
      Graphics2D g = head.createGraphics();

      try {
         g.drawImage(skin.getSubimage(8, 8, 8, 8), 0, 0, null);
         if (skin.getWidth() >= 48 && skin.getHeight() >= 16) {
            g.drawImage(skin.getSubimage(40, 8, 8, 8), 0, 0, null);
         }
      } finally {
         g.dispose();
      }

      return head;
   }

   private File cacheFile(UUID uuid) {
      return new File(this.cacheDir, uuid + ".png");
   }

   private BufferedImage loadFromDisk(UUID uuid) {
      File file = this.cacheFile(uuid);
      if (file.isFile() && System.currentTimeMillis() - file.lastModified() <= DISK_TTL_MILLIS) {
         try {
            BufferedImage img = ImageIO.read(file);
            return img != null && img.getWidth() == 8 && img.getHeight() == 8 ? img : null;
         } catch (Exception e) {
            return null;
         }
      } else {
         return null;
      }
   }

   private void saveToDisk(UUID uuid, BufferedImage head) {
      try {
         ImageIO.write(head, "png", this.cacheFile(uuid));
      } catch (Exception e) {
         this.plugin.getLogger().fine("Could not cache head for " + uuid + ": " + e.getMessage());
      }
   }

   public void invalidateAll() {
      this.heads.clear();
      this.failedAt.clear();
      File[] files = this.cacheDir.listFiles();
      if (files != null) {
         for (File f : files) {
            f.delete();
         }
      }
   }

   private static BufferedImage buildPlaceholder() {
      int hairDark = -13425382;
      int hair = -12176609;
      int skin = -4947105;
      int shade = -6262450;
      int white = -1;
      int eye = -12961109;
      int mouth = -9154246;
      int[][] face = new int[][]{
         {hairDark, hairDark, hairDark, hairDark, hairDark, hairDark, hairDark, hairDark},
         {hair, hair, hair, hair, hair, hair, hair, hair},
         {hair, skin, skin, skin, skin, skin, skin, hair},
         {hair, white, eye, skin, skin, eye, white, hair},
         {skin, skin, skin, shade, shade, skin, skin, skin},
         {skin, skin, mouth, mouth, mouth, mouth, skin, skin},
         {skin, skin, skin, skin, skin, skin, skin, skin},
         {shade, skin, skin, skin, skin, skin, skin, shade}
      };
      BufferedImage img = new BufferedImage(8, 8, 2);

      for (int y = 0; y < 8; y++) {
         for (int x = 0; x < 8; x++) {
            img.setRGB(x, y, face[y][x]);
         }
      }

      return img;
   }
}
