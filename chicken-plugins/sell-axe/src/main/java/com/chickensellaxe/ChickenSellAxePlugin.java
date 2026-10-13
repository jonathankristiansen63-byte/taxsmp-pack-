package com.chickensellaxe;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The Chicken Sell Axe (a netherite axe): hitting a chest or other container sells everything in
 * it at TaxSMP's sell prices. Prices, the payout and /stats go through the TaxSMP plugin.
 */
public final class ChickenSellAxePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
   private static final long LIFETIME_MILLIS = TimeUnit.DAYS.toMillis(3L);
   private static final double PAYOUT_CAP = 1.0E10;
   private static final String DISPLAY_NAME = "&eᴄʜɪᴄᴋᴇɴ ꜱᴇʟʟ ᴀxᴇ";
   /** The TaxSMP resource pack's look for this item. */
   private static final NamespacedKey ITEM_MODEL = new NamespacedKey("taxsmp", "tool/sell_axe");
   private NamespacedKey axeKey;
   private NamespacedKey expiresKey;
   private ScheduledTask expiryTask;

   @Override
   public void onEnable() {
      this.axeKey = new NamespacedKey(this, "chicken_sell_axe");
      this.expiresKey = new NamespacedKey(this, "expires_at");
      this.getServer().getPluginManager().registerEvents(this, this);
      this.getCommand("chickensellaxe").setExecutor(this);
      this.getCommand("chickensellaxe").setTabCompleter(this);
      this.expiryTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> {
         for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(this, playerTask -> this.sweepInventory(player), null);
         }
      }, 20L, 1200L);
   }

   @Override
   public void onDisable() {
      if (this.expiryTask != null) {
         this.expiryTask.cancel();
      }
   }

   // /chickensellaxe give <player> [amount]: the item just appears, no chat messages.
   @Override
   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (args.length < 2 || !args[0].equalsIgnoreCase("give")) {
         sender.sendMessage(this.color("&eUsage: /chickensellaxe give <player> [amount]"));
         return true;
      }
      Player target = Bukkit.getPlayerExact(args[1]);
      if (target == null) {
         sender.sendMessage(this.color("&cThat player is not online."));
         return true;
      }
      int amount = 1;
      if (args.length >= 3) {
         try {
            amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
         } catch (NumberFormatException e) {
            sender.sendMessage(this.color("&cAmount must be a number from 1 to 64."));
            return true;
         }
      }

      long expiresAt = System.currentTimeMillis() + LIFETIME_MILLIS;
      ItemStack[] axes = new ItemStack[amount];
      for (int i = 0; i < amount; i++) {
         axes[i] = this.createAxe(expiresAt);
      }
      Runnable give = () -> target.getInventory().addItem(axes)
         .values()
         .forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
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
      if (args.length == 1) {
         return List.of("give");
      } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
         String prefix = args[1].toLowerCase();
         List<String> names = new ArrayList<>();
         for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase().startsWith(prefix)) {
               names.add(player.getName());
            }
         }
         return names;
      } else {
         return args.length == 3 && args[0].equalsIgnoreCase("give") ? List.of("1", "5", "10") : List.of();
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBlockBreak(BlockBreakEvent event) {
      Player player = event.getPlayer();
      ItemStack tool = player.getInventory().getItemInMainHand();
      if (!this.isChickenSellAxe(tool)) {
         return;
      }
      event.setCancelled(true);
      if (this.isExpired(tool)) {
         this.removeOneHeldAxe(player);
         player.sendMessage(this.color("&cYour Chicken Sell Axe self destructed."));
         player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
         return;
      }

      this.updateLore(tool);
      Block block = event.getBlock();
      if (block.getState() instanceof Container container) {
         SellResult result = this.sellInventory(player, container.getInventory());
         if (result.soldItems != 0) {
            String mint = "§x§5§5§F§F§B§8";
            String payoutText = mint + "+$" + this.formatPrice(result.payout);
            player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(payoutText));
            player.sendMessage(payoutText);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
            this.recordSellEarnings(player, result.soldStacks, result.payout);
            this.damageAxe(player, tool);
         }
      } else {
         player.sendMessage(this.color("&cUse this on a chest or container."));
      }
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      event.getPlayer().getScheduler().run(this, task -> this.sweepInventory(event.getPlayer()), null);
   }

   private SellResult sellInventory(Player player, Inventory inventory) {
      double total = 0.0;
      int soldItems = 0;
      Map<Integer, ItemStack> replacements = new HashMap<>();
      List<ItemStack> soldStacks = new ArrayList<>();
      ItemStack[] contents = inventory.getContents();

      for (int slot = 0; slot < contents.length; slot++) {
         ItemStack item = contents[slot];
         if (item != null && item.getType() != Material.AIR) {
            double value = this.getStackValue(player, item);
            if (value > 0.0) {
               total += value;
               soldItems += item.getAmount();
               replacements.put(slot, null);
               soldStacks.add(item.clone());
            }
         }
      }

      double payout = Math.min(total, PAYOUT_CAP);
      if (payout > 0.0 && this.deposit(player, payout)) {
         replacements.forEach(inventory::setItem);
         if (total > PAYOUT_CAP) {
            player.sendMessage(this.color("&ePayout capped at $10B."));
         }
         return new SellResult(soldItems, payout, soldStacks);
      }
      return new SellResult(0, 0.0, List.of());
   }

   private double getStackValue(Player player, ItemStack item) {
      Plugin taxSmp = Bukkit.getPluginManager().getPlugin("TaxSMP");
      if (taxSmp != null && taxSmp.isEnabled()) {
         try {
            Method appraise = taxSmp.getClass().getMethod("appraiseSellStack", UUID.class, ItemStack.class);
            return appraise.invoke(taxSmp, player.getUniqueId(), item) instanceof Number number ? number.doubleValue() : 0.0;
         } catch (ReflectiveOperationException e) {
            // older TaxSMP: fall back to the flat price list
         }
      }
      return this.legacyStackValue(item);
   }

   private double legacyStackValue(ItemStack item) {
      double total = this.getPrice(item.getType()) * item.getAmount();
      if (item.getItemMeta() instanceof BlockStateMeta meta && meta.getBlockState() instanceof ShulkerBox shulker) {
         for (ItemStack inner : shulker.getInventory().getContents()) {
            if (inner != null && inner.getType() != Material.AIR) {
               total += this.legacyStackValue(inner);
            }
         }
      }
      return total;
   }

   private boolean deposit(OfflinePlayer player, double amount) {
      Plugin taxSmp = Bukkit.getPluginManager().getPlugin("TaxSMP");
      if (taxSmp == null || !taxSmp.isEnabled()) {
         return false;
      }
      try {
         Object economy = taxSmp.getClass().getMethod("getEconomy").invoke(taxSmp);
         if (economy == null) {
            return false;
         }
         economy.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class).invoke(economy, player, amount);
         return true;
      } catch (ReflectiveOperationException e) {
         this.getLogger().warning("Could not deposit sell axe payout: " + e.getMessage());
         return false;
      }
   }

   private void recordSellEarnings(Player player, List<ItemStack> soldStacks, double amount) {
      Plugin taxSmp = Bukkit.getPluginManager().getPlugin("TaxSMP");
      if (taxSmp == null || !taxSmp.isEnabled() || amount <= 0.0) {
         return;
      }
      try {
         Method credit = taxSmp.getClass().getMethod("creditSellSale", UUID.class, List.class, double.class);
         credit.invoke(taxSmp, player.getUniqueId(), soldStacks, amount);
      } catch (ReflectiveOperationException e) {
         try {
            Method addSell = taxSmp.getClass().getMethod("addSellEarnings", UUID.class, double.class);
            addSell.invoke(taxSmp, player.getUniqueId(), amount);
         } catch (ReflectiveOperationException e2) {
            this.getLogger().warning("Could not record sell axe earnings to /stats: " + e2.getMessage());
         }
      }
   }

   private double getPrice(Material material) {
      try {
         Class<?> priceManager = Class.forName("com.taxsmp.PriceManager");
         Method getSellPrice = priceManager.getMethod("getSellPrice", Material.class);
         return getSellPrice.invoke(null, material) instanceof Number number ? number.doubleValue() : 0.0;
      } catch (ReflectiveOperationException e) {
         return 0.0;
      }
   }

   private String formatPrice(double amount) {
      try {
         Class<?> priceManager = Class.forName("com.taxsmp.PriceManager");
         Method formatPrice = priceManager.getMethod("formatPrice", double.class);
         return String.valueOf(formatPrice.invoke(null, amount));
      } catch (ReflectiveOperationException e) {
         return amount == Math.rint(amount) ? String.format("%.0f", amount) : String.format("%.2f", amount);
      }
   }

   private ItemStack createAxe(long expiresAt) {
      ItemStack axe = new ItemStack(Material.NETHERITE_AXE);
      ItemMeta meta = axe.getItemMeta();
      meta.setDisplayName(this.color(DISPLAY_NAME));
      meta.addEnchant(Enchantment.EFFICIENCY, 5, true);
      meta.addEnchant(Enchantment.UNBREAKING, 3, true);
      meta.addEnchant(Enchantment.MENDING, 1, true);
      meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
      meta.setItemModel(ITEM_MODEL);
      meta.setEnchantmentGlintOverride(false);
      PersistentDataContainer data = meta.getPersistentDataContainer();
      data.set(this.axeKey, PersistentDataType.BYTE, (byte)1);
      data.set(this.expiresKey, PersistentDataType.LONG, expiresAt);
      axe.setItemMeta(meta);
      this.updateLore(axe);
      return axe;
   }

   private void updateLore(ItemStack axe) {
      ItemMeta meta = axe.getItemMeta();
      long remaining = Math.max(0L, this.getExpiresAt(axe) - System.currentTimeMillis());
      meta.setLore(List.of(
         this.color("&7Instantly Sells All Items in A Chest"), this.color("&8Self Destruct:"), this.color("&8" + this.formatRemaining(remaining))
      ));
      axe.setItemMeta(meta);
   }

   private boolean isChickenSellAxe(ItemStack item) {
      if (item != null && item.getType() == Material.NETHERITE_AXE && item.hasItemMeta()) {
         Byte marker = item.getItemMeta().getPersistentDataContainer().get(this.axeKey, PersistentDataType.BYTE);
         return marker != null && marker == 1;
      }
      return false;
   }

   private long getExpiresAt(ItemStack item) {
      Long expiresAt = item.getItemMeta().getPersistentDataContainer().get(this.expiresKey, PersistentDataType.LONG);
      return expiresAt == null ? 0L : expiresAt;
   }

   private boolean isExpired(ItemStack item) {
      return System.currentTimeMillis() >= this.getExpiresAt(item);
   }

   private void removeOneHeldAxe(Player player) {
      PlayerInventory inventory = player.getInventory();
      ItemStack held = inventory.getItemInMainHand();
      if (held.getAmount() <= 1) {
         inventory.setItemInMainHand(null);
      } else {
         held.setAmount(held.getAmount() - 1);
      }
   }

   private void damageAxe(Player player, ItemStack axe) {
      ItemMeta meta = axe.getItemMeta();
      if (meta instanceof Damageable damageable) {
         int newDamage = damageable.getDamage() + 1;
         if (newDamage >= axe.getType().getMaxDurability()) {
            this.removeOneHeldAxe(player);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
         } else {
            damageable.setDamage(newDamage);
            axe.setItemMeta(meta);
         }
      }
   }

   private void sweepInventory(Player player) {
      Inventory inventory = player.getInventory();
      boolean removed = false;
      for (int slot = 0; slot < inventory.getSize(); slot++) {
         ItemStack item = inventory.getItem(slot);
         if (this.isChickenSellAxe(item)) {
            if (this.isExpired(item)) {
               inventory.setItem(slot, null);
               removed = true;
            } else {
               this.updateLore(item);
               inventory.setItem(slot, item);
            }
         }
      }
      if (removed) {
         player.sendMessage(this.color("&cA Chicken Sell Axe self destructed."));
         player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0F, 1.0F);
      }
   }

   private String formatRemaining(long millis) {
      long totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis);
      long days = totalMinutes / 1440L;
      long hours = totalMinutes / 60L % 24L;
      long minutes = totalMinutes % 60L;
      return days + "d " + hours + "h " + minutes + "m";
   }

   private String color(String message) {
      return ChatColor.translateAlternateColorCodes('&', message);
   }

   private record SellResult(int soldItems, double payout, List<ItemStack> soldStacks) {
   }
}
