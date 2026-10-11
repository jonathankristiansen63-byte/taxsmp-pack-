package com.taxsmp.bounty;

import com.taxsmp.PriceManager;
import com.taxsmp.TaxSMP;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Rotation;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MapView.Scale;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public class BountyMapService {
   private static final int MAP_PIXELS = 128;
   private static final long REBUILD_DEBOUNCE_TICKS = 40L;
   private static final int ATTACH_PER_TICK = 20;
   private final TaxSMP plugin;
   private final BountyManager bounties;
   private final SkinCache skins;
   private final NamespacedKey posterKey;
   private final NamespacedKey boardKey;
   private final Map<Integer, BountyMapService.ImageMapRenderer> renderers = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> posterMaps = new ConcurrentHashMap<>();
   private final List<BountyMapService.Board> boards = new CopyOnWriteArrayList<>();
   private final AtomicInteger handheldBoardMap = new AtomicInteger(-1);
   private final AtomicBoolean rebuildQueued = new AtomicBoolean(false);
   private File file;
   private FileConfiguration yaml;

   public BountyMapService(TaxSMP plugin, BountyManager bounties, SkinCache skins) {
      this.plugin = plugin;
      this.bounties = bounties;
      this.skins = skins;
      this.posterKey = new NamespacedKey(plugin, "bounty_poster");
      this.boardKey = new NamespacedKey(plugin, "bounty_board");
      this.load();
      bounties.onChange(this::requestRebuild);
      skins.setRefreshHook(this::requestRebuild);
   }

   public void reattachAll() {
      List<Integer> ids = new ArrayList<>();
      int handheld = this.handheldBoardMap.get();
      if (handheld >= 0) {
         ids.add(handheld);
      }

      ids.addAll(this.posterMaps.values());

      for (BountyMapService.Board board : this.boards) {
         for (int id : board.mapIds) {
            ids.add(id);
         }
      }

      if (!ids.isEmpty()) {
         this.attachBatch(ids, 0);
      }
   }

   private void attachBatch(List<Integer> ids, int from) {
      Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
         int to = Math.min(ids.size(), from + 20);

         for (int i = from; i < to; i++) {
            this.attach(ids.get(i));
         }

         if (to < ids.size()) {
            this.attachBatch(ids, to);
         } else {
            this.plugin.getLogger().info("Restored " + this.renderers.size() + " bounty map renderer(s).");
            this.requestRebuild();
         }
      });
   }

   public void shutdown() {
      this.saveNow();
   }

   private BountyMapService.ImageMapRenderer attach(int mapId) {
      if (mapId < 0) {
         return null;
      }

      BountyMapService.ImageMapRenderer existing = this.renderers.get(mapId);
      if (existing != null) {
         return existing;
      }

      MapView view;
      try {
         view = Bukkit.getMap(mapId);
      } catch (Throwable t) {
         return null;
      }

      return view == null ? null : this.attach(view);
   }

   private BountyMapService.ImageMapRenderer attach(MapView view) {
      BountyMapService.ImageMapRenderer renderer = new BountyMapService.ImageMapRenderer();

      for (MapRenderer old : new ArrayList<>(view.getRenderers())) {
         view.removeRenderer(old);
      }

      view.addRenderer(renderer);

      try {
         view.setScale(Scale.CLOSEST);
         view.setTrackingPosition(false);
         view.setUnlimitedTracking(false);
         view.setLocked(true);
      } catch (Throwable var5) {
      }

      this.renderers.put(view.getId(), renderer);
      return renderer;
   }

   public void requestRebuild() {
      if (this.rebuildQueued.compareAndSet(false, true)) {
         Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, task -> {
            this.rebuildQueued.set(false);
            Bukkit.getAsyncScheduler().runNow(this.plugin, t -> this.rebuildNow());
         }, 40L);
      }
   }

   private void rebuildNow() {
      try {
         List<BountyManager.Entry> top = this.bounties.top(this.bounties.topSize());
         List<PosterRenderer.Row> rows = new ArrayList<>();
         StringBuilder rowsKeyBuilder = new StringBuilder();

         for (BountyManager.Entry entry : top) {
            rows.add(new PosterRenderer.Row(entry.name(), entry.amount(), this.skins.head(entry.uuid(), entry.name())));
            rowsKeyBuilder.append(entry.uuid())
               .append(':')
               .append(entry.amount())
               .append(':')
               .append(entry.name())
               .append(':')
               .append(this.skins.hasHead(entry.uuid()))
               .append(';');
         }

         String rowsKey = rowsKeyBuilder.toString();
         BountyMapService.ImageMapRenderer handheld = this.renderers.get(this.handheldBoardMap.get());
         if (handheld != null && !handheld.matches("1x1|" + rowsKey)) {
            handheld.setImage(PosterRenderer.board(128, 128, rows), "1x1|" + rowsKey);
         }

         for (BountyMapService.Board board : this.boards) {
            this.drawBoard(board, rows, rowsKey);
         }

         for (Entry<UUID, Integer> e : this.posterMaps.entrySet()) {
            BountyMapService.ImageMapRenderer renderer = this.renderers.get(e.getValue());
            if (renderer != null) {
               UUID target = e.getKey();
               double amount = this.bounties.total(target);
               String name = this.bounties.nameOf(target);
               String key = target + "|" + amount + "|" + name + "|" + this.skins.hasHead(target);
               if (!renderer.matches(key)) {
                  renderer.setImage(PosterRenderer.poster(128, 128, name, amount, this.skins.head(target, name), amount > 0.0 ? null : "- COLLECTED -"), key);
               }
            }
         }
      } catch (Throwable t) {
         this.plugin.getLogger().warning("Could not redraw the bounty maps: " + t);
      }
   }

   private void drawBoard(BountyMapService.Board board, List<PosterRenderer.Row> rows, String rowsKey) {
      String key = board.width + "x" + board.height + "|" + rowsKey;
      BountyMapService.ImageMapRenderer first = board.mapIds.length == 0 ? null : this.renderers.get(board.mapIds[0]);
      if (first == null || !first.matches(key)) {
         BufferedImage full = PosterRenderer.board(board.width * 128, board.height * 128, rows);

         for (int row = 0; row < board.height; row++) {
            for (int col = 0; col < board.width; col++) {
               int index = row * board.width + col;
               if (index < board.mapIds.length) {
                  BountyMapService.ImageMapRenderer renderer = this.renderers.get(board.mapIds[index]);
                  if (renderer != null) {
                     renderer.setImage(tile(full, col * 128, row * 128), key);
                  }
               }
            }
         }
      }
   }

   private static BufferedImage tile(BufferedImage source, int x, int y) {
      BufferedImage out = new BufferedImage(128, 128, 2);
      Graphics2D g = out.createGraphics();

      try {
         g.drawImage(source, -x, -y, null);
      } finally {
         g.dispose();
      }

      return out;
   }

   public void giveBoard(Player player) {
      int existing = this.handheldBoardMap.get();
      if (existing >= 0 && this.renderers.containsKey(existing)) {
         this.giveMapItem(player, existing, "§c§lBounty Board", List.of("§7The most wanted players.", "§8Updates on its own."), this.boardKey, "board");
      } else {
         World world = player.getWorld();
         Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
            int id = this.handheldBoardMap.get();
            if (id < 0 || this.attach(id) == null) {
               MapView view = Bukkit.createMap(world);
               this.attach(view);
               this.handheldBoardMap.set(view.getId());
               id = view.getId();
               this.save();
            }

            int finalId = id;
            this.requestRebuild();
            this.giveMapItem(player, finalId, "§c§lBounty Board", List.of("§7The most wanted players.", "§8Updates on its own."), this.boardKey, "board");
         });
      }
   }

   public void givePoster(Player player, UUID target, String targetName) {
      Integer existing = this.posterMaps.get(target);
      if (existing != null && this.renderers.containsKey(existing)) {
         this.giveMapItem(
            player,
            existing,
            "§c§lWanted: §c" + targetName,
            List.of("§7Reward: §c$" + PriceManager.formatShort(this.bounties.total(target))),
            this.posterKey,
            target.toString()
         );
      } else {
         World world = player.getWorld();
         Bukkit.getGlobalRegionScheduler()
            .execute(
               this.plugin,
               () -> {
                  Integer id = this.posterMaps.get(target);
                  if (id == null || this.attach(id) == null) {
                     MapView view = Bukkit.createMap(world);
                     this.attach(view);
                     this.posterMaps.put(target, view.getId());
                     id = view.getId();
                     this.save();
                  }

                  int finalId = id;
                  this.requestRebuild();
                  this.giveMapItem(
                     player,
                     finalId,
                     "§c§lWanted: §c" + targetName,
                     List.of("§7Reward: §c$" + PriceManager.formatShort(this.bounties.total(target))),
                     this.posterKey,
                     target.toString()
                  );
               }
            );
      }
   }

   private void giveMapItem(Player player, int mapId, String name, List<String> lore, NamespacedKey key, String tag) {
      Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> {
         ItemStack built = this.buildMapItem(mapId, name, lore, key, tag);
         player.getScheduler().run(this.plugin, task -> this.deliver(player, built, key, tag), null);
      });
   }

   private void deliver(Player player, ItemStack item, NamespacedKey key, String tag) {
      if (player.isOnline()) {
         if (item == null) {
            player.sendMessage("§cThe bounty map could not be created.");
         } else if (this.hasTaggedMap(player, key, tag)) {
            player.sendMessage("§7You already have that bounty map - it updates itself.");
         } else {
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack[]{item});
            if (leftovers.isEmpty()) {
               player.sendMessage("§7Added to your inventory. It updates itself.");
            } else {
               leftovers.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
               player.sendMessage("§7Your inventory was full, so the map was dropped at your feet.");
            }
         }
      }
   }

   private boolean hasTaggedMap(Player player, NamespacedKey key, String tag) {
      for (ItemStack stack : player.getInventory().getContents()) {
         if (stack != null && stack.getType() == Material.FILLED_MAP) {
            ItemMeta meta = stack.getItemMeta();
            if (meta != null && tag.equals(meta.getPersistentDataContainer().get(key, PersistentDataType.STRING))) {
               return true;
            }
         }
      }

      return false;
   }

   private ItemStack buildMapItem(int mapId, String name, List<String> lore, NamespacedKey key, String tag) {
      MapView view = Bukkit.getMap(mapId);
      if (view == null) {
         return null;
      }

      ItemStack item = new ItemStack(Material.FILLED_MAP);
      MapMeta meta = (MapMeta)item.getItemMeta();
      meta.setMapView(view);
      if (name != null) {
         meta.setDisplayName(name);
      }

      if (!lore.isEmpty()) {
         meta.setLore(new ArrayList<>(lore));
      }

      meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, tag);
      item.setItemMeta(meta);
      return item;
   }

   public void placeBoard(Player player, int width, int height, Consumer<String> feedback) {
      int w = Math.max(1, Math.min(8, width));
      int h = Math.max(1, Math.min(8, height));
      int needed = w * h;
      BlockFace facing = facingTowards(player);
      Location anchor = anchorFor(player);
      World world = anchor.getWorld();
      Bukkit.getGlobalRegionScheduler()
         .execute(
            this.plugin,
            () -> {
               int[] ids = new int[needed];

               for (int i = 0; i < needed; i++) {
                  MapView view = Bukkit.createMap(world);
                  this.attach(view);
                  ids[i] = view.getId();
               }

               BountyMapService.Board board = new BountyMapService.Board();
               board.id = UUID.randomUUID().toString();
               board.world = world.getName();
               board.x = anchor.getBlockX();
               board.y = anchor.getBlockY();
               board.z = anchor.getBlockZ();
               board.facing = facing;
               board.width = w;
               board.height = h;
               board.mapIds = ids;
               this.spawnFrames(
                  board,
                  spawned -> {
                     if (spawned == 0) {
                        feedback.accept("§cNothing could be placed there - try somewhere with more open space.");
                     } else {
                        this.boards.add(board);
                        this.save();
                        this.requestRebuild();
                        feedback.accept(
                           "§7Placed a §c"
                              + w
                              + "x"
                              + h
                              + " §7bounty board at §f"
                              + board.x
                              + ", "
                              + board.y
                              + ", "
                              + board.z
                              + " §7facing §f"
                              + facing.name().toLowerCase()
                              + "§7."
                        );
                     }
                  }
               );
            }
         );
   }

   private void spawnFrames(BountyMapService.Board board, Consumer<Integer> done) {
      World world = Bukkit.getWorld(board.world);
      if (world == null) {
         done.accept(0);
      } else {
         int[] right = rightVector(board.facing);
         Location origin = new Location(world, board.x, board.y, board.z);
         int total = board.width * board.height;
         AtomicInteger remaining = new AtomicInteger(total);
         AtomicInteger placed = new AtomicInteger();
         List<String> frameIds = new CopyOnWriteArrayList<>();
         int startX = origin.getBlockX() - right[0] * ((board.width - 1) / 2);
         int startZ = origin.getBlockZ() - right[1] * ((board.width - 1) / 2);
         int startY = origin.getBlockY() + (board.height - 1) / 2;

         for (int row = 0; row < board.height; row++) {
            for (int col = 0; col < board.width; col++) {
               int index = row * board.width + col;
               Location slot = new Location(world, startX + right[0] * col, startY - row, startZ + right[1] * col);
               ItemStack map = this.buildMapItem(board.mapIds[index], null, List.of(), this.boardKey, board.id);
               Consumer<ItemFrame> setup = f -> {
                  f.setFacingDirection(board.facing, true);
                  f.setFixed(true);
                  f.setVisible(false);
                  f.setInvulnerable(true);
                  f.setPersistent(true);
                  f.setSilent(true);
                  f.setRotation(Rotation.NONE);
                  f.setItemDropChance(0.0F);
                  if (map != null) {
                     f.setItem(map, false);
                  }
               };
               Bukkit.getRegionScheduler().execute(this.plugin, slot, () -> {
                  Block support = slot.getBlock().getRelative(board.facing.getOppositeFace());
                  BlockData borrowed = null;

                  try {
                     if (!support.getType().isSolid()) {
                        borrowed = support.getBlockData();
                        support.setType(Material.STONE, false);
                     }

                     ItemFrame frame = (ItemFrame)world.spawn(slot, ItemFrame.class, setup);
                     frameIds.add(frame.getUniqueId().toString());
                     placed.incrementAndGet();
                  } catch (Throwable t) {
                     this.plugin.getLogger().warning("Could not place a bounty board frame: " + t.getMessage());
                  } finally {
                     if (borrowed != null) {
                        support.setBlockData(borrowed, false);
                     }

                     if (remaining.decrementAndGet() == 0) {
                        board.frameIds = new ArrayList<>(frameIds);
                        Bukkit.getGlobalRegionScheduler().execute(this.plugin, () -> done.accept(placed.get()));
                     }
                  }
               });
            }
         }
      }
   }

   public boolean removeNearestBoard(Player player, int radius) {
      BountyMapService.Board nearest = null;
      double best = Double.MAX_VALUE;

      for (BountyMapService.Board board : this.boards) {
         if (board.world.equals(player.getWorld().getName())) {
            double dx = board.x - player.getLocation().getX();
            double dy = board.y - player.getLocation().getY();
            double dz = board.z - player.getLocation().getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance < best) {
               best = distance;
               nearest = board;
            }
         }
      }

      if (nearest != null && !(best > (double)radius * radius)) {
         this.removeBoard(nearest);
         return true;
      } else {
         return false;
      }
   }

   public int removeAllBoards() {
      int n = this.boards.size();

      for (BountyMapService.Board board : new ArrayList<>(this.boards)) {
         this.removeBoard(board);
      }

      return n;
   }

   private void removeBoard(BountyMapService.Board board) {
      this.boards.remove(board);

      for (int id : board.mapIds) {
         this.renderers.remove(id);
      }

      this.save();
      World world = Bukkit.getWorld(board.world);
      if (world != null) {
         int[] right = rightVector(board.facing);
         int startX = board.x - right[0] * ((board.width - 1) / 2);
         int startZ = board.z - right[1] * ((board.width - 1) / 2);
         int startY = board.y + (board.height - 1) / 2;

         for (int row = 0; row < board.height; row++) {
            for (int col = 0; col < board.width; col++) {
               Location slot = new Location(world, startX + right[0] * col + 0.5, startY - row + 0.5, startZ + right[1] * col + 0.5);
               Bukkit.getRegionScheduler().execute(this.plugin, slot, () -> {
                  for (Entity entity : world.getNearbyEntities(slot, 0.9, 0.9, 0.9)) {
                     if (entity instanceof ItemFrame frame && board.frameIds.contains(frame.getUniqueId().toString())) {
                        frame.setItem(null, false);
                        frame.remove();
                     }
                  }
               });
            }
         }
      }
   }

   public int boardCount() {
      return this.boards.size();
   }

   private static BlockFace facingTowards(Player player) {
      float yaw = player.getLocation().getYaw();
      float normalised = (yaw % 360.0F + 360.0F) % 360.0F;
      if (normalised >= 315.0F || normalised < 45.0F) {
         return BlockFace.NORTH;
      } else if (normalised < 135.0F) {
         return BlockFace.EAST;
      } else {
         return normalised < 225.0F ? BlockFace.SOUTH : BlockFace.WEST;
      }
   }

   private static int[] rightVector(BlockFace facing) {
      return switch (facing) {
         case SOUTH -> new int[]{1, 0};
         case NORTH -> new int[]{-1, 0};
         case WEST -> new int[]{0, 1};
         case EAST -> new int[]{0, -1};
         default -> new int[]{1, 0};
      };
   }

   private static Location anchorFor(Player player) {
      RayTraceResult hit = player.rayTraceBlocks(24.0);
      if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
         return hit.getHitBlock().getRelative(hit.getHitBlockFace()).getLocation();
      }

      Location eye = player.getEyeLocation();
      Vector forward = eye.getDirection().setY(0);
      if (forward.lengthSquared() < 1.0E-4) {
         forward = new Vector(0, 0, 1);
      }

      return eye.add(forward.normalize().multiply(4)).getBlock().getLocation();
   }

   private void load() {
      this.file = new File(this.plugin.getDataFolder(), "bountymaps.yml");
      this.yaml = this.file.exists() ? YamlConfiguration.loadConfiguration(this.file) : new YamlConfiguration();
      this.handheldBoardMap.set(this.yaml.getInt("handheld-board", -1));
      ConfigurationSection posters = this.yaml.getConfigurationSection("posters");
      if (posters != null) {
         for (String key : posters.getKeys(false)) {
            try {
               this.posterMaps.put(UUID.fromString(key), posters.getInt(key));
            } catch (IllegalArgumentException var8) {
            }
         }
      }

      ConfigurationSection boardSection = this.yaml.getConfigurationSection("boards");
      if (boardSection != null) {
         for (String key : boardSection.getKeys(false)) {
            try {
               BountyMapService.Board board = new BountyMapService.Board();
               board.id = key;
               board.world = boardSection.getString(key + ".world", "world");
               board.x = boardSection.getInt(key + ".x");
               board.y = boardSection.getInt(key + ".y");
               board.z = boardSection.getInt(key + ".z");
               board.facing = BlockFace.valueOf(boardSection.getString(key + ".facing", "NORTH"));
               board.width = Math.max(1, boardSection.getInt(key + ".width", 3));
               board.height = Math.max(1, boardSection.getInt(key + ".height", 3));
               List<Integer> ids = boardSection.getIntegerList(key + ".maps");
               board.mapIds = new int[ids.size()];

               for (int i = 0; i < ids.size(); i++) {
                  board.mapIds[i] = ids.get(i);
               }

               board.frameIds = new ArrayList<>(boardSection.getStringList(key + ".frames"));
               this.boards.add(board);
            } catch (Exception e) {
               this.plugin.getLogger().warning("Skipped an unreadable bounty board entry (" + key + "): " + e.getMessage());
            }
         }
      }
   }

   private synchronized void save() {
      FileConfiguration out = new YamlConfiguration();
      out.set("handheld-board", this.handheldBoardMap.get());

      for (Entry<UUID, Integer> e : this.posterMaps.entrySet()) {
         out.set("posters." + e.getKey(), e.getValue());
      }

      for (BountyMapService.Board board : this.boards) {
         String base = "boards." + board.id;
         out.set(base + ".world", board.world);
         out.set(base + ".x", board.x);
         out.set(base + ".y", board.y);
         out.set(base + ".z", board.z);
         out.set(base + ".facing", board.facing.name());
         out.set(base + ".width", board.width);
         out.set(base + ".height", board.height);
         List<Integer> ids = new ArrayList<>();

         for (int id : board.mapIds) {
            ids.add(id);
         }

         out.set(base + ".maps", ids);
         out.set(base + ".frames", board.frameIds);
      }

      this.yaml = out;
      File target = this.file;
      Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
         try {
            synchronized (this) {
               out.save(target);
            }
         } catch (IOException e) {
            this.plugin.getLogger().warning("Could not save bountymaps.yml: " + e.getMessage());
         }
      });
   }

   private synchronized void saveNow() {
      FileConfiguration out = new YamlConfiguration();
      out.set("handheld-board", this.handheldBoardMap.get());

      for (Entry<UUID, Integer> e : this.posterMaps.entrySet()) {
         out.set("posters." + e.getKey(), e.getValue());
      }

      for (BountyMapService.Board board : this.boards) {
         String base = "boards." + board.id;
         out.set(base + ".world", board.world);
         out.set(base + ".x", board.x);
         out.set(base + ".y", board.y);
         out.set(base + ".z", board.z);
         out.set(base + ".facing", board.facing.name());
         out.set(base + ".width", board.width);
         out.set(base + ".height", board.height);
         List<Integer> ids = new ArrayList<>();

         for (int id : board.mapIds) {
            ids.add(id);
         }

         out.set(base + ".maps", ids);
         out.set(base + ".frames", board.frameIds);
      }

      this.yaml = out;

      try {
         out.save(this.file);
      } catch (IOException e) {
         this.plugin.getLogger().warning("Could not save bountymaps.yml: " + e.getMessage());
      }
   }

   public static class Board {
      String id;
      String world;
      int x;
      int y;
      int z;
      BlockFace facing = BlockFace.NORTH;
      int width = 3;
      int height = 3;
      int[] mapIds = new int[0];
      List<String> frameIds = new ArrayList<>();

      @Override
      public String toString() {
         return this.world + " " + this.x + "," + this.y + "," + this.z + " " + this.width + "x" + this.height + " maps=" + Arrays.toString(this.mapIds);
      }
   }

   public static class ImageMapRenderer extends MapRenderer {
      private volatile byte[] pixels;
      private volatile String contentKey;
      private final AtomicInteger version = new AtomicInteger();
      private volatile int drawnVersion = -1;

      public ImageMapRenderer() {
         super(false);
      }

      public boolean matches(String key) {
         return key != null && key.equals(this.contentKey);
      }

      public void setImage(BufferedImage image, String key) {
         this.pixels = toMapColours(image);
         this.contentKey = key;
         this.version.incrementAndGet();
      }

      private static byte[] toMapColours(BufferedImage image) {
         int[] argb = image.getRGB(0, 0, 128, 128, null, 0, 128);
         byte[] out = new byte[16384];

         for (int i = 0; i < out.length; i++) {
            int pixel = argb[i];
            out[i] = pixel >>> 24 < 128 ? 0 : MapPalette.matchColor(pixel >> 16 & 0xFF, pixel >> 8 & 0xFF, pixel & 0xFF);
         }

         return out;
      }

      public void render(MapView view, MapCanvas canvas, Player player) {
         byte[] current = this.pixels;
         if (current != null) {
            int wanted = this.version.get();
            if (this.drawnVersion != wanted) {
               synchronized (this) {
                  if (this.drawnVersion != wanted) {
                     for (int x = 0; x < 128; x++) {
                        for (int y = 0; y < 128; y++) {
                           canvas.setPixel(x, y, current[y * 128 + x]);
                        }
                     }

                     this.drawnVersion = wanted;
                  }
               }
            }
         }
      }
   }
}
