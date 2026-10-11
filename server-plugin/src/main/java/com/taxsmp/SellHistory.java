package com.taxsmp;

import com.taxsmp.storage.Db;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

public final class SellHistory implements CommandExecutor, TabCompleter, Listener {
   private static final String MINT = "§x§5§5§F§F§B§8";
   private static final int PER_PAGE = 45;
   private static final List<String> SORTS = List.of("money", "amount", "value", "recent");
   private static final List<String> FILTERS = List.of("all", "combat", "armor", "tools", "blocks", "misc");
   private static volatile SellHistory instance;
   private final TaxSMP plugin;
   private final Db db;
   private final Map<UUID, Map<Material, SellHistory.Stat>> local = new ConcurrentHashMap<>();
   private final Map<UUID, String[]> viewState = new ConcurrentHashMap<>();
   private final AtomicBoolean saveQueued = new AtomicBoolean();
   private final File file;

   private SellHistory(TaxSMP plugin) {
      this.plugin = plugin;
      Db candidate = plugin.getDb();
      this.db = candidate != null && candidate.isEnabled() ? candidate : null;
      this.file = new File(plugin.getDataFolder(), "sellhistory.yml");
   }

   public static synchronized void init(TaxSMP plugin) {
      if (instance == null && plugin != null && plugin.isEnabled()) {
         SellHistory history = new SellHistory(plugin);
         PluginCommand command = plugin.getCommand("sellhistory");
         if (command != null) {
            command.setExecutor(history);
            command.setTabCompleter(history);
         }

         plugin.getServer().getPluginManager().registerEvents(history, plugin);
         if (history.db != null) {
            history.db.async(history::createTable);
         } else {
            history.loadFile();
         }

         instance = history;
      }
   }

   public static synchronized void shutdown() {
      SellHistory history = instance;
      instance = null;
      if (history != null && history.db == null) {
         history.writeFile();
      }
   }

   public static void recordSell(UUID uuid, List<ItemStack> stacks, List<Double> values, double payout) {
      SellHistory history = instance;
      if (history != null && uuid != null && payout > 0.0 && stacks != null) {
         Map<Material, SellHistory.Stat> sale = new EnumMap<>(Material.class);
         double appraised = 0.0;

         for (int i = 0; i < stacks.size(); i++) {
            double value = values != null && i < values.size() ? values.get(i) : 0.0;
            appraised += Math.max(0.0, value);
            spread(sale, stacks.get(i), value);
         }

         scale(sale, appraised > 0.0 ? payout / appraised : 1.0);
         history.add(uuid, sale);
      }
   }

   public static void recordExternal(UUID uuid, List<ItemStack> stacks, double payout) {
      SellHistory history = instance;
      if (history != null && uuid != null && payout > 0.0 && stacks != null) {
         Map<Material, SellHistory.Stat> sale = new EnumMap<>(Material.class);
         List<SellHistory.Part> parts = new ArrayList<>();

         for (ItemStack stack : stacks) {
            flatten(stack, 1, 0, parts);
         }

         share(sale, parts, payout);
         history.add(uuid, sale);
      }
   }

   private static void spread(Map<Material, SellHistory.Stat> sale, ItemStack stack, double value) {
      List<SellHistory.Part> parts = new ArrayList<>();
      flatten(stack, 1, 0, parts);
      share(sale, parts, Math.max(0.0, value));
   }

   private static void flatten(ItemStack stack, int multiplier, int depth, List<SellHistory.Part> parts) {
      if (stack != null && stack.getType() != Material.AIR && stack.getAmount() > 0) {
         long amount = (long)stack.getAmount() * multiplier;
         parts.add(new SellHistory.Part(stack.getType(), amount, PriceManager.getSellPrice(stack.getType()) * amount));
         if (depth < 2 && stack.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox shulker) {
            for (ItemStack inner : shulker.getInventory().getContents()) {
               flatten(inner, stack.getAmount() * multiplier, depth + 1, parts);
            }
         }
      }
   }

   private static void share(Map<Material, SellHistory.Stat> sale, List<SellHistory.Part> parts, double money) {
      double worth = 0.0;
      long count = 0L;

      for (SellHistory.Part part : parts) {
         worth += part.worth();
         count += part.amount();
      }

      long now = System.currentTimeMillis();

      for (SellHistory.Part part : parts) {
         double cut = worth > 0.0 ? money * part.worth() / worth : (count > 0L ? money * part.amount() / count : 0.0);
         sale.computeIfAbsent(part.material(), m -> new SellHistory.Stat(0L, 0.0, now)).add(part.amount(), cut, now);
      }
   }

   private static void scale(Map<Material, SellHistory.Stat> sale, double factor) {
      for (SellHistory.Stat stat : sale.values()) {
         stat.money *= factor;
      }
   }

   private void add(UUID uuid, Map<Material, SellHistory.Stat> sale) {
      if (!sale.isEmpty()) {
         if (this.db != null) {
            this.db.async(() -> this.upsert(uuid, sale));
         } else {
            Map<Material, SellHistory.Stat> mine = this.local.computeIfAbsent(uuid, id -> new ConcurrentHashMap<>());

            for (Entry<Material, SellHistory.Stat> entry : sale.entrySet()) {
               SellHistory.Stat add = entry.getValue();
               mine.merge(entry.getKey(), add, (old, fresh) -> {
                  old.add(fresh.amount, fresh.money, fresh.lastSold);
                  return (SellHistory.Stat)old;
               });
            }

            this.queueFileSave();
         }
      }
   }

   private void createTable() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_sell_history (uuid VARCHAR(36) NOT NULL, material VARCHAR(64) NOT NULL, amount BIGINT NOT NULL, money DOUBLE NOT NULL, last_sold BIGINT NOT NULL, PRIMARY KEY (uuid, material))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not create the sell history table: " + e.getMessage());
      }
   }

   private void upsert(UUID uuid, Map<Material, SellHistory.Stat> sale) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO taxsmp_sell_history (uuid, material, amount, money, last_sold) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount), money = money + VALUES(money), last_sold = GREATEST(last_sold, VALUES(last_sold))"
         );
      ) {
         for (Entry<Material, SellHistory.Stat> entry : sale.entrySet()) {
            ps.setString(1, uuid.toString());
            ps.setString(2, entry.getKey().name());
            ps.setLong(3, entry.getValue().amount);
            ps.setDouble(4, entry.getValue().money);
            ps.setLong(5, entry.getValue().lastSold);
            ps.addBatch();
         }

         ps.executeBatch();
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not save sell history: " + e.getMessage());
      }
   }

   private Map<Material, SellHistory.Stat> query(UUID uuid) throws SQLException {
      Map<Material, SellHistory.Stat> out = new EnumMap<>(Material.class);

      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT material, amount, money, last_sold FROM taxsmp_sell_history WHERE uuid = ?");
      ) {
         ps.setString(1, uuid.toString());

         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               Material material = Material.matchMaterial(rs.getString("material"));
               if (material != null) {
                  out.put(material, new SellHistory.Stat(rs.getLong("amount"), rs.getDouble("money"), rs.getLong("last_sold")));
               }
            }
         }
      }

      return out;
   }

   private void loadFile() {
      if (this.file.exists()) {
         ConfigurationSection players = YamlConfiguration.loadConfiguration(this.file).getConfigurationSection("players");
         if (players != null) {
            for (String key : players.getKeys(false)) {
               ConfigurationSection items = players.getConfigurationSection(key);
               if (items != null) {
                  try {
                     Map<Material, SellHistory.Stat> mine = new ConcurrentHashMap<>();

                     for (String name : items.getKeys(false)) {
                        Material material = Material.matchMaterial(name);
                        SellHistory.Stat stat = SellHistory.Stat.fromLine(items.getString(name));
                        if (material != null && stat != null) {
                           mine.put(material, stat);
                        }
                     }

                     this.local.put(UUID.fromString(key), mine);
                  } catch (IllegalArgumentException var10) {
                  }
               }
            }
         }
      }
   }

   private void queueFileSave() {
      if (this.saveQueued.compareAndSet(false, true)) {
         Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> {
            this.saveQueued.set(false);
            this.writeFile();
         }, 5L, TimeUnit.SECONDS);
      }
   }

   private synchronized void writeFile() {
      YamlConfiguration yaml = new YamlConfiguration();

      for (Entry<UUID, Map<Material, SellHistory.Stat>> player : this.local.entrySet()) {
         for (Entry<Material, SellHistory.Stat> item : player.getValue().entrySet()) {
            yaml.set("players." + player.getKey() + "." + item.getKey().name(), item.getValue().toLine());
         }
      }

      try {
         yaml.save(this.file);
      } catch (IOException e) {
         this.plugin.getLogger().warning("Could not save sellhistory.yml: " + e.getMessage());
      }
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player viewer) {
         if (args.length == 0) {
            this.open(viewer, viewer.getUniqueId(), viewer.getName(), 0);
            return true;
         }

         if (!viewer.hasPermission("taxsmp.sellhistory.others")) {
            viewer.sendMessage("§cYou can only see your own sell history.");
            return true;
         }

         OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
         if (target == null) {
            NetworkPlayerDirectory directory = this.plugin.getNetworkPlayerDirectory();
            target = directory != null ? directory.resolveOfflinePlayer(args[0]) : Bukkit.getOfflinePlayerIfCached(args[0]);
         }

         if (target == null) {
            viewer.sendMessage("§cPlayer not found.");
            return true;
         } else {
            this.open(viewer, target.getUniqueId(), target.getName() == null ? args[0] : target.getName(), 0);
            return true;
         }
      } else {
         sender.sendMessage("§cOnly players can open the sell history.");
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      if (args.length == 1 && sender.hasPermission("taxsmp.sellhistory.others")) {
         String prefix = args[0].toLowerCase(Locale.ROOT);
         List<String> names = new ArrayList<>();

         for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
               names.add(online.getName());
            }
         }

         return names;
      } else {
         return List.of();
      }
   }

   private void open(Player viewer, UUID target, String targetName, int page) {
      if (this.db == null) {
         Map<Material, SellHistory.Stat> copy = new EnumMap<>(Material.class);
         Map<Material, SellHistory.Stat> mine = this.local.get(target);
         if (mine != null) {
            for (Entry<Material, SellHistory.Stat> entry : mine.entrySet()) {
               copy.put(entry.getKey(), entry.getValue().copy());
            }
         }

         this.show(viewer, target, targetName, copy, page);
      } else {
         this.db.async(() -> {
            Map<Material, SellHistory.Stat> stats;
            try {
               stats = this.query(target);
            } catch (SQLException e) {
               this.plugin.getLogger().warning("Could not load sell history: " + e.getMessage());
               viewer.getScheduler().run(this.plugin, t -> viewer.sendMessage("§cCould not load the sell history right now."), null);
               return;
            }

            viewer.getScheduler().run(this.plugin, t -> this.show(viewer, target, targetName, stats, page), null);
         });
      }
   }

   private void show(Player viewer, UUID target, String targetName, Map<Material, SellHistory.Stat> stats, int page) {
      if (viewer.isOnline()) {
         String[] state = this.viewState.computeIfAbsent(viewer.getUniqueId(), id -> new String[]{"money", "all"});
         String sort = state[0];
         String filter = state[1];
         List<Entry<Material, SellHistory.Stat>> rows = new ArrayList<>();

         for (Entry<Material, SellHistory.Stat> entry : stats.entrySet()) {
            if (matchesFilter(entry.getKey(), filter)) {
               rows.add(entry);
            }
         }

         rows.sort(comparator(sort));
         int pages = Math.max(1, (rows.size() + 45 - 1) / 45);
         int shown = Math.max(0, Math.min(page, pages - 1));
         boolean self = viewer.getUniqueId().equals(target);
         String title = (self ? "Sell history" : targetName + "'s sell history") + " (Page " + (shown + 1) + ")";
         Inventory inv = Bukkit.createInventory(new SellHistory.Menu(target, targetName, shown, stats), 54, title);
         long now = System.currentTimeMillis();

         for (int i = 0; i < 45; i++) {
            int index = shown * 45 + i;
            if (index >= rows.size()) {
               break;
            }

            inv.setItem(i, this.statItem(rows.get(index).getKey(), rows.get(index).getValue(), now));
         }

         inv.setItem(45, item(Material.ARROW, "§eᴘʀᴇᴠɪᴏᴜꜱ ᴘᴀɢᴇ", List.of("§7Page " + (shown + 1) + "/" + pages)));
         inv.setItem(53, item(Material.ARROW, "§eɴᴇxᴛ ᴘᴀɢᴇ", List.of("§7Page " + (shown + 1) + "/" + pages)));
         inv.setItem(48, this.sortButton(sort));
         inv.setItem(49, this.refreshButton(stats));
         inv.setItem(50, this.filterButton(filter));
         viewer.openInventory(inv);
      }
   }

   private ItemStack statItem(Material material, SellHistory.Stat stat, long now) {
      double each = stat.amount > 0L ? stat.money / stat.amount : 0.0;
      List<String> lore = List.of(
         "§7Amount sold: §f" + String.format(Locale.US, "%,d", stat.amount),
         "§7Value per item: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(each),
         "§7Money made: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(stat.money),
         "§8Last sold " + ago(now - stat.lastSold)
      );
      Material icon = material.isItem() && material != Material.AIR ? material : Material.PAPER;
      return item(icon, "§e" + prettyMaterial(material.name()), lore);
   }

   private ItemStack sortButton(String current) {
      return item(
         Material.CAULDRON,
         "§eꜱᴏʀᴛ",
         List.of(
            ("money".equals(current) ? "§a" : "§7") + "ᴍᴏꜱᴛ ᴍᴏɴᴇʏ ᴍᴀᴅᴇ",
            ("amount".equals(current) ? "§a" : "§7") + "ᴍᴏꜱᴛ ꜱᴏʟᴅ",
            ("value".equals(current) ? "§a" : "§7") + "ʜɪɢʜᴇꜱᴛ ᴠᴀʟᴜᴇ ᴘᴇʀ ɪᴛᴇᴍ",
            ("recent".equals(current) ? "§a" : "§7") + "ʀᴇᴄᴇɴᴛʟʏ ꜱᴏʟᴅ"
         )
      );
   }

   private ItemStack filterButton(String current) {
      return item(
         Material.HOPPER,
         "§eғɪʟᴛᴇʀ",
         List.of(
            ("all".equals(current) ? "§a" : "§7") + "ᴀʟʟ",
            ("combat".equals(current) ? "§a" : "§7") + "ᴄᴏᴍʙᴀᴛ",
            ("armor".equals(current) ? "§a" : "§7") + "ᴀʀᴍᴏʀ",
            ("tools".equals(current) ? "§a" : "§7") + "ᴛᴏᴏʟꜱ",
            ("blocks".equals(current) ? "§a" : "§7") + "ʙʟᴏᴄᴋꜱ",
            ("misc".equals(current) ? "§a" : "§7") + "ᴍɪꜱᴄ"
         )
      );
   }

   private ItemStack refreshButton(Map<Material, SellHistory.Stat> stats) {
      long amount = 0L;
      double money = 0.0;

      for (SellHistory.Stat stat : stats.values()) {
         amount += stat.amount;
         money += stat.money;
      }

      return item(
         Material.ANVIL,
         "§eʀᴇғʀᴇꜱʜ",
         List.of(
            "§7Items sold: §f" + String.format(Locale.US, "%,d", amount),
            "§7Money made: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(money),
            "",
            "§8From /sell and the Chicken Sell Axe."
         )
      );
   }

   @EventHandler
   public void onClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof SellHistory.Menu menu) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player viewer) {
            int slot = event.getRawSlot();
            String[] state = this.viewState.computeIfAbsent(viewer.getUniqueId(), id -> new String[]{"money", "all"});
            if (slot == 45 && menu.page() > 0) {
               this.play(viewer);
               this.show(viewer, menu.target(), menu.targetName(), menu.stats(), menu.page() - 1);
            } else if (slot == 53) {
               this.play(viewer);
               this.show(viewer, menu.target(), menu.targetName(), menu.stats(), menu.page() + 1);
            } else if (slot == 48) {
               this.play(viewer);
               state[0] = SORTS.get((SORTS.indexOf(state[0]) + 1) % SORTS.size());
               this.show(viewer, menu.target(), menu.targetName(), menu.stats(), 0);
            } else if (slot == 50) {
               this.play(viewer);
               state[1] = FILTERS.get((FILTERS.indexOf(state[1]) + 1) % FILTERS.size());
               this.show(viewer, menu.target(), menu.targetName(), menu.stats(), 0);
            } else if (slot == 49) {
               this.play(viewer);
               this.open(viewer, menu.target(), menu.targetName(), menu.page());
            }
         }
      }
   }

   @EventHandler
   public void onDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof SellHistory.Menu) {
         event.setCancelled(true);
      }
   }

   private void play(Player viewer) {
      try {
         viewer.playSound(viewer.getLocation(), Sound.UI_BUTTON_CLICK, 0.6F, 1.2F);
      } catch (Throwable var3) {
      }
   }

   private static Comparator<Entry<Material, SellHistory.Stat>> comparator(String sort) {
      Comparator<Entry<Material, SellHistory.Stat>> byName = Comparator.comparing(e -> e.getKey().name());

      return switch (sort) {
         case "amount" -> Comparator.<Entry<Material, SellHistory.Stat>>comparingLong(e -> -((SellHistory.Stat)e.getValue()).amount).thenComparing(byName);
         case "value" -> Comparator.<Entry<Material, SellHistory.Stat>>comparingDouble(
               e -> ((SellHistory.Stat)e.getValue()).amount > 0L ? -(((SellHistory.Stat)e.getValue()).money / ((SellHistory.Stat)e.getValue()).amount) : 0.0
            )
            .thenComparing(byName);
         case "recent" -> Comparator.<Entry<Material, SellHistory.Stat>>comparingLong(e -> -((SellHistory.Stat)e.getValue()).lastSold).thenComparing(byName);
         default -> Comparator.<Entry<Material, SellHistory.Stat>>comparingDouble(e -> -((SellHistory.Stat)e.getValue()).money).thenComparing(byName);
      };
   }

   private static boolean matchesFilter(Material mat, String category) {
      String n = mat.name();

      return switch (category) {
         case "combat" -> n.contains("SWORD")
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
         case "armor" -> n.contains("HELMET") || n.contains("CHESTPLATE") || n.contains("LEGGINGS") || n.contains("BOOTS") || mat == Material.ELYTRA;
         case "tools" -> n.contains("PICKAXE")
            || n.endsWith("_AXE")
            || n.contains("SHOVEL")
            || n.contains("_HOE")
            || mat == Material.SHEARS
            || mat == Material.FLINT_AND_STEEL
            || mat == Material.FISHING_ROD;
         case "blocks" -> mat.isBlock();
         case "misc" -> !matchesFilter(mat, "combat") && !matchesFilter(mat, "armor") && !matchesFilter(mat, "tools") && !matchesFilter(mat, "blocks");
         default -> true;
      };
   }

   private static ItemStack item(Material material, String name, List<String> lore) {
      ItemStack stack = new ItemStack(material);
      ItemMeta meta = stack.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(name);
         meta.setLore(lore);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         stack.setItemMeta(meta);
      }

      return stack;
   }

   private static String prettyMaterial(String name) {
      StringBuilder out = new StringBuilder();

      for (String word : name.toLowerCase(Locale.ROOT).split("_")) {
         if (!word.isEmpty()) {
            out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
         }
      }

      return out.toString();
   }

   private static String ago(long millis) {
      long minutes = TimeUnit.MILLISECONDS.toMinutes(Math.max(0L, millis));
      if (minutes < 1L) {
         return "just now";
      }

      if (minutes < 60L) {
         return minutes + "m ago";
      }

      long hours = minutes / 60L;
      return hours < 24L ? hours + "h ago" : hours / 24L + "d ago";
   }

   private record Menu(UUID target, String targetName, int page, Map<Material, SellHistory.Stat> stats) implements InventoryHolder {
      public Inventory getInventory() {
         return null;
      }
   }

   private record Part(Material material, long amount, double worth) {
   }

   private static final class Stat {
      long amount;
      double money;
      long lastSold;

      Stat(long amount, double money, long lastSold) {
         this.amount = amount;
         this.money = money;
         this.lastSold = lastSold;
      }

      synchronized void add(long moreAmount, double moreMoney, long at) {
         this.amount += moreAmount;
         this.money += moreMoney;
         this.lastSold = Math.max(this.lastSold, at);
      }

      synchronized SellHistory.Stat copy() {
         return new SellHistory.Stat(this.amount, this.money, this.lastSold);
      }

      synchronized String toLine() {
         return this.amount + "|" + this.money + "|" + this.lastSold;
      }

      static SellHistory.Stat fromLine(String line) {
         if (line == null) {
            return null;
         }

         String[] parts = line.split("\\|");
         if (parts.length < 3) {
            return null;
         }

         try {
            return new SellHistory.Stat(Long.parseLong(parts[0]), Double.parseDouble(parts[1]), Long.parseLong(parts[2]));
         } catch (NumberFormatException e) {
            return null;
         }
      }
   }
}
