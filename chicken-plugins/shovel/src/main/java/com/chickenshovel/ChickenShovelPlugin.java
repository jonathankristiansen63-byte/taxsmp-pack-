package com.chickenshovel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import org.bukkit.util.RayTraceResult;

/**
 * The Chicken Shovel (an unbreakable netherite shovel): digs the 3x3 of sand, gravel, dirt and
 * other shovel blocks around the block you break, and self-destructs after 3 days.
 */
public class ChickenShovelPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
   public static final NamespacedKey CHICKEN_SHOVEL_KEY = new NamespacedKey("chickenshovel", "is_chicken_shovel");
   public static final NamespacedKey DESTRUCT_TIME_KEY = new NamespacedKey("chickenshovel", "destruct_time");
   /** The TaxSMP resource pack's look for this item. */
   private static final NamespacedKey ITEM_MODEL = new NamespacedKey("taxsmp", "tool/shovel");
   private static final long DEFAULT_DURATION = 259200L; // seconds: 3 days
   private static final TextColor GRAY = TextColor.color(170, 170, 170);
   private static final TextColor DARK_GRAY = TextColor.color(85, 85, 85);
   private static final Set<Material> DIGGABLE = EnumSet.of(
      Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL,
      Material.WHITE_CONCRETE_POWDER, Material.ORANGE_CONCRETE_POWDER, Material.MAGENTA_CONCRETE_POWDER,
      Material.LIGHT_BLUE_CONCRETE_POWDER, Material.YELLOW_CONCRETE_POWDER, Material.LIME_CONCRETE_POWDER,
      Material.PINK_CONCRETE_POWDER, Material.GRAY_CONCRETE_POWDER, Material.LIGHT_GRAY_CONCRETE_POWDER,
      Material.CYAN_CONCRETE_POWDER, Material.PURPLE_CONCRETE_POWDER, Material.BLUE_CONCRETE_POWDER,
      Material.BROWN_CONCRETE_POWDER, Material.GREEN_CONCRETE_POWDER, Material.RED_CONCRETE_POWDER,
      Material.BLACK_CONCRETE_POWDER, Material.DRAGON_EGG, Material.ANVIL, Material.CHIPPED_ANVIL,
      Material.DAMAGED_ANVIL, Material.SCAFFOLDING, Material.POINTED_DRIPSTONE, Material.SNOW,
      Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.GRASS_BLOCK, Material.DIRT_PATH,
      Material.PODZOL, Material.MYCELIUM, Material.FARMLAND, Material.MUD, Material.MUDDY_MANGROVE_ROOTS,
      Material.CLAY, Material.SOUL_SAND, Material.SOUL_SOIL, Material.SNOW_BLOCK, Material.POWDER_SNOW
   );
   private final Set<Player> processingPlayers = new HashSet<>();

   @Override
   public void onEnable() {
      this.getServer().getPluginManager().registerEvents(this, this);
      this.getCommand("chicken_shovel").setExecutor(this);
      this.getCommand("chicken_shovel").setTabCompleter(this);
      Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
         for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(this, t -> this.checkPlayerInventory(player), null);
         }
      }, 20L, 20L);
   }

   // ---------------------------------------------------------------- the item

   public static ItemStack create(long durationSeconds) {
      ItemStack item = new ItemStack(Material.NETHERITE_SHOVEL);
      ItemMeta meta = item.getItemMeta();
      meta.displayName(Component.text("ᴄʜɪᴄᴋᴇɴ ꜱʜᴏᴠᴇʟ").color(TextColor.color(255, 255, 85))
         .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, false));
      meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
      meta.addEnchant(Enchantment.UNBREAKING, 3, true);
      meta.addEnchant(Enchantment.MENDING, 1, true);
      meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
      meta.setItemModel(ITEM_MODEL);
      meta.setEnchantmentGlintOverride(false);
      List<Component> lore = new ArrayList<>();
      for (String line : new String[]{"Efficiency V", "Unbreaking III", "Mending", "Breaks 9 Blocks at Once"}) {
         lore.add(Component.text(line).color(GRAY).decoration(TextDecoration.ITALIC, false));
      }
      lore.add(Component.text("Self Destruct:").color(DARK_GRAY).decoration(TextDecoration.ITALIC, false));
      lore.add(Component.text(formatTime(durationSeconds)).color(DARK_GRAY).decoration(TextDecoration.ITALIC, false));
      meta.lore(lore);
      PersistentDataContainer pdc = meta.getPersistentDataContainer();
      pdc.set(CHICKEN_SHOVEL_KEY, PersistentDataType.BYTE, (byte)1);
      pdc.set(DESTRUCT_TIME_KEY, PersistentDataType.LONG, System.currentTimeMillis() + durationSeconds * 1000L);
      meta.setUnbreakable(true);
      meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
      item.setItemMeta(meta);
      return item;
   }

   public static boolean isChickenShovel(ItemStack item) {
      if (item == null || item.getType() != Material.NETHERITE_SHOVEL) {
         return false;
      }
      ItemMeta meta = item.getItemMeta();
      return meta != null && meta.getPersistentDataContainer().has(CHICKEN_SHOVEL_KEY, PersistentDataType.BYTE);
   }

   private static long getDestructTime(ItemStack item) {
      ItemMeta meta = item.getItemMeta();
      if (meta == null) {
         return -1L;
      }
      Long val = meta.getPersistentDataContainer().get(DESTRUCT_TIME_KEY, PersistentDataType.LONG);
      return val != null ? val : -1L;
   }

   /** Rewrites the last lore line (the time left); returns true only if the text changed (about once a minute). */
   private static boolean updateTimerLore(ItemStack item) {
      long destructAt = getDestructTime(item);
      if (destructAt < 0L) {
         return false;
      }
      long remaining = Math.max(0L, destructAt - System.currentTimeMillis());
      ItemMeta meta = item.getItemMeta();
      List<Component> lore = meta.lore();
      if (lore == null) {
         return false;
      }
      List<Component> newLore = new ArrayList<>(lore.subList(0, Math.max(0, lore.size() - 1)));
      newLore.add(Component.text(formatTime(remaining / 1000L)).color(DARK_GRAY).decoration(TextDecoration.ITALIC, false));
      // Compare the visible text: Paper may rebuild the component's style slightly differently.
      String newTime = formatTime(remaining / 1000L);
      if (!lore.isEmpty() && newTime.equals(PlainTextComponentSerializer.plainText().serialize(lore.get(lore.size() - 1)))) {
         return false;
      }
      meta.lore(newLore);
      item.setItemMeta(meta);
      return true;
   }

   public static String formatTime(long totalSeconds) {
      if (totalSeconds <= 0L) {
         return "0m";
      }
      long days = totalSeconds / 86400L;
      long hours = totalSeconds % 86400L / 3600L;
      long minutes = totalSeconds % 3600L / 60L;
      StringBuilder sb = new StringBuilder();
      if (days > 0L) {
         sb.append(days).append("d ");
      }
      if (hours > 0L || days > 0L) {
         sb.append(hours).append("h ");
      }
      sb.append(minutes).append("m");
      return sb.toString().trim();
   }

   // ---------------------------------------------------------------- self destruct

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      player.getScheduler().run(this, t -> this.checkPlayerInventory(player), null);
   }

   private void checkPlayerInventory(Player player) {
      PlayerInventory inv = player.getInventory();
      long now = System.currentTimeMillis();
      for (int slot = 0; slot < inv.getSize(); slot++) {
         ItemStack item = inv.getItem(slot);
         if (isChickenShovel(item)) {
            long destructAt = getDestructTime(item);
            if (destructAt >= 0L) {
               if (now >= destructAt) {
                  inv.setItem(slot, null);
               } else if (updateTimerLore(item)) {
                  // Only resend the slot when the timer text changed.
                  inv.setItem(slot, item);
               }
            }
         }
      }
      ItemStack offhand = inv.getItemInOffHand();
      if (isChickenShovel(offhand)) {
         long destructAt = getDestructTime(offhand);
         if (destructAt >= 0L && now >= destructAt) {
            inv.setItemInOffHand(null);
         } else if (destructAt >= 0L && updateTimerLore(offhand)) {
            inv.setItemInOffHand(offhand);
         }
      }
   }

   // ---------------------------------------------------------------- digging

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onBlockBreak(BlockBreakEvent event) {
      Player player = event.getPlayer();
      ItemStack mainHand = player.getInventory().getItemInMainHand();
      if (!isChickenShovel(mainHand) || this.processingPlayers.contains(player)) {
         return;
      }
      Block broken = event.getBlock();
      if (!DIGGABLE.contains(broken.getType())) {
         return;
      }
      this.processingPlayers.add(player);
      try {
         for (Block b : getBlocksToBreak(player, broken)) {
            if (!b.equals(broken) && !b.getType().isAir() && DIGGABLE.contains(b.getType())) {
               b.breakNaturally(mainHand);
            }
         }
      } finally {
         this.processingPlayers.remove(player);
      }
   }

   private static List<Block> getBlocksToBreak(Player player, Block center) {
      List<Block> blocks = new ArrayList<>();
      RayTraceResult hit = player.rayTraceBlocks(6.0);
      BlockFace face = hit != null && center.equals(hit.getHitBlock()) ? hit.getHitBlockFace() : null;
      if (face == null) {
         face = player.getFacing();
      }
      if (face == BlockFace.DOWN) {
         addVerticalThreeByTwo(blocks, center, player.getFacing(), true);
      } else if (face == BlockFace.UP) {
         addVerticalThreeByTwo(blocks, center, player.getFacing(), false);
      } else if (face != BlockFace.NORTH && face != BlockFace.SOUTH) {
         for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
               blocks.add(center.getRelative(0, dy, dz));
            }
         }
      } else {
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               blocks.add(center.getRelative(dx, dy, 0));
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

   // ---------------------------------------------------------------- giving (no chat messages, just the item)

   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (!sender.isOp()) {
         return true;
      }
      if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
         sender.sendMessage(Component.text("Usage: /chicken_shovel give <player> <amount>").color(NamedTextColor.RED));
         return true;
      }
      Player target = this.getServer().getPlayer(args[1]);
      if (target == null) {
         sender.sendMessage(Component.text("Player not found: " + args[1]).color(NamedTextColor.RED));
         return true;
      }
      int amount;
      try {
         amount = Integer.parseInt(args[2]);
      } catch (NumberFormatException e) {
         sender.sendMessage(Component.text("Invalid amount: " + args[2]).color(NamedTextColor.RED));
         return true;
      }
      if (amount < 1 || amount > 64) {
         sender.sendMessage(Component.text("Amount must be between 1 and 64.").color(NamedTextColor.RED));
         return true;
      }
      int count = amount;
      Runnable give = () -> {
         for (int i = 0; i < count; i++) {
            target.getInventory().addItem(create(DEFAULT_DURATION));
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
   public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
      List<String> completions = new ArrayList<>();
      if (!sender.isOp()) {
         return completions;
      }
      if (args.length == 1) {
         if ("give".startsWith(args[0].toLowerCase())) {
            completions.add("give");
         }
      } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
         String partial = args[1].toLowerCase();
         for (Player p : this.getServer().getOnlinePlayers()) {
            if (p.getName().toLowerCase().startsWith(partial)) {
               completions.add(p.getName());
            }
         }
      } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
         completions.add("1");
         completions.add("2");
         completions.add("5");
      }
      return completions;
   }
}
