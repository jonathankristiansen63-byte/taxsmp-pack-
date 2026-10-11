package com.taxsmp;

import com.taxsmp.storage.Db;
import com.taxsmp.storage.ItemCodec;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import me.kirug.signgui.SignGUI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public class AuctionManager implements CommandExecutor, Listener {
   private static final String CHICKEN_PICKAXE_ID = "chickenpickaxe";
   private static final NamespacedKey SUMMER_TAG = NamespacedKey.fromString("summeritems:summer_item");
   private static final long DAY3_MILLIS = 259200000L;
   private static final NamespacedKey CP_MARK = NamespacedKey.fromString("chickenpickaxe:chicken_pickaxe");
   private static final NamespacedKey CP_CREATED = NamespacedKey.fromString("chickenpickaxe:created_at");
   private static final NamespacedKey CA_MARK = NamespacedKey.fromString("chickenaxe:is_chicken_axe");
   private static final NamespacedKey CA_CREATED = NamespacedKey.fromString("chickenaxe:created_at");
   private static final NamespacedKey CS_MARK = NamespacedKey.fromString("chickenshovel:is_chicken_shovel");
   private static final NamespacedKey CS_DESTRUCT = NamespacedKey.fromString("chickenshovel:destruct_time");
   private static final NamespacedKey CSA_MARK = NamespacedKey.fromString("chickensellaxe:chicken_sell_axe");
   private static final NamespacedKey CSA_EXPIRES = NamespacedKey.fromString("chickensellaxe:expires_at");
   private static final NamespacedKey SELL_WAND_MARK = NamespacedKey.fromString("taxsmp:sell_wand");
   private final TaxSMP plugin;
   private final List<AuctionManager.AuctionItem> auctionItems = new CopyOnWriteArrayList<>();
   private final Db db;
   private ScheduledTask syncTask = null;
   private final Map<UUID, List<AuctionManager.AuctionItem>> viewerUserActive = new ConcurrentHashMap<>();
   private final Map<UUID, List<AuctionManager.ReturnItem>> viewerUserReturns = new ConcurrentHashMap<>();
   private final Map<UUID, ItemStack> pendingItems = new HashMap<>();
   private final Map<UUID, Double> pendingPrices = new HashMap<>();
   private final Map<UUID, AuctionManager.AuctionItem> pendingBuys = new HashMap<>();
   private final Set<Long> claimsInFlight = ConcurrentHashMap.newKeySet();
   private final ExecutorService claimExecutor = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "TaxSMP-AH-Claim");
      t.setDaemon(true);
      return t;
   });
   private static final long CLAIM_CONN_IDLE_MS = 60000L;
   private final Map<UUID, List<AuctionManager.ReturnItem>> pendingReturns = new HashMap<>();
   private final Map<UUID, Double> offlineEarnings = new HashMap<>();
   private final Map<UUID, List<String>> offlineNotifications = new HashMap<>();
   private ScheduledTask cleanupTask = null;
   private final Map<UUID, Integer> viewerPage = new HashMap<>();
   private final Map<UUID, UUID> viewerFilter = new HashMap<>();
   private final Map<UUID, List<AuctionManager.AuctionItem>> viewerList = new HashMap<>();
   private final Map<UUID, String> viewerSortChoice = new HashMap<>();
   private final Map<UUID, String> viewerSearchQuery = new HashMap<>();
   private final Map<UUID, String> viewerFilterChoice = new HashMap<>();
   private final Map<UUID, List<AuctionManager.TransactionRecord>> recentTransactions = new HashMap<>();
   private final Map<UUID, String> txSearchQuery = new HashMap<>();
   private final Map<UUID, ScheduledTask> liveTimerTasks = new ConcurrentHashMap<>();
   private static final long SAVE_DEBOUNCE_MS = 2000L;
   private final AtomicBoolean saveQueued = new AtomicBoolean(false);
   private final Object auctionFileLock = new Object();
   private volatile long lastSaveAtMs = 0L;

   public AuctionManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      long period = 1200L;
      this.cleanupTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> this.cleanupExpired(), period, period);
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(plugin, t -> {
            this.initDb();
            this.reloadFromDb();
            this.expireRenamedListings();
         });
         long secs = Math.max(3L, plugin.getConfig().getLong("mysql.ah-sync-seconds", 5L));
         this.syncTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> {
            if (!Bukkit.getOnlinePlayers().isEmpty()) {
               this.reloadFromDb();
            }
         }, secs, secs, TimeUnit.SECONDS);
      } else {
         this.loadAuctions();
         this.expireRenamedListings();
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private boolean openSignGuiInput(Player p, boolean forAh) {
      try {
         SignGUI.builder().setLines("ꜱᴇᴀʀᴄʜ", "§e^^^^^^^^^^^^^^^", "§eᴜꜱᴇ: /ᴀʜ <ꜱᴇᴀʀᴄʜ", "§eᴛᴇʀᴍꜱ>").setHandler(0, (player, searchText) -> {
            if (searchText != null && !searchText.trim().isEmpty() && !searchText.equalsIgnoreCase("Search")) {
               player.getScheduler().run(this.plugin, task -> {
                  if (forAh) {
                     String query = searchText.trim().toLowerCase();
                     this.viewerSearchQuery.put(player.getUniqueId(), query);
                     this.viewerFilter.remove(player.getUniqueId());
                     this.performItemSearch(player, query, 1);
                  } else {
                     this.txSearchQuery.put(player.getUniqueId(), searchText.trim());
                     this.openTransactions(player, 1);
                  }
               }, null);
            } else {
               player.getScheduler().run(this.plugin, task -> {
                  if (forAh) {
                     player.sendMessage("§eSearch cleared.");
                     this.viewerSearchQuery.remove(player.getUniqueId());
                     this.viewerFilter.remove(player.getUniqueId());
                     this.openAH(player, 1, null);
                  } else {
                     player.sendMessage("§eTransactions search cleared.");
                     this.txSearchQuery.remove(player.getUniqueId());
                     this.openTransactions(player, 1);
                  }
               }, null);
            }
         }).build().open(p);
         return true;
      } catch (Throwable t) {
         this.plugin.getLogger().warning("Failed to open sign GUI: " + t.getMessage());
         t.printStackTrace();
         return false;
      }
   }

   public void saveAuctions() {
      long now = System.currentTimeMillis();
      if (now - this.lastSaveAtMs < 2000L) {
         this.queueTrailingSave();
      } else {
         this.lastSaveAtMs = now;
         this.writeAuctionsNow(false);
      }
   }

   private void queueTrailingSave() {
      if (this.saveQueued.compareAndSet(false, true)) {
         try {
            Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, task -> {
               this.saveQueued.set(false);
               this.lastSaveAtMs = System.currentTimeMillis();
               this.writeAuctionsNow(false);
            }, Math.max(1L, 40L));
         } catch (Throwable t) {
            this.saveQueued.set(false);
            this.writeAuctionsNow(false);
         }
      }
   }

   public void saveAuctionsBlocking() {
      this.lastSaveAtMs = System.currentTimeMillis();
      this.writeAuctionsNow(true);
   }

   private void writeAuctionsFile(File f, String dump) {
      synchronized (this.auctionFileLock) {
         File tmp = new File(f.getParentFile(), f.getName() + ".tmp");

         try {
            Files.writeString(tmp.toPath(), dump, StandardCharsets.UTF_8);

            try {
               Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicUnsupported) {
               Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (Exception e) {
            this.plugin.getLogger().warning("Failed to save auctions: " + e.getMessage());

            try {
               Files.writeString(f.toPath(), dump, StandardCharsets.UTF_8);
            } catch (Exception fallbackFailed) {
               this.plugin.getLogger().severe("auctions.yml could not be written at all: " + fallbackFailed.getMessage());
            }
         }
      }
   }

   private void writeAuctionsNow(boolean blocking) {
      MenuUpdates.pruneTransactions(this);

      try {
         File f = new File(this.plugin.getDataFolder(), "auctions.yml");
         if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
         }

         YamlConfiguration cfg = new YamlConfiguration();
         if (!this.usingDb()) {
            int i = 0;

            for (AuctionManager.AuctionItem auctionItem : this.auctionItems) {
               String string = "auctions." + i;
               cfg.set(string + ".seller", auctionItem.seller.toString());
               cfg.set(string + ".price", auctionItem.price);
               cfg.set(string + ".createdAt", auctionItem.createdAt);
               cfg.set(string + ".item", auctionItem.item);
               i++;
            }
         }

         for (Entry entry : this.viewerSortChoice.entrySet()) {
            cfg.set("playerSorts." + ((UUID)entry.getKey()).toString(), entry.getValue());
         }

         for (Entry entry2 : this.viewerFilterChoice.entrySet()) {
            cfg.set("playerFilters." + ((UUID)entry2.getKey()).toString(), entry2.getValue());
         }

         if (!this.usingDb()) {
            int j = 0;

            for (Entry<UUID, List<AuctionManager.ReturnItem>> entry : this.pendingReturns.entrySet()) {
               String base = "returns." + j;
               cfg.set(base + ".owner", entry.getKey().toString());
               int k = 0;

               for (AuctionManager.ReturnItem ri : entry.getValue()) {
                  cfg.set(base + ".items." + k + ".item", ri.item);
                  cfg.set(base + ".items." + k + ".returnedAt", ri.returnedAt);
                  k++;
               }

               j++;
            }
         }

         if (!this.offlineEarnings.isEmpty()) {
            for (Entry<UUID, Double> entry : this.offlineEarnings.entrySet()) {
               cfg.set("offlineEarnings." + entry.getKey().toString(), entry.getValue());
            }
         }

         if (!this.offlineNotifications.isEmpty()) {
            for (Entry<UUID, List<String>> entry : this.offlineNotifications.entrySet()) {
               cfg.set("offlineNotifications." + entry.getKey().toString(), entry.getValue());
            }
         }

         int tx = 0;

         for (Entry<UUID, List<AuctionManager.TransactionRecord>> entry : this.recentTransactions.entrySet()) {
            String base = "transactions." + tx;
            cfg.set(base + ".owner", entry.getKey().toString());
            int t = 0;

            for (AuctionManager.TransactionRecord record : entry.getValue()) {
               cfg.set(base + ".items." + t + ".item", record.item);
               cfg.set(base + ".items." + t + ".message", record.message);
               cfg.set(base + ".items." + t + ".timestamp", record.timestamp);
               t++;
            }

            tx++;
         }

         String dump = cfg.saveToString();
         if (blocking) {
            this.writeAuctionsFile(f, dump);
         } else {
            try {
               Bukkit.getAsyncScheduler().runNow(this.plugin, task -> this.writeAuctionsFile(f, dump));
            } catch (Throwable schedulerUnavailable) {
               this.writeAuctionsFile(f, dump);
            }
         }
      } catch (Exception ex) {
         this.plugin.getLogger().warning("Failed to save auctions!");
      }
   }

   private void loadAuctions() {
      label140: {
         try {
            File f = new File(this.plugin.getDataFolder(), "auctions.yml");
            if (!f.exists()) {
               break label140;
            }

            YamlConfiguration cfg = new YamlConfiguration();
            cfg.load(f);
            int i = 0;
            this.auctionItems.clear();

            for (; cfg.contains("auctions." + i + ".seller"); i++) {
               UUID seller = UUID.fromString(cfg.getString("auctions." + i + ".seller"));
               double price = cfg.getDouble("auctions." + i + ".price");
               long createdAt = cfg.getLong("auctions." + i + ".createdAt");
               ItemStack item = cfg.getItemStack("auctions." + i + ".item");
               if (item != null) {
                  this.auctionItems.add(new AuctionManager.AuctionItem(seller, item, price, createdAt));
               }
            }

            if (cfg.contains("playerSorts")) {
               for (String key : cfg.getConfigurationSection("playerSorts").getKeys(false)) {
                  this.viewerSortChoice.put(UUID.fromString(key), cfg.getString("playerSorts." + key));
               }
            }

            if (cfg.contains("playerFilters")) {
               for (String key : cfg.getConfigurationSection("playerFilters").getKeys(false)) {
                  this.viewerFilterChoice.put(UUID.fromString(key), cfg.getString("playerFilters." + key));
               }
            }

            if (cfg.contains("offlineEarnings")) {
               for (String key : cfg.getConfigurationSection("offlineEarnings").getKeys(false)) {
                  this.offlineEarnings.put(UUID.fromString(key), cfg.getDouble("offlineEarnings." + key));
               }
            }

            if (cfg.contains("offlineNotifications")) {
               for (String key : cfg.getConfigurationSection("offlineNotifications").getKeys(false)) {
                  List list = cfg.getStringList("offlineNotifications." + key);
                  if (list != null && !list.isEmpty()) {
                     this.offlineNotifications.put(UUID.fromString(key), new ArrayList<>(list));
                  }
               }
            }

            if (cfg.contains("transactions")) {
               for (int tx = 0; cfg.contains("transactions." + tx + ".owner"); tx++) {
                  UUID owner = UUID.fromString(cfg.getString("transactions." + tx + ".owner"));
                  List<AuctionManager.TransactionRecord> records = new LinkedList<>();

                  for (int t = 0; cfg.contains("transactions." + tx + ".items." + t + ".item"); t++) {
                     ItemStack item = cfg.getItemStack("transactions." + tx + ".items." + t + ".item");
                     String message = cfg.getString("transactions." + tx + ".items." + t + ".message");
                     long timestamp = cfg.getLong("transactions." + tx + ".items." + t + ".timestamp");
                     if (item != null && message != null) {
                        records.add(new AuctionManager.TransactionRecord(item, message, timestamp));
                     }
                  }

                  if (!records.isEmpty()) {
                     this.recentTransactions.put(owner, records);
                  }
               }
            }

            if (cfg.contains("returns")) {
               for (int j = 0; cfg.contains("returns." + j + ".owner"); j++) {
                  UUID owner = UUID.fromString(cfg.getString("returns." + j + ".owner"));
                  ArrayList<AuctionManager.ReturnItem> items = new ArrayList<>();

                  for (int k = 0; cfg.contains("returns." + j + ".items." + k + ".item"); k++) {
                     ItemStack it = cfg.getItemStack("returns." + j + ".items." + k + ".item");
                     long returnedAt = cfg.getLong("returns." + j + ".items." + k + ".returnedAt");
                     if (it != null) {
                        items.add(new AuctionManager.ReturnItem(it, returnedAt));
                     }
                  }

                  if (!items.isEmpty()) {
                     this.pendingReturns.put(owner, items);
                  }
               }
            }
         } catch (Exception var12) {
         }

         MenuUpdates.pruneTransactions(this);
         return;
      }

      MenuUpdates.pruneTransactions(this);
   }

   private boolean isRenamedItem(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         if (meta == null || !meta.hasDisplayName()) {
            return false;
         } else if (this.isSummerItem(item)) {
            return false;
         } else {
            return this.isSellWand(item) ? false : !this.isSpecialChickenItem(item);
         }
      } else {
         return false;
      }
   }

   private boolean holdsRenamedItem(ItemStack item) {
      return this.holdsRenamedItem(item, 0);
   }

   private boolean holdsRenamedItem(ItemStack item, int depth) {
      if (item != null && depth <= 3 && item.hasItemMeta()) {
         if (!Tag.SHULKER_BOXES.isTagged(item.getType())) {
            return false;
         }

         if (item.getItemMeta() instanceof BlockStateMeta bsm) {
            try {
               if (!(bsm.getBlockState() instanceof ShulkerBox box)) {
                  return false;
               }

               for (ItemStack child : box.getInventory().getContents()) {
                  if (child != null && (this.isRenamedItem(child) || this.holdsRenamedItem(child, depth + 1))) {
                     return true;
                  }
               }
            } catch (Exception var9) {
            }

            return false;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private boolean isSellWand(ItemStack item) {
      if (item != null && item.hasItemMeta() && SELL_WAND_MARK != null) {
         ItemMeta meta = item.getItemMeta();
         if (meta == null) {
            return false;
         }

         Byte b = (Byte)meta.getPersistentDataContainer().get(SELL_WAND_MARK, PersistentDataType.BYTE);
         return b != null && b == 1;
      } else {
         return false;
      }
   }

   private boolean isSummerItem(ItemStack item) {
      if (item != null && item.hasItemMeta() && SUMMER_TAG != null) {
         ItemMeta meta = item.getItemMeta();
         return meta == null ? false : meta.getPersistentDataContainer().has(SUMMER_TAG, PersistentDataType.BYTE);
      } else {
         return false;
      }
   }

   private boolean isChickenPickaxe(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         if (meta == null) {
            return false;
         }

         HashSet<String> tokens = new HashSet<>();
         if (meta.hasDisplayName()) {
            tokens.add(ChatColor.stripColor(meta.getDisplayName()));
         }

         if (meta.hasLore() && meta.getLore() != null) {
            for (String loreLine : meta.getLore()) {
               if (loreLine != null) {
                  tokens.add(ChatColor.stripColor(loreLine));
               }
            }
         }

         PersistentDataContainer pdc = meta.getPersistentDataContainer();

         for (NamespacedKey key : pdc.getKeys()) {
            tokens.add(key.getNamespace());
            tokens.add(key.getKey());

            try {
               String value = (String)pdc.get(key, PersistentDataType.STRING);
               if (value != null) {
                  tokens.add(value);
               }
            } catch (IllegalArgumentException var8) {
            }
         }

         for (String token : tokens) {
            String normalized = this.normalizeIdentifier(token);
            if (normalized.contains("chickenpickaxe")) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private String normalizeIdentifier(String value) {
      return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
   }

   private void expireRenamedListings() {
      long now = System.currentTimeMillis();
      ArrayList<AuctionManager.AuctionItem> toReturn = new ArrayList<>();

      for (AuctionManager.AuctionItem ai : new ArrayList<>(this.auctionItems)) {
         if (this.isRenamedItem(ai.item) || this.holdsRenamedItem(ai.item)) {
            toReturn.add(ai);
         }
      }

      if (!toReturn.isEmpty()) {
         for (AuctionManager.AuctionItem ai : toReturn) {
            if (this.usingDb() && !this.dbDeleteListing(ai.id)) {
               this.auctionItems.removeIf(x -> x.id == ai.id);
            } else {
               this.auctionItems.remove(ai);
               this.addReturn(ai.seller, ai.item, now);
               String itemName = this.prettyName(ai.item.getType());
               boolean insideShulker = !this.isRenamedItem(ai.item);
               String msg = insideShulker
                  ? "§cYour " + itemName + " listing expired and was returned. Shulkers with renamed items inside cannot be sold on /ah."
                  : "§cYour renamed " + itemName + " listing expired and was returned. Renamed items cannot be sold on /ah.";
               if (!this.plugin.sendAuctionAlert(ai.seller, msg)) {
                  this.offlineNotifications
                     .computeIfAbsent(ai.seller, k -> new ArrayList<>())
                     .add(
                        insideShulker
                           ? "Your " + itemName + " listing held renamed items and was returned."
                           : "Your renamed " + itemName + " listing expired and was returned."
                     );
               }
            }
         }

         if (!this.usingDb()) {
            this.saveAuctions();
         }
      }
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (!(sender instanceof Player p)) {
         return true;
      } else {
         if (args.length == 0) {
            this.openAH(p, 1, null);
            return true;
         }

         if (args[0].equalsIgnoreCase("sell")) {
            if (args.length >= 2 && this.parseFriendlyNumber(args[1]) < 0.0) {
               StringBuilder qb = new StringBuilder();

               for (String a : args) {
                  qb.append(a).append(" ");
               }

               String q = qb.toString().trim().toLowerCase();
               this.viewerFilter.remove(p.getUniqueId());
               this.viewerSearchQuery.put(p.getUniqueId(), q);
               this.performItemSearch(p, q, 1);
               return true;
            } else {
               ItemStack hand = p.getInventory().getItemInMainHand();
               if (hand != null && !hand.getType().isAir()) {
                  this.removeGeneratedWorthLore(hand);
                  if (this.isRenamedItem(hand)) {
                     p.sendMessage("§cYou can't sell renamed items.");
                     p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                     return true;
                  } else if (this.holdsRenamedItem(hand)) {
                     p.sendMessage("§cYou can't sell a shulker with renamed items inside.");
                     p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                     return true;
                  } else if (args.length < 2) {
                     p.sendMessage("§cThis number is invalid.");
                     p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                     return true;
                  } else {
                     double price = this.parseFriendlyNumber(args[1]);
                     if (price < 0.0) {
                        p.sendMessage("§cThis number is invalid.");
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                        return true;
                     } else {
                        double basePrice = PriceManager.getPrice(hand.getType()) * hand.getAmount();
                        if (price < basePrice) {
                           p.sendMessage("§cThis is too cheap.");
                           p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                           return true;
                        } else {
                           this.openSellConfirmGUI(p, hand.clone(), price);
                           return true;
                        }
                     }
                  }
               } else {
                  p.sendMessage("§cHold an item.");
                  return true;
               }
            }
         } else {
            StringBuilder sb = new StringBuilder();

            for (String arg : args) {
               sb.append(arg).append(" ");
            }

            String query;
            String realQuery = query = sb.toString().trim().toLowerCase();
            if (args[0].equalsIgnoreCase("item") && args.length >= 2) {
               realQuery = query.substring(5);
            } else if ((args[0].equalsIgnoreCase("user") || args[0].equalsIgnoreCase("player")) && args.length >= 2) {
               realQuery = query.substring(args[0].length() + 1);
            }

            OfflinePlayer target = null;

            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
               if (op.getName() != null && op.getName().equalsIgnoreCase(realQuery)) {
                  target = op;
                  break;
               }
            }

            if (target != null && target.hasPlayedBefore()) {
               this.viewerSearchQuery.remove(p.getUniqueId());
               this.openAH(p, 1, target.getUniqueId());
            } else {
               this.viewerFilter.remove(p.getUniqueId());
               this.viewerSearchQuery.put(p.getUniqueId(), realQuery);
               this.performItemSearch(p, realQuery, 1);
            }

            return true;
         }
      }
   }

   private void performItemSearch(Player p, String query, int page) {
      ArrayList<AuctionManager.AuctionItem> res = new ArrayList<>();

      for (AuctionManager.AuctionItem ai : this.auctionItems) {
         String mat = ai.item.getType().name().toLowerCase().replace("_", " ");
         String disp = ai.item.hasItemMeta() && ai.item.getItemMeta().hasDisplayName() ? ai.item.getItemMeta().getDisplayName().toLowerCase() : "";
         boolean matched = mat.contains(query) || disp.contains(query);
         if (!matched && ai.item.hasItemMeta() && ai.item.getItemMeta() instanceof BlockStateMeta) {
            try {
               BlockStateMeta bsm = (BlockStateMeta)ai.item.getItemMeta();
               if (bsm.getBlockState() instanceof ShulkerBox) {
                  ShulkerBox sb = (ShulkerBox)bsm.getBlockState();

                  for (ItemStack child : sb.getInventory().getContents()) {
                     if (child != null) {
                        String childMat = child.getType().name().toLowerCase().replace("_", " ");
                        String childDisp = child.hasItemMeta() && child.getItemMeta().hasDisplayName()
                           ? child.getItemMeta().getDisplayName().toLowerCase()
                           : "";
                        if (childMat.contains(query) || childDisp.contains(query)) {
                           matched = true;
                           break;
                        }
                     }
                  }
               }
            } catch (Exception var18) {
            }
         }

         if (matched) {
            res.add(ai);
         }
      }

      this.openAHWithList(p, res, "Search: " + query, page);
   }

   private boolean matchesQuery(ItemStack item, String query) {
      if (item == null) {
         return false;
      }

      String mat = item.getType().name().toLowerCase().replace('_', ' ');
      String disp = item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? ChatColor.stripColor(item.getItemMeta().getDisplayName()).toLowerCase() : "";
      if (!mat.contains(query) && !disp.contains(query)) {
         if (item.hasItemMeta() && item.getItemMeta() instanceof BlockStateMeta) {
            try {
               BlockStateMeta bsm = (BlockStateMeta)item.getItemMeta();
               if (bsm.getBlockState() instanceof ShulkerBox sb) {
                  for (ItemStack child : sb.getInventory().getContents()) {
                     if (child != null) {
                        String childMat = child.getType().name().toLowerCase().replace('_', ' ');
                        String childDisp = child.hasItemMeta() && child.getItemMeta().hasDisplayName()
                           ? ChatColor.stripColor(child.getItemMeta().getDisplayName()).toLowerCase()
                           : "";
                        if (childMat.contains(query) || childDisp.contains(query)) {
                           return true;
                        }
                     }
                  }
               }
            } catch (Exception var13) {
            }
         }

         return false;
      } else {
         return true;
      }
   }

   public void openAH(Player p, int page, UUID sellerFilter) {
      ArrayList<AuctionManager.AuctionItem> list = new ArrayList<>();

      for (AuctionManager.AuctionItem ai : this.auctionItems) {
         if (sellerFilter == null || ai.seller.equals(sellerFilter)) {
            list.add(ai);
         }
      }

      String filterChoice = this.viewerFilterChoice.getOrDefault(p.getUniqueId(), "all");
      list = new ArrayList<>(this.applyFilterCategory(list, filterChoice));
      String choice = this.viewerSortChoice.getOrDefault(p.getUniqueId(), "recent");
      this.applySortChoice(list, choice);
      int total = list.size();
      int maxPage = Math.max(1, (int)Math.ceil(total / 45.0));
      if (page < 1) {
         page = 1;
      }

      if (page > maxPage) {
         page = maxPage;
      }

      Inventory ah = Bukkit.createInventory(null, 54, MenuUpdates.displayTitle("§0" + toSmallCaps("auction house - page " + page)));
      this.renderItems(ah, list, page);
      if (page > 1) {
         ah.setItem(45, this.createItem(Material.ARROW, "§eᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ"));
      }

      ah.setItem(49, this.createItem(Material.EGG, "§eʀᴇғʀᴇꜱʜ"));
      if (page < maxPage) {
         ah.setItem(53, this.createItem(Material.ARROW, "§eɴᴇxᴛ ᴘᴀɢᴇ"));
      }

      ah.setItem(47, com.taxsmp.ui.GuiSkin.button(this.createSortButton(choice), "sort"));
      ah.setItem(48, com.taxsmp.ui.GuiSkin.button(this.createFilterButton(filterChoice), "filter"));
      ItemStack searchItem = this.createItem(Material.OAK_SIGN, "§eꜱᴇᴀʀᴄʜ");
      ItemMeta searchMeta = searchItem.getItemMeta();
      searchMeta.setLore(Arrays.asList("§7ᴜꜱᴇ: /ᴀʜ <ꜱᴇᴀʀᴄʜ ᴛᴇʀᴍꜱ>"));
      searchItem.setItemMeta(searchMeta);
      ah.setItem(50, com.taxsmp.ui.GuiSkin.button(searchItem, "search"));
      ah.setItem(51, this.createItem(Material.CHEST, "§eʏᴏᴜʀ ɪᴛᴇᴍꜱ"));
      this.viewerPage.put(p.getUniqueId(), page);
      this.viewerList.put(p.getUniqueId(), list);
      if (sellerFilter != null) {
         this.viewerFilter.put(p.getUniqueId(), sellerFilter);
         this.viewerSearchQuery.remove(p.getUniqueId());
      } else {
         this.viewerFilter.remove(p.getUniqueId());
         this.viewerSearchQuery.remove(p.getUniqueId());
      }

      p.openInventory(ah);
      this.startLiveExpiryTimer(p);
   }

   private void renderItems(Inventory inv, List<AuctionManager.AuctionItem> list, int page) {
      int start = (page - 1) * 45;

      int idx;
      for (int i = 0; i < 45 && (idx = start + i) < list.size(); i++) {
         inv.setItem(i, this.buildListingDisplay(list.get(idx)));
      }
   }

   private ItemStack buildListingDisplay(AuctionManager.AuctionItem ai) {
      ItemStack it = ai.item.clone();
      ItemMeta meta = it.getItemMeta();
      List<String> customlore = new ArrayList<>();
      if (meta != null) {
         Long remain = this.getSpecialRemainingMillis(ai.item);
         List<String> nativeLore = meta.hasLore() && meta.getLore() != null ? meta.getLore() : new ArrayList<>();
         if (remain != null) {
            customlore.addAll(this.refreshSelfDestructLore(nativeLore, remain));
         } else if (this.hasSelfDestructLore(ai.item) || this.isSellWand(ai.item)) {
            customlore.addAll(nativeLore);
         }
      }

      String sName = AhNames.name(ai.seller);
      customlore.add("§7Price: §a$" + PriceManager.formatPrice(ai.price));
      customlore.add("§7Seller: §a" + (sName == null ? "Unknown" : sName));
      long rem = Math.max(0L, ai.createdAt + 259200000L - System.currentTimeMillis());
      customlore.add("§7Time Left: §b" + rem / 86400000L + "d " + rem / 3600000L % 24L + "h " + rem / 60000L % 60L + "m");
      if (meta != null) {
         meta.setLore(customlore);
         it.setItemMeta(meta);
      }

      return it;
   }

   private List<String> refreshSelfDestructLore(List<String> lore, long remainMs) {
      List<String> out = new ArrayList<>();
      if (lore != null) {
         out.addAll(lore);
      }

      for (int i = 0; i < out.size() - 1; i++) {
         String line = out.get(i);
         String stripped = line == null ? "" : ChatColor.stripColor(line).trim();
         if (stripped.equalsIgnoreCase("Self Destruct:")) {
            String color = this.leadingColor(out.get(i + 1), "§8");
            out.set(i + 1, remainMs <= 0L ? "§cExpired" : color + this.formatDHM(remainMs));
            break;
         }
      }

      return out;
   }

   private String formatDHM(long ms) {
      long d = ms / 86400000L;
      long h = ms / 3600000L % 24L;
      long m = ms / 60000L % 60L;
      return d + "d " + h + "h " + m + "m";
   }

   private String leadingColor(String s, String def) {
      if (s != null && !s.isEmpty()) {
         StringBuilder sb = new StringBuilder();

         for (int i = 0; i + 1 < s.length() && s.charAt(i) == 167; i += 2) {
            sb.append(s, i, i + 2);
         }

         return sb.length() > 0 ? sb.toString() : def;
      } else {
         return def;
      }
   }

   private boolean hasExpiry(ItemStack item) {
      return this.getSpecialRemainingMillis(item) != null;
   }

   private boolean hasSelfDestructLore(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         if (meta != null && meta.hasLore() && meta.getLore() != null) {
            for (String line : meta.getLore()) {
               if (line != null && ChatColor.stripColor(line).trim().equalsIgnoreCase("Self Destruct:")) {
                  return true;
               }
            }

            return false;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private boolean isSpecialChickenItem(ItemStack item) {
      return this.getSpecialRemainingMillis(item) != null || this.hasSelfDestructLore(item) || this.isChickenPickaxe(item);
   }

   private Long getSpecialRemainingMillis(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         if (meta == null) {
            return null;
         } else {
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            long now = System.currentTimeMillis();
            Long v;
            if (this.hasMark(pdc, CP_MARK) && (v = this.getLong(pdc, CP_CREATED)) != null) {
               return Math.max(0L, 259200000L - (now - v));
            } else if (this.hasMark(pdc, CA_MARK) && (v = this.getLong(pdc, CA_CREATED)) != null) {
               return Math.max(0L, 259200000L - (now - v));
            } else if (this.hasMark(pdc, CS_MARK) && (v = this.getLong(pdc, CS_DESTRUCT)) != null) {
               return Math.max(0L, v - now);
            } else {
               return this.hasMark(pdc, CSA_MARK) && (v = this.getLong(pdc, CSA_EXPIRES)) != null ? Math.max(0L, v - now) : null;
            }
         }
      } else {
         return null;
      }
   }

   private boolean hasMark(PersistentDataContainer pdc, NamespacedKey key) {
      if (key == null) {
         return false;
      }

      Byte b = (Byte)pdc.get(key, PersistentDataType.BYTE);
      return b != null && b == 1;
   }

   private Long getLong(PersistentDataContainer pdc, NamespacedKey key) {
      return key == null ? null : (Long)pdc.get(key, PersistentDataType.LONG);
   }

   private void startLiveExpiryTimer(Player p) {
      this.stopLiveExpiryTimer(p);
      List<AuctionManager.AuctionItem> list = this.viewerList.getOrDefault(p.getUniqueId(), Collections.emptyList());
      int page = this.viewerPage.getOrDefault(p.getUniqueId(), 1);
      int start = (page - 1) * 45;
      boolean hasTimed = false;

      for (int i = 0; i < 45 && start + i < list.size(); i++) {
         if (this.hasExpiry(list.get(start + i).item)) {
            hasTimed = true;
            break;
         }
      }

      if (hasTimed) {
         UUID uid = p.getUniqueId();

         try {
            ScheduledTask task = p.getScheduler().runAtFixedRate(this.plugin, t -> this.tickLiveExpiry(p), () -> this.liveTimerTasks.remove(uid), 20L, 20L);
            if (task != null) {
               this.liveTimerTasks.put(uid, task);
            }
         } catch (Throwable var8) {
         }
      }
   }

   private void stopLiveExpiryTimer(Player p) {
      if (p != null) {
         ScheduledTask task = this.liveTimerTasks.remove(p.getUniqueId());
         if (task != null) {
            try {
               task.cancel();
            } catch (Throwable var4) {
            }
         }
      }
   }

   private void tickLiveExpiry(Player p) {
      if (p != null && p.isOnline()) {
         String title = p.getOpenInventory() != null ? MenuUpdates.internalTitle(p.getOpenInventory().getOriginalTitle()) : null;
         if (title != null && title.startsWith("§0" + toSmallCaps("auction house - "))) {
            Inventory top = p.getOpenInventory().getTopInventory();
            if (top == null) {
               this.stopLiveExpiryTimer(p);
            } else {
               List<AuctionManager.AuctionItem> list = this.viewerList.getOrDefault(p.getUniqueId(), Collections.emptyList());
               int page = this.viewerPage.getOrDefault(p.getUniqueId(), 1);
               int start = (page - 1) * 45;
               boolean changed = false;

               for (int slot = 0; slot < 45; slot++) {
                  int idx = start + slot;
                  if (idx >= list.size()) {
                     break;
                  }

                  AuctionManager.AuctionItem ai = list.get(idx);
                  if (this.hasExpiry(ai.item)) {
                     top.setItem(slot, this.buildListingDisplay(ai));
                     changed = true;
                  }
               }

               if (changed) {
                  p.updateInventory();
               }
            }
         } else {
            this.stopLiveExpiryTimer(p);
         }
      } else {
         this.stopLiveExpiryTimer(p);
      }
   }

   private ItemStack createSortButton(String currentSort) {
      ItemStack it = new ItemStack(Material.CAULDRON);
      ItemMeta im = it.getItemMeta();
      im.setDisplayName("§eꜱᴏʀᴛ");
      List<String> lore = new ArrayList<>();
      lore.add(("highest".equals(currentSort) ? "§a" : "§7") + "ʜɪɢʜᴇꜱᴛ ᴘʀɪᴄᴇ");
      lore.add(("lowest".equals(currentSort) ? "§a" : "§7") + "ʟᴏᴡᴇꜱᴛ ᴘʀɪᴄᴇ");
      lore.add(("last".equals(currentSort) ? "§a" : "§7") + "ʟᴀꜱᴛ ʟɪꜱᴛᴇᴅ");
      lore.add(("recent".equals(currentSort) ? "§a" : "§7") + "ʀᴇᴄᴇɴᴛʟʏ ʟɪꜱᴛᴇᴅ");
      im.setLore(lore);
      it.setItemMeta(im);
      return it;
   }

   private ItemStack createFilterButton(String currentFilter) {
      ItemStack it = new ItemStack(Material.HOPPER);
      ItemMeta im = it.getItemMeta();
      im.setDisplayName("§eғɪʟᴛᴇʀ");
      List<String> lore = new ArrayList<>();
      lore.add(("all".equals(currentFilter) ? "§a" : "§7") + "ᴀʟʟ");
      lore.add(("combat".equals(currentFilter) ? "§a" : "§7") + "ᴄᴏᴍʙᴀᴛ");
      lore.add(("armor".equals(currentFilter) ? "§a" : "§7") + "ᴀʀᴍᴏʀ");
      lore.add(("tools".equals(currentFilter) ? "§a" : "§7") + "ᴛᴏᴏʟꜱ");
      lore.add(("blocks".equals(currentFilter) ? "§a" : "§7") + "ʙʟᴏᴄᴋꜱ");
      lore.add(("misc".equals(currentFilter) ? "§a" : "§7") + "ᴍɪꜱᴄ");
      im.setLore(lore);
      it.setItemMeta(im);
      return it;
   }

   private String cycleSort(String current) {
      switch (current) {
         case "highest":
            return "lowest";
         case "lowest":
            return "last";
         case "last":
            return "recent";
         case "recent":
            return "highest";
         default:
            return "highest";
      }
   }

   private String cycleFilter(String current) {
      switch (current) {
         case "all":
            return "combat";
         case "combat":
            return "armor";
         case "armor":
            return "tools";
         case "tools":
            return "blocks";
         case "blocks":
            return "misc";
         case "misc":
            return "all";
         default:
            return "all";
      }
   }

   private boolean matchesFilterCategory(Material mat, String category) {
      if ("all".equals(category)) {
         return true;
      }

      String n = mat.name();
      switch (category) {
         case "combat":
            return n.contains("SWORD")
               || mat == Material.BOW
               || mat == Material.CROSSBOW
               || mat == Material.TRIDENT
               || n.contains("ARROW")
               || mat == Material.SHIELD
               || mat == Material.TOTEM_OF_UNDYING
               || mat == Material.GOLDEN_APPLE
               || mat == Material.ENCHANTED_GOLDEN_APPLE
               || mat == Material.END_CRYSTAL
               || mat == Material.ENDER_PEARL
               || mat == Material.TNT
               || mat == Material.RESPAWN_ANCHOR;
         case "armor":
            return n.contains("HELMET") || n.contains("CHESTPLATE") || n.contains("LEGGINGS") || n.contains("BOOTS") || mat == Material.ELYTRA;
         case "tools":
            return n.contains("PICKAXE")
               || n.endsWith("_AXE")
               || n.contains("SHOVEL")
               || n.contains("_HOE")
               || mat == Material.SHEARS
               || mat == Material.FLINT_AND_STEEL
               || mat == Material.FISHING_ROD;
         case "blocks":
            return mat.isBlock();
         case "misc":
            return !this.matchesFilterCategory(mat, "combat")
               && !this.matchesFilterCategory(mat, "armor")
               && !this.matchesFilterCategory(mat, "tools")
               && !this.matchesFilterCategory(mat, "blocks");
         default:
            return true;
      }
   }

   private List<AuctionManager.AuctionItem> applyFilterCategory(List<AuctionManager.AuctionItem> list, String category) {
      if ("all".equals(category)) {
         return list;
      }

      ArrayList<AuctionManager.AuctionItem> filtered = new ArrayList<>();

      for (AuctionManager.AuctionItem ai : list) {
         if (this.matchesFilterCategory(ai.item.getType(), category)) {
            filtered.add(ai);
         }
      }

      return filtered;
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent e) {
      String title = MenuUpdates.internalTitle(e.getView().getOriginalTitle());
      Player p = (Player)e.getWhoClicked();
      int slot = e.getRawSlot();
      if (slot < 0) {
         if (this.isAuctionView(title)) {
            e.setCancelled(true);
         }
      } else if (title.startsWith("§0" + toSmallCaps("auction house - "))) {
         e.setCancelled(true);
         if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getBottomInventory())) {
            return;
         }

         int page = this.viewerPage.getOrDefault(p.getUniqueId(), 1);
         if (slot == 47) {
            String curSort = this.viewerSortChoice.getOrDefault(p.getUniqueId(), "recent");
            String newSort = this.cycleSort(curSort);
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.viewerSortChoice.put(p.getUniqueId(), newSort);
            this.saveAuctions();
            UUID sf = this.viewerFilter.get(p.getUniqueId());
            String sq = this.viewerSearchQuery.get(p.getUniqueId());
            if (sf != null) {
               this.openAH(p, 1, sf);
            } else if (sq != null) {
               this.performItemSearch(p, sq, 1);
            } else {
               this.openAH(p, 1, null);
            }
         } else if (slot == 48) {
            String curFilter = this.viewerFilterChoice.getOrDefault(p.getUniqueId(), "all");
            String newFilter = this.cycleFilter(curFilter);
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.viewerFilterChoice.put(p.getUniqueId(), newFilter);
            this.saveAuctions();
            UUID sf2 = this.viewerFilter.get(p.getUniqueId());
            String sq2 = this.viewerSearchQuery.get(p.getUniqueId());
            if (sf2 != null) {
               this.openAH(p, 1, sf2);
            } else if (sq2 != null) {
               this.performItemSearch(p, sq2, 1);
            } else {
               this.openAH(p, 1, null);
            }
         } else if (slot != 50) {
            if (slot == 51) {
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               this.openUserItems(p);
            } else if (slot == 49) {
               p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
               UUID filter = this.viewerFilter.get(p.getUniqueId());
               String query = this.viewerSearchQuery.get(p.getUniqueId());
               if (filter != null) {
                  this.openAH(p, page, filter);
               } else if (query != null) {
                  this.performItemSearch(p, query, page);
               } else {
                  this.openAH(p, page, null);
               }
            } else if (slot == 45) {
               if (e.getCurrentItem() == null || e.getCurrentItem().getType() != Material.ARROW) {
                  return;
               }

               int targetPage = Math.max(1, page - 1);
               UUID filter = this.viewerFilter.get(p.getUniqueId());
               String query = this.viewerSearchQuery.get(p.getUniqueId());
               if (filter != null) {
                  this.openAH(p, targetPage, filter);
               } else if (query != null) {
                  this.performItemSearch(p, query, targetPage);
               } else {
                  this.openAH(p, targetPage, null);
               }
            } else if (slot == 53) {
               if (e.getCurrentItem() == null || e.getCurrentItem().getType() != Material.ARROW) {
                  return;
               }

               int targetPage = page + 1;
               UUID filter = this.viewerFilter.get(p.getUniqueId());
               String query = this.viewerSearchQuery.get(p.getUniqueId());
               if (filter != null) {
                  this.openAH(p, targetPage, filter);
               } else if (query != null) {
                  this.performItemSearch(p, query, targetPage);
               } else {
                  this.openAH(p, targetPage, null);
               }
            } else if (slot >= 0 && slot < 45) {
               e.setCancelled(true);
               ItemStack clickedItem = e.getCurrentItem();
               if (clickedItem == null || clickedItem.getType() == Material.AIR) {
                  return;
               }

               this.handleAHItemClick(p, slot);
            }
         }
      } else if (title.equals("§0" + toSmallCaps("confirm ah buy"))) {
         e.setCancelled(true);
         if (slot == 15) {
            this.processPurchase(p);
         } else if (slot == 11) {
            p.closeInventory();
         }
      } else if (title.equals("§0" + toSmallCaps("confirm ah sell"))) {
         e.setCancelled(true);
         if (slot == 15) {
            this.processListing(p);
         } else if (slot == 11) {
            p.closeInventory();
         }
      } else if (title.equals("§0" + toSmallCaps("auction -> your items"))) {
         e.setCancelled(true);
         this.handleUserItemClick(p, slot);
      } else if (title.startsWith("§0" + toSmallCaps("transactions (page "))) {
         e.setCancelled(true);
         this.handleTransactionsClick(p, slot, title);
      }
   }

   private boolean isAuctionView(String title) {
      return title.startsWith("§0" + toSmallCaps("auction house - "))
         || title.equals("§0" + toSmallCaps("confirm ah buy"))
         || title.equals("§0" + toSmallCaps("confirm ah sell"))
         || title.equals("§0" + toSmallCaps("auction -> your items"))
         || title.startsWith("§0" + toSmallCaps("transactions (page "));
   }

   private void handleAHItemClick(Player p, int slot) {
      List<AuctionManager.AuctionItem> current = this.viewerList.getOrDefault(p.getUniqueId(), this.auctionItems);
      int page = this.viewerPage.getOrDefault(p.getUniqueId(), 1);
      int idx = (page - 1) * 45 + slot;
      if (idx < current.size()) {
         AuctionManager.AuctionItem ai = current.get(idx);
         if (ai.seller.equals(p.getUniqueId())) {
            p.sendMessage("§eTo withdraw this item, please click the §bChest §eicon below.");
         } else {
            double balance = this.plugin.getEconomy().getBalance(p);
            if (balance < ai.price) {
               p.sendMessage("§cYou cannot afford this! Need $" + PriceManager.formatPrice(ai.price));
               p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            } else {
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               if (this.isQuickAuctionBuyEnabled(p)) {
                  this.pendingBuys.put(p.getUniqueId(), ai);
                  this.processPurchase(p);
               } else {
                  this.pendingBuys.put(p.getUniqueId(), ai);
                  this.openBuyConfirmGUI(p, ai);
               }
            }
         }
      }
   }

   private void processPurchase(Player p) {
      AuctionManager.AuctionItem ai = this.pendingBuys.remove(p.getUniqueId());
      if (ai != null && this.auctionItems.contains(ai)) {
         if (p.getInventory().firstEmpty() == -1) {
            p.sendMessage("§cYour inventory is full — clear some space and try again.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
         } else if (!(this.plugin.getEconomy().getBalance(p) < ai.price)) {
            if (!this.usingDb()) {
               this.completePurchase(p, ai, Math.min(ai.price, 1.0E10));
            } else {
               double payout = Math.min(ai.price, 1.0E10);
               if (this.claimsInFlight.add(ai.id)) {
                  if (!this.plugin.getEconomy().withdrawPlayer(p, payout)) {
                     this.claimsInFlight.remove(ai.id);
                  } else {
                     this.claimExecutor.execute(() -> {
                        boolean won = this.claimListingNow(ai.id);
                        p.getScheduler().run(this.plugin, tt -> {
                           this.claimsInFlight.remove(ai.id);
                           if (won) {
                              this.completePurchase(p, ai, payout);
                           } else {
                              this.plugin.getEconomy().depositPlayer(p, payout);
                              this.auctionItems.removeIf(x -> x.id == ai.id);
                              p.sendMessage("§cThat item was just bought by someone else.");
                              p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                              this.reopenAfterPurchase(p);
                           }
                        }, () -> this.settleAbandonedPurchase(p.getUniqueId(), ai, payout, won));
                     });
                  }
               }
            }
         }
      }
   }

   private void reopenAfterPurchase(Player p) {
      int page = this.viewerPage.getOrDefault(p.getUniqueId(), 1);
      UUID filter = this.viewerFilter.get(p.getUniqueId());
      String query = this.viewerSearchQuery.get(p.getUniqueId());
      if (filter != null) {
         this.openAH(p, page, filter);
      } else if (query != null) {
         this.performItemSearch(p, query, page);
      } else {
         this.openAH(p, page, null);
      }
   }

   private void settleAbandonedPurchase(UUID buyer, AuctionManager.AuctionItem ai, double payout, boolean won) {
      this.claimsInFlight.remove(ai.id);
      if (!won) {
         this.plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(buyer), payout);
         this.auctionItems.removeIf(x -> x.id == ai.id);
      } else {
         this.auctionItems.remove(ai);
         this.plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(ai.seller), payout);
         this.addAhSpent(buyer, payout);
         this.addAhMade(ai.seller, payout);
         Bukkit.getAsyncScheduler().runNow(this.plugin, t -> this.addReturn(buyer, ai.item, System.currentTimeMillis()));
      }
   }

   private void completePurchase(Player p, AuctionManager.AuctionItem ai, double payout) {
      if (!this.usingDb()) {
         this.plugin.getEconomy().withdrawPlayer(p, payout);
      }

      this.plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(ai.seller), payout);
      Map<Integer, ItemStack> leftovers = p.getInventory().addItem(new ItemStack[]{ai.item});
      this.auctionItems.remove(ai);
      if (!leftovers.isEmpty()) {
         long now = System.currentTimeMillis();

         for (ItemStack it : leftovers.values()) {
            this.addReturn(p.getUniqueId(), it, now);
         }

         p.sendMessage("§eYour inventory was full; purchased item placed in /ah -> Your Items to collect.");
      }

      String itemName = this.prettyName(ai.item.getType());
      String fmt = PriceManager.formatPrice(payout);
      p.sendMessage("§aYou bought " + itemName + " for $" + fmt);
      p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
      p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
      p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
      UUID sellerId = ai.seller;
      String sellerMsg = "§x§5§5§F§F§B§8" + p.getName() + "§7 bought your " + itemName + " for §x§5§5§F§F§B§8$" + fmt;
      String sellerName = AhNames.name(sellerId);
      if (sellerName == null || sellerName.isBlank()) {
         sellerName = "Unknown";
      }

      this.addTransaction(p.getUniqueId(), ai.item, "§fYou bought §a" + sellerName + "§f's " + itemName + " for §a$" + fmt);
      this.addTransaction(sellerId, ai.item, "§a" + p.getName() + " §fbought your " + itemName + " for §a$" + fmt);
      this.addAhSpent(p.getUniqueId(), payout);
      this.addAhMade(sellerId, payout);
      if (!this.plugin.sendAuctionAlert(sellerId, sellerMsg)) {
         this.offlineNotifications.computeIfAbsent(sellerId, k -> new ArrayList<>()).add(sellerMsg + "§7 while you were away");
         this.offlineEarnings.put(sellerId, this.offlineEarnings.getOrDefault(sellerId, 0.0) + payout);
      }

      if (!this.usingDb()) {
         this.saveAuctions();
      }

      this.reopenAfterPurchase(p);
   }

   private void processListing(Player p) {
      ItemStack item = this.pendingItems.remove(p.getUniqueId());
      Double price = this.pendingPrices.remove(p.getUniqueId());
      if (item != null && price != null) {
         this.removeGeneratedWorthLore(item);
         if (this.isRenamedItem(item)) {
            p.sendMessage("§cYou can't sell renamed items.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            p.closeInventory();
         } else if (this.holdsRenamedItem(item)) {
            p.sendMessage("§cYou can't sell a shulker with renamed items inside.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            p.closeInventory();
         } else {
            int active = 0;

            for (AuctionManager.AuctionItem ai : this.auctionItems) {
               if (ai.seller.equals(p.getUniqueId())) {
                  active++;
               }
            }

            int max = this.hasTwentyAuctionPermission(p) ? 20 : 14;
            if (active >= max) {
               p.sendMessage("§cYou may only have up to " + max + " active listings.");
               p.closeInventory();
            } else {
               if (p.getInventory().removeItem(new ItemStack[]{item}).isEmpty()) {
                  double cap = 1.0E10;
                  double storePrice = Math.min(price, cap);
                  AuctionManager.AuctionItem ai = new AuctionManager.AuctionItem(p.getUniqueId(), item, storePrice, System.currentTimeMillis());
                  if (this.usingDb()) {
                     Bukkit.getAsyncScheduler().runNow(this.plugin, t -> {
                        long id = this.dbInsertListing(ai);
                        p.getScheduler().run(this.plugin, tt -> {
                           if (id > 0L) {
                              this.auctionItems.add(ai);
                              if (price > cap) {
                                 p.sendMessage("§ePrice capped at $10,000,000,000.00");
                              }

                              p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                           } else {
                              this.addReturn(p.getUniqueId(), ai.item, System.currentTimeMillis());
                              p.sendMessage("§cCouldn't list that item right now — it's in /ah -> Your Items.");
                              p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                           }
                        }, null);
                     });
                  } else {
                     this.auctionItems.add(ai);
                     this.saveAuctions();
                     if (price > cap) {
                        p.sendMessage("§ePrice capped at $10,000,000,000.00");
                     }

                     p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                  }
               }

               p.closeInventory();
            }
         }
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent e) {
      Player player = e.getPlayer();
      UUID uid = player.getUniqueId();
      List<String> notes = this.offlineNotifications.remove(uid);
      if (notes != null && !notes.isEmpty()) {
         LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();

         for (String s : notes) {
            counts.merge(s, 1, Integer::sum);
         }

         player.getScheduler().run(this.plugin, task -> {
            if (this.plugin.isAuctionAlertsEnabledFor(player)) {
               for (Entry<String, Integer> entry : counts.entrySet()) {
                  String line = entry.getKey();
                  int count = entry.getValue();
                  if (count > 1) {
                     int idx = line.indexOf("§7 while you were away");
                     if (idx >= 0) {
                        line = line.substring(0, idx) + " §7(x" + count + ")" + line.substring(idx);
                     } else {
                        line = line + " §7(x" + count + ")";
                     }
                  }

                  player.sendMessage(line);
                  player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
               }
            }
         }, null);
         this.offlineEarnings.remove(uid);
         this.saveAuctions();
      }
   }

   private void openUserItems(Player p) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, t -> {
            List<AuctionManager.ReturnItem> rets = this.dbLoadReturns(p.getUniqueId());
            p.getScheduler().run(this.plugin, tt -> this.renderUserItems(p, rets), null);
         });
      } else {
         this.renderUserItems(p, this.pendingReturns.getOrDefault(p.getUniqueId(), new ArrayList<>()));
      }
   }

   private void renderUserItems(Player p, List<AuctionManager.ReturnItem> returned) {
      Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("auction -> your items")));
      int i = 0;
      long now = System.currentTimeMillis();
      ArrayList<AuctionManager.AuctionItem> mineActive = new ArrayList<>();

      for (AuctionManager.AuctionItem ai : this.auctionItems) {
         if (ai.seller.equals(p.getUniqueId())) {
            mineActive.add(ai);
         }
      }

      this.viewerUserActive.put(p.getUniqueId(), mineActive);
      this.viewerUserReturns.put(p.getUniqueId(), new ArrayList<>(returned));

      for (AuctionManager.AuctionItem ai : mineActive) {
         if (i >= 26) {
            break;
         }

         ItemStack it = ai.item.clone();
         ItemMeta m = it.getItemMeta();
         List<String> lore = new ArrayList<>();
         if (m != null) {
            Long remain = this.getSpecialRemainingMillis(ai.item);
            List<String> nativeLore = m.hasLore() && m.getLore() != null ? m.getLore() : new ArrayList<>();
            if (remain != null) {
               lore.addAll(this.refreshSelfDestructLore(nativeLore, remain));
            } else if (this.hasSelfDestructLore(ai.item)) {
               lore.addAll(nativeLore);
            }
         }

         lore.add("§7Price: §a$" + PriceManager.formatPrice(ai.price));
         long rem = Math.max(0L, ai.createdAt + 259200000L - now);
         lore.add("§7Time Left: §b" + rem / 86400000L + "d " + rem / 3600000L % 24L + "h " + rem / 60000L % 60L + "m");
         if (m != null) {
            m.setLore(lore);
            it.setItemMeta(m);
         }

         inv.setItem(i++, it);
      }

      long deleteWindow = 604800000L;

      for (AuctionManager.ReturnItem ri : returned) {
         if (i >= 26) {
            break;
         }

         ItemStack it = ri.item.clone();
         ItemMeta m = it.getItemMeta();
         List<String> lore = new ArrayList<>();
         lore.add("§cYour item was not sold");
         lore.add("§7Click to collect");
         long rem = Math.max(0L, deleteWindow - (now - ri.returnedAt));
         long days = rem / 86400000L;
         long hours = rem / 3600000L % 24L;
         long mins = rem / 60000L % 60L;
         lore.add("§7" + days + "d " + hours + "h " + mins + "m Until Item Deletes");
         m.setLore(lore);
         it.setItemMeta(m);
         inv.setItem(i++, it);
      }

      inv.setItem(26, this.createTransactionsOpenBook());
      p.openInventory(inv);
   }

   private void handleUserItemClick(Player p, int slot) {
      if (slot == 26) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.openTransactions(p, 1);
      } else if (slot >= 0 && slot <= 26) {
         List<AuctionManager.AuctionItem> mine = this.viewerUserActive.getOrDefault(p.getUniqueId(), Collections.emptyList());
         List<AuctionManager.ReturnItem> returned = this.viewerUserReturns.getOrDefault(p.getUniqueId(), Collections.emptyList());
         if (slot < mine.size()) {
            AuctionManager.AuctionItem ai = mine.get(slot);
            if (this.usingDb()) {
               Bukkit.getAsyncScheduler().runNow(this.plugin, t -> {
                  boolean won = this.dbDeleteListing(ai.id);
                  p.getScheduler().run(this.plugin, tt -> {
                     this.auctionItems.removeIf(x -> x.id == ai.id);
                     if (won) {
                        this.giveBackOrReturn(p, ai.item);
                     } else {
                        p.sendMessage("§cThat item is no longer available.");
                     }

                     p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                     this.openUserItems(p);
                  }, null);
               });
            } else if (!this.auctionItems.contains(ai)) {
               this.openUserItems(p);
            } else {
               this.auctionItems.remove(ai);
               this.giveBackOrReturn(p, ai.item);
               this.saveAuctions();
               p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
               this.openUserItems(p);
            }
         } else {
            int rIdx = slot - mine.size();
            if (rIdx >= 0 && rIdx < returned.size()) {
               AuctionManager.ReturnItem ri = returned.get(rIdx);
               if (this.usingDb()) {
                  Bukkit.getAsyncScheduler().runNow(this.plugin, t -> {
                     boolean won = this.dbDeleteReturn(ri.id);
                     p.getScheduler().run(this.plugin, tt -> {
                        if (won) {
                           this.giveBackOrReturn(p, ri.item);
                        } else {
                           p.sendMessage("§cThat item is no longer available.");
                        }

                        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                        this.openUserItems(p);
                     }, null);
                  });
                  return;
               }

               List<AuctionManager.ReturnItem> list = this.pendingReturns.get(p.getUniqueId());
               if (list != null) {
                  list.remove(ri);
               }

               this.giveBackOrReturn(p, ri.item);
               this.saveAuctions();
               p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
               this.openUserItems(p);
            }
         }
      }
   }

   private void giveBackOrReturn(Player p, ItemStack item) {
      Map<Integer, ItemStack> leftovers = p.getInventory().addItem(new ItemStack[]{item});
      if (!leftovers.isEmpty()) {
         long now = System.currentTimeMillis();

         for (ItemStack it : leftovers.values()) {
            this.addReturn(p.getUniqueId(), it, now);
         }

         p.sendMessage("§eYour inventory was full; item placed in Returns (open /ah -> Your Items).");
      }
   }

   private String prettyName(Material m) {
      return m.name().toLowerCase().replace('_', ' ');
   }

   private void openBuyConfirmGUI(Player p, AuctionManager.AuctionItem ai) {
      Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("confirm ah buy")));
      ItemStack preview = ai.item.clone();
      ItemMeta previewMeta = preview.getItemMeta();
      if (previewMeta != null) {
         Long remain = this.getSpecialRemainingMillis(ai.item);
         if (remain != null && previewMeta.hasLore() && previewMeta.getLore() != null) {
            previewMeta.setLore(this.refreshSelfDestructLore(previewMeta.getLore(), remain));
            preview.setItemMeta(previewMeta);
         }
      }

      inv.setItem(13, preview);
      ItemStack green = this.createItem(Material.LIME_DYE, "§aᴄᴏɴғɪʀᴍ ᴘᴜʀᴄʜᴀꜱᴇ");
      ItemMeta gm = green.getItemMeta();
      gm.setLore(Collections.singletonList("§7Price: §a$" + PriceManager.formatPrice(ai.price)));
      green.setItemMeta(gm);
      inv.setItem(15, green);
      inv.setItem(11, this.createItem(Material.RED_DYE, "§cᴄᴀɴᴄᴇʟ"));
      p.openInventory(inv);
   }

   private void openSellConfirmGUI(Player p, ItemStack hand, double price) {
      this.removeGeneratedWorthLore(hand);
      Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("confirm ah sell")));
      inv.setItem(13, hand);
      ItemStack green = com.taxsmp.ui.GuiSkin.button(this.createItem(Material.LIME_DYE, "§aᴄᴏɴғɪʀᴍ ʟɪꜱᴛɪɴɢ"), "yes");
      ItemMeta gm = green.getItemMeta();
      double raw = price > 0.0 ? price : PriceManager.getPrice(hand.getType()) * hand.getAmount();
      double cap = 1.0E10;
      double pFinal = Math.min(raw, cap);
      gm.setLore(Collections.singletonList("§7Price: §a$" + PriceManager.formatPrice(pFinal)));
      green.setItemMeta(gm);
      inv.setItem(15, green);
      inv.setItem(11, this.createItem(Material.RED_DYE, "§cᴄᴀɴᴄᴇʟ"));
      this.pendingItems.put(p.getUniqueId(), hand);
      this.pendingPrices.put(p.getUniqueId(), pFinal);
      p.openInventory(inv);
   }

   private void removeGeneratedWorthLore(ItemStack item) {
      if (item != null && !item.getType().isAir() && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         if (meta != null && meta.hasLore() && meta.getLore() != null) {
            List<String> lore = new ArrayList<>(meta.getLore());
            boolean removed = lore.removeIf(line -> {
               String stripped = ChatColor.stripColor(line);
               return stripped != null && stripped.startsWith("Worth:");
            });
            if (removed) {
               meta.setLore(lore.isEmpty() ? null : lore);
               item.setItemMeta(meta);
            }
         }
      }
   }

   private void openTransactions(Player p, int page) {
      MenuUpdates.pruneTransactions(this);
      List<AuctionManager.TransactionRecord> all = this.recentTransactions.getOrDefault(p.getUniqueId(), Collections.emptyList());
      String query = this.txSearchQuery.get(p.getUniqueId());
      List<AuctionManager.TransactionRecord> transactions;
      if (query != null && !query.isBlank()) {
         String q = query.toLowerCase();
         transactions = new ArrayList<>();

         for (AuctionManager.TransactionRecord tr : all) {
            String mat = tr.item.getType().name().toLowerCase().replace('_', ' ');
            String disp = tr.item.hasItemMeta() && tr.item.getItemMeta().hasDisplayName()
               ? ChatColor.stripColor(tr.item.getItemMeta().getDisplayName()).toLowerCase()
               : "";
            if (mat.contains(q) || disp.contains(q)) {
               transactions.add(tr);
            }
         }
      } else {
         transactions = all;
      }

      int pageCapacity = 45;
      int maxPage = Math.max(1, (int)Math.ceil(transactions.size() / 45.0));
      if (page < 1) {
         page = 1;
      }

      if (page > maxPage) {
         page = maxPage;
      }

      Inventory inv = Bukkit.createInventory(null, 54, MenuUpdates.displayTitle("§0" + toSmallCaps("transactions (page " + page + ")")));
      int start = (page - 1) * 45;
      int placed = 0;

      for (int slot = 0; slot < 45 && placed < 45; slot++) {
         int idx = start + placed;
         if (idx >= transactions.size()) {
            break;
         }

         AuctionManager.TransactionRecord record = transactions.get(idx);
         ItemStack item = record.item.clone();
         ItemMeta meta = item.getItemMeta();
         if (meta != null) {
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            if (!lore.isEmpty()) {
               lore.add("");
            }

            lore.add(record.message);
            lore.add("§b" + this.formatTimeAgo(record.timestamp) + " ago");
            meta.setLore(lore);
            item.setItemMeta(meta);
         }

         inv.setItem(slot, item);
         placed++;
      }

      inv.setItem(45, page > 1 ? this.createItem(Material.ARROW, "§e" + toSmallCaps("previous page")) : null);
      inv.setItem(48, this.createStatsBook(p));
      inv.setItem(49, this.createItem(Material.WRITABLE_BOOK, "§e" + toSmallCaps("refresh")));
      inv.setItem(53, page < maxPage ? this.createItem(Material.ARROW, "§e" + toSmallCaps("next page")) : null);
      p.openInventory(inv);
   }

   private void handleTransactionsClick(Player p, int slot, String title) {
      MenuUpdates.pruneTransactions(this);
      int page = 1;

      try {
         int open = title.lastIndexOf(40);
         int close = title.lastIndexOf(41);
         if (open >= 0 && close > open) {
            String inner = ChatColor.stripColor(title.substring(open + 1, close));
            StringBuilder digits = new StringBuilder();

            for (char c : inner.toCharArray()) {
               if (Character.isDigit(c)) {
                  digits.append(c);
               }
            }

            if (digits.length() > 0) {
               page = Integer.parseInt(digits.toString());
            }
         }
      } catch (Exception var13) {
      }

      List<AuctionManager.TransactionRecord> base = this.recentTransactions.getOrDefault(p.getUniqueId(), Collections.emptyList());
      String q = this.txSearchQuery.get(p.getUniqueId());
      List<AuctionManager.TransactionRecord> transactions;
      if (q != null && !q.isBlank()) {
         String ql = q.toLowerCase();
         transactions = new ArrayList<>();

         for (AuctionManager.TransactionRecord tr : base) {
            String mat = tr.item.getType().name().toLowerCase().replace('_', ' ');
            String disp = tr.item.hasItemMeta() && tr.item.getItemMeta().hasDisplayName()
               ? ChatColor.stripColor(tr.item.getItemMeta().getDisplayName()).toLowerCase()
               : "";
            if (mat.contains(ql) || disp.contains(ql)) {
               transactions.add(tr);
            }
         }
      } else {
         transactions = base;
      }

      int pageCapacity = 45;
      int maxPage = Math.max(1, (int)Math.ceil(transactions.size() / 45.0));
      if (slot == 45 && page > 1) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.openTransactions(p, page - 1);
      } else if (slot == 48) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
      } else if (slot == 49) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.txSearchQuery.remove(p.getUniqueId());
         this.openTransactions(p, page);
      } else if (slot == 53 && page < maxPage) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.openTransactions(p, page + 1);
      }
   }

   private void addTransaction(UUID owner, ItemStack item, String message) {
      MenuUpdates.pruneTransactions(this);
      List<AuctionManager.TransactionRecord> records = this.recentTransactions.computeIfAbsent(owner, k -> new LinkedList<>());
      records.add(0, new AuctionManager.TransactionRecord(item.clone(), message, System.currentTimeMillis()));

      while (records.size() > Integer.MAX_VALUE) {
         records.remove(records.size() - 1);
      }
   }

   private ItemStack createStatsBook(Player player) {
      ItemStack item = new ItemStack(Material.BOOK);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§e" + toSmallCaps("stats"));
         List<String> lore = new ArrayList<>();
         lore.add("§e" + toSmallCaps("total spent") + ": §e$" + PriceManager.formatShort(this.getAhSpent(player.getUniqueId())));
         lore.add("§e" + toSmallCaps("total made") + ": §e$" + PriceManager.formatShort(this.getAhMade(player.getUniqueId())));
         meta.setLore(lore);
         item.setItemMeta(meta);
      }

      return item;
   }

   private ItemStack createTransactionsOpenBook() {
      ItemStack item = new ItemStack(Material.BOOK);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§e" + toSmallCaps("transactions"));
         List<String> lore = new ArrayList<>();
         lore.add("§7" + toSmallCaps("click to see your purchases and sales"));
         meta.setLore(lore);
         item.setItemMeta(meta);
      }

      return item;
   }

   private double getAhSpent(UUID uuid) {
      return this.plugin.getPlayerTotal(uuid, "ah_spent");
   }

   private double getAhMade(UUID uuid) {
      return this.plugin.getPlayerTotal(uuid, "ah_made");
   }

   private void addAhSpent(UUID uuid, double amount) {
      this.plugin.addPlayerTotal(uuid, "ah_spent", amount);
   }

   private void addAhMade(UUID uuid, double amount) {
      this.plugin.addPlayerTotal(uuid, "ah_made", amount);
   }

   private String formatTimeAgo(long timestamp) {
      long diff = Math.max(0L, System.currentTimeMillis() - timestamp);
      long days = diff / 86400000L;
      long hours = diff / 3600000L % 24L;
      long minutes = diff / 60000L % 60L;
      long seconds = diff / 1000L % 60L;
      if (days > 0L) {
         return days + "d " + hours + "h " + minutes + "m " + seconds + "s";
      } else if (hours > 0L) {
         return hours + "h " + minutes + "m " + seconds + "s";
      } else {
         return minutes > 0L ? minutes + "m " + seconds + "s" : seconds + "s";
      }
   }

   private void openAHWithList(Player p, List<AuctionManager.AuctionItem> list, String filterName, int page) {
      String sortChoice = this.viewerSortChoice.getOrDefault(p.getUniqueId(), "recent");
      String filterCatChoice = this.viewerFilterChoice.getOrDefault(p.getUniqueId(), "all");
      list = new ArrayList<>(this.applyFilterCategory(list, filterCatChoice));
      this.applySortChoice(list, sortChoice);
      int total = list.size();
      int maxPage = Math.max(1, (int)Math.ceil(total / 45.0));
      if (page < 1) {
         page = 1;
      }

      if (page > maxPage) {
         page = maxPage;
      }

      Inventory ah = Bukkit.createInventory(null, 54, MenuUpdates.displayTitle("§0" + toSmallCaps("auction house - " + filterName)));
      this.renderItems(ah, list, page);
      if (page > 1) {
         ah.setItem(45, this.createItem(Material.ARROW, "§eᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ"));
      }

      ah.setItem(49, this.createItem(Material.EGG, "§eʀᴇғʀᴇꜱʜ"));
      if (page < maxPage) {
         ah.setItem(53, this.createItem(Material.ARROW, "§eɴᴇxᴛ ᴘᴀɢᴇ"));
      }

      ah.setItem(47, com.taxsmp.ui.GuiSkin.button(this.createSortButton(sortChoice), "sort"));
      ah.setItem(48, com.taxsmp.ui.GuiSkin.button(this.createFilterButton(filterCatChoice), "filter"));
      ItemStack searchItem2 = this.createItem(Material.OAK_SIGN, "§eꜱᴇᴀʀᴄʜ");
      ItemMeta searchMeta2 = searchItem2.getItemMeta();
      searchMeta2.setLore(Arrays.asList("§7ᴜꜱᴇ: /ᴀʜ <ꜱᴇᴀʀᴄʜ ᴛᴇʀᴍꜱ>"));
      searchItem2.setItemMeta(searchMeta2);
      ah.setItem(50, com.taxsmp.ui.GuiSkin.button(searchItem2, "search"));
      ah.setItem(51, this.createItem(Material.CHEST, "§eʏᴏᴜʀ ɪᴛᴇᴍꜱ"));
      this.viewerList.put(p.getUniqueId(), list);
      this.viewerPage.put(p.getUniqueId(), page);
      p.openInventory(ah);
      this.startLiveExpiryTimer(p);
   }

   private double parseFriendlyNumber(String s) {
      s = s.replace(",", "").toLowerCase().trim();
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

      try {
         return Double.parseDouble(s) * mult;
      } catch (Exception e) {
         return -1.0;
      }
   }

   private void applySortChoice(List<AuctionManager.AuctionItem> list, String choice) {
      if (choice.equals("highest")) {
         list.sort((a, b) -> Double.compare(b.price, a.price));
      } else if (choice.equals("lowest")) {
         list.sort((a, b) -> Double.compare(a.price, b.price));
      } else if (choice.equals("last")) {
         list.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
      } else {
         list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
      }
   }

   private String prettifySortName(String raw) {
      if (raw.equals("highest")) {
         return "Highest Price";
      } else if (raw.equals("lowest")) {
         return "Lowest Price";
      } else {
         return raw.equals("last") ? "Last Listed" : "Recently Listed";
      }
   }

   private static String toSmallCaps(String s) {
      StringBuilder sb = new StringBuilder(s.length());

      for (char c : s.toLowerCase().toCharArray()) {
         sb.append(switch (c) {
            case 'a' -> 'ᴀ';
            case 'b' -> 'ʙ';
            case 'c' -> 'ᴄ';
            case 'd' -> 'ᴅ';
            case 'e' -> 'ᴇ';
            case 'f' -> 'ғ';
            case 'g' -> 'ɢ';
            case 'h' -> 'ʜ';
            case 'i' -> 'ɪ';
            case 'j' -> 'ᴊ';
            case 'k' -> 'ᴋ';
            case 'l' -> 'ʟ';
            case 'm' -> 'ᴍ';
            case 'n' -> 'ɴ';
            case 'o' -> 'ᴏ';
            case 'p' -> 'ᴘ';
            case 'q' -> 'ǫ';
            case 'r' -> 'ʀ';
            case 's' -> 'ꜱ';
            case 't' -> 'ᴛ';
            case 'u' -> 'ᴜ';
            case 'v' -> 'ᴠ';
            case 'w' -> 'ᴡ';
            case 'x' -> 'x';
            case 'y' -> 'ʏ';
            case 'z' -> 'ᴢ';
            default -> c;
         });
      }

      return sb.toString();
   }

   private void cleanupExpired() {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, t -> this.cleanupExpiredWork());
      } else {
         this.cleanupExpiredWork();
      }
   }

   private void cleanupExpiredWork() {
      this.expireRenamedListings();
      long now = System.currentTimeMillis();
      long liveWindow = 259200000L;
      long returnDeleteWindow = 604800000L;
      ArrayList<AuctionManager.AuctionItem> toReturn = new ArrayList<>();

      for (AuctionManager.AuctionItem ai : new ArrayList<>(this.auctionItems)) {
         if (now - ai.createdAt > liveWindow) {
            toReturn.add(ai);
         }
      }

      for (AuctionManager.AuctionItem ai : toReturn) {
         if (this.usingDb() && !this.dbDeleteListing(ai.id)) {
            this.auctionItems.removeIf(x -> x.id == ai.id);
         } else {
            this.auctionItems.remove(ai);
            this.addReturn(ai.seller, ai.item, now);
            String itemName = this.prettyName(ai.item.getType());
            String msg = "§cYour " + itemName + " was not sold and has been returned. Open /ah -> Your Items to collect.";
            if (!this.plugin.sendAuctionAlert(ai.seller, msg)) {
               this.offlineNotifications.computeIfAbsent(ai.seller, k -> new ArrayList<>()).add("Your " + itemName + " was returned to you (not sold).");
            }
         }
      }

      long wipeBefore = now - returnDeleteWindow;
      if (this.usingDb()) {
         this.dbDeleteReturnsOlderThan(wipeBefore);
      } else {
         for (Entry<UUID, List<AuctionManager.ReturnItem>> e : new ArrayList<>(this.pendingReturns.entrySet())) {
            List<AuctionManager.ReturnItem> list = e.getValue();
            list.removeIf(ri -> ri.returnedAt < wipeBefore);
            if (list.isEmpty()) {
               this.pendingReturns.remove(e.getKey());
            }
         }

         this.saveAuctions();
      }
   }

   private ItemStack createItem(Material mat, String name) {
      return com.taxsmp.ui.GuiSkin.autoButton(this.createPlainItem(mat, name));
   }

   private ItemStack createPlainItem(Material m, String name) {
      ItemStack it = new ItemStack(m);
      ItemMeta im = it.getItemMeta();
      im.setDisplayName(name);
      it.setItemMeta(im);
      return it;
   }

   private boolean hasTwentyAuctionPermission(Player p) {
      return p.hasPermission("taxsmp.20auctionitems") || p.hasPermission("taxsmp.20auctionitems.use") || p.hasPermission("taxsmp.20auction");
   }

   private boolean isQuickAuctionBuyEnabled(Player p) {
      return this.plugin.isQuickAuctionBuyEnabledFor(p);
   }

   private void initDb() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_ah_listings (id BIGINT AUTO_INCREMENT PRIMARY KEY,seller VARCHAR(36) NOT NULL,price DOUBLE NOT NULL,created_at BIGINT NOT NULL,item MEDIUMTEXT NOT NULL,INDEX idx_ah_seller (seller))"
         );
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_ah_returns (id BIGINT AUTO_INCREMENT PRIMARY KEY,owner VARCHAR(36) NOT NULL,returned_at BIGINT NOT NULL,item MEDIUMTEXT NOT NULL,INDEX idx_ah_owner (owner))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create AH tables: " + e.getMessage());
         return;
      }

      this.migrateLocalListings();
   }

   private void migrateLocalListings() {
      File f = new File(this.plugin.getDataFolder(), "auctions.yml");
      if (f.exists()) {
         this.loadAuctions();
         List<AuctionManager.AuctionItem> localListings = new ArrayList<>(this.auctionItems);
         Map<UUID, List<AuctionManager.ReturnItem>> localReturns = new HashMap<>(this.pendingReturns);
         this.auctionItems.clear();
         this.pendingReturns.clear();

         boolean tableHasRows;
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_ah_listings LIMIT 1");
            ResultSet rs = ps.executeQuery();
         ) {
            tableHasRows = rs.next();
         } catch (SQLException e) {
            tableHasRows = true;
         }

         if (tableHasRows) {
            this.plugin.getLogger().info("AH listings already in MySQL; skipping local import on this server.");
         } else {
            int migrated = 0;

            for (AuctionManager.AuctionItem ai : localListings) {
               if (this.dbInsertListing(ai) > 0L) {
                  migrated++;
               }
            }

            for (Entry<UUID, List<AuctionManager.ReturnItem>> e : localReturns.entrySet()) {
               for (AuctionManager.ReturnItem ri : e.getValue()) {
                  this.dbInsertReturn(e.getKey(), ri.item, ri.returnedAt);
               }
            }

            this.plugin.getLogger().info("Imported " + migrated + " AH listings from auctions.yml into MySQL.");
         }

         File backup = new File(this.plugin.getDataFolder(), "auctions.yml.imported");
         if (!backup.exists()) {
            f.renameTo(backup);
         }
      }
   }

   private void reloadFromDb() {
      if (this.usingDb()) {
         Set<Long> freshIds = new HashSet<>();

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT id FROM taxsmp_ah_listings");
            ResultSet rs = ps.executeQuery();
         ) {
            while (rs.next()) {
               freshIds.add(rs.getLong(1));
            }
         } catch (SQLException e) {
            this.plugin.getLogger().warning("AH sync read failed: " + e.getMessage());
            return;
         }

         Set<Long> haveIds = new HashSet<>();

         for (AuctionManager.AuctionItem ai : this.auctionItems) {
            haveIds.add(ai.id);
         }

         List<Long> missing = new ArrayList<>();

         for (long id : freshIds) {
            if (!haveIds.contains(id)) {
               missing.add(id);
            }
         }

         List<AuctionManager.AuctionItem> fresh = new ArrayList<>();

         for (int from = 0; from < missing.size(); from += 200) {
            int to = Math.min(missing.size(), from + 200);
            List<Long> chunk = missing.subList(from, to);
            StringBuilder sql = new StringBuilder("SELECT id, seller, price, created_at, item FROM taxsmp_ah_listings WHERE id IN (");

            for (int i = 0; i < chunk.size(); i++) {
               sql.append(i == 0 ? "?" : ",?");
            }

            sql.append(')');

            try (
               Connection conn = this.db.getConnection();
               PreparedStatement ps = conn.prepareStatement(sql.toString());
            ) {
               for (int i = 0; i < chunk.size(); i++) {
                  ps.setLong(i + 1, chunk.get(i));
               }

               try (ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                     ItemStack it = ItemCodec.decode(rs.getString("item"));
                     if (it != null) {
                        try {
                           AuctionManager.AuctionItem ai = new AuctionManager.AuctionItem(
                              UUID.fromString(rs.getString("seller")), it, rs.getDouble("price"), rs.getLong("created_at")
                           );
                           ai.id = rs.getLong("id");
                           fresh.add(ai);
                        } catch (IllegalArgumentException var20) {
                        }
                     }
                  }
               }
            } catch (SQLException e) {
               this.plugin.getLogger().warning("AH sync read failed: " + e.getMessage());
               return;
            }
         }

         this.auctionItems.removeIf(aix -> haveIds.contains(aix.id) && !freshIds.contains(aix.id));
         this.auctionItems.addAll(fresh);
      }
   }

   private long dbInsertListing(AuctionManager.AuctionItem ai) {
      String enc = ItemCodec.encode(ai.item);
      if (enc == null) {
         return -1L;
      }

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("INSERT INTO taxsmp_ah_listings (seller, price, created_at, item) VALUES (?,?,?,?)", 1);
      ) {
         ps.setString(1, ai.seller.toString());
         ps.setDouble(2, ai.price);
         ps.setLong(3, ai.createdAt);
         ps.setString(4, enc);
         ps.executeUpdate();

         try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
               ai.id = keys.getLong(1);
               return ai.id;
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("AH insert failed: " + e.getMessage());
      }

      return -1L;
   }

   private boolean claimListingNow(long id) {
      for (int attempt = 0; attempt < 2; attempt++) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_ah_listings WHERE id = ?");
         ) {
            ps.setLong(1, id);
            return ps.executeUpdate() == 1;
         } catch (SQLException e) {
            if (attempt > 0) {
               this.plugin.getLogger().warning("AH claim failed: " + e.getMessage());
            }
         }
      }

      return false;
   }

   public void shutdown() {
      this.saveAuctionsBlocking();
      this.claimExecutor.shutdown();

      try {
         this.claimExecutor.awaitTermination(5L, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
      }
   }

   private boolean dbDeleteListing(long id) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_ah_listings WHERE id = ?");
      ) {
         ps.setLong(1, id);
         return ps.executeUpdate() == 1;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("AH delete failed: " + e.getMessage());
         return false;
      }
   }

   private long dbInsertReturn(UUID owner, ItemStack item, long ts) {
      String enc = ItemCodec.encode(item);
      if (enc == null) {
         return -1L;
      }

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("INSERT INTO taxsmp_ah_returns (owner, returned_at, item) VALUES (?,?,?)", 1);
      ) {
         ps.setString(1, owner.toString());
         ps.setLong(2, ts);
         ps.setString(3, enc);
         ps.executeUpdate();

         try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
               return keys.getLong(1);
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("AH return insert failed: " + e.getMessage());
      }

      return -1L;
   }

   private List<AuctionManager.ReturnItem> dbLoadReturns(UUID owner) {
      List<AuctionManager.ReturnItem> out = new ArrayList<>();

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT id, returned_at, item FROM taxsmp_ah_returns WHERE owner = ? ORDER BY id");
      ) {
         ps.setString(1, owner.toString());

         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               ItemStack it = ItemCodec.decode(rs.getString("item"));
               if (it != null) {
                  AuctionManager.ReturnItem ri = new AuctionManager.ReturnItem(it, rs.getLong("returned_at"));
                  ri.id = rs.getLong("id");
                  out.add(ri);
               }
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("AH returns read failed: " + e.getMessage());
      }

      return out;
   }

   private boolean dbDeleteReturn(long id) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_ah_returns WHERE id = ?");
      ) {
         ps.setLong(1, id);
         return ps.executeUpdate() == 1;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("AH return delete failed: " + e.getMessage());
         return false;
      }
   }

   private void dbDeleteReturnsOlderThan(long cutoff) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_ah_returns WHERE returned_at < ?");
      ) {
         ps.setLong(1, cutoff);
         ps.executeUpdate();
      } catch (SQLException e) {
         this.plugin.getLogger().warning("AH return prune failed: " + e.getMessage());
      }
   }

   private void addReturn(UUID var1, ItemStack var2, long var3) {
      AuctionAsync.addReturn(this, this.plugin, this.db, var1, var2, var3);
   }

   void addReturn$taxOrig(UUID owner, ItemStack item, long ts) {
      if (this.usingDb()) {
         this.dbInsertReturn(owner, item, ts);
      } else {
         this.pendingReturns.computeIfAbsent(owner, k -> new ArrayList<>()).add(new AuctionManager.ReturnItem(item, ts));
      }
   }

   public static class AuctionItem {
      public long id = -1L;
      public final UUID seller;
      public final ItemStack item;
      public final double price;
      public final long createdAt;

      public AuctionItem(UUID s, ItemStack i, double p, long c) {
         this.seller = s;
         this.item = i;
         this.price = p;
         this.createdAt = c;
      }
   }

   private static class ReturnItem {
      public long id = -1L;
      public final ItemStack item;
      public final long returnedAt;

      public ReturnItem(ItemStack item, long returnedAt) {
         this.item = item;
         this.returnedAt = returnedAt;
      }
   }

   private static class TransactionRecord {
      public final ItemStack item;
      public final String message;
      public final long timestamp;

      public TransactionRecord(ItemStack item, String message, long timestamp) {
         this.item = item;
         this.message = message;
         this.timestamp = timestamp;
      }
   }
}
