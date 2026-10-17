package com.example.chickenaxe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Chicken Tree Chopper (a netherite axe): breaking one log of a tree cuts down that whole
 * tree, its own leaves included, and nothing of the trees next to it.
 *
 * The tree is every log of the same wood touching the chopped one. Its leaves are found through
 * the distance every natural leaf block keeps to its nearest log (1 = touching a log): starting
 * next to this tree's logs, only steps to a leaf exactly one further away are followed, so the
 * search stops where another tree's leaves start (their distance goes down again, toward their
 * own logs). Logs with no natural leaves on them (a log wall, a cabin) are not a tree: only the
 * chopped log breaks, like with a normal axe.
 */
public class ChickenAxePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
   public static final long EXPIRY_MILLIS = TimeUnit.DAYS.toMillis(3L);
   public static final String DISPLAY_NAME = "§eᴄʜɪᴄᴋᴇɴ ᴛʀᴇᴇ ᴄʜᴏᴘᴘᴇʀ";
   /** The TaxSMP resource pack's look for this item. */
   private static final NamespacedKey ITEM_MODEL = new NamespacedKey("taxsmp", "tool/tree_chopper");
   private static final int MAX_LOGS = 512;
   private static final int MAX_LEAVES = 4096;
   /** How far sideways from the chopped log a tree's logs may reach (big jungle and dark oak trees). */
   private static final int REACH = 12;
   private static final BlockFace[] FACES = {
      BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
   };
   private NamespacedKey markerKey;
   private NamespacedKey createdAtKey;
   private static boolean folia;

   @Override
   public void onEnable() {
      this.markerKey = new NamespacedKey("chickenaxe", "is_chicken_axe");
      this.createdAtKey = new NamespacedKey("chickenaxe", "created_at");
      if (this.getCommand("chickenaxe") != null) {
         this.getCommand("chickenaxe").setExecutor(this);
         this.getCommand("chickenaxe").setTabCompleter(this);
      } else {
         this.getLogger().warning("Command 'chickenaxe' is missing from plugin.yml");
      }

      this.getServer().getPluginManager().registerEvents(this, this);
      this.startSelfDestructTask();
   }

   public ItemStack createChickenAxe() {
      long createdAt = System.currentTimeMillis();
      ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
      ItemMeta meta = axe.getItemMeta();
      meta.setDisplayName(DISPLAY_NAME);
      meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
      meta.addEnchant(Enchantment.UNBREAKING, 3, true);
      meta.addEnchant(Enchantment.MENDING, 1, true);
      meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
      meta.setItemModel(ITEM_MODEL);
      meta.setEnchantmentGlintOverride(false);
      PersistentDataContainer pdc = meta.getPersistentDataContainer();
      pdc.set(this.markerKey, PersistentDataType.BYTE, (byte)1);
      pdc.set(this.createdAtKey, PersistentDataType.LONG, createdAt);
      meta.setLore(this.buildLore(createdAt));
      axe.setItemMeta(meta);
      return axe;
   }

   private List<String> buildLore(long createdAt) {
      long remaining = Math.max(0L, EXPIRY_MILLIS - (System.currentTimeMillis() - createdAt));
      List<String> lore = new ArrayList<>();
      lore.add("§7Breaks Trees Instantly");
      lore.add("§8Self Destruct:");
      lore.add(remaining <= 0L ? "§cExpired" : "§8" + formatDHM(remaining));
      return lore;
   }

   public boolean isChickenAxe(ItemStack item) {
      if (item != null && item.getType() == Material.NETHERITE_AXE && item.hasItemMeta()) {
         Byte marker = item.getItemMeta().getPersistentDataContainer().get(this.markerKey, PersistentDataType.BYTE);
         return marker != null && marker == 1;
      }
      return false;
   }

   private long remainingMillis(ItemStack item) {
      Long createdAt = item.getItemMeta().getPersistentDataContainer().get(this.createdAtKey, PersistentDataType.LONG);
      return createdAt == null ? -1L : Math.max(0L, EXPIRY_MILLIS - (System.currentTimeMillis() - createdAt));
   }

   /** Updates the timer lore; returns true only if the text changed (about once a minute). */
   private boolean refreshLore(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      Long createdAt = meta.getPersistentDataContainer().get(this.createdAtKey, PersistentDataType.LONG);
      if (createdAt == null) {
         return false;
      }
      List<String> lore = this.buildLore(createdAt);
      if (sameText(lore, meta.getLore())) {
         return false;
      }
      meta.setLore(lore);
      item.setItemMeta(meta);
      return true;
   }

   /** Same visible text, ignoring color codes (Paper may rebuild the colors slightly differently). */
   private static boolean sameText(List<String> a, List<String> b) {
      if (b == null || a.size() != b.size()) {
         return false;
      }
      for (int i = 0; i < a.size(); i++) {
         if (!ChatColor.stripColor(a.get(i)).equals(ChatColor.stripColor(b.get(i)))) {
            return false;
         }
      }
      return true;
   }

   private void startSelfDestructTask() {
      if (folia) {
         Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
               p.getScheduler().run(this, t -> this.sweep(p), null);
            }
         }, 20L, 20L);
      } else {
         Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
               this.sweep(p);
            }
         }, 20L, 20L);
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player p = event.getPlayer();
      if (folia) {
         p.getScheduler().run(this, t -> this.sweep(p), null);
      } else {
         this.sweep(p);
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBlockBreak(BlockBreakEvent event) {
      ItemStack tool = event.getPlayer().getInventory().getItemInMainHand();
      Block origin = event.getBlock();
      if (!this.isChickenAxe(tool) || !Tag.LOGS.isTagged(origin.getType())) {
         return;
      }

      Set<Block> logs = this.findLogs(origin);
      Set<Block> leaves = this.findLeaves(logs);
      if (leaves.isEmpty() && !this.hasWartCap(logs)) {
         return; // not a grown tree: just the chopped log breaks
      }

      for (Block log : logs) {
         if (!log.equals(origin)) {
            log.breakNaturally(tool, true);
         }
      }
      for (Block leaf : leaves) {
         leaf.breakNaturally(tool, true);
      }
   }

   /** Every log of the chopped log's wood connected to it, corners included (branches touch diagonally). */
   private Set<Block> findLogs(Block origin) {
      Material wood = origin.getType();
      Set<Block> logs = new HashSet<>();
      Deque<Block> queue = new ArrayDeque<>();
      logs.add(origin);
      queue.add(origin);
      while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
         Block current = queue.poll();
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  Block next = current.getRelative(dx, dy, dz);
                  if (Math.abs(next.getX() - origin.getX()) > REACH || Math.abs(next.getZ() - origin.getZ()) > REACH
                     || logs.contains(next) || !Bukkit.isOwnedByCurrentRegion(next)) {
                     continue;
                  }
                  if (next.getType() == wood) {
                     logs.add(next);
                     queue.add(next);
                  }
               }
            }
         }
      }
      return logs;
   }

   /** This tree's own leaves: walks outward from its logs, one leaf distance step at a time. */
   private Set<Block> findLeaves(Set<Block> logs) {
      Set<Block> leaves = new HashSet<>();
      Deque<Block> queue = new ArrayDeque<>();
      for (Block log : logs) {
         for (BlockFace face : FACES) {
            Block next = log.getRelative(face);
            if (leafDistance(next) == 1 && leaves.add(next)) {
               queue.add(next);
            }
         }
      }
      while (!queue.isEmpty() && leaves.size() < MAX_LEAVES) {
         Block leaf = queue.poll();
         int further = leafDistance(leaf) + 1;
         for (BlockFace face : FACES) {
            Block next = leaf.getRelative(face);
            if (!leaves.contains(next) && leafDistance(next) == further) {
               leaves.add(next);
               queue.add(next);
            }
         }
      }
      return leaves;
   }

   /** Steps from a natural leaf block to its nearest log, or -1 for anything else (placed leaves too). */
   private static int leafDistance(Block block) {
      if (Bukkit.isOwnedByCurrentRegion(block) && block.getBlockData() instanceof Leaves leaves && !leaves.isPersistent()) {
         return leaves.getDistance();
      }
      return -1;
   }

   /** Nether fungus trees have wart blocks instead of leaves. */
   private boolean hasWartCap(Set<Block> logs) {
      for (Block log : logs) {
         for (BlockFace face : FACES) {
            Block next = log.getRelative(face);
            if (Bukkit.isOwnedByCurrentRegion(next)) {
               Material type = next.getType();
               if (type == Material.NETHER_WART_BLOCK || type == Material.WARPED_WART_BLOCK || type == Material.SHROOMLIGHT) {
                  return true;
               }
            }
         }
      }
      return false;
   }

   private void sweep(Player player) {
      PlayerInventory inv = player.getInventory();
      boolean destroyed = false;

      for (int slot = 0; slot < inv.getSize(); slot++) {
         ItemStack item = inv.getItem(slot);
         if (this.isChickenAxe(item)) {
            if (this.remainingMillis(item) <= 0L) {
               inv.setItem(slot, null);
               destroyed = true;
            } else if (this.refreshLore(item)) {
               // Only resend the slot when the timer text changed; resending it every second floods players.
               inv.setItem(slot, item);
            }
         }
      }

      if (destroyed) {
         player.sendMessage("§cYour Chicken Tree Chopper self destructed.");
      }
   }

   public static String formatDHM(long ms) {
      long d = ms / 86400000L;
      long h = ms / 3600000L % 24L;
      long m = ms / 60000L % 60L;
      return d + "d " + h + "h " + m + "m";
   }

   // /chickenaxe give <player> [amount]: the item just appears, no chat messages.
   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
         sender.sendMessage(ChatColor.YELLOW + "Usage: /chickenaxe give <player> [amount]");
         return true;
      }
      Player target = Bukkit.getPlayerExact(args[1]);
      if (target == null) {
         sender.sendMessage(ChatColor.RED + "That player is not online.");
         return true;
      }
      int amount = 1;
      if (args.length >= 3) {
         try {
            amount = Integer.parseInt(args[2]);
         } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Amount must be a number.");
            return true;
         }
         if (amount < 1 || amount > 64) {
            sender.sendMessage(ChatColor.RED + "Amount must be between 1 and 64.");
            return true;
         }
      }
      int count = amount;
      Runnable give = () -> {
         for (int i = 0; i < count; i++) {
            target.getInventory().addItem(this.createChickenAxe())
               .values()
               .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
         }
      };
      // The console runs commands on the global thread; a player's inventory belongs to their region.
      if (Bukkit.isOwnedByCurrentRegion(target)) {
         give.run();
      } else {
         target.getScheduler().run(this, t -> give.run(), null);
      }
      return true;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      List<String> out = new ArrayList<>();
      if (args.length == 1) {
         if ("give".startsWith(args[0].toLowerCase())) {
            out.add("give");
         }
      } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
         String prefix = args[1].toLowerCase();
         for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(prefix)) {
               out.add(p.getName());
            }
         }
      } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
         out.add("1");
         out.add("5");
         out.add("10");
      }
      return out;
   }

   static {
      try {
         Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
         folia = true;
      } catch (ClassNotFoundException e) {
         folia = false;
      }
   }
}
