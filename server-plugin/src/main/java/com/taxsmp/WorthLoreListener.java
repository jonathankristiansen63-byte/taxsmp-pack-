package com.taxsmp;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType.Play.Server;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

public class WorthLoreListener implements Listener {
   private static final String WORTH_PREFIX = "Worth:";
   private static final String WORTH_LABEL_COLOR = "§7";
   private static final String WORTH_AMOUNT_COLOR = hexColor("55FFB8");
   private static final int MAX_SHULKER_DEPTH = 8;
   private static final boolean DEBUG = false;
   private final TaxSMP plugin;
   private final Set<UUID> worthEnabled = ConcurrentHashMap.newKeySet();
   private final Map<UUID, Integer> guiTopSize = new ConcurrentHashMap<>();
   private final Map<UUID, Integer> settleGen = new ConcurrentHashMap<>();
   private static final long SETTLE_REFRESH_TICKS = 1L;

   public WorthLoreListener(TaxSMP plugin) {
      this.plugin = plugin;
      TaxSMP.registerSettingsHook((playerId, key, enabled) -> {
         if (key.equals("worth_display")) {
            Player player = Bukkit.getPlayer(playerId);
            if (enabled && canShowWorth(player)) {
               this.worthEnabled.add(playerId);
            } else {
               this.worthEnabled.remove(playerId);
            }

            if (player != null) {
               this.refreshSoon(player);
            }
         }
      });

      for (Player online : Bukkit.getOnlinePlayers()) {
         this.updateWorthFlag(online);
         this.refreshSoon(online);
      }

      this.registerPacketListener();
   }

   private static boolean canShowWorth(Player player) {
      return player != null && player.getGameMode() != GameMode.CREATIVE;
   }

   private void updateWorthFlag(Player player) {
      if (player != null) {
         if (this.plugin.isWorthDisplayEnabledFor(player) && canShowWorth(player)) {
            this.worthEnabled.add(player.getUniqueId());
         } else {
            this.worthEnabled.remove(player.getUniqueId());
         }
      }
   }

   @EventHandler
   public void onGameModeChange(PlayerGameModeChangeEvent event) {
      Player player = event.getPlayer();
      if (event.getNewGameMode() == GameMode.CREATIVE) {
         this.worthEnabled.remove(player.getUniqueId());
      } else if (this.plugin.isWorthDisplayEnabledFor(player)) {
         this.worthEnabled.add(player.getUniqueId());
      }

      this.refreshSoon(player);
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.updateWorthFlag(player);
      this.refreshSoon(player);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      UUID id = event.getPlayer().getUniqueId();
      this.worthEnabled.remove(id);
      this.settleGen.remove(id);
      this.guiTopSize.remove(id);
   }

   @EventHandler
   public void onInventoryOpen(InventoryOpenEvent event) {
      if (event.getPlayer() instanceof Player player) {
         Inventory top = event.getInventory();
         boolean virtual = top != null && top.getHolder() == null && top.getType() != InventoryType.ENDER_CHEST;
         if (virtual) {
            this.guiTopSize.put(player.getUniqueId(), top.getSize());
         } else {
            this.guiTopSize.remove(player.getUniqueId());
         }
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         this.scheduleSettleRefresh(player);
      }
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getWhoClicked() instanceof Player player) {
         this.scheduleSettleRefresh(player);
      }
   }

   @EventHandler
   public void onItemPickup(EntityPickupItemEvent event) {
      if (event.getEntity() instanceof Player player) {
         this.scheduleSettleRefresh(player);
      }
   }

   private void scheduleSettleRefresh(Player player) {
      UUID id = player.getUniqueId();
      if (this.worthEnabled.contains(id) && canShowWorth(player)) {
         int gen = this.settleGen.merge(id, 1, Integer::sum);
         player.getScheduler().runDelayed(this.plugin, task -> {
            Integer current = this.settleGen.get(id);
            if (current != null && current == gen) {
               player.updateInventory();
            }
         }, null, 1L);
      }
   }

   @EventHandler
   public void onInventoryClose(InventoryCloseEvent event) {
      if (event.getPlayer() instanceof Player player) {
         this.guiTopSize.remove(player.getUniqueId());
         this.refreshSoon(player);
      }
   }

   private void refreshSoon(Player player) {
      player.getScheduler().runDelayed(this.plugin, task -> {
         this.stripLegacyWorth(player);
         if (canShowWorth(player)) {
            player.updateInventory();
         }
      }, null, 1L);
   }

   private void stripLegacyWorth(Player player) {
      PlayerInventory inventory = player.getInventory();
      ItemStack[] contents = inventory.getContents();
      boolean modified = false;

      for (ItemStack item : contents) {
         if (this.stripWorthInPlace(item)) {
            modified = true;
         }
      }

      if (modified) {
         inventory.setContents(contents);
      }
   }

   private boolean stripWorthInPlace(ItemStack item) {
      if (item != null && !item.getType().isAir()) {
         ItemMeta meta = item.getItemMeta();
         if (meta != null && meta.hasLore() && meta.getLore() != null) {
            List<String> lore = new ArrayList<>(meta.getLore());
            boolean removed = lore.removeIf(this::isWorthLine);
            if (!removed) {
               return false;
            }

            meta.setLore(lore.isEmpty() ? null : lore);
            item.setItemMeta(meta);
            return true;
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   private void registerPacketListener() {
      if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
         this.plugin.getLogger().warning("ProtocolLib not found - item Worth display is disabled. Install ProtocolLib to enable it.");
      } else {
         try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            pm.addPacketListener(new PacketAdapter(this.plugin, ListenerPriority.HIGH, Server.WINDOW_ITEMS) {
               public void onPacketSending(PacketEvent event) {
                  Player player = event.getPlayer();
                  if (player != null) {
                     UUID id;
                     try {
                        id = player.getUniqueId();
                     } catch (UnsupportedOperationException temporaryPlayer) {
                        return;
                     }

                     boolean on = WorthLoreListener.this.worthEnabled.contains(id);
                     if (on) {
                        PacketContainer packet = event.getPacket();
                        Integer topSize = WorthLoreListener.this.guiTopSize.get(id);
                        int containerId = WorthLoreListener.readInt(packet.getIntegers(), 0, 0);
                        int from = topSize != null && containerId != 0 ? topSize : 0;
                        WorthLoreListener.this.augmentList(packet, from);
                     }
                  }
               }
            });
            this.plugin.getLogger().info("Item Worth display enabled via ProtocolLib.");
         } catch (Throwable t) {
            this.plugin.getLogger().warning("Failed to enable item Worth display: " + t);
         }
      }
   }

   private void augmentList(PacketContainer packet, int from) {
      StructureModifier<List<ItemStack>> listMod = packet.getItemListModifier();
      List<ItemStack> list = (List<ItemStack>)listMod.readSafely(0);
      if (list != null) {
         boolean changed = false;
         int priced = 0;

         for (int i = Math.max(0, from); i < list.size(); i++) {
            ItemStack augmented = this.augment(list.get(i));
            if (augmented != null) {
               list.set(i, augmented);
               changed = true;
               priced++;
            }
         }

         if (changed) {
            listMod.write(0, list);
         }
      }
   }

   private static int readInt(StructureModifier<Integer> ints, int index, int def) {
      Integer value = (Integer)ints.readSafely(index);
      return value != null ? value : def;
   }

   private ItemStack augment(ItemStack item) {
      if (item != null && !item.getType().isAir()) {
         double worth = this.calculateWorth(item);
         if (worth <= 0.0) {
            return null;
         }

         ItemStack copy = item.clone();
         ItemMeta meta = copy.getItemMeta();
         if (meta == null) {
            return null;
         }

         List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
         lore.removeIf(this::isWorthLine);
         lore.add("§7Worth: " + WORTH_AMOUNT_COLOR + "$" + formatWithCommas(worth));
         meta.setLore(lore);
         copy.setItemMeta(meta);
         return copy;
      } else {
         return null;
      }
   }

   private static String formatWithCommas(double amt) {
      return amt == (long)amt ? String.format("%,d", (long)amt) : String.format("%,.2f", amt);
   }

   private static String hexColor(String hex) {
      StringBuilder sb = new StringBuilder("§x");

      for (char c : hex.toCharArray()) {
         sb.append('§').append(c);
      }

      return sb.toString();
   }

   private boolean isWorthLine(String line) {
      String stripped = ChatColor.stripColor(line);
      return stripped != null && stripped.startsWith("Worth:");
   }

   private double calculateWorth(ItemStack item) {
      return this.calculateWorth(item, 0);
   }

   private double calculateWorth(ItemStack item, int depth) {
      if (item != null && !item.getType().isAir()) {
         double singleItemWorth = PriceManager.getSellPrice(item.getType());
         if (this.isShulker(item) && depth < 8) {
            double shulkerBoxWorth = this.getShulkerBoxWorth(item.getType());
            double contentsWorth = this.getShulkerContentsWorth(item, depth + 1);
            singleItemWorth = shulkerBoxWorth + contentsWorth;
         }

         return singleItemWorth * item.getAmount();
      } else {
         return 0.0;
      }
   }

   private boolean isShulker(ItemStack item) {
      return item.getItemMeta() instanceof BlockStateMeta bmeta && bmeta.getBlockState() instanceof ShulkerBox;
   }

   private double getShulkerContentsWorth(ItemStack shulker, int depth) {
      if (shulker.getItemMeta() instanceof BlockStateMeta bmeta && bmeta.getBlockState() instanceof ShulkerBox shulkerBox) {
         double var12 = 0.0;

         for (ItemStack inner : shulkerBox.getInventory().getContents()) {
            var12 += this.calculateWorth(inner, depth);
         }

         return var12;
      } else {
         return 0.0;
      }
   }

   private double getShulkerBoxWorth(Material material) {
      double exactWorth = PriceManager.getSellPrice(material);
      return exactWorth > 0.0 ? exactWorth : PriceManager.getSellPrice(Material.SHULKER_BOX);
   }
}
