package com.chickenitems;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

/**
 * The Chicken Drill (a netherite pickaxe, called Chicken Pickaxe before): mines the 3x3 around
 * the block you break, minus obsidian, spawners and bedrock, and self-destructs after 3 days.
 * Given with /chickenpickaxe give, /givechickenpickaxe or /give <player> chicken_pickaxe.
 */
public class ChickenPickaxePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
   public static final long EXPIRY_MILLIS = TimeUnit.DAYS.toMillis(3L);
   public static final String DISPLAY_NAME = "ᴄʜɪᴄᴋᴇɴ ᴅʀɪʟʟ";
   /** The TaxSMP resource pack's look for this item. */
   private static final NamespacedKey ITEM_MODEL = new NamespacedKey("taxsmp", "tool/drill");
   private NamespacedKey chickenPickaxeKey;
   private NamespacedKey createdAtKey;

   @Override
   public void onEnable() {
      this.chickenPickaxeKey = new NamespacedKey(this, "chicken_pickaxe");
      this.createdAtKey = new NamespacedKey(this, "created_at");
      this.getServer().getPluginManager().registerEvents(this, this);
      for (String name : new String[]{"chickenpickaxe", "givechickenpickaxe"}) {
         PluginCommand command = this.getCommand(name);
         if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
         } else {
            this.getLogger().warning("Command " + name + " missing in plugin.yml");
         }
      }
   }

   // ---------------------------------------------------------------- the item

   public ItemStack create() {
      ItemStack item = new ItemStack(Material.NETHERITE_PICKAXE);
      ItemMeta meta = item.getItemMeta();
      meta.setDisplayName(ChatColor.YELLOW + DISPLAY_NAME);
      meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
      meta.addEnchant(Enchantment.UNBREAKING, 3, true);
      meta.addEnchant(Enchantment.MENDING, 1, true);
      meta.setItemModel(ITEM_MODEL);
      meta.setEnchantmentGlintOverride(false);
      long createdAt = System.currentTimeMillis();
      meta.getPersistentDataContainer().set(this.chickenPickaxeKey, PersistentDataType.BYTE, (byte)1);
      meta.getPersistentDataContainer().set(this.createdAtKey, PersistentDataType.LONG, createdAt);
      meta.setLore(buildLore(createdAt));
      item.setItemMeta(meta);
      return item;
   }

   public boolean isChickenPickaxe(ItemStack item) {
      if (item == null || item.getType() != Material.NETHERITE_PICKAXE || !item.hasItemMeta()) {
         return false;
      }
      Byte marker = item.getItemMeta().getPersistentDataContainer().get(this.chickenPickaxeKey, PersistentDataType.BYTE);
      return marker != null && marker == 1;
   }

   private boolean isExpired(ItemStack item) {
      Long createdAt = item.getItemMeta().getPersistentDataContainer().get(this.createdAtKey, PersistentDataType.LONG);
      return createdAt == null || System.currentTimeMillis() - createdAt >= EXPIRY_MILLIS;
   }

   /** Updates the timer lore, but only touches the item when the text changed (about once a minute). */
   private void refreshLore(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      Long createdAt = meta.getPersistentDataContainer().get(this.createdAtKey, PersistentDataType.LONG);
      if (createdAt != null) {
         List<String> lore = buildLore(createdAt);
         if (!sameText(lore, meta.getLore())) {
            meta.setLore(lore);
            item.setItemMeta(meta);
         }
      }
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

   private static List<String> buildLore(long createdAt) {
      long remaining = Math.max(0L, EXPIRY_MILLIS - (System.currentTimeMillis() - createdAt));
      long days = TimeUnit.MILLISECONDS.toDays(remaining);
      long hours = TimeUnit.MILLISECONDS.toHours(remaining) % 24L;
      long minutes = TimeUnit.MILLISECONDS.toMinutes(remaining) % 60L;
      List<String> lore = new ArrayList<>();
      lore.add(ChatColor.GRAY + "Breaks 9 Blocks at Once");
      lore.add(ChatColor.DARK_GRAY + "Self Destruct:");
      if (remaining <= 0L) {
         lore.add(ChatColor.DARK_RED + "Expired");
      } else {
         lore.add(ChatColor.DARK_GRAY + String.format("%dd %dh %dm", days, hours, minutes));
      }
      return lore;
   }

   // ---------------------------------------------------------------- mining

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onBlockBreak(BlockBreakEvent event) {
      Player player = event.getPlayer();
      ItemStack tool = player.getInventory().getItemInMainHand();
      if (!this.isChickenPickaxe(tool)) {
         return;
      }
      if (this.isExpired(tool)) {
         player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
         player.sendMessage(ChatColor.RED + "Your ᴄʜɪᴄᴋᴇɴ ᴅʀɪʟʟ expired and self-destructed.");
         event.setCancelled(true);
         return;
      }
      this.refreshLore(tool);
      Block origin = event.getBlock();
      if (isObsidian(origin.getType())) {
         return;
      }
      for (Block block : getExtraBlocks(player, origin)) {
         if (!block.equals(origin) && isBreakable(block) && !isObsidian(block.getType())) {
            int expToDrop = getExpToDrop(player, tool, block);
            block.breakNaturally(tool, true);
            if (expToDrop > 0) {
               Location orbLocation = block.getLocation().add(0.5, 0.5, 0.5);
               block.getWorld().spawn(orbLocation, ExperienceOrb.class).setExperience(expToDrop);
            }
         }
      }
   }

   @EventHandler
   public void onHeldItemChange(PlayerItemHeldEvent event) {
      ItemStack newItem = event.getPlayer().getInventory().getItem(event.getNewSlot());
      if (!this.isChickenPickaxe(newItem)) {
         return;
      }
      if (this.isExpired(newItem)) {
         event.getPlayer().getInventory().setItem(event.getNewSlot(), new ItemStack(Material.AIR));
         event.getPlayer().sendMessage(ChatColor.RED + "Your ᴄʜɪᴄᴋᴇɴ ᴅʀɪʟʟ expired and self-destructed.");
      } else {
         this.refreshLore(newItem);
      }
   }

   private static List<Block> getExtraBlocks(Player player, Block origin) {
      List<Block> blocks = new ArrayList<>();
      RayTraceResult hit = player.rayTraceBlocks(6.0);
      BlockFace hitFace = hit != null && origin.equals(hit.getHitBlock()) ? hit.getHitBlockFace() : null;
      BlockFace facing = hitFace != null ? hitFace : player.getFacing();
      if (facing == BlockFace.DOWN) {
         addVerticalThreeByTwo(blocks, origin, player.getFacing(), true);
      } else if (facing == BlockFace.UP) {
         addVerticalThreeByTwo(blocks, origin, player.getFacing(), false);
      } else if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH) {
         for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
               blocks.add(origin.getRelative(0, dy, dz));
            }
         }
      } else {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               blocks.add(origin.getRelative(dx, dy, 0));
            }
         }
      }
      return blocks;
   }

   private static void addVerticalThreeByTwo(List<Block> blocks, Block origin, BlockFace facing, boolean up) {
      BlockFace side = facing != BlockFace.NORTH && facing != BlockFace.SOUTH ? BlockFace.SOUTH : BlockFace.EAST;
      int yStep = up ? 1 : -1;
      for (Block block : new Block[]{origin.getRelative(side.getOppositeFace()), origin, origin.getRelative(side)}) {
         blocks.add(block);
         blocks.add(block.getRelative(0, yStep, 0));
      }
   }

   private static boolean isBreakable(Block block) {
      Material type = block.getType();
      if (type.isAir() || type == Material.SPAWNER || type.name().equals("TRIAL_SPAWNER") || type == Material.BEDROCK) {
         return false;
      }
      return type.getHardness() >= 0.0F;
   }

   private static boolean isObsidian(Material material) {
      return material == Material.OBSIDIAN || material == Material.CRYING_OBSIDIAN;
   }

   private static int getExpToDrop(Player player, ItemStack tool, Block block) {
      if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
         return 0;
      }
      if (tool != null && tool.getEnchantmentLevel(Enchantment.SILK_TOUCH) > 0) {
         return 0;
      }
      return switch (block.getType()) {
         case COAL_ORE, DEEPSLATE_COAL_ORE -> randomBetween(0, 2);
         case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE, EMERALD_ORE, DEEPSLATE_EMERALD_ORE -> randomBetween(3, 7);
         case LAPIS_ORE, DEEPSLATE_LAPIS_ORE, NETHER_QUARTZ_ORE -> randomBetween(2, 5);
         case REDSTONE_ORE, DEEPSLATE_REDSTONE_ORE -> randomBetween(1, 5);
         case NETHER_GOLD_ORE -> randomBetween(0, 1);
         case SPAWNER -> randomBetween(15, 43);
         default -> 0;
      };
   }

   private static int randomBetween(int min, int max) {
      return ThreadLocalRandom.current().nextInt(min, max + 1);
   }

   // ---------------------------------------------------------------- giving (no chat messages, just the item)

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.hasPermission("chickenpickaxe.admin")) {
         sender.sendMessage(ChatColor.RED + "You do not have permission.");
         return true;
      }
      boolean direct = command.getName().equalsIgnoreCase("givechickenpickaxe");
      String targetName;
      int amount = 1;
      if (direct) {
         if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <player> [amount]");
            return true;
         }
         targetName = args[0];
         if (args.length >= 2) {
            amount = parseAmount(args[1]);
         }
      } else {
         if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " give <player> [amount]");
            return true;
         }
         targetName = args[1];
         if (args.length >= 3) {
            amount = parseAmount(args[2]);
         }
      }
      this.give(sender, targetName, amount);
      return true;
   }

   @Override
   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      boolean direct = command.getName().equalsIgnoreCase("givechickenpickaxe");
      int nameArg = direct ? 0 : 1;
      if (!direct && args.length == 1) {
         return Collections.singletonList("give");
      }
      if (args.length != nameArg + 1 || (!direct && !args[0].equalsIgnoreCase("give"))) {
         return Collections.emptyList();
      }
      String prefix = args[nameArg].toLowerCase(Locale.ROOT);
      return Bukkit.getOnlinePlayers().stream()
         .map(Player::getName)
         .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
         .sorted()
         .toList();
   }

   /** Catches the vanilla style "/give <player> chicken_pickaxe [amount]" (chicken_drill works too). */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
      String[] parts = event.getMessage().substring(1).trim().split("\\s+");
      if (isGiveAlias(parts)) {
         event.setCancelled(true);
         if (!event.getPlayer().hasPermission("chickenpickaxe.admin")) {
            event.getPlayer().sendMessage(ChatColor.RED + "You do not have permission.");
         } else {
            this.give(event.getPlayer(), parts[1], parts.length >= 4 ? parseAmount(parts[3]) : 1);
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onServerCommand(ServerCommandEvent event) {
      String full = event.getCommand().trim();
      if (full.startsWith("/")) {
         full = full.substring(1).trim();
      }
      String[] parts = full.split("\\s+");
      if (isGiveAlias(parts)) {
         event.setCancelled(true);
         this.give(event.getSender(), parts[1], parts.length >= 4 ? parseAmount(parts[3]) : 1);
      }
   }

   private static boolean isGiveAlias(String[] parts) {
      return parts.length >= 3 && parts[0].equalsIgnoreCase("give")
         && (parts[2].equalsIgnoreCase("chicken_pickaxe") || parts[2].equalsIgnoreCase("chicken_drill"));
   }

   private void give(CommandSender sender, String targetName, int amount) {
      Player target = Bukkit.getPlayerExact(targetName);
      if (target == null) {
         sender.sendMessage(ChatColor.RED + "Player not found: " + targetName);
         return;
      }
      int count = Math.max(1, Math.min(64, amount));
      Runnable give = () -> {
         for (int i = 0; i < count; i++) {
            target.getInventory().addItem(this.create());
         }
      };
      // The console runs commands on the global thread; a player's inventory belongs to their region.
      if (Bukkit.isOwnedByCurrentRegion(target)) {
         give.run();
      } else {
         target.getScheduler().run(this, t -> give.run(), null);
      }
   }

   private static int parseAmount(String rawAmount) {
      try {
         return Integer.parseInt(rawAmount);
      } catch (NumberFormatException e) {
         return 1;
      }
   }
}
