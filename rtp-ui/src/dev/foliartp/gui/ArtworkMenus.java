package dev.foliartp.gui;

import dev.foliartp.FoliaRTP;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Adds artwork menus while retaining FoliaRTP's existing command/teleport logic. */
public final class ArtworkMenus implements Listener {
   private final FoliaRTP plugin;
   private ArtworkMenus(FoliaRTP plugin) { this.plugin = plugin; }

   public static void register(FoliaRTP plugin) {
      Bukkit.getPluginManager().registerEvents(new ArtworkMenus(plugin), plugin);
   }

   private static boolean isBedrock(Player player) {
      if (player.getUniqueId().getMostSignificantBits() == 0L) return true;
      try {
         Class<?> api = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
         Object instance = api.getMethod("getInstance").invoke(null);
         return (Boolean)api.getMethod("isFloodgatePlayer", java.util.UUID.class).invoke(instance, player.getUniqueId());
      } catch (ReflectiveOperationException | LinkageError ignored) { return false; }
   }

   public static void open(Player player, FoliaRTP plugin, String dimension) {
      boolean nativeMenu = isBedrock(player) || !plugin.getConfig().getBoolean("rtp.artwork-menu", true);
      boolean root = "root".equals(dimension);
      int size = nativeMenu ? 27 : 45;
      Component title = nativeMenu ? Component.text(root ? "Random Teleport" : "Select Region", NamedTextColor.DARK_GRAY)
         : Component.text("\uE143" + (char)(root ? 0xE170 : "end".equals(dimension) ? 0xE172 : 0xE171), NamedTextColor.WHITE)
            .font(Key.key("taxsmp:gui")).shadowColor(ShadowColor.none());
      Holder holder = new Holder(dimension, nativeMenu);
      Inventory inventory = Bukkit.createInventory(holder, size, title);
      holder.inventory = inventory;
      if (root) {
         if (nativeMenu) {
            inventory.setItem(11, button(Material.MOSS_BLOCK, "Overworld", "rtp/overworld", "Select EU Central or NA East"));
            inventory.setItem(13, button(Material.NETHER_WART_BLOCK, "Nether", "rtp/nether", "Select EU Central or NA East"));
            inventory.setItem(15, button(Material.END_STONE_BRICKS, "End", "rtp/end", "Select EU Central"));
         } else {
            for (int slot = 0; slot < size; slot++) {
               String world = ArtworkLayout.dimension(slot, false);
               String label = switch (world) { case "overworld" -> "Overworld"; case "nether" -> "Nether"; default -> "End"; };
               inventory.setItem(slot, button(Material.PAPER, label, "rtp/hit", "end".equals(world) ? "Select EU Central" : "Select EU Central or NA East"));
            }
         }
      } else {
         if (nativeMenu) {
            inventory.setItem(11, button(Material.MOSS_BLOCK, "EU Central", "rtp/region_eu", "Random teleport in the " + dimension));
            if (!"end".equals(dimension)) inventory.setItem(15, button(Material.MOSS_BLOCK, "NA East", "rtp/region_na", "Random teleport in the " + dimension));
         } else {
            for (int slot = 0; slot < size; slot++) {
               String region = ArtworkLayout.region(slot, false, dimension);
               if (region != null) inventory.setItem(slot, button(Material.PAPER, "eu".equals(region) ? "EU Central" : "NA East", "rtp/hit", "Random teleport in the " + dimension));
            }
         }
      }
      player.openInventory(inventory);
   }

   private static ItemStack button(Material material, String label, String model, String hint) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      meta.displayName(Component.text(label, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
      meta.lore(List.of(Component.text(hint, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
      meta.setItemModel(NamespacedKey.fromString("taxsmp:" + model));
      item.setItemMeta(meta);
      return item;
   }

   @EventHandler
   public void click(InventoryClickEvent event) {
      if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) return;
      event.setCancelled(true);
      if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0
         || event.getRawSlot() >= event.getView().getTopInventory().getSize()) return;
      int slot = event.getRawSlot();
      if ("root".equals(holder.dimension)) {
         String world = ArtworkLayout.dimension(slot, holder.nativeMenu);
         if (world == null) return;
         player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1, 1);
         open(player, plugin, world);
      } else {
         String region = ArtworkLayout.region(slot, holder.nativeMenu, holder.dimension);
         if (region != null) execute(player, holder.dimension, region);
      }
   }

   private void execute(Player player, String dimension, String region) {
      player.closeInventory();
      PluginCommand command = plugin.getCommand("rtp");
      if (command != null && command.getExecutor() != null) {
         // The original executor enforces combat, permission, NA closure and cooldowns,
         // chooses local/cross-server teleporting and already routes the End to EU.
         command.getExecutor().onCommand(player, command, "rtp", ArtworkLayout.command(dimension, region));
      }
   }

   @EventHandler
   public void drag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder) event.setCancelled(true);
   }

   private static final class Holder implements InventoryHolder {
      private final String dimension;
      private final boolean nativeMenu;
      private Inventory inventory;
      private Holder(String dimension, boolean nativeMenu) { this.dimension = dimension; this.nativeMenu = nativeMenu; }
      public Inventory getInventory() { return inventory; }
   }
}
