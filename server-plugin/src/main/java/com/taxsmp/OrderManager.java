package com.taxsmp;

import com.taxsmp.storage.Db;
import com.taxsmp.storage.ItemCodec;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

public class OrderManager implements CommandExecutor, Listener {
   private static void openSignPrompt(Player p, String[] lines, java.util.function.Consumer<String> callback) {
      if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
         p.sendMessage("§cSign input is unavailable (ProtocolLib missing).");
      } else {
         SignPrompt.open(p, lines, callback);
      }
   }

   private static final long ORDER_DURATION = 1209600000L;
   private static final double PAYOUT_CAP = 1.0E10;
   private static final String MINT = "§x§5§5§F§F§B§8";
   private final TaxSMP plugin;
   private final List<OrderManager.Order> orders = new CopyOnWriteArrayList<>();
   private int nextId = 1;
   private final Db db;
   private ScheduledTask syncTask;
   private final ExecutorService dbWriter = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "TaxSMP-Orders-DB");
      t.setDaemon(true);
      return t;
   });
   private final Set<UUID> sellInFlight = ConcurrentHashMap.newKeySet();
   private volatile long lastCounterRebuild;
   private volatile boolean lootCounters = true;
   private final Map<UUID, Integer> viewerPage = new HashMap<>();
   private final Map<UUID, List<OrderManager.Order>> viewerList = new HashMap<>();
   private final Map<UUID, String> viewerSortChoice = new HashMap<>();
   private final Map<UUID, String> viewerFilterChoice = new HashMap<>();
   private final Map<UUID, String> viewerSearchQuery = new HashMap<>();
   private final Map<UUID, List<OrderManager.Order>> viewerOwnList = new HashMap<>();
   private final Map<UUID, Integer> viewerOwnPage = new HashMap<>();
   private final Map<UUID, Integer> matPage = new HashMap<>();
   private final Map<UUID, List<Material>> matList = new HashMap<>();
   private final Map<UUID, String> matSort = new HashMap<>();
   private final Map<UUID, String> matFilter = new HashMap<>();
   private final Map<UUID, String> matSearch = new HashMap<>();
   private final Map<UUID, List<Enchantment>> viewerEnchList = new HashMap<>();
   private final Map<UUID, OrderManager.Draft> drafts = new HashMap<>();
   private final Map<UUID, Integer> pendingDeliverOrder = new HashMap<>();
   private final Map<UUID, OrderManager.DeliverPacket> pendingDeliverItems = new HashMap<>();
   private final Map<UUID, OrderManager.DeliverAnim> deliverAnims = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> pendingCancel = new HashMap<>();
   private final Map<UUID, Integer> editingOrder = new HashMap<>();
   private final Map<UUID, Integer> collectPage = new HashMap<>();
   private final Map<UUID, List<OrderManager.LootStack>> collectRows = new HashMap<>();
   private final Set<UUID> collectSuppressClose = ConcurrentHashMap.newKeySet();
   private final Set<UUID> collectEmptied = ConcurrentHashMap.newKeySet();
   private final Map<UUID, List<String>> offlineNotifications = new HashMap<>();
   private final Map<UUID, Long> offlineItemsReceived = new HashMap<>();
   private ScheduledTask cleanupTask;
   private static volatile List<Material> ALL_ITEMS = null;
   private static final Set<String> BLOCKED_ITEMS = Set.of(
      "BEDROCK",
      "BARRIER",
      "LIGHT",
      "STRUCTURE_BLOCK",
      "STRUCTURE_VOID",
      "JIGSAW",
      "COMMAND_BLOCK",
      "CHAIN_COMMAND_BLOCK",
      "REPEATING_COMMAND_BLOCK",
      "COMMAND_BLOCK_MINECART",
      "DEBUG_STICK",
      "KNOWLEDGE_BOOK",
      "PETRIFIED_OAK_SLAB",
      "FARMLAND",
      "DIRT_PATH",
      "END_PORTAL_FRAME",
      "SPAWNER",
      "TRIAL_SPAWNER",
      "VAULT",
      "REINFORCED_DEEPSLATE",
      "BUDDING_AMETHYST",
      "FROGSPAWN",
      "INFESTED_STONE",
      "INFESTED_COBBLESTONE",
      "INFESTED_STONE_BRICKS",
      "INFESTED_MOSSY_STONE_BRICKS",
      "INFESTED_CRACKED_STONE_BRICKS",
      "INFESTED_CHISELED_STONE_BRICKS",
      "INFESTED_DEEPSLATE",
      "PLAYER_HEAD"
   );
   private static final Map<Material, List<Enchantment>> ENCH_CACHE = new ConcurrentHashMap<>();
   private static final int OWN_PER_PAGE = 18;
   private static final int COLLECT_PER_PAGE = 45;
   private static final int LOOT_CHUNK = 200;

   public OrderManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      long period = 1200L;
      this.cleanupTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> this.cleanupExpired(), period, period);
      if (this.usingDb()) {
         this.dbWriter.execute(() -> {
            this.initDb();
            this.reloadFromDb();
         });
         long secs = Math.max(3L, plugin.getConfig().getLong("mysql.orders-sync-seconds", 6L));
         this.syncTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> {
            if (!Bukkit.getOnlinePlayers().isEmpty()) {
               this.reloadFromDb();
            }
         }, secs, secs, TimeUnit.SECONDS);
      } else {
         this.loadOrders();
      }
   }

   public void shutdown() {
      if (this.syncTask != null) {
         this.syncTask.cancel();
      }

      if (this.cleanupTask != null) {
         this.cleanupTask.cancel();
      }

      this.dbWriter.shutdown();

      try {
         this.dbWriter.awaitTermination(10L, TimeUnit.SECONDS);
      } catch (InterruptedException ignored) {
         Thread.currentThread().interrupt();
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (sender instanceof Player p) {
         if (args.length == 0) {
            this.openOrders(p, 1, null);
            return true;
         }

         if (!args[0].equalsIgnoreCase("new") && !args[0].equalsIgnoreCase("create")) {
            StringBuilder sb = new StringBuilder();

            for (String a : args) {
               sb.append(a).append(' ');
            }

            String query = sb.toString().trim().toLowerCase();
            this.viewerSearchQuery.put(p.getUniqueId(), query);
            this.openOrders(p, 1, query);
            return true;
         } else {
            this.startNewOrder(p);
            return true;
         }
      } else {
         return true;
      }
   }

   public void openOrders(Player p, int page, String searchQuery) {
      List<OrderManager.Order> list = new ArrayList<>();
      long now = System.currentTimeMillis();

      for (OrderManager.Order o : this.orders) {
         if (o.isPubliclyDeliverable(now) && (searchQuery == null || searchQuery.isBlank() || this.matchesSearch(o, searchQuery))) {
            list.add(o);
         }
      }

      String filterChoice = this.viewerFilterChoice.getOrDefault(p.getUniqueId(), "all");
      list = new ArrayList<>(this.applyFilterCategory(list, filterChoice));
      String sortChoice = this.viewerSortChoice.getOrDefault(p.getUniqueId(), "recent");
      this.applySortChoice(list, sortChoice);
      int total = list.size();
      int maxPage = Math.max(1, (int)Math.ceil(total / 45.0));
      if (page < 1) {
         page = 1;
      }

      if (page > maxPage) {
         page = maxPage;
      }

      String titleTail = searchQuery != null && !searchQuery.isBlank() ? "search: " + searchQuery : "page " + page;
      Inventory inv = Bukkit.createInventory(null, 54, MenuUpdates.displayTitle("§0" + toSmallCaps("orders - " + titleTail)));
      int start = (page - 1) * 45;

      for (int i = 0; i < 45 && start + i < list.size(); i++) {
         inv.setItem(i, this.buildOrderDisplay(list.get(start + i)));
      }

      if (page > 1) {
         inv.setItem(45, this.createItem(Material.ARROW, "§eᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ"));
      }

      inv.setItem(47, this.createSortButton(sortChoice));
      inv.setItem(48, this.createFilterButton(filterChoice));
      ItemStack refresh = this.createItem(Material.EGG, "§eᴏʀᴅᴇʀꜱ");
      ItemMeta rm = refresh.getItemMeta();
      rm.setLore(Arrays.asList("§7ᴄʟɪᴄᴋ ᴛᴏ ʀᴇғʀᴇꜱʜ"));
      refresh.setItemMeta(rm);
      inv.setItem(49, com.taxsmp.ui.GuiSkin.button(refresh, "refresh"));
      ItemStack search = this.createItem(Material.OAK_SIGN, "§eꜱᴇᴀʀᴄʜ");
      ItemMeta sm = search.getItemMeta();
      sm.setLore(Arrays.asList("§7ᴄʟɪᴄᴋ ᴛᴏ ꜱᴇᴀʀᴄʜ"));
      search.setItemMeta(sm);
      inv.setItem(50, search);
      ItemStack yours = this.createItem(Material.CHEST, "§eʏᴏᴜʀ ᴏʀᴅᴇʀꜱ");
      ItemMeta ym = yours.getItemMeta();
      ym.setLore(Arrays.asList("§7ᴄʟɪᴄᴋ ᴛᴏ ᴠɪᴇᴡ ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ"));
      yours.setItemMeta(ym);
      inv.setItem(51, yours);
      if (page < maxPage) {
         inv.setItem(53, this.createItem(Material.ARROW, "§eɴᴇxᴛ ᴘᴀɢᴇ"));
      }

      this.viewerPage.put(p.getUniqueId(), page);
      this.viewerList.put(p.getUniqueId(), list);
      if (searchQuery == null || searchQuery.isBlank()) {
         this.viewerSearchQuery.remove(p.getUniqueId());
      }

      p.openInventory((Inventory)MenuUpdates.prepare(inv, "openOrders"));
   }

   private boolean matchesSearch(OrderManager.Order o, String query) {
      String mat = o.material.name().toLowerCase().replace('_', ' ');
      return mat.contains(query);
   }

   private List<String> orderInfoLore(OrderManager.Order o) {
      List<String> lore = new ArrayList<>();
      double paid = o.deliveredAmount * o.pricePer;
      double total = o.amount * o.pricePer;
      lore.add("§x§5§5§F§F§B§8" + PriceManager.formatShort(o.pricePer) + " each");
      lore.add("");
      lore.add("§e" + PriceManager.formatShort(o.deliveredAmount) + "/§x§5§5§F§F§B§8" + PriceManager.formatShort(o.amount) + " §7delivered");
      lore.add("§e" + PriceManager.formatShort(paid) + "/§x§5§5§F§F§B§8" + PriceManager.formatShort(total) + " §7 Paid");
      return lore;
   }

   private ItemStack buildOrderDisplay(OrderManager.Order o) {
      ItemStack it = new ItemStack(o.material);
      ItemMeta meta = it.getItemMeta();
      if (meta != null) {
         String buyer = Bukkit.getOfflinePlayer(o.owner).getName();
         meta.setDisplayName("§x§5§5§F§F§B§8" + (buyer == null ? "Unknown" : buyer) + "'s Order");
         List<String> lore = this.orderInfoLore(o);
         lore.add("");
         long rem = Math.max(0L, o.createdAt + 1209600000L - System.currentTimeMillis());
         lore.add("§7" + this.formatDHM(rem) + " Until Order expires");
         meta.setLore(lore);
         it.setItemMeta(meta);
      }

      applyDisplayEnchants(it, o.enchants);
      return it;
   }

   private void startNewOrder(Player p) {
      int active = this.countOwnerOrders(p.getUniqueId());
      int max = this.maxOrders(p);
      if (active >= max) {
         p.sendMessage("§cYou've created the maximum number of orders (" + max + ").");
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
      } else {
         this.drafts.put(p.getUniqueId(), new OrderManager.Draft(Material.STONE));
         this.openNewOrder(p);
      }
   }

   private void openNewOrder(Player p) {
      OrderManager.Draft d = this.drafts.get(p.getUniqueId());
      if (d == null) {
         this.openOrders(p, 1, null);
      } else {
         Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("new order")));
         inv.setItem(10, this.createItem(Material.RED_STAINED_GLASS_PANE, "§cᴄᴀɴᴄᴇʟ"));
         ItemStack item = new ItemStack(d.material, 1);
         ItemMeta im = item.getItemMeta();
         if (im != null) {
            im.setDisplayName("§eɪᴛᴇᴍ");
            im.setLore(Arrays.asList("§8(" + this.prettyName(d.material) + ")", "§7Click to choose an item"));
            item.setItemMeta(im);
         }

         applyDisplayEnchants(item, d.enchants);
         inv.setItem(12, item);
         ItemStack amountBtn = this.createItem(Material.CHEST, "§eᴀᴍᴏᴜɴᴛ");
         ItemMeta am = amountBtn.getItemMeta();
         am.setLore(Arrays.asList("§7Click to type number of items", "§8(" + d.amount + ")"));
         amountBtn.setItemMeta(am);
         inv.setItem(13, amountBtn);
         ItemStack priceBtn = this.createItem(Material.EMERALD, "§eᴘʀɪᴄᴇ");
         ItemMeta prm = priceBtn.getItemMeta();
         prm.setLore(Arrays.asList("§7Click to type the price per item", "§8($" + PriceManager.formatPrice(d.price) + ")"));
         priceBtn.setItemMeta(prm);
         inv.setItem(14, priceBtn);
         ItemStack confirm = this.createItem(Material.LIME_STAINED_GLASS_PANE, "§aᴄᴏɴғɪʀᴍ");
         ItemMeta cm = confirm.getItemMeta();
         cm.setLore(Arrays.asList("§7Click to confirm order", "§8(Total: $" + PriceManager.formatPrice(d.price * d.amount) + ")"));
         confirm.setItemMeta(cm);
         inv.setItem(16, confirm);
         p.openInventory((Inventory)MenuUpdates.prepare(inv, "openNewOrder"));
      }
   }

   private static List<Material> allItems() {
      List<Material> cached = ALL_ITEMS;
      if (cached != null) {
         return cached;
      }

      List<Material> list = new ArrayList<>();

      for (Material m : Material.values()) {
         if (!m.isLegacy() && !m.isAir() && m.isItem() && isOrderable(m)) {
            list.add(m);
         }
      }

      list.sort((a, b) -> a.name().compareTo(b.name()));
      ALL_ITEMS = list;
      return list;
   }

   private static boolean isOrderable(Material m) {
      String n = m.name();
      return !n.endsWith("_SPAWN_EGG") && !n.equals("SPAWN_EGG") ? !BLOCKED_ITEMS.contains(n) : false;
   }

   private void openMaterialsList(Player p, int page) {
      String sort = this.matSort.getOrDefault(p.getUniqueId(), "az");
      String filter = this.matFilter.getOrDefault(p.getUniqueId(), "all");
      String search = this.matSearch.get(p.getUniqueId());
      List<Material> list = new ArrayList<>();

      for (Material m : allItems()) {
         if (this.matchesFilterCategory(m, filter) && (search == null || search.isBlank() || m.name().toLowerCase().replace('_', ' ').contains(search))) {
            list.add(m);
         }
      }

      if ("za".equals(sort)) {
         list.sort((a, b) -> b.name().compareTo(a.name()));
      } else {
         list.sort((a, b) -> a.name().compareTo(b.name()));
      }

      int total = list.size();
      int maxPage = Math.max(1, (int)Math.ceil(total / 45.0));
      if (page < 1) {
         page = 1;
      }

      if (page > maxPage) {
         page = maxPage;
      }

      Inventory inv = Bukkit.createInventory(null, 54, MenuUpdates.displayTitle("§0" + toSmallCaps("select item")));
      int start = (page - 1) * 45;

      for (int i = 0; i < 45 && start + i < list.size(); i++) {
         Material m = list.get(start + i);
         ItemStack it = new ItemStack(m);
         ItemMeta im = it.getItemMeta();
         if (im != null) {
            im.setDisplayName("§e" + this.prettyName(m));
            im.setLore(Arrays.asList("§7Click to select"));
            it.setItemMeta(im);
         }

         inv.setItem(i, it);
      }

      if (page > 1) {
         inv.setItem(45, this.createItem(Material.ARROW, "§eᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ"));
      }

      inv.setItem(46, this.createItem(Material.RED_STAINED_GLASS_PANE, "§cʙᴀᴄᴋ"));
      ItemStack sortBtn = new ItemStack(Material.CAULDRON);
      ItemMeta sim = sortBtn.getItemMeta();
      sim.setDisplayName("§eꜱᴏʀᴛ");
      sim.setLore(Arrays.asList(("az".equals(sort) ? "§a" : "§7") + "ᴀ - ᴢ", ("za".equals(sort) ? "§a" : "§7") + "ᴢ - ᴀ"));
      sortBtn.setItemMeta(sim);
      inv.setItem(48, com.taxsmp.ui.GuiSkin.button(sortBtn, "sort"));
      inv.setItem(49, this.createFilterButton(filter));
      ItemStack searchBtn = this.createItem(Material.OAK_SIGN, "§eꜱᴇᴀʀᴄʜ");
      ItemMeta sm = searchBtn.getItemMeta();
      sm.setLore(Arrays.asList("§7Click to search"));
      searchBtn.setItemMeta(sm);
      inv.setItem(50, searchBtn);
      if (page < maxPage) {
         inv.setItem(53, this.createItem(Material.ARROW, "§eɴᴇxᴛ ᴘᴀɢᴇ"));
      }

      this.matPage.put(p.getUniqueId(), page);
      this.matList.put(p.getUniqueId(), list);
      this.fillBottomRow(inv);
      p.openInventory((Inventory)MenuUpdates.prepare(inv, "openMaterialsList"));
   }

   private void handleMaterialsClick(Player p, int slot, ItemStack clicked) {
      int page = this.matPage.getOrDefault(p.getUniqueId(), 1);
      switch (slot) {
         case 45:
            this.openMaterialsList(p, page - 1);
            break;
         case 46:
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.openNewOrder(p);
            break;
         case 47:
         case 51:
         case 52:
         default:
            if (slot >= 0 && slot < 45) {
               List<Material> list = this.matList.getOrDefault(p.getUniqueId(), Collections.emptyList());
               int idx = (page - 1) * 45 + slot;
               if (idx < 0 || idx >= list.size()) {
                  return;
               }

               OrderManager.Draft d = this.drafts.get(p.getUniqueId());
               if (d == null) {
                  this.openOrders(p, 1, null);
                  return;
               }

               d.material = list.get(idx);
               d.enchants.clear();
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               if (isEnchantable(d.material)) {
                  this.openEnchanter(p);
               } else {
                  this.openNewOrder(p);
               }
            }
            break;
         case 48:
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            String cur = this.matSort.getOrDefault(p.getUniqueId(), "az");
            this.matSort.put(p.getUniqueId(), "az".equals(cur) ? "za" : "az");
            this.openMaterialsList(p, 1);
            break;
         case 49:
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.matFilter.put(p.getUniqueId(), this.cycleFilter(this.matFilter.getOrDefault(p.getUniqueId(), "all")));
            this.openMaterialsList(p, 1);
            break;
         case 50:
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.openMaterialsSearchSign(p);
            break;
         case 53:
            this.openMaterialsList(p, page + 1);
      }
   }

   private void openMaterialsSearchSign(Player p) {
      openSignPrompt(p, new String[]{"", "§e^^^^^^^^^^^^^^^", "§eᴛʏᴘᴇ ᴀɴ ɪᴛᴇᴍ", "§eɴᴀᴍᴇ"}, text -> {
         if (text != null && !text.trim().isEmpty()) {
            this.matSearch.put(p.getUniqueId(), text.trim().toLowerCase());
         } else {
            this.matSearch.remove(p.getUniqueId());
         }

         this.openMaterialsList(p, 1);
      });
   }

   private void openAmountSign(Player p) {
      openSignPrompt(p, new String[]{"", "§e^^^^^^^^^^^^^^^", "§eᴇɴᴛᴇʀ ᴀᴍᴏᴜɴᴛ", ""}, text -> {
         OrderManager.Draft d = this.drafts.get(p.getUniqueId());
         if (d == null) {
            this.openOrders(p, 1, null);
         } else {
            double amt = this.parseFriendlyNumber(text);
            if (amt >= 1.0) {
               d.amount = (int)Math.min(amt, 5.0E8);
            } else {
               p.sendMessage("§cInvalid amount.");
            }

            this.openNewOrder(p);
         }
      });
   }

   private void openPriceSign(Player p) {
      openSignPrompt(p, new String[]{"", "§e^^^^^^^^^^^^^^^", "§eᴇɴᴛᴇʀ ᴘʀɪᴄᴇ", "§eᴘᴇʀ ɪᴛᴇᴍ"}, text -> {
         OrderManager.Draft d = this.drafts.get(p.getUniqueId());
         if (d == null) {
            this.openOrders(p, 1, null);
         } else {
            double price = MenuUpdates.validPrice(this.parseFriendlyNumber(text));
            if (price > 0.0) {
               d.price = price;
            } else {
               p.sendMessage("§cInvalid price.");
            }

            this.openNewOrder(p);
         }
      });
   }

   private void openSearchSign(Player p) {
      openSignPrompt(p, new String[]{"", "§e^^^^^^^^^^^^^^^", "§eᴛʏᴘᴇ ᴀ ꜱᴇᴀʀᴄʜ", "§eᴛᴇʀᴍ"}, text -> {
         if (text != null && !text.trim().isEmpty() && !text.equalsIgnoreCase("Search")) {
            String q = text.trim().toLowerCase();
            this.viewerSearchQuery.put(p.getUniqueId(), q);
            this.openOrders(p, 1, q);
         } else {
            this.viewerSearchQuery.remove(p.getUniqueId());
            this.openOrders(p, 1, null);
         }
      });
   }

   private static List<Enchantment> applicableEnchants(Material m) {
      return ENCH_CACHE.computeIfAbsent(m, mat -> {
         List<Enchantment> list = new ArrayList<>();
         ItemStack probe = new ItemStack(mat);

         try {
            for (Enchantment e : Registry.ENCHANTMENT) {
               try {
                  if (e.canEnchantItem(probe)) {
                     list.add(e);
                  }
               } catch (Throwable var6) {
               }
            }
         } catch (Throwable var7) {
         }

         return list;
      });
   }

   private static boolean isEnchantable(Material m) {
      return !applicableEnchants(m).isEmpty();
   }

   private void openEnchanter(Player p) {
      OrderManager.Draft d = this.drafts.get(p.getUniqueId());
      if (d == null) {
         this.openOrders(p, 1, null);
      } else {
         List<Enchantment> ench = applicableEnchants(d.material);
         if (ench.isEmpty()) {
            this.openNewOrder(p);
         } else {
            Inventory inv = Bukkit.createInventory(null, 54, MenuUpdates.displayTitle("§0" + toSmallCaps("pick enchantments")));

            for (int i = 0; i < 45 && i < ench.size(); i++) {
               Enchantment e = ench.get(i);
               int lvl = d.enchants.getOrDefault(e, 0);
               ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
               ItemMeta bm = book.getItemMeta();
               if (bm != null) {
                  bm.setDisplayName((lvl > 0 ? "§a" : "§7") + prettyEnchant(e));
                  List<String> lore = new ArrayList<>();
                  lore.add(lvl > 0 ? "§7Required: §a" + toRoman(lvl) : "§7Not required");
                  lore.add("§8Click to change level");
                  bm.setLore(lore);
                  book.setItemMeta(bm);
               }

               inv.setItem(i, book);
            }

            inv.setItem(45, this.createItem(Material.RED_STAINED_GLASS_PANE, "§cᴄᴀɴᴄᴇʟ"));
            inv.setItem(53, this.createItem(Material.LIME_STAINED_GLASS_PANE, "§aᴄᴏɴꜰɪʀᴍ"));
            this.viewerEnchList.put(p.getUniqueId(), ench);
            p.openInventory((Inventory)MenuUpdates.prepare(inv, "openEnchanter"));
         }
      }
   }

   private void handleEnchanterClick(Player p, int slot) {
      OrderManager.Draft d = this.drafts.get(p.getUniqueId());
      if (d == null) {
         this.openOrders(p, 1, null);
      } else if (slot == 45) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.openMaterialsList(p, 1);
      } else if (slot == 53) {
         p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
         this.openNewOrder(p);
      } else if (slot >= 0 && slot < 45) {
         List<Enchantment> ench = this.viewerEnchList.getOrDefault(p.getUniqueId(), Collections.emptyList());
         if (slot >= ench.size()) {
            return;
         }

         Enchantment e = ench.get(slot);
         int max = Math.max(1, e.getMaxLevel());
         int lvl = d.enchants.getOrDefault(e, 0) + 1;
         if (lvl > max) {
            lvl = 0;
         }

         if (lvl == 0) {
            d.enchants.remove(e);
         } else {
            d.enchants.put(e, lvl);
         }

         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.openEnchanter(p);
      }
   }

   private static String prettyEnchant(Enchantment e) {
      String key = e.getKey().getKey().replace('_', ' ');
      StringBuilder sb = new StringBuilder();

      for (String w : key.split(" ")) {
         if (!w.isEmpty()) {
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
         }
      }

      return sb.toString().trim();
   }

   private static String toRoman(int n) {
      return switch (n) {
         case 1 -> "I";
         case 2 -> "II";
         case 3 -> "III";
         case 4 -> "IV";
         case 5 -> "V";
         case 6 -> "VI";
         case 7 -> "VII";
         case 8 -> "VIII";
         case 9 -> "IX";
         case 10 -> "X";
         default -> String.valueOf(n);
      };
   }

   private static void applyDisplayEnchants(ItemStack it, Map<Enchantment, Integer> enchants) {
      if (it != null && enchants != null && !enchants.isEmpty()) {
         for (Entry<Enchantment, Integer> en : enchants.entrySet()) {
            try {
               it.addUnsafeEnchantment(en.getKey(), Math.max(1, en.getValue()));
            } catch (Throwable var5) {
            }
         }
      }
   }

   private void confirmNewOrder(Player p) {
      OrderManager.Draft d = this.drafts.get(p.getUniqueId());
      if (d == null) {
         this.openOrders(p, 1, null);
      } else if (d.amount <= 0) {
         p.sendMessage("§cSet an amount first.");
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
      } else if (MenuUpdates.validPrice(d.price) <= 0.0) {
         p.sendMessage("§cSet a price first.");
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
      } else {
         int active = this.countOwnerOrders(p.getUniqueId());
         int max = this.maxOrders(p);
         if (active >= max) {
            p.sendMessage("§cYou may only have up to " + max + " active orders.");
         } else {
            double total = MenuUpdates.validPrice(d.price) * d.amount;
            if (total > 1.0E10) {
               p.sendMessage("§cOrder total is too large. Maximum is $" + PriceManager.formatPrice(1.0E10) + ".");
               p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            } else if (this.plugin.getEconomy() != null && this.plugin.getEconomy().withdrawPlayer(p, total)) {
               this.drafts.remove(p.getUniqueId());
               if (this.usingDb()) {
                  OrderManager.Order tmp = new OrderManager.Order(
                     0, p.getUniqueId(), d.material, MenuUpdates.validPrice(d.price), d.amount, System.currentTimeMillis()
                  );
                  tmp.enchants.putAll(d.enchants);
                  double escrow = total;
                  this.dbWriter.execute(() -> {
                     long id = this.dbInsertOrder(tmp);
                     p.getScheduler().run(this.plugin, tt -> {
                        if (id > 0L) {
                           OrderManager.Order ox = new OrderManager.Order((int)id, tmp.owner, tmp.material, tmp.pricePer, tmp.amount, tmp.createdAt);
                           ox.enchants.putAll(tmp.enchants);
                           this.orders.add(ox);
                           p.sendMessage("§7Order created.");
                           p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                           this.openOrders(p, 1, null);
                        } else {
                           if (this.plugin.getEconomy() != null) {
                              this.plugin.getEconomy().depositPlayer(p, escrow);
                           }

                           p.sendMessage("§cCouldn't place that order right now — you were refunded.");
                           p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                        }
                     }, null);
                  });
               } else {
                  OrderManager.Order o = new OrderManager.Order(
                     this.nextId++, p.getUniqueId(), d.material, MenuUpdates.validPrice(d.price), d.amount, System.currentTimeMillis()
                  );
                  o.enchants.putAll(d.enchants);
                  this.orders.add(o);
                  this.saveOrders();
                  p.sendMessage("§7Order created.");
                  p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                  this.openOrders(p, 1, null);
               }
            } else {
               p.sendMessage("§cYou don't have enough money to place this order.");
               p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            }
         }
      }
   }

   private void handleDeliverClick(Player p, int slot) {
      List<OrderManager.Order> list = this.viewerList.getOrDefault(p.getUniqueId(), Collections.emptyList());
      int page = this.viewerPage.getOrDefault(p.getUniqueId(), 1);
      int idx = (page - 1) * 45 + slot;
      if (idx >= 0 && idx < list.size()) {
         OrderManager.Order o = list.get(idx);
         if (!this.orders.contains(o)) {
            this.openOrders(p, page, this.viewerSearchQuery.get(p.getUniqueId()));
         } else if (o.owner.equals(p.getUniqueId())) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.openEditOrder(p, o);
         } else if (o.amount - o.deliveredAmount <= 0) {
            p.sendMessage("§cThis order has already been filled.");
         } else {
            this.openDeliverChest(p, o);
         }
      }
   }

   private void openDeliverChest(Player p, OrderManager.Order o) {
      this.pendingDeliverOrder.put(p.getUniqueId(), o.id);
      Inventory inv = Bukkit.createInventory(null, 36, MenuUpdates.displayTitle("§0" + toSmallCaps("deliver items")));
      p.openInventory((Inventory)MenuUpdates.prepare(inv, "openDeliverChest"));
   }

   private void processDeliverChestClose(Player p, Inventory inv) {
      Integer orderId = this.pendingDeliverOrder.remove(p.getUniqueId());
      List<ItemStack> contents = new ArrayList<>();

      for (ItemStack it : inv.getContents()) {
         if (it != null && !it.getType().isAir()) {
            contents.add(it);
         }
      }

      inv.clear();
      if (!contents.isEmpty()) {
         OrderManager.Order o = orderId == null ? null : this.findById(orderId);
         if (o != null && !o.owner.equals(p.getUniqueId()) && o.isPubliclyDeliverable(System.currentTimeMillis())) {
            int[] cap = new int[]{Math.max(0, o.amount - o.deliveredAmount)};
            List<ItemStack> matched = new ArrayList<>();
            List<ItemStack> returned = new ArrayList<>();

            for (ItemStack it : contents) {
               if (cap[0] <= 0) {
                  returned.add(it);
               } else if (this.isShulker(it)) {
                  this.extractFromShulker(it, o, cap, matched);
                  returned.add(it);
               } else if (this.matchesOrder(it, o)) {
                  int take = Math.min(it.getAmount(), cap[0]);
                  ItemStack m = it.clone();
                  m.setAmount(take);
                  matched.add(m);
                  cap[0] -= take;
                  if (it.getAmount() > take) {
                     ItemStack extra = it.clone();
                     extra.setAmount(it.getAmount() - take);
                     returned.add(extra);
                  }
               } else {
                  returned.add(it);
               }
            }

            if (!returned.isEmpty()) {
               this.returnItemsNextTick(p, returned);
            }

            int matchedCount = 0;

            for (ItemStack m : matched) {
               matchedCount += m.getAmount();
            }

            if (matchedCount > 0) {
               double payout = Math.min(o.pricePer * matchedCount, 1.0E10);
               this.pendingDeliverItems.put(p.getUniqueId(), new OrderManager.DeliverPacket(o.id, matched, matchedCount, payout));
               int mc = matchedCount;
               double pay = payout;
               p.getScheduler().runDelayed(this.plugin, task -> this.openDeliverConfirm(p, o, mc, pay), null, 1L);
            }
         } else {
            this.returnItemsNextTick(p, contents);
         }
      }
   }

   private void openDeliverConfirm(Player p, OrderManager.Order o, int amount, double payout) {
      Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("confirm delivery")));
      ItemStack preview = new ItemStack(o.material);
      ItemMeta pm = preview.getItemMeta();
      if (pm != null) {
         String buyer = Bukkit.getOfflinePlayer(o.owner).getName();
         pm.setDisplayName("§x§5§5§F§F§B§8" + (buyer == null ? "Unknown" : buyer) + "'s Order");
         pm.setLore(
            Arrays.asList(
               "§7You're delivering §a" + amount + "x " + this.prettyName(o.material), "§7You'll receive §x§5§5§F§F§B§8$" + PriceManager.formatShort(payout)
            )
         );
         preview.setItemMeta(pm);
      }

      applyDisplayEnchants(preview, o.enchants);
      inv.setItem(13, preview);
      ItemStack confirm = this.createItem(Material.LIME_STAINED_GLASS_PANE, "§aᴄᴏɴғɪʀᴍ");
      ItemMeta cm = confirm.getItemMeta();
      cm.setLore(Collections.singletonList("§7Click to confirm delivery"));
      confirm.setItemMeta(cm);
      inv.setItem(15, confirm);
      inv.setItem(11, this.createItem(Material.RED_STAINED_GLASS_PANE, "§cᴄᴀɴᴄᴇʟ"));
      p.openInventory((Inventory)MenuUpdates.prepare(inv, "openDeliverConfirm"));
   }

   private void processDelivery(Player p) {
      OrderManager.DeliverPacket pk = this.pendingDeliverItems.remove(p.getUniqueId());
      if (pk != null) {
         OrderManager.Order o = this.findById(pk.orderId);
         if (o == null || o.owner.equals(p.getUniqueId())) {
            this.giveOrDrop(p, pk.items);
         } else if (this.usingDb()) {
            this.processDeliveryDb(p, o, pk);
         } else {
            int remaining = o.amount - o.deliveredAmount;
            if (remaining <= 0) {
               this.giveOrDrop(p, pk.items);
               p.sendMessage("§cThis order has already been filled.");
            } else {
               int amount = 0;
               List<ItemStack> deliver = new ArrayList<>();
               List<ItemStack> giveBack = new ArrayList<>();

               for (ItemStack it : pk.items) {
                  if (amount >= remaining) {
                     giveBack.add(it);
                  } else {
                     int take = Math.min(it.getAmount(), remaining - amount);
                     ItemStack d = it.clone();
                     d.setAmount(take);
                     deliver.add(d);
                     amount += take;
                     if (it.getAmount() > take) {
                        ItemStack ex = it.clone();
                        ex.setAmount(it.getAmount() - take);
                        giveBack.add(ex);
                     }
                  }
               }

               if (!giveBack.isEmpty()) {
                  this.giveOrDrop(p, giveBack);
               }

               if (amount > 0) {
                  o.loot.addAll(deliver);
                  o.deliveredAmount += amount;
                  double payout = Math.min(o.pricePer * amount, 1.0E10);
                  if (this.plugin.getEconomy() != null) {
                     this.plugin.getEconomy().depositPlayer(p, payout);
                  }

                  this.saveOrders();
                  String itemName = this.prettyName(o.material);
                  String ownerMsg = "§x§5§5§F§F§B§8" + p.getName() + " §7delivered you §x§5§5§F§F§B§8" + amount + " " + itemName;
                  if (!this.plugin.sendAuctionAlert(o.owner, ownerMsg)) {
                     this.offlineItemsReceived.merge(o.owner, (long)amount, Long::sum);
                  }

                  this.startDeliveringAnimation(p, amount, itemName, payout);
               }
            }
         }
      }

      MenuUpdates.reopenOrders(this, p);
   }

   private void processDeliveryDb(Player p, OrderManager.Order o, OrderManager.DeliverPacket pk) {
      int requested = 0;

      for (ItemStack it : pk.items) {
         requested += it.getAmount();
      }

      int req = requested;
      this.startDeliveringLoop(p);
      this.dbWriter.execute(() -> {
         int accepted = this.dbDeliverAndStore(o.id, req, pk.items);
         List<ItemStack> deliver = new ArrayList<>();
         List<ItemStack> giveBack = new ArrayList<>();
         splitByCount(pk.items, accepted, deliver, giveBack);
         double payout = Math.min(o.pricePer * accepted, 1.0E10);
         p.getScheduler().run(this.plugin, tt -> {
            if (!giveBack.isEmpty()) {
               this.giveOrDrop(p, giveBack);
            }

            if (accepted <= 0) {
               this.completeDeliveringLoop(p, false, 0, this.prettyName(o.material), 0.0);
               p.sendMessage("§cThis order has already been filled.");
               p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            } else {
               o.deliveredAmount += accepted;
               o.lootAmount += accepted;
               o.lootRows = o.lootRows + deliver.size();
               if (this.plugin.getEconomy() != null) {
                  this.plugin.getEconomy().depositPlayer(p, payout);
               }

               String itemName = this.prettyName(o.material);
               String ownerMsg = "§x§5§5§F§F§B§8" + p.getName() + " §7delivered you §x§5§5§F§F§B§8" + accepted + " " + itemName;
               if (!this.plugin.sendAuctionAlert(o.owner, ownerMsg)) {
                  this.offlineItemsReceived.merge(o.owner, (long)accepted, Long::sum);
               }

               this.completeDeliveringLoop(p, true, accepted, itemName, payout);
            }
         }, null);
      });
   }

   private static void splitByCount(List<ItemStack> src, int n, List<ItemStack> firstOut, List<ItemStack> restOut) {
      int taken = 0;

      for (ItemStack it : src) {
         if (taken >= n) {
            restOut.add(it.clone());
         } else {
            int take = Math.min(it.getAmount(), n - taken);
            ItemStack a = it.clone();
            a.setAmount(take);
            firstOut.add(a);
            taken += take;
            if (it.getAmount() > take) {
               ItemStack b = it.clone();
               b.setAmount(it.getAmount() - take);
               restOut.add(b);
            }
         }
      }
   }

   private void cancelPendingDeliver(Player p) {
      OrderManager.DeliverPacket pk = this.pendingDeliverItems.remove(p.getUniqueId());
      if (pk != null) {
         this.giveOrDrop(p, pk.items);
      }

      this.openOrders(p, 1, null);
   }

   private void startDeliveringLoop(Player p) {
      UUID uid = p.getUniqueId();
      OrderManager.DeliverAnim old = this.deliverAnims.remove(uid);
      if (old != null && old.task != null) {
         try {
            old.task.cancel();
         } catch (Throwable var7) {
         }
      }

      OrderManager.DeliverAnim anim = new OrderManager.DeliverAnim();
      p.playSound(p.getLocation(), Sound.ENTITY_MINECART_RIDING, 1.0F, 1.0F);
      String[] frames = new String[]{"§7Delivering.", "§7Delivering..", "§7Delivering..."};
      int minFrames = 2;
      anim.task = p.getScheduler()
         .runAtFixedRate(
            this.plugin,
            t -> {
               if (!p.isOnline()) {
                  t.cancel();
                  this.deliverAnims.remove(uid);

                  try {
                     p.stopSound(Sound.ENTITY_MINECART_RIDING);
                  } catch (Throwable var8) {
                  }
               } else if (anim.resultReady && anim.frames >= 2) {
                  t.cancel();
                  this.deliverAnims.remove(uid);
                  p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(" "));

                  try {
                     p.stopSound(Sound.ENTITY_MINECART_RIDING);
                  } catch (Throwable var7x) {
                  }

                  if (anim.success) {
                     p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
                     p.sendMessage(
                        "§7You delivered §x§5§5§F§F§B§8"
                           + anim.amount
                           + " "
                           + anim.itemName
                           + " §7and received §x§5§5§F§F§B§8$"
                           + PriceManager.formatShort(anim.payout)
                     );
                  }
               } else {
                  p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(frames[anim.frames % frames.length]));
                  anim.frames++;
               }
            },
            null,
            1L,
            2L
         );
      this.deliverAnims.put(uid, anim);
   }

   private void completeDeliveringLoop(Player p, boolean success, int amount, String itemName, double payout) {
      OrderManager.DeliverAnim anim = this.deliverAnims.get(p.getUniqueId());
      if (anim == null) {
         if (success) {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
            p.sendMessage("§7You delivered §x§5§5§F§F§B§8" + amount + " " + itemName + " §7and received §x§5§5§F§F§B§8$" + PriceManager.formatShort(payout));
         }
      } else {
         anim.amount = amount;
         anim.itemName = itemName;
         anim.payout = payout;
         anim.success = success;
         anim.resultReady = true;
      }

      MenuUpdates.refreshOrders(this, p);
   }

   private void startDeliveringAnimation(Player p, int amount, String itemName, double payout) {
      p.playSound(p.getLocation(), Sound.ENTITY_MINECART_RIDING, 1.0F, 1.0F);
      String[] frames = new String[]{"§7Delivering.", "§7Delivering..", "§7Delivering...", "§7Delivering.", "§7Delivering..", "§7Delivering..."};
      int[] i = new int[]{0};
      p.getScheduler().runAtFixedRate(this.plugin, task -> {
         if (!p.isOnline()) {
            task.cancel();

            try {
               p.stopSound(Sound.ENTITY_MINECART_RIDING);
            } catch (Throwable var10) {
            }
         } else if (i[0] < frames.length) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(frames[i[0]]));
            i[0]++;
         } else {
            task.cancel();
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(" "));

            try {
               p.stopSound(Sound.ENTITY_MINECART_RIDING);
            } catch (Throwable var9) {
            }

            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
            p.sendMessage("§7You delivered §x§5§5§F§F§B§8" + amount + " " + itemName + " §7and received §x§5§5§F§F§B§8$" + PriceManager.formatShort(payout));
         }
      }, null, 4L, 4L);
   }

   private boolean isShulker(ItemStack it) {
      return it != null && it.hasItemMeta() ? it.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox : false;
   }

   private void extractFromShulker(ItemStack shulker, OrderManager.Order o, int[] cap, List<ItemStack> matched) {
      try {
         BlockStateMeta bsm = (BlockStateMeta)shulker.getItemMeta();
         ShulkerBox box = (ShulkerBox)bsm.getBlockState();
         Inventory binv = box.getInventory();

         for (int i = 0; i < binv.getSize() && cap[0] > 0; i++) {
            ItemStack inner = binv.getItem(i);
            if (inner != null && this.matchesOrder(inner, o)) {
               int take = Math.min(inner.getAmount(), cap[0]);
               ItemStack m = inner.clone();
               m.setAmount(take);
               matched.add(m);
               cap[0] -= take;
               int left = inner.getAmount() - take;
               if (left <= 0) {
                  binv.setItem(i, null);
               } else {
                  inner.setAmount(left);
                  binv.setItem(i, inner);
               }
            }
         }

         bsm.setBlockState(box);
         shulker.setItemMeta(bsm);
      } catch (Throwable var13) {
      }
   }

   private boolean matchesOrder(ItemStack it, OrderManager.Order o) {
      if (it != null && it.getType() == o.material) {
         ItemMeta meta = it.hasItemMeta() ? it.getItemMeta() : null;
         if (meta != null && meta.hasDisplayName()) {
            return false;
         }

         if (!o.enchants.isEmpty()) {
            for (Entry<Enchantment, Integer> en : o.enchants.entrySet()) {
               if (it.getEnchantmentLevel(en.getKey()) < en.getValue()) {
                  return false;
               }
            }

            return true;
         } else {
            return meta == null ? true : !meta.hasEnchants() && !meta.hasLore();
         }
      } else {
         return false;
      }
   }

   private void openYourOrders(Player p) {
      this.openYourOrders(p, 1);
   }

   private void openYourOrders(Player p, int page) {
      List<OrderManager.Order> mine = new ArrayList<>();

      for (OrderManager.Order o : this.orders) {
         if (o.owner.equals(p.getUniqueId())) {
            mine.add(o);
         }
      }

      mine.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
      this.viewerOwnList.put(p.getUniqueId(), mine);
      int total = mine.size() + 1;
      int maxPage = Math.max(1, (int)Math.ceil(total / 18.0));
      if (page < 1) {
         page = 1;
      }

      if (page > maxPage) {
         page = maxPage;
      }

      this.viewerOwnPage.put(p.getUniqueId(), page);
      Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("your orders")));
      long now = System.currentTimeMillis();
      int start = (page - 1) * 18;

      for (int slot = 0; slot < 18; slot++) {
         int idx = start + slot;
         if (idx > mine.size()) {
            break;
         }

         if (idx == mine.size()) {
            ItemStack neworder = this.createItem(Material.LIME_STAINED_GLASS_PANE, "§aɴᴇᴡ ᴏʀᴅᴇʀ");
            ItemMeta nm = neworder.getItemMeta();
            nm.setLore(Arrays.asList("§7Click to create a new order"));
            neworder.setItemMeta(nm);
            inv.setItem(slot, neworder);
         } else {
            OrderManager.Order o = mine.get(idx);
            ItemStack it = new ItemStack(o.material);
            ItemMeta m = it.getItemMeta();
            if (m != null) {
               m.setDisplayName("§x§5§5§F§F§B§8" + this.prettyName(o.material) + " §7Order");
               List<String> lore = this.orderInfoLore(o);
               if (o.cancelled) {
                  lore.add("§cCancelled");
               } else if (o.expired) {
                  lore.add("§cExpired");
               }

               lore.add("");
               lore.add("§eClick to edit this order");
               m.setLore(lore);
               it.setItemMeta(m);
            }

            applyDisplayEnchants(it, o.enchants);
            inv.setItem(slot, it);
         }
      }

      if (page > 1) {
         inv.setItem(18, this.createItem(Material.ARROW, "§eᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ"));
      }

      if (page < maxPage) {
         inv.setItem(26, this.createItem(Material.ARROW, "§eɴᴇxᴛ ᴘᴀɢᴇ"));
      }

      p.openInventory((Inventory)MenuUpdates.prepare(inv, "openYourOrders"));
   }

   private void handleYourOrdersClick(Player p, int slot) {
      int page = this.viewerOwnPage.getOrDefault(p.getUniqueId(), 1);
      if (slot == 18) {
         p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
         this.openYourOrders(p, page - 1);
      } else if (slot == 26) {
         p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
         this.openYourOrders(p, page + 1);
      } else if (slot >= 0 && slot < 18) {
         List<OrderManager.Order> mine = this.viewerOwnList.getOrDefault(p.getUniqueId(), Collections.emptyList());
         int idx = (page - 1) * 18 + slot;
         if (idx == mine.size()) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.startNewOrder(p);
         } else if (idx >= 0 && idx < mine.size()) {
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            this.openEditOrder(p, mine.get(idx));
         }
      }
   }

   private void openEditOrder(Player p, OrderManager.Order o) {
      this.editingOrder.put(p.getUniqueId(), o.id);
      Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("edit order")));
      ItemStack preview = new ItemStack(o.material);
      ItemMeta pm = preview.getItemMeta();
      if (pm != null) {
         pm.setDisplayName("§x§5§5§F§F§B§8" + this.prettyName(o.material) + " §7Order");
         List<String> lore = this.orderInfoLore(o);
         if (o.cancelled) {
            lore.add("§cCancelled");
         } else if (o.expired) {
            lore.add("§cExpired");
         }

         pm.setLore(lore);
         preview.setItemMeta(pm);
      }

      inv.setItem(10, preview);
      ItemStack cancel = this.createItem(Material.RED_TERRACOTTA, "§cᴄᴀɴᴄᴇʟ");
      ItemMeta cm = cancel.getItemMeta();
      cm.setLore(Arrays.asList("§7Click to cancel this order"));
      cancel.setItemMeta(cm);
      inv.setItem(13, cancel);
      ItemStack collect = this.createItem(Material.CHEST, "§eᴄᴏʟʟᴇᴄᴛ");
      ItemMeta com = collect.getItemMeta();
      com.setLore(Arrays.asList("§7Click to collect items", "§8(" + this.collectableCount(o) + " items)"));
      collect.setItemMeta(com);
      inv.setItem(15, collect);
      p.openInventory((Inventory)MenuUpdates.prepare(inv, "openEditOrder"));
   }

   private void handleEditOrderClick(Player p, int slot) {
      Integer id = this.editingOrder.get(p.getUniqueId());
      OrderManager.Order o = id == null ? null : this.findById(id);
      if (o == null || !o.owner.equals(p.getUniqueId())) {
         this.openYourOrders(p);
      } else if (slot == 13) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.pendingCancel.put(p.getUniqueId(), o.id);
         this.openCancelConfirm(p, o);
      } else if (slot == 15) {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.openCollectItems(p, o);
      }
   }

   private void openCollectItems(Player p, OrderManager.Order o) {
      this.editingOrder.put(p.getUniqueId(), o.id);
      this.collectEmptied.remove(p.getUniqueId());
      if (this.usingDb()) {
         this.dbWriter.execute(() -> {
            List<OrderManager.LootStack> loot = this.dbLoadLoot(o.id);
            int items = 0;

            for (OrderManager.LootStack ls : loot) {
               if (ls.item != null) {
                  items += ls.item.getAmount();
               }
            }

            int trueItems = items;
            if (o.lootAmount != trueItems || o.lootRows != loot.size()) {
               this.dbSetLootCounters(o.id, trueItems, loot.size());
            }

            p.getScheduler().run(this.plugin, tt -> {
               o.lootAmount = trueItems;
               o.lootRows = loot.size();
               this.collectRows.put(p.getUniqueId(), new ArrayList<>(loot));
               this.collectPage.put(p.getUniqueId(), 0);
               this.renderCollect(p, o, 0);
            }, null);
         });
      } else {
         List<OrderManager.LootStack> rows = new ArrayList<>();

         for (ItemStack it : o.loot) {
            rows.add(new OrderManager.LootStack(-1L, it));
         }

         this.collectRows.put(p.getUniqueId(), rows);
         this.collectPage.put(p.getUniqueId(), 0);
         this.renderCollect(p, o, 0);
      }
   }

   private List<ItemStack> buildMergedDisplay(List<OrderManager.LootStack> rows) {
      List<ItemStack> variants = new ArrayList<>();

      for (OrderManager.LootStack ls : rows) {
         if (ls != null && ls.item != null) {
            boolean merged = false;

            for (ItemStack v : variants) {
               if (v.isSimilar(ls.item)) {
                  v.setAmount(v.getAmount() + ls.item.getAmount());
                  merged = true;
                  break;
               }
            }

            if (!merged) {
               variants.add(ls.item.clone());
            }
         }
      }

      List<ItemStack> out = new ArrayList<>();

      for (ItemStack v : variants) {
         int max = v.getMaxStackSize();
         if (max <= 0) {
            max = 64;
         }

         int left = v.getAmount();

         while (left > 0) {
            int n = Math.min(left, max);
            ItemStack s = v.clone();
            s.setAmount(n);
            out.add(s);
            left -= n;
         }
      }

      return out;
   }

   private void renderCollect(Player p, OrderManager.Order o, int page) {
      List<OrderManager.LootStack> rows = this.collectRows.get(p.getUniqueId());
      if (rows == null) {
         rows = new ArrayList<>();
         this.collectRows.put(p.getUniqueId(), rows);
      }

      List<ItemStack> display = this.buildMergedDisplay(rows);
      boolean emptied = this.collectEmptied.contains(p.getUniqueId());
      int totalPages = Math.max(1, (int)Math.ceil(display.size() / 45.0));
      if (page < 0) {
         page = 0;
      }

      if (!emptied && page > totalPages - 1) {
         page = totalPages - 1;
      }

      this.collectPage.put(p.getUniqueId(), page);
      Inventory inv = Bukkit.createInventory(null, 54, MenuUpdates.displayTitle("§0" + toSmallCaps("collect items")));
      int start = page * 45;
      if (!emptied) {
         for (int i = 0; i < 45 && start + i < display.size(); i++) {
            inv.setItem(i, display.get(start + i));
         }
      }

      if (page > 0) {
         inv.setItem(45, this.createItem(Material.ARROW, "§eᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ"));
      }

      ItemStack sellAll = this.createItem(Material.GOLD_INGOT, "§x§5§5§F§F§B§8" + toSmallCaps("sell all"));
      ItemMeta sm = sellAll.getItemMeta();
      sm.setLore(Arrays.asList("§7Sell every item in this order"));
      sellAll.setItemMeta(sm);
      inv.setItem(49, sellAll);
      ItemStack dropAll = this.createItem(Material.DROPPER, "§x§5§5§F§F§B§8" + toSmallCaps("drop loot"));
      ItemMeta dm = dropAll.getItemMeta();
      dm.setLore(Arrays.asList("§7Click to drop loot!"));
      dropAll.setItemMeta(dm);
      inv.setItem(52, dropAll);
      boolean hasNext = emptied ? display.size() > start : page < totalPages - 1;
      if (hasNext) {
         inv.setItem(53, this.createItem(Material.ARROW, "§eɴᴇxᴛ ᴘᴀɢᴇ"));
      }

      p.openInventory((Inventory)MenuUpdates.prepare(inv, "renderCollect"));
   }

   private void handleCollectClick(InventoryClickEvent e, Player p) {
      int raw = e.getRawSlot();
      Inventory top = e.getView().getTopInventory();
      Inventory clicked = e.getClickedInventory();
      if (raw >= 45 && raw < top.getSize() && clicked != null && clicked.equals(top)) {
         e.setCancelled(true);
         Integer id = this.editingOrder.get(p.getUniqueId());
         OrderManager.Order o = id == null ? null : this.findById(id);
         if (o != null && o.owner.equals(p.getUniqueId())) {
            int page = this.collectPage.getOrDefault(p.getUniqueId(), 0);
            boolean emptied = this.collectEmptied.contains(p.getUniqueId());
            if (raw == 45) {
               this.navCollect(p, o, page - 1, top);
            } else if (raw == 53) {
               this.navCollect(p, o, emptied ? page : page + 1, top);
            } else if (raw == 49) {
               this.openSellOrderConfirm(p, o, top);
            } else if (raw == 52) {
               this.dropCollectLoot(p, o, top);
            }
         } else {
            this.openYourOrders(p);
         }
      } else if (clicked != null && clicked.equals(top)) {
         switch (e.getAction()) {
            case PLACE_ALL:
            case PLACE_SOME:
            case PLACE_ONE:
            case SWAP_WITH_CURSOR:
            case HOTBAR_SWAP:
            case HOTBAR_MOVE_AND_READD:
               e.setCancelled(true);
         }
      } else if (clicked != null && clicked.equals(e.getView().getBottomInventory()) && e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
         e.setCancelled(true);
      }
   }

   private void navCollect(Player p, OrderManager.Order o, int newPage, Inventory currentTop) {
      this.reconcileCollect(p, o, currentTop);
      this.collectEmptied.remove(p.getUniqueId());
      p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
      this.collectSuppressClose.add(p.getUniqueId());
      this.renderCollect(p, o, newPage);
   }

   private double appraiseRows(UUID id, List<OrderManager.LootStack> rows) {
      SellMultiManager sellMulti = this.plugin.getSellMultiManager();
      if (sellMulti != null && rows != null) {
         Map<SellMultiManager.Category, Double> scratch = new EnumMap<>(SellMultiManager.Category.class);
         double total = 0.0;

         for (OrderManager.LootStack ls : rows) {
            if (ls != null && ls.item != null) {
               total += sellMulti.appraiseStack(id, ls.item, scratch);
            }
         }

         return total;
      } else {
         return 0.0;
      }
   }

   private void openSellOrderConfirm(Player p, OrderManager.Order o, Inventory currentTop) {
      this.reconcileCollect(p, o, currentTop);
      UUID uid = p.getUniqueId();
      List<OrderManager.LootStack> rows = this.collectRows.get(uid);
      double total = this.appraiseRows(uid, rows);
      if (total <= 0.0) {
         p.sendMessage("§cNothing sellable in this order.");
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.collectSuppressClose.add(uid);
         this.renderCollect(p, o, this.collectPage.getOrDefault(uid, 0));
      } else {
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         this.collectSuppressClose.add(uid);
         Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("sell order items")));
         ItemStack preview = this.createItem(Material.GOLD_INGOT, "§x§5§5§F§F§B§8" + toSmallCaps("sell all"));
         ItemMeta pm = preview.getItemMeta();
         pm.setLore(Arrays.asList("§7Sells every sellable item in this order.", "§7Worth: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(Math.min(total, 1.0E10))));
         preview.setItemMeta(pm);
         inv.setItem(13, preview);
         ItemStack confirm = this.createItem(Material.LIME_STAINED_GLASS_PANE, "§aᴄᴏɴғɪʀᴍ");
         ItemMeta cm = confirm.getItemMeta();
         cm.setLore(Collections.singletonList("§7Sell the items"));
         confirm.setItemMeta(cm);
         inv.setItem(15, confirm);
         inv.setItem(11, this.createItem(Material.RED_STAINED_GLASS_PANE, "§cʙᴀᴄᴋ"));
         p.openInventory((Inventory)MenuUpdates.prepare(inv, "openSellOrderConfirm"));
      }
   }

   private void processSellOrderItems(Player p) {
      UUID uid = p.getUniqueId();
      Integer id = this.editingOrder.get(uid);
      OrderManager.Order o = id == null ? null : this.findById(id);
      List<OrderManager.LootStack> rows = this.collectRows.get(uid);
      if (o != null && o.owner.equals(uid) && rows != null) {
         SellMultiManager sellMulti = this.plugin.getSellMultiManager();
         if (sellMulti == null || this.plugin.getEconomy() == null) {
            p.sendMessage("§cEconomy not available.");
         } else if (!this.sellInFlight.add(uid)) {
            p.sendMessage("§7Already selling — one moment.");
         } else {
            this.collectEmptied.remove(uid);
            List<OrderManager.LootStack> sellable = new ArrayList<>();
            List<Double> values = new ArrayList<>();
            List<Map<SellMultiManager.Category, Double>> credits = new ArrayList<>();

            for (OrderManager.LootStack ls : rows) {
               if (ls != null && ls.item != null) {
                  Map<SellMultiManager.Category, Double> one = new EnumMap<>(SellMultiManager.Category.class);
                  double value = sellMulti.appraiseStack(uid, ls.item, one);
                  if (!(value <= 0.0)) {
                     sellable.add(ls);
                     values.add(value);
                     credits.add(one);
                  }
               }
            }

            if (sellable.isEmpty()) {
               this.sellInFlight.remove(uid);
               p.sendMessage("§cNothing sellable in this order.");
               this.collectSuppressClose.add(uid);
               this.renderCollect(p, o, 0);
            } else if (!this.usingDb()) {
               double total = 0.0;
               Map<SellMultiManager.Category, Double> credit = new EnumMap<>(SellMultiManager.Category.class);

               for (int i = 0; i < sellable.size(); i++) {
                  total += values.get(i);
                  mergeCredit(credit, credits.get(i));
                  o.loot.remove(sellable.get(i).item);
               }

               rows.removeAll(sellable);
               this.paySellOrder(p, total, credit);
               this.cleanupFilledOrders(o.owner);
               this.saveOrders();
               this.sellInFlight.remove(uid);
               this.collectSuppressClose.add(uid);
               this.renderCollect(p, o, 0);
            } else {
               OrderManager.LootChange change = new OrderManager.LootChange();
               Map<Long, Double> valueByRow = new HashMap<>();
               Map<Long, Map<SellMultiManager.Category, Double>> creditByRow = new HashMap<>();

               for (int i = 0; i < sellable.size(); i++) {
                  OrderManager.LootStack ls = sellable.get(i);
                  change.deleteIds.add(ls.rowId);
                  change.deleteAmts.add(ls.item.getAmount());
                  valueByRow.put(ls.rowId, values.get(i));
                  creditByRow.put(ls.rowId, credits.get(i));
               }

               int oid = o.id;
               int attempted = change.deleteIds.size();
               this.dbWriter.execute(() -> {
                  OrderManager.LootApply res = this.dbApplyLootChanges(oid, change);
                  double total = 0.0;
                  Map<SellMultiManager.Category, Double> credit = new EnumMap<>(SellMultiManager.Category.class);

                  for (long rid : res.deleted) {
                     Double v = valueByRow.get(rid);
                     if (v != null) {
                        total += v;
                        mergeCredit(credit, creditByRow.get(rid));
                     }
                  }

                  double soldValue = total;
                  p.getScheduler().run(this.plugin, tt -> {
                     this.sellInFlight.remove(uid);
                     OrderManager.Order cur = this.findById(oid);
                     if (cur != null) {
                        cur.lootAmount = Math.max(0, cur.lootAmount - res.itemsRemoved);
                        cur.lootRows = Math.max(0, cur.lootRows - res.rowsRemoved);
                     }

                     List<OrderManager.LootStack> open = this.collectRows.get(uid);
                     if (open != null) {
                        open.removeIf(ls -> ls != null && res.deleted.contains(ls.rowId));
                     }

                     if (res.deleted.isEmpty()) {
                        p.sendMessage("§cCouldn't sell that right now — nothing was taken and you were not charged. Try again in a moment.");
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                     } else {
                        this.paySellOrder(p, soldValue, credit);
                        if (res.deleted.size() < attempted) {
                           p.sendMessage("§eSome items couldn't be sold right now — they're still in your order.");
                        }
                     }

                     Integer stillEditing = this.editingOrder.get(uid);
                     if (cur != null && stillEditing != null && stillEditing == oid && this.collectRows.containsKey(uid)) {
                        this.collectSuppressClose.add(uid);
                        this.renderCollect(p, cur, 0);
                     }
                  }, () -> {
                     this.sellInFlight.remove(uid);
                     if (soldValue > 0.0 && this.plugin.getEconomy() != null) {
                        this.plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(uid), Math.min(soldValue, 1.0E10));
                     }
                  });
               });
            }
         }
      } else {
         this.collectRows.remove(uid);
         this.collectPage.remove(uid);
         this.openYourOrders(p);
      }
   }

   private static void mergeCredit(Map<SellMultiManager.Category, Double> into, Map<SellMultiManager.Category, Double> from) {
      if (from != null) {
         for (Entry<SellMultiManager.Category, Double> e : from.entrySet()) {
            into.merge(e.getKey(), e.getValue(), Double::sum);
         }
      }
   }

   private void paySellOrder(Player p, double total, Map<SellMultiManager.Category, Double> credit) {
      if (!(total <= 0.0) && this.plugin.getEconomy() != null) {
         double payout = Math.min(total, 1.0E10);
         this.plugin.getEconomy().depositPlayer(p, payout);
         String payoutText = "§x§5§5§F§F§B§8+$" + PriceManager.formatPrice(payout);
         p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(payoutText));
         p.sendMessage(payoutText);
         p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
         this.plugin.addPlayerTotal(p.getUniqueId(), "sell_made", payout);
         if (total > 1.0E10) {
            p.sendMessage("§ePayout capped at $10,000,000,000");
         }

         SellMultiManager sellMulti = this.plugin.getSellMultiManager();
         if (sellMulti != null) {
            sellMulti.creditProgress(p, credit, payout / total);
         }
      }
   }

   private void dropCollectLoot(Player p, OrderManager.Order o, Inventory currentTop) {
      UUID uid = p.getUniqueId();
      if (this.collectEmptied.contains(uid)) {
         p.sendMessage("§7This page is empty — turn the page to drop the rest.");
         p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
      } else {
         List<ItemStack> stacks = new ArrayList<>();

         for (int i = 0; i < 45 && i < currentTop.getSize(); i++) {
            ItemStack it = currentTop.getItem(i);
            if (it != null && !it.getType().isAir()) {
               stacks.add(it.clone());
               currentTop.setItem(i, null);
            }
         }

         this.reconcileCollect(p, o, currentTop);
         if (!stacks.isEmpty()) {
            this.collectEmptied.add(uid);
            Vector dir = p.getEyeLocation().getDirection().normalize();
            Location dropLoc = p.getLocation().add(0.0, 1.35, 0.0).add(dir.clone().multiply(0.35));
            Vector velocity = dir.clone().multiply(0.45);
            ArrayDeque<ItemStack> queue = new ArrayDeque<>(stacks);
            Bukkit.getRegionScheduler().runAtFixedRate(this.plugin, dropLoc, task -> {
               for (int i = 0; i < 8 && !queue.isEmpty(); i++) {
                  ItemStack stack = queue.poll();
                  dropLoc.getWorld().dropItem(dropLoc, stack, it -> {
                     it.setVelocity(velocity);
                     it.setPickupDelay(10);
                  });
               }

               if (queue.isEmpty()) {
                  task.cancel();
               }
            }, 1L, 1L);
         }

         p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0F, 1.0F);
         this.collectSuppressClose.add(uid);
         this.renderCollect(p, o, this.collectPage.getOrDefault(uid, 0));
      }
   }

   private void reconcileCollect(Player p, OrderManager.Order o, Inventory topInv) {
      UUID uid = p.getUniqueId();
      List<OrderManager.LootStack> rows = this.collectRows.get(uid);
      if (rows != null && topInv != null && o != null && !this.collectEmptied.contains(uid)) {
         int page = this.collectPage.getOrDefault(uid, 0);
         List<ItemStack> display = this.buildMergedDisplay(rows);
         int start = page * 45;
         int end = Math.min(display.size(), start + 45);
         if (start < end) {
            List<ItemStack> reps = new ArrayList<>();
            List<Integer> origAmt = new ArrayList<>();

            for (int i = start; i < end; i++) {
               ItemStack s = display.get(i);
               int fi = indexOfSimilar(reps, s);
               if (fi < 0) {
                  reps.add(s.clone());
                  origAmt.add(s.getAmount());
               } else {
                  origAmt.set(fi, origAmt.get(fi) + s.getAmount());
               }
            }

            List<Integer> remAmt = new ArrayList<>(Collections.nCopies(reps.size(), 0));

            for (int i = 0; i < 45 && i < topInv.getSize(); i++) {
               ItemStack it = topInv.getItem(i);
               if (it != null && !it.getType().isAir()) {
                  int fi = indexOfSimilar(reps, it);
                  if (fi >= 0) {
                     remAmt.set(fi, remAmt.get(fi) + it.getAmount());
                  }
               }
            }

            OrderManager.LootChange change = new OrderManager.LootChange();
            int removedItems = 0;
            int removedRows = 0;

            for (int v = 0; v < reps.size(); v++) {
               int collected = Math.max(0, origAmt.get(v) - remAmt.get(v));
               if (collected > 0) {
                  ItemStack rep = reps.get(v);
                  Iterator<OrderManager.LootStack> it = rows.iterator();

                  while (collected > 0 && it.hasNext()) {
                     OrderManager.LootStack ls = it.next();
                     if (ls != null && ls.item != null && ls.item.isSimilar(rep)) {
                        int amt = ls.item.getAmount();
                        if (collected >= amt) {
                           it.remove();
                           removedItems += amt;
                           removedRows++;
                           if (this.usingDb()) {
                              change.deleteIds.add(ls.rowId);
                              change.deleteAmts.add(amt);
                           } else {
                              o.loot.remove(ls.item);
                           }

                           collected -= amt;
                        } else {
                           int newAmt = amt - collected;
                           removedItems += collected;
                           if (this.usingDb()) {
                              change.updIds.add(ls.rowId);
                              change.updAmts.add(newAmt);
                              change.updRemoved.add(collected);
                           }

                           ls.item.setAmount(newAmt);
                           if (this.usingDb()) {
                              change.updEncs.add(ItemCodec.encode(ls.item));
                           }

                           collected = 0;
                        }
                     }
                  }
               }
            }

            if (removedItems > 0 || removedRows > 0) {
               if (this.usingDb()) {
                  o.lootAmount = Math.max(0, o.lootAmount - removedItems);
                  o.lootRows = Math.max(0, o.lootRows - removedRows);
                  int oid = o.id;
                  this.dbWriter.execute(() -> {
                     OrderManager.LootApply res = this.dbApplyLootChanges(oid, change);
                     if (!res.ok) {
                        this.plugin.getLogger().warning("Collected items for order " + oid + " could not be cleared from the database; they may reappear.");
                     }
                  });
               } else {
                  this.cleanupFilledOrders(o.owner);
                  this.saveOrders();
               }
            }
         }
      }
   }

   private static int indexOfSimilar(List<ItemStack> reps, ItemStack it) {
      for (int i = 0; i < reps.size(); i++) {
         if (reps.get(i).isSimilar(it)) {
            return i;
         }
      }

      return -1;
   }

   private void openCancelConfirm(Player p, OrderManager.Order o) {
      Inventory inv = Bukkit.createInventory(null, 27, MenuUpdates.displayTitle("§0" + toSmallCaps("cancel order")));
      ItemStack preview = new ItemStack(o.material);
      ItemMeta pm = preview.getItemMeta();
      if (pm != null) {
         pm.setDisplayName("§x§5§5§F§F§B§8" + this.prettyName(o.material) + " §7Order");
         pm.setLore(this.orderInfoLore(o));
         preview.setItemMeta(pm);
      }

      inv.setItem(13, preview);
      ItemStack confirm = this.createItem(Material.LIME_STAINED_GLASS_PANE, "§aᴄᴏɴғɪʀᴍ");
      ItemMeta cm = confirm.getItemMeta();
      cm.setLore(Collections.singletonList("§7Cancel this order"));
      confirm.setItemMeta(cm);
      inv.setItem(15, confirm);
      inv.setItem(11, this.createItem(Material.RED_STAINED_GLASS_PANE, "§cʙᴀᴄᴋ"));
      p.openInventory((Inventory)MenuUpdates.prepare(inv, "openCancelConfirm"));
   }

   private void processCancel(Player p) {
      Integer id = this.pendingCancel.remove(p.getUniqueId());
      if (id == null) {
         this.openYourOrders(p);
      } else {
         OrderManager.Order o = this.findById(id);
         if (o == null || !o.owner.equals(p.getUniqueId())) {
            this.openYourOrders(p);
         } else if (this.usingDb()) {
            this.dbWriter.execute(() -> {
               boolean won = this.dbTryCancel(o.id);
               p.getScheduler().run(this.plugin, tt -> {
                  if (won) {
                     int refundQtyx = o.amount - o.deliveredAmount;
                     if (refundQtyx > 0 && this.plugin.getEconomy() != null) {
                        this.plugin.getEconomy().depositPlayer(p, Math.min(o.pricePer * refundQtyx, 1.0E10));
                     }

                     o.cancelled = true;
                  }

                  p.sendMessage("§7Order cancelled.");
                  p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                  int oid = o.id;
                  this.dbWriter.execute(() -> this.dbDeleteOrderIfEmpty(oid));
                  this.openYourOrders(p);
               }, null);
            });
         } else {
            int refundQty = o.amount - o.deliveredAmount;
            if (!o.expired && !o.cancelled && refundQty > 0 && this.plugin.getEconomy() != null) {
               double refund = Math.min(o.pricePer * refundQty, 1.0E10);
               this.plugin.getEconomy().depositPlayer(p, refund);
            }

            p.sendMessage("§7Order cancelled.");
            o.cancelled = true;
            if (o.loot.isEmpty()) {
               this.orders.remove(o);
            }

            this.saveOrders();
            p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
            this.openYourOrders(p);
         }
      }
   }

   private void returnItemsNextTick(Player p, List<ItemStack> items) {
      if (items != null && !items.isEmpty()) {
         List<ItemStack> snapshot = new ArrayList<>(items);
         p.getScheduler().runDelayed(this.plugin, task -> this.giveOrDrop(p, snapshot), null, 1L);
      }
   }

   private void giveOrDrop(Player p, List<ItemStack> items) {
      Map<Integer, ItemStack> leftovers = p.getInventory().addItem(items.toArray(new ItemStack[0]));

      for (ItemStack it : leftovers.values()) {
         p.getWorld().dropItemNaturally(p.getLocation(), it);
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent e) {
      String title = MenuUpdates.internalTitle(e.getView().getOriginalTitle());
      if (e.getWhoClicked() instanceof Player p) {
         int var7 = e.getRawSlot();
         if (title.startsWith("§0" + toSmallCaps("orders - "))) {
            e.setCancelled(true);
            if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getBottomInventory())) {
               return;
            }

            int page = this.viewerPage.getOrDefault(p.getUniqueId(), 1);
            String query = this.viewerSearchQuery.get(p.getUniqueId());
            switch (var7) {
               case 45:
                  this.openOrders(p, Math.max(1, page - 1), query);
                  break;
               case 46:
               case 52:
               default:
                  if (var7 >= 0 && var7 < 45) {
                     this.handleDeliverClick(p, var7);
                  }
                  break;
               case 47:
                  p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                  this.viewerSortChoice.put(p.getUniqueId(), this.cycleSort(this.viewerSortChoice.getOrDefault(p.getUniqueId(), "recent")));
                  this.openOrders(p, 1, query);
                  break;
               case 48:
                  p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                  this.viewerFilterChoice.put(p.getUniqueId(), this.cycleFilter(this.viewerFilterChoice.getOrDefault(p.getUniqueId(), "all")));
                  this.openOrders(p, 1, query);
                  break;
               case 49:
                  p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F, 1.0F);
                  this.openOrders(p, page, query);
                  break;
               case 50:
                  p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                  this.openSearchSign(p);
                  break;
               case 51:
                  p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                  this.openYourOrders(p);
                  break;
               case 53:
                  this.openOrders(p, page + 1, query);
            }
         } else if (title.equals("§0" + toSmallCaps("select item"))) {
            e.setCancelled(true);
            if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getBottomInventory())) {
               return;
            }

            this.handleMaterialsClick(p, var7, e.getCurrentItem());
         } else if (title.equals("§0" + toSmallCaps("new order"))) {
            e.setCancelled(true);
            if (var7 == 12) {
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               this.openMaterialsList(p, 1);
            } else if (var7 == 13) {
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               this.openAmountSign(p);
            } else if (var7 == 14) {
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               this.openPriceSign(p);
            } else if (var7 == 16) {
               this.confirmNewOrder(p);
            } else if (var7 == 10) {
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               this.drafts.remove(p.getUniqueId());
               this.openOrders(p, 1, null);
            }
         } else if (title.equals("§0" + toSmallCaps("pick enchantments"))) {
            e.setCancelled(true);
            if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getBottomInventory())) {
               return;
            }

            this.handleEnchanterClick(p, var7);
         } else if (title.equals("§0" + toSmallCaps("confirm delivery"))) {
            e.setCancelled(true);
            if (var7 == 15) {
               this.processDelivery(p);
            } else if (var7 == 11) {
               this.cancelPendingDeliver(p);
            }
         } else if (title.equals("§0" + toSmallCaps("your orders"))) {
            e.setCancelled(true);
            if (e.getClickedInventory() != null && e.getClickedInventory().equals(e.getView().getBottomInventory())) {
               return;
            }

            this.handleYourOrdersClick(p, var7);
         } else if (title.equals("§0" + toSmallCaps("edit order"))) {
            e.setCancelled(true);
            this.handleEditOrderClick(p, var7);
         } else if (title.equals("§0" + toSmallCaps("collect items"))) {
            this.handleCollectClick(e, p);
         } else if (title.equals("§0" + toSmallCaps("sell order items"))) {
            e.setCancelled(true);
            if (var7 == 15) {
               this.processSellOrderItems(p);
            } else if (var7 == 11) {
               Integer id = this.editingOrder.get(p.getUniqueId());
               OrderManager.Order o = id == null ? null : this.findById(id);
               if (o == null || !o.owner.equals(p.getUniqueId())) {
                  this.openYourOrders(p);
                  return;
               }

               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               this.collectSuppressClose.add(p.getUniqueId());
               this.renderCollect(p, o, this.collectPage.getOrDefault(p.getUniqueId(), 0));
            }
         } else if (title.equals("§0" + toSmallCaps("cancel order"))) {
            e.setCancelled(true);
            if (var7 == 15) {
               this.processCancel(p);
            } else if (var7 == 11) {
               this.pendingCancel.remove(p.getUniqueId());
               this.openYourOrders(p);
            }
         }
      }
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent e) {
      if (e.getWhoClicked() instanceof Player p && MenuUpdates.internalTitle(e.getView().getOriginalTitle()).equals("§0" + toSmallCaps("collect items"))) {
         int topSize = e.getView().getTopInventory().getSize();

         for (int raw : e.getRawSlots()) {
            if (raw < topSize) {
               e.setCancelled(true);
               return;
            }
         }
      }
   }

   @EventHandler
   public void onInventoryClose(InventoryCloseEvent e) {
      if (e.getPlayer() instanceof Player p) {
         String title = MenuUpdates.internalTitle(e.getView().getOriginalTitle());
         if (title.equals("§0" + toSmallCaps("deliver items"))) {
            this.processDeliverChestClose(p, e.getInventory());
         } else if (title.equals("§0" + toSmallCaps("confirm delivery"))) {
            OrderManager.DeliverPacket pk = this.pendingDeliverItems.remove(p.getUniqueId());
            if (pk != null) {
               this.returnItemsNextTick(p, pk.items);
            }
         } else if (title.equals("§0" + toSmallCaps("collect items"))) {
            UUID uid = p.getUniqueId();
            if (this.collectSuppressClose.remove(uid)) {
               return;
            }

            Integer id = this.editingOrder.get(uid);
            OrderManager.Order o = id == null ? null : this.findById(id);
            this.reconcileCollect(p, o, e.getInventory());
            this.collectRows.remove(uid);
            this.collectPage.remove(uid);
            this.collectEmptied.remove(uid);
         } else if (title.equals("§0" + toSmallCaps("sell order items"))) {
            UUID uid = p.getUniqueId();
            if (this.collectSuppressClose.remove(uid)) {
               return;
            }

            this.collectRows.remove(uid);
            this.collectPage.remove(uid);
            this.collectEmptied.remove(uid);
         }
      }
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent e) {
      UUID id = e.getPlayer().getUniqueId();
      this.pendingDeliverOrder.remove(id);
      OrderManager.DeliverPacket pk = this.pendingDeliverItems.remove(id);
      if (pk != null) {
         this.giveOrDrop(e.getPlayer(), pk.items);
      }

      OrderManager.DeliverAnim anim = this.deliverAnims.remove(id);
      if (anim != null && anim.task != null) {
         try {
            anim.task.cancel();
         } catch (Throwable var6) {
         }
      }

      this.collectSuppressClose.remove(id);
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent e) {
      Player player = e.getPlayer();
      List<String> notes = this.offlineNotifications.remove(player.getUniqueId());
      Long received = this.offlineItemsReceived.remove(player.getUniqueId());
      boolean hasNotes = notes != null && !notes.isEmpty();
      boolean hasReceived = received != null && received > 0L;
      if (hasNotes || hasReceived) {
         LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
         if (hasNotes) {
            for (String s : notes) {
               counts.merge(s, 1, Integer::sum);
            }
         }

         long total = hasReceived ? received : 0L;
         player.getScheduler().run(this.plugin, task -> {
            if (this.plugin.isAuctionAlertsEnabledFor(player)) {
               for (Entry<String, Integer> entry : counts.entrySet()) {
                  String line = entry.getKey();
                  if (entry.getValue() > 1) {
                     line = line + " §7(x" + entry.getValue() + ")";
                  }

                  player.sendMessage(line);
                  player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
               }

               if (total > 0L) {
                  player.sendMessage("§7You received §x§5§5§F§F§B§8" + PriceManager.formatShort(total) + " §7items while you were offline");
                  player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0F, 1.0F);
               }
            }
         }, null);
         this.saveOrders();
      }
   }

   private void cleanupExpired() {
      if (this.usingDb()) {
         this.dbWriter.execute(this::cleanupExpiredDb);
      } else {
         long now = System.currentTimeMillis();
         boolean changed = false;

         for (OrderManager.Order o : new ArrayList<>(this.orders)) {
            if (now - o.createdAt > 1209600000L) {
               if (!o.expired) {
                  int refundQty = o.amount - o.deliveredAmount;
                  if (refundQty > 0 && this.plugin.getEconomy() != null) {
                     this.plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(o.owner), o.pricePer * refundQty);
                  }

                  o.expired = true;
                  if (!o.loot.isEmpty()) {
                     String msg = "§eAn order for §a" + this.prettyName(o.material) + " §eexpired; collect the delivered items via /orders → Your Orders.";
                     if (!this.plugin.sendAuctionAlert(o.owner, msg)) {
                        this.offlineNotifications.computeIfAbsent(o.owner, k -> new ArrayList<>()).add(msg);
                     }
                  }

                  changed = true;
               }

               if (o.loot.isEmpty()) {
                  this.orders.remove(o);
                  changed = true;
               }
            }
         }

         if (changed) {
            this.saveOrders();
         }
      }
   }

   private void cleanupExpiredDb() {
      long now = System.currentTimeMillis();
      if (this.lootCounters && now - this.lastCounterRebuild > 900000L && !Bukkit.getOnlinePlayers().isEmpty()) {
         this.rebuildLootCounters();
      }

      for (OrderManager.Order o : new ArrayList<>(this.orders)) {
         if (now - o.createdAt > 1209600000L && (!o.expired || !this.hasLoot(o))) {
            if (this.dbTryExpire(o.id)) {
               int refundQty = o.amount - o.deliveredAmount;
               if (refundQty > 0 && this.plugin.getEconomy() != null) {
                  this.plugin.getEconomy().depositPlayer(Bukkit.getOfflinePlayer(o.owner), o.pricePer * refundQty);
               }

               o.expired = true;
               if (this.hasLoot(o)) {
                  String msg = "§eAn order for §a" + this.prettyName(o.material) + " §eexpired; collect the delivered items via /orders → Your Orders.";
                  if (!this.plugin.sendAuctionAlert(o.owner, msg)) {
                     this.offlineNotifications.computeIfAbsent(o.owner, k -> new ArrayList<>()).add(msg);
                  }
               }
            } else {
               o.expired = true;
            }

            this.dbDeleteOrderIfEmpty(o.id);
         }
      }
   }

   private void cleanupFilledOrders(UUID owner) {
      if (!this.usingDb()) {
         this.orders.removeIf(ox -> ox.owner.equals(owner) && ox.loot.isEmpty() && (ox.deliveredAmount >= ox.amount || ox.expired || ox.cancelled));
      } else {
         for (OrderManager.Order o : new ArrayList<>(this.orders)) {
            if (o.owner.equals(owner) && !this.hasLoot(o) && (o.deliveredAmount >= o.amount || o.expired || o.cancelled)) {
               int oid = o.id;
               this.dbWriter.execute(() -> this.dbDeleteOrderIfEmpty(oid));
            }
         }
      }
   }

   private int countOwnerOrders(UUID owner) {
      int n = 0;

      for (OrderManager.Order o : this.orders) {
         if (o.owner.equals(owner)) {
            n++;
         }
      }

      return n;
   }

   private OrderManager.Order findById(int id) {
      for (OrderManager.Order o : this.orders) {
         if (o.id == id) {
            return o;
         }
      }

      return null;
   }

   private int maxOrders(Player p) {
      return !p.hasPermission("taxsmp.orders.27") && !p.hasPermission("donutorders.max.27") ? 14 : 27;
   }

   private int countMatching(Player p, Material mat) {
      int n = 0;

      for (ItemStack it : p.getInventory().getContents()) {
         if (it != null && it.getType() == mat && this.isPlainItem(it)) {
            n += it.getAmount();
         }
      }

      return n;
   }

   private void removeMatching(Player p, Material mat, int amount) {
      int left = amount;
      int max = mat.getMaxStackSize();
      if (max <= 0) {
         max = 64;
      }

      while (left > 0) {
         int chunk = Math.min(left, max);
         Map<Integer, ItemStack> notRemoved = p.getInventory().removeItem(new ItemStack[]{new ItemStack(mat, chunk)});
         int failed = 0;

         for (ItemStack it : notRemoved.values()) {
            failed += it.getAmount();
         }

         left -= chunk - failed;
         if (failed > 0) {
            break;
         }
      }

      p.updateInventory();
   }

   private boolean isPlainItem(ItemStack item) {
      if (item == null || item.getType().isAir()) {
         return false;
      } else if (!item.hasItemMeta()) {
         return true;
      } else {
         ItemMeta meta = item.getItemMeta();
         if (meta == null) {
            return true;
         } else {
            return !meta.hasDisplayName() && !meta.hasLore() && !meta.hasEnchants() ? !(meta instanceof Damageable dm && dm.hasDamage()) : false;
         }
      }
   }

   private List<ItemStack> splitStacks(Material mat, int amount) {
      List<ItemStack> out = new ArrayList<>();
      int max = mat.getMaxStackSize();
      if (max <= 0) {
         max = 64;
      }

      int left = amount;

      while (left > 0) {
         int n = Math.min(left, max);
         out.add(new ItemStack(mat, n));
         left -= n;
      }

      return out;
   }

   private int parseInt(String s) {
      if (s == null) {
         return -1;
      }

      try {
         return Integer.parseInt(s.replaceAll("[^0-9]", ""));
      } catch (Exception e) {
         return -1;
      }
   }

   private double parseFriendlyNumber(String s) {
      if (s == null) {
         return -1.0;
      }

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

   private String prettyName(Material m) {
      return MenuUpdates.titleCase(m.name().toLowerCase().replace('_', ' '));
   }

   private String formatDHM(long ms) {
      long d = ms / 86400000L;
      long h = ms / 3600000L % 24L;
      long m = ms / 60000L % 60L;
      return d + "d " + h + "h " + m + "m";
   }

   private ItemStack createItem(Material m, String name) {
      ItemStack it = new ItemStack(m);
      ItemMeta im = it.getItemMeta();
      im.setDisplayName(name);
      it.setItemMeta(im);
      return com.taxsmp.ui.GuiSkin.autoButton(it);
   }

   private void fillBottomRow(Inventory inv) {
      ItemStack filler = this.createItem(Material.GRAY_STAINED_GLASS_PANE, "§7 ");

      for (int i = Math.max(0, inv.getSize() - 9); i < inv.getSize(); i++) {
         ItemStack cur = inv.getItem(i);
         if (cur == null || cur.getType().isAir()) {
            inv.setItem(i, filler);
         }
      }
   }

   private ItemStack createSortButton(String currentSort) {
      ItemStack it = new ItemStack(Material.CAULDRON);
      ItemMeta im = it.getItemMeta();
      im.setDisplayName("§eꜱᴏʀᴛ");
      List<String> lore = new ArrayList<>();
      lore.add(("delivered".equals(currentSort) ? "§a" : "§7") + "ᴍᴏꜱᴛ ᴅᴇʟɪᴠᴇʀᴇᴅ");
      lore.add(("paid".equals(currentSort) ? "§a" : "§7") + "ᴍᴏꜱᴛ ᴘᴀɪᴅ");
      lore.add(("recent".equals(currentSort) ? "§a" : "§7") + "ʀᴇᴄᴇɴᴛʟʏ ʟɪꜱᴛᴇᴅ");
      lore.add(("peritem".equals(currentSort) ? "§a" : "§7") + "ᴍᴏꜱᴛ ᴍᴏɴᴇʏ ᴘᴇʀ ɪᴛᴇᴍ");
      im.setLore(lore);
      it.setItemMeta(im);
      return com.taxsmp.ui.GuiSkin.button(it, "sort");
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
      return com.taxsmp.ui.GuiSkin.button(it, "filter");
   }

   private String cycleSort(String current) {
      return switch (current) {
         case "delivered" -> "paid";
         case "paid" -> "recent";
         case "recent" -> "peritem";
         case "peritem" -> "delivered";
         default -> "delivered";
      };
   }

   private String cycleFilter(String current) {
      return switch (current) {
         case "all" -> "combat";
         case "combat" -> "armor";
         case "armor" -> "tools";
         case "tools" -> "blocks";
         case "blocks" -> "misc";
         case "misc" -> "all";
         default -> "all";
      };
   }

   private void applySortChoice(List<OrderManager.Order> list, String choice) {
      switch (choice) {
         case "delivered":
            list.sort((a, b) -> Integer.compare(b.deliveredAmount, a.deliveredAmount));
            break;
         case "paid":
            list.sort((a, b) -> Double.compare(b.deliveredAmount * b.pricePer, a.deliveredAmount * a.pricePer));
            break;
         case "peritem":
            list.sort((a, b) -> Double.compare(b.pricePer, a.pricePer));
            break;
         default:
            list.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
      }
   }

   private List<OrderManager.Order> applyFilterCategory(List<OrderManager.Order> list, String category) {
      if ("all".equals(category)) {
         return list;
      }

      List<OrderManager.Order> filtered = new ArrayList<>();

      for (OrderManager.Order o : list) {
         if (this.matchesFilterCategory(o.material, category)) {
            filtered.add(o);
         }
      }

      return filtered;
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

   private boolean hasLoot(OrderManager.Order o) {
      return this.usingDb() ? o.lootRows > 0 : !o.loot.isEmpty();
   }

   private int collectableCount(OrderManager.Order o) {
      return this.usingDb() ? o.lootAmount : o.collectable();
   }

   private static String encodeEnchants(Map<Enchantment, Integer> ench) {
      if (ench != null && !ench.isEmpty()) {
         StringBuilder sb = new StringBuilder();

         for (Entry<Enchantment, Integer> en : ench.entrySet()) {
            if (sb.length() > 0) {
               sb.append(';');
            }

            sb.append(en.getKey().getKey().toString()).append('=').append(en.getValue());
         }

         return sb.toString();
      } else {
         return "";
      }
   }

   private static void decodeEnchants(String s, OrderManager.Order o) {
      if (s != null && !s.isEmpty()) {
         for (String part : s.split(";")) {
            int eq = part.lastIndexOf(61);
            if (eq >= 0) {
               try {
                  NamespacedKey nk = NamespacedKey.fromString(part.substring(0, eq));
                  Enchantment ench = nk == null ? null : (Enchantment)Registry.ENCHANTMENT.get(nk);
                  if (ench != null) {
                     o.enchants.put(ench, Integer.parseInt(part.substring(eq + 1)));
                  }
               } catch (Exception var9) {
               }
            }
         }
      }
   }

   private void initDb() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_orders (id BIGINT AUTO_INCREMENT PRIMARY KEY,owner VARCHAR(36) NOT NULL,material VARCHAR(64) NOT NULL,price DOUBLE NOT NULL,amount INT NOT NULL,delivered INT NOT NULL DEFAULT 0,created_at BIGINT NOT NULL,expired TINYINT NOT NULL DEFAULT 0,cancelled TINYINT NOT NULL DEFAULT 0,enchants TEXT,loot_amount INT NOT NULL DEFAULT 0,loot_rows INT NOT NULL DEFAULT 0,INDEX idx_ord_owner (owner))"
         );
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_order_loot (id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT NOT NULL,amount INT NOT NULL,item MEDIUMTEXT NOT NULL,INDEX idx_loot_order (order_id))"
         );
         addColumnIfMissing(st, "taxsmp_orders", "loot_amount", "INT NOT NULL DEFAULT 0");
         addColumnIfMissing(st, "taxsmp_orders", "loot_rows", "INT NOT NULL DEFAULT 0");

         try {
            st.executeUpdate("CREATE INDEX idx_loot_order_amt ON taxsmp_order_loot (order_id, amount)");
         } catch (SQLException var7) {
         }
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create orders tables: " + e.getMessage());
         return;
      }

      this.lootCounters = this.columnsUsable();
      if (!this.lootCounters) {
         this.plugin
            .getLogger()
            .severe(
               "taxsmp_orders is missing loot_amount / loot_rows and they could not be added. Orders still work, but the sync falls back to the slower aggregate. Fix with: ALTER TABLE taxsmp_orders ADD COLUMN loot_amount INT NOT NULL DEFAULT 0, ADD COLUMN loot_rows INT NOT NULL DEFAULT 0;"
            );
      } else {
         this.rebuildLootCounters();
      }

      this.migrateLocalOrders();
   }

   private boolean columnsUsable() {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT loot_amount, loot_rows FROM taxsmp_orders LIMIT 1");
      ) {
         ps.executeQuery().close();
         return true;
      } catch (SQLException e) {
         return false;
      }
   }

   private static void addColumnIfMissing(Statement st, String table, String column, String definition) {
      try {
         st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
      } catch (SQLException var5) {
      }
   }

   private void rebuildLootCounters() {
      this.lastCounterRebuild = System.currentTimeMillis();

      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate("DELETE l FROM taxsmp_order_loot l LEFT JOIN taxsmp_orders o ON o.id = l.order_id WHERE o.id IS NULL");
         st.executeUpdate(
            "UPDATE taxsmp_orders o LEFT JOIN (SELECT order_id, SUM(amount) AS a, COUNT(*) AS c FROM taxsmp_order_loot GROUP BY order_id) l ON l.order_id = o.id SET o.loot_amount = COALESCE(l.a, 0), o.loot_rows = COALESCE(l.c, 0)"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order loot counter rebuild failed: " + e.getMessage());
      }
   }

   private void migrateLocalOrders() {
      File f = new File(this.plugin.getDataFolder(), "orders.yml");
      if (f.exists()) {
         this.loadOrders();
         List<OrderManager.Order> local = new ArrayList<>(this.orders);
         this.orders.clear();

         boolean tableHasRows;
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM taxsmp_orders LIMIT 1");
            ResultSet rs = ps.executeQuery();
         ) {
            tableHasRows = rs.next();
         } catch (SQLException e) {
            tableHasRows = true;
         }

         if (tableHasRows) {
            this.plugin.getLogger().info("Orders already in MySQL; skipping local import on this server.");
         } else {
            int migrated = 0;

            for (OrderManager.Order o : local) {
               long id = this.dbInsertOrder(o);
               if (id > 0L) {
                  if (o.deliveredAmount > 0 || o.expired || o.cancelled) {
                     try (
                        Connection conn = this.db.getConnection();
                        PreparedStatement ps = conn.prepareStatement("UPDATE taxsmp_orders SET delivered=?, expired=?, cancelled=? WHERE id=?");
                     ) {
                        ps.setInt(1, o.deliveredAmount);
                        ps.setInt(2, o.expired ? 1 : 0);
                        ps.setInt(3, o.cancelled ? 1 : 0);
                        ps.setLong(4, id);
                        ps.executeUpdate();
                     } catch (SQLException var20) {
                     }
                  }

                  if (!o.loot.isEmpty()) {
                     this.dbInsertLoot(id, o.loot);
                  }

                  migrated++;
               }
            }

            this.plugin.getLogger().info("Imported " + migrated + " orders from orders.yml into MySQL.");
         }

         File backup = new File(this.plugin.getDataFolder(), "orders.yml.imported");
         if (!backup.exists()) {
            f.renameTo(backup);
         }
      }
   }

   private void reloadFromDb() {
      if (this.usingDb()) {
         List<OrderManager.Order> fresh = new ArrayList<>();
         Set<Integer> knownBefore = new HashSet<>();

         for (OrderManager.Order o : this.orders) {
            knownBefore.add(o.id);
         }

         String sql = this.lootCounters
            ? "SELECT id, owner, material, price, amount, delivered, created_at, expired, cancelled, enchants, loot_amount, loot_rows FROM taxsmp_orders"
            : "SELECT o.id, o.owner, o.material, o.price, o.amount, o.delivered, o.created_at, o.expired, o.cancelled, o.enchants, COALESCE(SUM(l.amount),0) AS loot_amount, COUNT(l.id) AS loot_rows FROM taxsmp_orders o LEFT JOIN taxsmp_order_loot l ON l.order_id = o.id GROUP BY o.id";

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
         ) {
            while (rs.next()) {
               try {
                  Material mat = Material.valueOf(rs.getString("material"));
                  OrderManager.Order o = new OrderManager.Order(
                     (int)rs.getLong("id"), UUID.fromString(rs.getString("owner")), mat, rs.getDouble("price"), rs.getInt("amount"), rs.getLong("created_at")
                  );
                  o.deliveredAmount = rs.getInt("delivered");
                  o.expired = rs.getInt("expired") != 0;
                  o.cancelled = rs.getInt("cancelled") != 0;
                  o.lootAmount = rs.getInt("loot_amount");
                  o.lootRows = rs.getInt("loot_rows");
                  decodeEnchants(rs.getString("enchants"), o);
                  fresh.add(o);
               } catch (Exception var12) {
               }
            }
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Orders sync read failed: " + e.getMessage());
            return;
         }

         Map<Integer, OrderManager.Order> have = new HashMap<>();

         for (OrderManager.Order o : this.orders) {
            have.put(o.id, o);
         }

         Set<Integer> freshIds = new HashSet<>();

         for (OrderManager.Order f : fresh) {
            freshIds.add(f.id);
            OrderManager.Order cur = have.get(f.id);
            if (cur != null) {
               cur.deliveredAmount = f.deliveredAmount;
               cur.expired = f.expired;
               cur.cancelled = f.cancelled;
               cur.lootAmount = f.lootAmount;
               cur.lootRows = f.lootRows;
            } else {
               this.orders.add(f);
            }
         }

         this.orders.removeIf(ox -> knownBefore.contains(ox.id) && !freshIds.contains(ox.id));
      }
   }

   private long dbInsertOrder(OrderManager.Order o) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO taxsmp_orders (owner, material, price, amount, delivered, created_at, expired, cancelled, enchants) VALUES (?,?,?,?,?,?,0,0,?)", 1
         );
      ) {
         ps.setString(1, o.owner.toString());
         ps.setString(2, o.material.name());
         ps.setDouble(3, o.pricePer);
         ps.setInt(4, o.amount);
         ps.setInt(5, o.deliveredAmount);
         ps.setLong(6, o.createdAt);
         ps.setString(7, encodeEnchants(o.enchants));
         ps.executeUpdate();

         try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
               return keys.getLong(1);
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order insert failed: " + e.getMessage());
      }

      return -1L;
   }

   private int dbDeliverAndStore(int orderId, int requested, List<ItemStack> items) {
      Connection conn = null;

      try {
         conn = this.db.getConnection();
         conn.setAutoCommit(false);
         int take = 0;

         try (PreparedStatement sel = conn.prepareStatement("SELECT amount, delivered, cancelled, expired FROM taxsmp_orders WHERE id = ? FOR UPDATE")) {
            sel.setInt(1, orderId);

            try (ResultSet rs = sel.executeQuery()) {
               if (rs.next() && rs.getInt("cancelled") == 0 && rs.getInt("expired") == 0) {
                  int room = rs.getInt("amount") - rs.getInt("delivered");
                  take = Math.max(0, Math.min(requested, room));
               }
            }
         }

         if (take > 0) {
            List<ItemStack> accepted = new ArrayList<>();
            List<ItemStack> rest = new ArrayList<>();
            splitByCount(items, take, accepted, rest);
            int storedRows = 0;
            int storedItems = 0;

            try (PreparedStatement ins = conn.prepareStatement("INSERT INTO taxsmp_order_loot (order_id, amount, item) VALUES (?,?,?)")) {
               for (ItemStack it : accepted) {
                  String enc = ItemCodec.encode(it);
                  if (enc != null) {
                     ins.setLong(1, orderId);
                     ins.setInt(2, it.getAmount());
                     ins.setString(3, enc);
                     ins.addBatch();
                     storedRows++;
                     storedItems += it.getAmount();
                  }
               }

               ins.executeBatch();
            }

            if (this.lootCounters) {
               try (PreparedStatement upd = conn.prepareStatement(
                     "UPDATE taxsmp_orders SET delivered = delivered + ?, loot_amount = loot_amount + ?, loot_rows = loot_rows + ? WHERE id = ?"
                  )) {
                  upd.setInt(1, take);
                  upd.setInt(2, storedItems);
                  upd.setInt(3, storedRows);
                  upd.setInt(4, orderId);
                  upd.executeUpdate();
               }
            } else {
               try (PreparedStatement upd = conn.prepareStatement("UPDATE taxsmp_orders SET delivered = delivered + ? WHERE id = ?")) {
                  upd.setInt(1, take);
                  upd.setInt(2, orderId);
                  upd.executeUpdate();
               }
            }
         }

         conn.commit();
         return take;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order delivery txn failed: " + e.getMessage());
         if (conn != null) {
            try {
               conn.rollback();
            } catch (SQLException var37) {
            }
         }

         return 0;
      } finally {
         if (conn != null) {
            try {
               conn.setAutoCommit(true);
            } catch (SQLException var36) {
            }

            try {
               conn.close();
            } catch (SQLException var35) {
            }
         }
      }
   }

   private void dbInsertLoot(long orderId, List<ItemStack> items) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("INSERT INTO taxsmp_order_loot (order_id, amount, item) VALUES (?,?,?)");
      ) {
         int rows = 0;
         int amt = 0;

         for (ItemStack it : items) {
            String enc = ItemCodec.encode(it);
            if (enc != null) {
               ps.setLong(1, orderId);
               ps.setInt(2, it.getAmount());
               ps.setString(3, enc);
               ps.addBatch();
               rows++;
               amt += it.getAmount();
            }
         }

         ps.executeBatch();
         if (this.lootCounters) {
            try (PreparedStatement upd = conn.prepareStatement("UPDATE taxsmp_orders SET loot_amount = loot_amount + ?, loot_rows = loot_rows + ? WHERE id = ?")) {
               upd.setInt(1, amt);
               upd.setInt(2, rows);
               upd.setLong(3, orderId);
               upd.executeUpdate();
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order loot insert failed: " + e.getMessage());
      }
   }

   private List<OrderManager.LootStack> dbLoadLoot(int orderId) {
      List<OrderManager.LootStack> out = new ArrayList<>();

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT id, item FROM taxsmp_order_loot WHERE order_id = ? ORDER BY id");
      ) {
         ps.setInt(1, orderId);

         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               ItemStack it = ItemCodec.decode(rs.getString("item"));
               if (it != null) {
                  out.add(new OrderManager.LootStack(rs.getLong("id"), it));
               }
            }
         }
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order loot read failed: " + e.getMessage());
      }

      return out;
   }

   private OrderManager.LootApply dbApplyLootChanges(int orderId, OrderManager.LootChange ch) {
      OrderManager.LootApply result = new OrderManager.LootApply();
      if (ch.isEmpty()) {
         result.ok = true;
         return result;
      }

      for (int attempt = 1; attempt <= 3; attempt++) {
         OrderManager.LootApply r = this.applyLootOnce(orderId, ch);
         if (r.ok) {
            return r;
         }

         result = r;

         try {
            Thread.sleep(250L * attempt);
         } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            break;
         }
      }

      return result;
   }

   private OrderManager.LootApply applyLootOnce(int orderId, OrderManager.LootChange ch) {
      OrderManager.LootApply out = new OrderManager.LootApply();
      Connection conn = null;

      try {
         conn = this.db.getConnection();
         conn.setAutoCommit(false);

         for (int from = 0; from < ch.deleteIds.size(); from += 200) {
            int to = Math.min(ch.deleteIds.size(), from + 200);
            List<Long> chunk = ch.deleteIds.subList(from, to);
            StringBuilder sql = new StringBuilder("DELETE FROM taxsmp_order_loot WHERE order_id = ? AND id IN (");

            for (int i = 0; i < chunk.size(); i++) {
               sql.append(i == 0 ? "?" : ",?");
            }

            sql.append(')');

            int affected;
            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
               ps.setInt(1, orderId);

               for (int i = 0; i < chunk.size(); i++) {
                  ps.setLong(i + 2, chunk.get(i));
               }

               affected = ps.executeUpdate();
            }

            if (affected == chunk.size()) {
               out.deleted.addAll(chunk);
            } else {
               Set<Long> survivors = new HashSet<>();
               StringBuilder q = new StringBuilder("SELECT id FROM taxsmp_order_loot WHERE id IN (");

               for (int i = 0; i < chunk.size(); i++) {
                  q.append(i == 0 ? "?" : ",?");
               }

               q.append(')');

               try (PreparedStatement ps = conn.prepareStatement(q.toString())) {
                  for (int i = 0; i < chunk.size(); i++) {
                     ps.setLong(i + 1, chunk.get(i));
                  }

                  try (ResultSet rs = ps.executeQuery()) {
                     while (rs.next()) {
                        survivors.add(rs.getLong(1));
                     }
                  }
               }

               for (long rid : chunk) {
                  if (!survivors.contains(rid)) {
                     out.deleted.add(rid);
                  }
               }
            }
         }

         if (!ch.updIds.isEmpty()) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE taxsmp_order_loot SET amount = ?, item = ? WHERE id = ?")) {
               for (int i = 0; i < ch.updIds.size(); i++) {
                  if (ch.updEncs.get(i) != null) {
                     ps.setInt(1, ch.updAmts.get(i));
                     ps.setString(2, ch.updEncs.get(i));
                     ps.setLong(3, ch.updIds.get(i));
                     ps.addBatch();
                  }
               }

               ps.executeBatch();
            }
         }

         for (int i = 0; i < ch.deleteIds.size(); i++) {
            if (out.deleted.contains(ch.deleteIds.get(i))) {
               out.itemsRemoved = out.itemsRemoved + ch.deleteAmts.get(i);
               out.rowsRemoved++;
            }
         }

         for (int i = 0; i < ch.updIds.size(); i++) {
            if (ch.updEncs.get(i) != null) {
               out.itemsRemoved = out.itemsRemoved + ch.updRemoved.get(i);
            }
         }

         if (this.lootCounters && (out.itemsRemoved > 0 || out.rowsRemoved > 0)) {
            try (PreparedStatement ps = conn.prepareStatement(
                  "UPDATE taxsmp_orders SET loot_amount = GREATEST(0, loot_amount - ?), loot_rows = GREATEST(0, loot_rows - ?) WHERE id = ?"
               )) {
               ps.setInt(1, out.itemsRemoved);
               ps.setInt(2, out.rowsRemoved);
               ps.setInt(3, orderId);
               ps.executeUpdate();
            }
         }

         try (PreparedStatement ps = conn.prepareStatement(
               "DELETE FROM taxsmp_orders WHERE id = ? AND (delivered >= amount OR expired = 1 OR cancelled = 1) AND NOT EXISTS (SELECT 1 FROM taxsmp_order_loot WHERE order_id = ?)"
            )) {
            ps.setInt(1, orderId);
            ps.setInt(2, orderId);
            ps.executeUpdate();
         }

         conn.commit();
         out.ok = true;
         return out;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order loot update failed: " + e.getMessage());
         if (conn != null) {
            try {
               conn.rollback();
            } catch (SQLException var41) {
            }
         }

         OrderManager.LootApply failed = new OrderManager.LootApply();
         failed.ok = false;
         return failed;
      } finally {
         if (conn != null) {
            try {
               conn.setAutoCommit(true);
            } catch (SQLException var40) {
            }

            try {
               conn.close();
            } catch (SQLException var39) {
            }
         }
      }
   }

   private void dbSetLootCounters(int orderId, int items, int rows) {
      if (this.lootCounters) {
         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("UPDATE taxsmp_orders SET loot_amount = ?, loot_rows = ? WHERE id = ?");
         ) {
            ps.setInt(1, items);
            ps.setInt(2, rows);
            ps.setInt(3, orderId);
            ps.executeUpdate();
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Order loot counter fix failed: " + e.getMessage());
         }
      }
   }

   private boolean dbTryExpire(int orderId) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("UPDATE taxsmp_orders SET expired = 1 WHERE id = ? AND expired = 0 AND cancelled = 0");
      ) {
         ps.setInt(1, orderId);
         return ps.executeUpdate() == 1;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order expire failed: " + e.getMessage());
         return false;
      }
   }

   private boolean dbTryCancel(int orderId) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("UPDATE taxsmp_orders SET cancelled = 1 WHERE id = ? AND cancelled = 0 AND expired = 0");
      ) {
         ps.setInt(1, orderId);
         return ps.executeUpdate() == 1;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order cancel failed: " + e.getMessage());
         return false;
      }
   }

   private void dbDeleteOrderIfEmpty(int orderId) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement(
            "DELETE FROM taxsmp_orders WHERE id = ? AND (delivered >= amount OR expired = 1 OR cancelled = 1) AND NOT EXISTS (SELECT 1 FROM taxsmp_order_loot WHERE order_id = ?)"
         );
      ) {
         ps.setInt(1, orderId);
         ps.setInt(2, orderId);
         ps.executeUpdate();
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Order cleanup delete failed: " + e.getMessage());
      }
   }

   public void saveOrders() {
      try {
         File f = new File(this.plugin.getDataFolder(), "orders.yml");
         if (!this.plugin.getDataFolder().exists()) {
            this.plugin.getDataFolder().mkdirs();
         }

         YamlConfiguration cfg = new YamlConfiguration();
         if (!this.usingDb()) {
            int i = 0;

            for (OrderManager.Order o : this.orders) {
               String base = "orders." + i;
               cfg.set(base + ".id", o.id);
               cfg.set(base + ".owner", o.owner.toString());
               cfg.set(base + ".material", o.material.name());
               cfg.set(base + ".price", o.pricePer);
               cfg.set(base + ".amount", o.amount);
               cfg.set(base + ".delivered", o.deliveredAmount);
               cfg.set(base + ".createdAt", o.createdAt);
               cfg.set(base + ".expired", o.expired);
               cfg.set(base + ".cancelled", o.cancelled);
               if (!o.enchants.isEmpty()) {
                  List<String> enchStrs = new ArrayList<>();

                  for (Entry<Enchantment, Integer> en : o.enchants.entrySet()) {
                     enchStrs.add(en.getKey().getKey().toString() + "=" + en.getValue());
                  }

                  cfg.set(base + ".enchants", enchStrs);
               }

               int k = 0;

               for (ItemStack it : o.loot) {
                  cfg.set(base + ".loot." + k, it);
                  k++;
               }

               i++;
            }

            cfg.set("nextId", this.nextId);
         }

         for (Entry<UUID, List<String>> entry : this.offlineNotifications.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
               cfg.set("offlineNotifications." + entry.getKey(), entry.getValue());
            }
         }

         for (Entry<UUID, Long> entry : this.offlineItemsReceived.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0L) {
               cfg.set("offlineItemsReceived." + entry.getKey(), entry.getValue());
            }
         }

         cfg.save(f);
      } catch (Exception ex) {
         this.plugin.getLogger().warning("Failed to save orders.yml: " + ex.getMessage());
      }
   }

   private void loadOrders() {
      try {
         File f = new File(this.plugin.getDataFolder(), "orders.yml");
         if (!f.exists()) {
            return;
         }

         YamlConfiguration cfg = new YamlConfiguration();
         cfg.load(f);
         this.orders.clear();

         for (int i = 0; cfg.contains("orders." + i + ".owner"); i++) {
            String base = "orders." + i;

            try {
               int id = cfg.getInt(base + ".id", i + 1);
               UUID owner = UUID.fromString(cfg.getString(base + ".owner"));
               Material mat = Material.valueOf(cfg.getString(base + ".material"));
               double price = cfg.getDouble(base + ".price");
               int amount = cfg.getInt(base + ".amount");
               int delivered = cfg.getInt(base + ".delivered");
               long createdAt = cfg.getLong(base + ".createdAt");
               OrderManager.Order o = new OrderManager.Order(id, owner, mat, price, amount, createdAt);
               o.deliveredAmount = delivered;
               o.expired = cfg.getBoolean(base + ".expired", false);
               o.cancelled = cfg.getBoolean(base + ".cancelled", false);

               for (String s : cfg.getStringList(base + ".enchants")) {
                  int eq = s.lastIndexOf(61);
                  if (eq >= 0) {
                     try {
                        NamespacedKey nk = NamespacedKey.fromString(s.substring(0, eq));
                        Enchantment ench = nk == null ? null : (Enchantment)Registry.ENCHANTMENT.get(nk);
                        if (ench != null) {
                           o.enchants.put(ench, Integer.parseInt(s.substring(eq + 1)));
                        }
                     } catch (Exception var21) {
                     }
                  }
               }

               if (cfg.contains(base + ".loot")) {
                  for (int k = 0; cfg.contains(base + ".loot." + k); k++) {
                     ItemStack it = cfg.getItemStack(base + ".loot." + k);
                     if (it != null) {
                        o.loot.add(it);
                     }
                  }
               }

               this.orders.add(o);
            } catch (Exception var22) {
            }
         }

         this.nextId = cfg.getInt("nextId", this.computeNextId());
         if (cfg.contains("offlineNotifications")) {
            for (String key : cfg.getConfigurationSection("offlineNotifications").getKeys(false)) {
               List<String> list = cfg.getStringList("offlineNotifications." + key);
               if (list != null && !list.isEmpty()) {
                  this.offlineNotifications.put(UUID.fromString(key), new ArrayList<>(list));
               }
            }
         }

         if (cfg.contains("offlineItemsReceived")) {
            for (String key : cfg.getConfigurationSection("offlineItemsReceived").getKeys(false)) {
               long v = cfg.getLong("offlineItemsReceived." + key);
               if (v > 0L) {
                  try {
                     this.offlineItemsReceived.put(UUID.fromString(key), v);
                  } catch (Exception var20) {
                  }
               }
            }
         }
      } catch (Exception var23) {
      }
   }

   private int computeNextId() {
      int max = 0;

      for (OrderManager.Order o : this.orders) {
         max = Math.max(max, o.id);
      }

      return max + 1;
   }

   private static final class DeliverAnim {
      ScheduledTask task;
      int frames = 0;
      volatile boolean resultReady = false;
      volatile boolean success = false;
      volatile int amount = 0;
      volatile String itemName = "";
      volatile double payout = 0.0;
   }

   private static final class DeliverPacket {
      final int orderId;
      final List<ItemStack> items;
      final int amount;
      final double payout;

      DeliverPacket(int orderId, List<ItemStack> items, int amount, double payout) {
         this.orderId = orderId;
         this.items = items;
         this.amount = amount;
         this.payout = payout;
      }
   }

   private static class Draft {
      Material material;
      int amount = 1;
      double price = 1.0;
      final Map<Enchantment, Integer> enchants = new LinkedHashMap<>();

      Draft(Material material) {
         this.material = material;
      }
   }

   private static final class LootApply {
      final Set<Long> deleted = new HashSet<>();
      int itemsRemoved;
      int rowsRemoved;
      boolean ok;
   }

   private static final class LootChange {
      final List<Long> deleteIds = new ArrayList<>();
      final List<Integer> deleteAmts = new ArrayList<>();
      final List<Long> updIds = new ArrayList<>();
      final List<Integer> updAmts = new ArrayList<>();
      final List<String> updEncs = new ArrayList<>();
      final List<Integer> updRemoved = new ArrayList<>();

      boolean isEmpty() {
         return this.deleteIds.isEmpty() && this.updIds.isEmpty();
      }
   }

   private static final class LootStack {
      final long rowId;
      final ItemStack item;

      LootStack(long rowId, ItemStack item) {
         this.rowId = rowId;
         this.item = item;
      }
   }

   private static class Order {
      final int id;
      final UUID owner;
      final Material material;
      final double pricePer;
      final int amount;
      int deliveredAmount;
      final long createdAt;
      boolean expired;
      boolean cancelled;
      final List<ItemStack> loot = new ArrayList<>();
      int lootAmount;
      int lootRows;
      final Map<Enchantment, Integer> enchants = new LinkedHashMap<>();

      Order(int id, UUID owner, Material material, double pricePer, int amount, long createdAt) {
         this.id = id;
         this.owner = owner;
         this.material = material;
         this.pricePer = pricePer;
         this.amount = amount;
         this.createdAt = createdAt;
      }

      int collectable() {
         int n = 0;

         for (ItemStack it : this.loot) {
            n += it.getAmount();
         }

         return n;
      }

      boolean isPubliclyDeliverable(long now) {
         return !this.expired && !this.cancelled && this.deliveredAmount < this.amount && now - this.createdAt <= 1209600000L;
      }
   }
}
