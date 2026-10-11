package com.taxsmp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;

public class ShopManager implements CommandExecutor, Listener {
   private final TaxSMP plugin;
   private static final String MINT = "§x§5§5§F§F§B§8";
   private static final String SHOP_TITLE = "§8ꜱʜᴏᴘ";
   private static final String GEAR_TITLE = "§8ꜱʜᴏᴘ - ɢᴇᴀʀ";
   private static final String END_TITLE = "§8ꜱʜᴏᴘ - ᴇɴᴅ";
   private static final String NETHER_TITLE = "§8ꜱʜᴏᴘ - ɴᴇᴛʜᴇʀ";
   private static final String FOOD_TITLE = "§8ꜱʜᴏᴘ - ғᴏᴏᴅ";
   private static final String EGGS_TITLE = "§8ꜱʜᴏᴘ - ᴇɢɢꜱ";
   private static final String REDSTONE_TITLE = "§8ꜱʜᴏᴘ - ʀᴇᴅꜱᴛᴏɴᴇ";
   private static final String GEMSHOP_TITLE = "§8ɢᴇᴍ ꜱʜᴏᴘ";
   private static final String VAULTSHOP_TITLE = "§8ɢᴇᴍ ꜱʜᴏᴘ | Vaults";
   private static final String CRATEKEYS_TITLE = "§8ɢᴇᴍ ꜱʜᴏᴘ | Crate Keys";
   private static final String CHATTAGS_TITLE = "§8ɢᴇᴍ ꜱʜᴏᴘ | Chat Tags";
   private static final String SELLWANDS_TITLE = "§8ɢᴇᴍ ꜱʜᴏᴘ | Sellwands";
   private static final int VAULT_GEM_COST = 500;
   private static final int VAULTS_BUTTON_SLOT = 10;
   private static final int CHATTAGS_BUTTON_SLOT = 12;
   private static final int CRATES_BUTTON_SLOT = 14;
   private static final int SELLWANDS_BUTTON_SLOT = 16;
   private static final int[] SELLWAND_SLOTS = new int[]{11, 13, 15};
   private static final String BUY_PREFIX = "§8ʙᴜʏɪɴɢ ";
   private static final String TOTEM_TITLE = "§8ʙᴜʏɪɴɢ ᴛᴏᴛᴇᴍ ᴏғ ᴜɴᴅʏɪɴɢ";
   private static final Map<String, Material> SINGLE_BUY = Map.of(
      "§8ʙᴜʏɪɴɢ ᴛᴏᴛᴇᴍ ᴏғ ᴜɴᴅʏɪɴɢ",
      Material.TOTEM_OF_UNDYING,
      "§8ʙᴜʏɪɴɢ " + toSmallCaps("water bucket"),
      Material.WATER_BUCKET,
      "§8ʙᴜʏɪɴɢ " + toSmallCaps("lava bucket"),
      Material.LAVA_BUCKET
   );
   private static final NamespacedKey CUSTOM_SPAWNER_TYPE_KEY = new NamespacedKey("customspawners", "spawner_type");
   private static final String SPAWNER_NAME_COLOR = "§x§F§F§D§E§2§1";
   private final Map<UUID, Long> clickCooldown = new HashMap<>();
   private final Map<UUID, Integer> buyQuantity = new HashMap<>();
   private final Map<UUID, String> lastPage = new HashMap<>();
   private static final int[] VAULT_SLOTS = new int[]{10, 11, 12, 13, 14, 15, 16, 19, 20, 21};

   public ShopManager(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public void openShop(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      this.lastPage.remove(player.getUniqueId());
      Inventory inv = Bukkit.createInventory(null, 27, "§8ꜱʜᴏᴘ");
      inv.setItem(11, this.makeCategoryItem(Material.EGG, "§eᴇɢɢꜱ"));
      inv.setItem(12, this.makeCategoryItem(Material.COOKED_BEEF, "§eғᴏᴏᴅ"));
      inv.setItem(13, this.makeCategoryItem(Material.TOTEM_OF_UNDYING, "§eɢᴇᴀʀ"));
      inv.setItem(14, this.makeCategoryItem(Material.REDSTONE, "§eʀᴇᴅꜱᴛᴏɴᴇ"));
      inv.setItem(15, this.makeCategoryItem(Material.DIAMOND, "§bɢᴇᴍ ꜱʜᴏᴘ"));
      player.openInventory(inv);
   }

   private ItemStack makeCategoryItem(Material mat, String name) {
      ItemStack item = new ItemStack(mat);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(name);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         item.setItemMeta(meta);
      }

      return item;
   }

   private void addBackButton(Inventory inv) {
      ItemStack back = new ItemStack(Material.RED_STAINED_GLASS_PANE);
      ItemMeta bm = back.getItemMeta();
      if (bm != null) {
         bm.setDisplayName("§cʙᴀᴄᴋ");
         back.setItemMeta(bm);
      }

      inv.setItem(18, com.taxsmp.ui.GuiSkin.button(back, "back"));
   }

   private void openGearPage(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      this.lastPage.put(player.getUniqueId(), "§8ꜱʜᴏᴘ - ɢᴇᴀʀ");
      Inventory inv = Bukkit.createInventory(null, 27, "§8ꜱʜᴏᴘ - ɢᴇᴀʀ");
      this.addShopItem(inv, 2, Material.ENDER_CHEST);
      this.addShopItem(inv, 3, Material.EXPERIENCE_BOTTLE);
      this.addShopItem(inv, 4, Material.ENDER_PEARL);
      this.addShopItem(inv, 5, Material.SHULKER_BOX);
      this.addShopItem(inv, 6, Material.SHULKER_SHELL);
      this.addShopItem(inv, 10, Material.OBSIDIAN);
      this.addShopItem(inv, 11, Material.COBWEB);
      this.addShopItem(inv, 12, Material.WATER_BUCKET);
      this.addShopItem(inv, 13, Material.TOTEM_OF_UNDYING);
      this.addShopItem(inv, 14, Material.LAVA_BUCKET);
      this.addShopItem(inv, 15, Material.GOLDEN_APPLE);
      this.addShopItem(inv, 16, Material.COBBLESTONE);
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private void openEndPage(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      this.lastPage.put(player.getUniqueId(), "§8ꜱʜᴏᴘ - ᴇɴᴅ");
      Inventory inv = Bukkit.createInventory(null, 27, "§8ꜱʜᴏᴘ - ᴇɴᴅ");
      this.addShopItem(inv, 9, Material.ENDER_CHEST);
      this.addShopItem(inv, 10, Material.ENDER_PEARL);
      this.addShopItemQty(inv, 11, Material.END_STONE, 16);
      this.addShopItem(inv, 12, Material.DRAGON_BREATH);
      this.addShopItem(inv, 13, Material.END_ROD);
      this.addShopItem(inv, 14, Material.CHORUS_FRUIT);
      this.addShopItem(inv, 15, Material.POPPED_CHORUS_FRUIT);
      this.addShopItem(inv, 16, Material.SHULKER_SHELL);
      this.addShopItem(inv, 17, Material.SHULKER_BOX);
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private void openNetherPage(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      this.lastPage.put(player.getUniqueId(), "§8ꜱʜᴏᴘ - ɴᴇᴛʜᴇʀ");
      Inventory inv = Bukkit.createInventory(null, 27, "§8ꜱʜᴏᴘ - ɴᴇᴛʜᴇʀ");
      this.addShopItem(inv, 9, Material.BLAZE_ROD);
      this.addShopItem(inv, 10, Material.NETHER_WART);
      this.addShopItem(inv, 11, Material.GLOWSTONE_DUST);
      this.addShopItem(inv, 12, Material.MAGMA_CREAM);
      this.addShopItem(inv, 13, Material.GHAST_TEAR);
      this.addShopItem(inv, 14, Material.QUARTZ);
      this.addShopItem(inv, 15, Material.SOUL_SAND);
      this.addShopItem(inv, 16, Material.MAGMA_BLOCK);
      this.addShopItem(inv, 17, Material.CRYING_OBSIDIAN);
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private void openFoodPage(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      this.lastPage.put(player.getUniqueId(), "§8ꜱʜᴏᴘ - ғᴏᴏᴅ");
      Inventory inv = Bukkit.createInventory(null, 27, "§8ꜱʜᴏᴘ - ғᴏᴏᴅ");
      this.addShopItem(inv, 9, Material.POTATO);
      this.addShopItem(inv, 10, Material.SWEET_BERRIES);
      this.addShopItem(inv, 11, Material.MELON_SLICE);
      this.addShopItem(inv, 12, Material.CARROT);
      this.addShopItem(inv, 13, Material.APPLE);
      this.addShopItem(inv, 14, Material.COOKED_CHICKEN);
      this.addShopItem(inv, 15, Material.COOKED_BEEF);
      this.addShopItem(inv, 16, Material.GOLDEN_CARROT);
      this.addShopItem(inv, 17, Material.GOLDEN_APPLE);
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private void openRedstonePage(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      this.lastPage.put(player.getUniqueId(), "§8ꜱʜᴏᴘ - ʀᴇᴅꜱᴛᴏɴᴇ");
      Inventory inv = Bukkit.createInventory(null, 27, "§8ꜱʜᴏᴘ - ʀᴇᴅꜱᴛᴏɴᴇ");
      this.addShopItem(inv, 2, Material.SLIME_BLOCK);
      this.addShopItem(inv, 3, Material.DROPPER);
      this.addShopItem(inv, 4, Material.TARGET);
      this.addShopItem(inv, 5, Material.NOTE_BLOCK);
      this.addShopItem(inv, 6, Material.REDSTONE_LAMP);
      this.addShopItem(inv, 9, Material.REDSTONE_TORCH);
      this.addShopItem(inv, 10, Material.REDSTONE);
      this.addShopItem(inv, 11, Material.REDSTONE_BLOCK);
      this.addShopItem(inv, 12, Material.DISPENSER);
      this.addShopItem(inv, 13, Material.OBSERVER);
      this.addShopItem(inv, 14, Material.REPEATER);
      this.addShopItem(inv, 15, Material.COMPARATOR);
      this.addShopItem(inv, 16, Material.PISTON);
      this.addShopItem(inv, 17, Material.STICKY_PISTON);
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private void openEggsPage(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      Inventory inv = Bukkit.createInventory(null, 27, "§8ꜱʜᴏᴘ - ᴇɢɢꜱ");

      for (ShopManager.EggShopSpawner spawner : ShopManager.EggShopSpawner.values()) {
         inv.setItem(spawner.slot, this.createEggShopItem(spawner));
      }

      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private ItemStack createEggShopItem(ShopManager.EggShopSpawner spawner) {
      ItemStack item = new ItemStack(Material.SPAWNER);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§x§F§F§D§E§2§1" + spawner.displayName);
         meta.setLore(List.of("§fBuy Price: §c" + spawner.eggPrice + "x Eggs"));
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         meta.getPersistentDataContainer().set(CUSTOM_SPAWNER_TYPE_KEY, PersistentDataType.STRING, spawner.spawnerType);
         item.setItemMeta(meta);
      }

      return item;
   }

   private void openGemShop(Player player) {
      this.buyQuantity.remove(player.getUniqueId());
      Inventory inv = Bukkit.createInventory(null, 27, "§8ɢᴇᴍ ꜱʜᴏᴘ");
      inv.setItem(11, this.makeVaultsCategoryItem());
      inv.setItem(13, this.makeChatTagsCategoryItem());
      inv.setItem(15, this.makeCratesCategoryItem());
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private ItemStack makeSellwandsCategoryItem() {
      ItemStack item = new ItemStack(Material.BLAZE_ROD);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§cSellwands");
         meta.setLore(List.of("§8ɢᴇᴍ ꜱʜᴏᴘ", "", "§cInformation:", "§c| Open sellwands", "§c| Pick a multiplier", "", "§6⏩ Click to Open"));
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         item.setItemMeta(meta);
      }

      return item;
   }

   private ItemStack makeChatTagsCategoryItem() {
      ItemStack item = new ItemStack(Material.NAME_TAG);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§bChat Tags");
         meta.setLore(List.of("§8ɢᴇᴍ ꜱʜᴏᴘ", "", "§bInformation:", "§b| §fOpen chat tags", "§b| §fCustomize your tag", "", "§6⏩ Click §fto Open"));
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         item.setItemMeta(meta);
      }

      return item;
   }

   private ItemStack makeCratesCategoryItem() {
      ItemStack item = new ItemStack(Material.CHEST_MINECART);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§eCrates");
         meta.setLore(List.of("§8ɢᴇᴍ ꜱʜᴏᴘ", "", "§eInformation:", "§e| §fOpen crates shop", "§e| §fKeys & rewards", "", "§6⏩ Click §fto Open"));
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         item.setItemMeta(meta);
      }

      return item;
   }

   private ItemStack makeVaultsCategoryItem() {
      ItemStack item = new ItemStack(Material.BARREL);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§eVaults");
         meta.setLore(List.of("§8ɢᴇᴍ ꜱʜᴏᴘ", "", "§eInformation:", "§e| §fOpen vaults shop", "§e| §fExtra storage", "", "§6⏩ Click §fto Open"));
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         item.setItemMeta(meta);
      }

      return item;
   }

   @EventHandler
   public void onGemShopClick(InventoryClickEvent e) {
      if (e.getView().getOriginalTitle().equals("§8ɢᴇᴍ ꜱʜᴏᴘ")) {
         if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            e.setCancelled(true);
            if (e.getClick() != ClickType.DOUBLE_CLICK && e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  int slot = e.getRawSlot();
                  if (slot == 18 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openShop(p);
                  } else if (slot == 11) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openVaultShop(p);
                  } else if (slot == 13) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openChatTagsShop(p);
                  } else if (slot == 15) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openCrateShop(p);
                  }
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   private void openCrateShop(Player player) {
      Inventory inv = Bukkit.createInventory(null, 27, "§8ɢᴇᴍ ꜱʜᴏᴘ | Crate Keys");
      int gems = this.plugin.getGemManager() != null ? this.plugin.getGemManager().getGems(player) : 0;
      this.populateCrateShop(inv, gems);
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private void populateCrateShop(Inventory inv, int gems) {
      for (ShopManager.CrateKey k : ShopManager.CrateKey.values()) {
         inv.setItem(k.slot, this.makeCrateKeyItem(k, gems));
      }
   }

   private ItemStack makeCrateKeyItem(ShopManager.CrateKey k, int gems) {
      ItemStack item = new ItemStack(k.material);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(k.color + k.display);
         List<String> lore = new ArrayList<>();
         lore.add("§8ɢᴇᴍ ꜱʜᴏᴘ");
         lore.add("");
         lore.add("§eInformation:");
         lore.add("§e| §fGrants 1 " + k.keyId + " key");
         lore.add("§e| §fUse it on the " + k.keyId + " crate");
         lore.add("");
         lore.add("§e» Cost: §f" + k.cost + " Gems");
         lore.add("");
         lore.add(gems >= k.cost ? "§e⏩ Click to Purchase" : "§cNot enough gems");
         meta.setLore(lore);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         item.setItemMeta(meta);
      }

      return item;
   }

   @EventHandler
   public void onCrateShopClick(InventoryClickEvent e) {
      if (e.getView().getOriginalTitle().equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Crate Keys")) {
         if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            e.setCancelled(true);
            if (e.getClick() != ClickType.DOUBLE_CLICK && e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  int slot = e.getRawSlot();
                  if (slot == 18 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openGemShop(p);
                  } else {
                     ShopManager.CrateKey key = ShopManager.CrateKey.fromSlot(slot);
                     if (key != null) {
                        this.buyCrateKey(p, key, e.getView().getTopInventory());
                     }
                  }
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   private void buyCrateKey(Player p, ShopManager.CrateKey key, Inventory inv) {
      GemManager gems = this.plugin.getGemManager();
      if (gems == null) {
         p.sendMessage("§cThe gem shop is currently offline.");
      } else if (Bukkit.getPluginManager().getPlugin("CrateSystem") == null) {
         p.sendMessage("§cThe crate system is not installed, so keys can't be sold right now.");
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
      } else {
         int balance = gems.getGems(p);
         if (balance < key.cost) {
            p.sendMessage("§cYou need §b" + key.cost + " gems §cfor a " + key.display + ". You have §b" + balance + "§c.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
         } else {
            gems.setGems(p.getUniqueId(), balance - key.cost);
            String name = p.getName();
            Bukkit.getGlobalRegionScheduler()
               .run(this.plugin, task -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "givekey " + key.keyId + " " + name + " 1"));
            p.sendMessage("§aPurchased a §b" + key.display + " §afor §b" + key.cost + " gems§a!");
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
            this.populateCrateShop(inv, balance - key.cost);
         }
      }
   }

   private void openSellwandsShop(Player player) {
      Inventory inv = Bukkit.createInventory(null, 27, "§8ɢᴇᴍ ꜱʜᴏᴘ | Sellwands");
      int gems = this.plugin.getGemManager() != null ? this.plugin.getGemManager().getGems(player) : 0;
      this.populateSellwandsShop(inv, gems);
      this.addBackButton(inv);
      player.openInventory(inv);
   }

   private void populateSellwandsShop(Inventory inv, int gems) {
      SellWandManager.Tier[] tiers = SellWandManager.Tier.values();

      for (int i = 0; i < SELLWAND_SLOTS.length && i < tiers.length; i++) {
         inv.setItem(SELLWAND_SLOTS[i], this.makeSellwandTierItem(tiers[i], gems));
      }
   }

   private ItemStack makeSellwandTierItem(SellWandManager.Tier tier, int gems) {
      ItemStack item = new ItemStack(Material.BLAZE_ROD);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§cSell Wand [Tier " + tier.tier + "]");
         List<String> lore = new ArrayList<>();
         lore.add("§8ɢᴇᴍ ꜱʜᴏᴘ");
         lore.add("");
         lore.add("§eInformation:");
         lore.add("§fInstantly sell all items inside");
         lore.add("§fchest using your Sellwand.");
         lore.add("");
         lore.add("§eExtra Details:");
         lore.add("§b» Multiplier: " + tier.multiplierLabel);
         lore.add("§a» Uses: " + tier.maxUses + "/" + tier.maxUses);
         lore.add("§c» Cost: " + tier.gemCost + " Gems");
         lore.add("");
         lore.add(gems >= tier.gemCost ? "§6⏩ Click to Purchase" : "§cNot enough gems");
         meta.setLore(lore);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         item.setItemMeta(meta);
      }

      return item;
   }

   @EventHandler
   public void onSellwandsClick(InventoryClickEvent e) {
      if (e.getView().getOriginalTitle().equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Sellwands")) {
         if (e.getRawSlot() >= e.getView().getTopInventory().getSize()) {
            if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
               e.setCancelled(true);
            }
         } else {
            e.setCancelled(true);
            if (e.getClick() != ClickType.DOUBLE_CLICK && e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  int slot = e.getRawSlot();
                  if (slot == 18 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openGemShop(p);
                  } else {
                     SellWandManager.Tier[] tiers = SellWandManager.Tier.values();

                     for (int i = 0; i < SELLWAND_SLOTS.length && i < tiers.length; i++) {
                        if (SELLWAND_SLOTS[i] == slot) {
                           this.buySellWand(p, tiers[i], e.getView().getTopInventory());
                           return;
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private void buySellWand(Player p, SellWandManager.Tier tier, Inventory inv) {
      GemManager gems = this.plugin.getGemManager();
      SellWandManager wands = this.plugin.getSellWandManager();
      if (gems != null && wands != null) {
         int balance = gems.getGems(p);
         if (balance < tier.gemCost) {
            p.sendMessage("§cYou need §b" + tier.gemCost + " gems §cfor a Sell Wand [Tier " + tier.tier + "]. You have §b" + balance + "§c.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
         } else if (p.getInventory().firstEmpty() == -1) {
            p.sendMessage("§cInventory full!");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
         } else {
            gems.setGems(p.getUniqueId(), balance - tier.gemCost);
            p.getInventory().addItem(new ItemStack[]{wands.createWand(tier)});
            p.sendMessage("§aPurchased a §cSell Wand [Tier " + tier.tier + "] §afor §b" + tier.gemCost + " gems§a!");
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
            this.populateSellwandsShop(inv, balance - tier.gemCost);
         }
      } else {
         p.sendMessage("§cThe gem shop is currently offline.");
      }
   }

   private void openChatTagsShop(Player player) {
      Inventory inv = Bukkit.createInventory(null, 54, "§8ɢᴇᴍ ꜱʜᴏᴘ | Chat Tags");
      int gems = this.plugin.getGemManager() != null ? this.plugin.getGemManager().getGems(player) : 0;
      this.populateChatTagsShop(inv, player, gems);
      ItemStack back = new ItemStack(Material.RED_STAINED_GLASS_PANE);
      ItemMeta bm = back.getItemMeta();
      if (bm != null) {
         bm.setDisplayName("§cʙᴀᴄᴋ");
         back.setItemMeta(bm);
      }

      inv.setItem(45, com.taxsmp.ui.GuiSkin.button(back, "back"));
      player.openInventory(inv);
   }

   private void populateChatTagsShop(Inventory inv, Player player, int gems) {
      ChatTagManager mgr = this.plugin.getChatTagManager();
      Set<String> owned = mgr != null ? mgr.getOwnedIds(player.getUniqueId()) : Collections.emptySet();
      ChatTag active = mgr != null ? mgr.getActive(player.getUniqueId()) : null;

      for (ChatTag tag : ChatTag.values()) {
         inv.setItem(tag.slot(), this.makeChatTagItem(tag, owned.contains(tag.id()), tag == active, gems));
      }
   }

   private ItemStack makeChatTagItem(ChatTag tag, boolean owned, boolean equipped, int gems) {
      ItemStack item = new ItemStack(Material.NAME_TAG);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(tag.display());
         List<String> lore = new ArrayList<>();
         lore.add("§8ᴄʜᴀᴛ ᴛᴀɢꜱ");
         lore.add("");
         lore.add("§bInformation:");
         lore.add("§b| §fCustom chat tag to");
         lore.add("§b| §fShow off in chat");
         lore.add("");
         if (equipped) {
            lore.add("§a✔ Equipped");
            lore.add("");
            lore.add("§e⏩ Click to Unequip");
         } else if (owned) {
            lore.add("§a✔ Owned");
            lore.add("");
            lore.add("§e⏩ Click to Equip");
         } else {
            lore.add("§b» Cost: §f250 Gems");
            lore.add("");
            lore.add(gems >= 250 ? "§e⏩ Click to Purchase" : "§cNot enough gems");
         }

         meta.setLore(lore);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         item.setItemMeta(meta);
      }

      return item;
   }

   @EventHandler
   public void onChatTagsClick(InventoryClickEvent e) {
      if (e.getView().getOriginalTitle().equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Chat Tags")) {
         if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            e.setCancelled(true);
            if (e.getClick() != ClickType.DOUBLE_CLICK && e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  int slot = e.getRawSlot();
                  if (slot == 45 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openGemShop(p);
                  } else {
                     ChatTag tag = ChatTag.fromSlot(slot);
                     if (tag != null) {
                        this.handleChatTagClick(p, tag, e.getView().getTopInventory());
                     }
                  }
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   private void handleChatTagClick(Player p, ChatTag tag, Inventory inv) {
      ChatTagManager mgr = this.plugin.getChatTagManager();
      GemManager gems = this.plugin.getGemManager();
      if (mgr != null && gems != null) {
         boolean owned = mgr.owns(p.getUniqueId(), tag);
         ChatTag active = mgr.getActive(p.getUniqueId());
         if (!owned) {
            int balance = gems.getGems(p);
            if (balance < 250) {
               p.sendMessage("§cYou need §b250 gems §cfor that chat tag. You have §b" + balance + "§c.");
               p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
               return;
            }

            gems.setGems(p.getUniqueId(), balance - 250);
            mgr.grantAndEquip(p.getUniqueId(), tag);
            p.sendMessage("§aPurchased the " + tag.display() + " §atag for §b250 gems§a!");
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
         } else if (tag == active) {
            mgr.setActive(p.getUniqueId(), null);
            p.sendMessage("§eUnequipped your chat tag.");
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         } else {
            mgr.setActive(p.getUniqueId(), tag);
            p.sendMessage("§aEquipped the " + tag.display() + " §atag!");
            p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
         }

         this.populateChatTagsShop(inv, p, gems.getGems(p));
      } else {
         p.sendMessage("§cChat tags are currently offline.");
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
      }
   }

   private void openVaultShop(Player player) {
      Inventory inv = Bukkit.createInventory(null, 36, "§8ɢᴇᴍ ꜱʜᴏᴘ | Vaults");
      int owned = this.plugin.getVaultBridge() != null ? this.plugin.getVaultBridge().effectiveVaults(player) : 0;
      int gems = this.plugin.getGemManager() != null ? this.plugin.getGemManager().getGems(player) : 0;
      this.populateVaultShop(inv, owned, gems);
      inv.setItem(27, this.makeDye(Material.RED_STAINED_GLASS_PANE, 1, "§cʙᴀᴄᴋ"));
      player.openInventory(inv);
   }

   private void populateVaultShop(Inventory inv, int owned, int gems) {
      for (int i = 0; i < VAULT_SLOTS.length; i++) {
         inv.setItem(VAULT_SLOTS[i], this.makeVaultEntry(i + 1, owned, gems));
      }
   }

   private int vaultNumberForSlot(int slot) {
      for (int i = 0; i < VAULT_SLOTS.length; i++) {
         if (VAULT_SLOTS[i] == slot) {
            return i + 1;
         }
      }

      return -1;
   }

   private ItemStack makeVaultEntry(int vaultNum, int owned, int gems) {
      ItemStack item = new ItemStack(Material.BARREL);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         List<String> lore = new ArrayList<>();
         lore.add("§8ɢᴇᴍ ꜱʜᴏᴘ");
         lore.add("");
         if (vaultNum <= owned) {
            meta.setDisplayName("§aVault " + vaultNum);
            lore.add("§a✔ Already Owned");
         } else if (vaultNum == owned + 1) {
            meta.setDisplayName("§aVault " + vaultNum);
            lore.add("§aInformation:");
            lore.add("§8| §7Extra vault storage");
            lore.add("§8| §7Adds Vault " + vaultNum);
            lore.add("");
            lore.add("§e» §aCost: §e500 Gems");
            lore.add("");
            lore.add(gems >= 500 ? "§6⏩ Click §fto Purchase" : "§cNot enough gems");
         } else {
            meta.setDisplayName("§7Vault " + vaultNum);
            lore.add("§cLocked:");
            lore.add("§8| §fYou must purchase §cVault " + (vaultNum - 1) + " §ffirst");
            lore.add("");
            lore.add("§cVault " + (vaultNum - 1) + " Required");
         }

         meta.setLore(lore);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES});
         item.setItemMeta(meta);
      }

      return item;
   }

   @EventHandler
   public void onVaultShopClick(InventoryClickEvent e) {
      if (e.getView().getOriginalTitle().equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Vaults")) {
         if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            e.setCancelled(true);
            if (e.getClick() != ClickType.DOUBLE_CLICK && e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  int slot = e.getRawSlot();
                  if (slot == 27 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openGemShop(p);
                  } else {
                     int vaultNum = this.vaultNumberForSlot(slot);
                     if (vaultNum != -1) {
                        int owned = this.plugin.getVaultBridge() != null ? this.plugin.getVaultBridge().effectiveVaults(p) : 0;
                        if (vaultNum <= owned) {
                           p.sendMessage("§eYou already own Vault " + vaultNum + ".");
                           p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                        } else if (vaultNum == owned + 1) {
                           this.buyVault(p, e.getView().getTopInventory());
                        } else {
                           p.sendMessage("§cYou must purchase Vault " + (vaultNum - 1) + " first.");
                           p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                        }
                     }
                  }
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   private void buyVault(Player p, Inventory inv) {
      GemManager gems = this.plugin.getGemManager();
      VaultBridge vault = this.plugin.getVaultBridge();
      if (gems == null || vault == null) {
         p.sendMessage("§cThe gem shop is currently offline.");
      } else if (!vault.isAvailable()) {
         p.sendMessage("§cThe PlayerVaults plugin is not installed, so vaults can't be sold right now.");
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
      } else {
         int owned = vault.effectiveVaults(p);
         if (owned >= 10) {
            p.sendMessage("§cYou already own the maximum of 10 player vaults.");
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            this.populateVaultShop(inv, owned, gems.getGems(p));
         } else {
            int balance = gems.getGems(p);
            if (balance < 500) {
               p.sendMessage("§cYou need §b500 gems §cto buy a vault. You have §b" + balance + "§c.");
               p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
            } else {
               gems.setGems(p.getUniqueId(), balance - 500);
               int newVault = vault.grantNextVault(p);
               p.sendMessage("§aPurchased §bVault " + newVault + " §afor §b500 gems§a! Open it with §f/pv " + newVault);
               p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
               p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
               p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
               this.populateVaultShop(inv, newVault, balance - 500);
            }
         }
      }
   }

   private void addShopItem(Inventory inv, int slot, Material mat) {
      ItemStack item = new ItemStack(mat);
      double price = PriceManager.getShopPrice(mat);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setLore(Collections.singletonList("§7Price: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(price)));
         item.setItemMeta(meta);
      }

      inv.setItem(slot, item);
   }

   private void addShopItemQty(Inventory inv, int slot, Material mat, int qty) {
      ItemStack item = new ItemStack(mat, qty);
      double price = PriceManager.getShopPrice(mat) * qty;
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setLore(Collections.singletonList("§7Price: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(price)));
         item.setItemMeta(meta);
      }

      inv.setItem(slot, item);
   }

   private void addSlowFallingArrowItem(Inventory inv, int slot) {
      ItemStack item = new ItemStack(Material.TIPPED_ARROW);
      PotionMeta pm = (PotionMeta)item.getItemMeta();
      if (pm != null) {
         pm.setBasePotionType(PotionType.SLOW_FALLING);
         double price = PriceManager.getShopPrice(Material.TIPPED_ARROW);
         pm.setLore(Collections.singletonList("§7Price: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(price)));
         pm.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         item.setItemMeta(pm);
      }

      inv.setItem(slot, item);
   }

   private ItemStack createSlowFallingArrow(int amount) {
      ItemStack item = new ItemStack(Material.TIPPED_ARROW, amount);
      PotionMeta pm = (PotionMeta)item.getItemMeta();
      if (pm != null) {
         pm.setBasePotionType(PotionType.SLOW_FALLING);
         item.setItemMeta(pm);
      }

      return item;
   }

   @EventHandler
   public void onShopClick(InventoryClickEvent e) {
      if (e.getView().getOriginalTitle().equals("§8ꜱʜᴏᴘ")) {
         if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            e.setCancelled(true);
            if (e.getClick() != ClickType.DOUBLE_CLICK && e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                  switch (e.getRawSlot()) {
                     case 11:
                        this.openEggsPage(p);
                        break;
                     case 12:
                        this.openFoodPage(p);
                        break;
                     case 13:
                        this.openGearPage(p);
                        break;
                     case 14:
                        this.openRedstonePage(p);
                        break;
                     case 15:
                        this.openGemShop(p);
                  }
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   private boolean isCategoryPage(String title) {
      return title.equals("§8ꜱʜᴏᴘ - ᴇɴᴅ")
         || title.equals("§8ꜱʜᴏᴘ - ɴᴇᴛʜᴇʀ")
         || title.equals("§8ꜱʜᴏᴘ - ɢᴇᴀʀ")
         || title.equals("§8ꜱʜᴏᴘ - ғᴏᴏᴅ")
         || title.equals("§8ꜱʜᴏᴘ - ʀᴇᴅꜱᴛᴏɴᴇ");
   }

   @EventHandler
   public void onCategoryPageClick(InventoryClickEvent e) {
      String title = e.getView().getOriginalTitle();
      if (this.isCategoryPage(title)) {
         if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            e.setCancelled(true);
            if (e.getClick() != ClickType.DOUBLE_CLICK && e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  int slot = e.getRawSlot();
                  if (slot == 18 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openShop(p);
                  } else {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     if (SINGLE_BUY.containsValue(clicked.getType()) && title.equals("§8ꜱʜᴏᴘ - ɢᴇᴀʀ")) {
                        this.openSingleConfirm(p, clicked.getType());
                     } else if (clicked.getType() == Material.END_STONE && title.equals("§8ꜱʜᴏᴘ - ᴇɴᴅ")) {
                        this.openBuyGUI(p, Material.END_STONE, 16);
                     } else {
                        this.openBuyGUI(p, clicked.getType(), 1);
                     }
                  }
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   @EventHandler
   public void onEggsClick(InventoryClickEvent e) {
      if (e.getView().getOriginalTitle().equals("§8ꜱʜᴏᴘ - ᴇɢɢꜱ")) {
         if (e.getRawSlot() < e.getView().getTopInventory().getSize()) {
            e.setCancelled(true);
            if (e.getWhoClicked() instanceof Player p) {
               ItemStack clicked = e.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  if (e.getRawSlot() == 18 && clicked.getType() == Material.RED_STAINED_GLASS_PANE) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.openShop(p);
                  } else {
                     ShopManager.EggShopSpawner spawner = ShopManager.EggShopSpawner.fromSlot(e.getRawSlot());
                     if (spawner != null && clicked.getType() == Material.SPAWNER) {
                        if (Bukkit.getPluginManager().getPlugin("CustomSpawners") == null) {
                           p.sendMessage("§cCustomSpawners is not installed, so spawner purchases are unavailable right now.");
                           p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                        } else {
                           EggManager eggManager = this.plugin.getEggManager();
                           if (eggManager == null) {
                              p.sendMessage("§cEgg system is currently offline.");
                              p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                           } else {
                              int eggs = eggManager.getEggs(p);
                              if (eggs < spawner.eggPrice) {
                                 p.sendMessage("§cYou need §c" + spawner.eggPrice + " eggs §cto buy this spawner.");
                                 p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                              } else {
                                 ItemStack reward = this.createPurchasedSpawner(spawner);
                                 if (!this.canFitItem(p, reward)) {
                                    p.sendMessage("§cInventory full!");
                                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                                 } else {
                                    eggManager.setEggs(p, eggs - spawner.eggPrice);
                                    p.getInventory().addItem(new ItemStack[]{reward});
                                    p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
                                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   private ItemStack createPurchasedSpawner(ShopManager.EggShopSpawner spawner) {
      ItemStack item = new ItemStack(Material.SPAWNER);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName("§x§F§F§D§E§2§1" + spawner.displayName);
         meta.setLore(null);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         meta.getPersistentDataContainer().set(CUSTOM_SPAWNER_TYPE_KEY, PersistentDataType.STRING, spawner.spawnerType);
         item.setItemMeta(meta);
      }

      return item;
   }

   private boolean canFitItem(Player player, ItemStack item) {
      for (ItemStack content : player.getInventory().getStorageContents()) {
         if (content == null || content.getType().isAir()) {
            return true;
         }

         if (content.isSimilar(item) && content.getAmount() < content.getMaxStackSize()) {
            return true;
         }
      }

      return false;
   }

   @EventHandler
   public void onProtectedInventoryDrag(InventoryDragEvent e) {
      String title = e.getView().getOriginalTitle();
      if (title.equals("§8ꜱʜᴏᴘ")
         || this.isCategoryPage(title)
         || title.equals("§8ꜱʜᴏᴘ - ᴇɢɢꜱ")
         || title.equals("§8ɢᴇᴍ ꜱʜᴏᴘ")
         || title.equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Vaults")
         || title.equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Crate Keys")
         || title.equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Chat Tags")
         || title.equals("§8ɢᴇᴍ ꜱʜᴏᴘ | Sellwands")
         || title.startsWith("§8ʙᴜʏɪɴɢ ")
         || title.equals("§8ʙᴜʏɪɴɢ ᴛᴏᴛᴇᴍ ᴏғ ᴜɴᴅʏɪɴɢ")) {
         for (int slot : e.getRawSlots()) {
            if (slot < e.getView().getTopInventory().getSize()) {
               e.setCancelled(true);
               break;
            }
         }
      }
   }

   private int getMaxQty(Material mat) {
      return mat == Material.ENDER_PEARL ? 16 : 64;
   }

   private void openBuyGUI(Player p, Material mat, int quantity) {
      int maxQty = this.getMaxQty(mat);
      quantity = Math.max(1, Math.min(quantity, maxQty));
      this.buyQuantity.put(p.getUniqueId(), quantity);
      String title = "§8ʙᴜʏɪɴɢ " + toSmallCaps(this.prettyName(mat));
      Inventory inv = Bukkit.createInventory(null, 27, title);
      this.updateBuyContents(inv, mat, quantity);
      inv.setItem(21, this.makeDye(Material.RED_STAINED_GLASS_PANE, 1, "§cᴄᴀɴᴄᴇʟ"));
      p.openInventory(inv);
   }

   private void updateBuyContents(Inventory inv, Material mat, int quantity) {
      int maxQty = this.getMaxQty(mat);
      double totalPrice = PriceManager.getShopPrice(mat) * quantity;
      inv.setItem(9, null);
      inv.setItem(10, null);
      inv.setItem(11, null);
      inv.setItem(15, null);
      inv.setItem(16, null);
      inv.setItem(17, null);
      if (quantity > 1) {
         inv.setItem(9, this.makeDye(Material.RED_STAINED_GLASS_PANE, maxQty, "§c-" + maxQty));
         inv.setItem(10, this.makeDye(Material.RED_STAINED_GLASS_PANE, 10, "§c-10"));
         inv.setItem(11, this.makeDye(Material.RED_STAINED_GLASS_PANE, 1, "§c-1"));
      }

      ItemStack preview;
      if (mat == Material.TIPPED_ARROW) {
         preview = this.createSlowFallingArrow(Math.min(quantity, mat.getMaxStackSize()));
      } else {
         preview = new ItemStack(mat, Math.min(quantity, mat.getMaxStackSize()));
      }

      ItemMeta pm = preview.getItemMeta();
      if (pm != null) {
         if (mat == Material.TIPPED_ARROW) {
            pm.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         }

         preview.setItemMeta(pm);
      }

      inv.setItem(13, preview);
      if (quantity < maxQty) {
         inv.setItem(15, this.makeDye(Material.LIME_STAINED_GLASS_PANE, 1, "§a+1"));
         inv.setItem(16, this.makeDye(Material.LIME_STAINED_GLASS_PANE, 10, "§a+10"));
         inv.setItem(17, this.makeDye(Material.LIME_STAINED_GLASS_PANE, maxQty, "§a+" + maxQty));
      }

      ItemStack confirm = this.makeDye(Material.LIME_STAINED_GLASS_PANE, 1, "§aᴄᴏɴғɪʀᴍ ᴘᴜʀᴄʜᴀꜱᴇ");
      ItemMeta cm = confirm.getItemMeta();
      if (cm != null) {
         cm.setLore(Collections.singletonList("§7Total: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(totalPrice)));
         confirm.setItemMeta(cm);
      }

      inv.setItem(23, confirm);
   }

   private void openSingleConfirm(Player p, Material mat) {
      String title = "§8ʙᴜʏɪɴɢ ᴛᴏᴛᴇᴍ ᴏғ ᴜɴᴅʏɪɴɢ";

      for (Entry<String, Material> entry : SINGLE_BUY.entrySet()) {
         if (entry.getValue() == mat) {
            title = entry.getKey();
         }
      }

      Inventory inv = Bukkit.createInventory(null, 27, title);
      double price = PriceManager.getShopPrice(mat);
      ItemStack preview = new ItemStack(mat, 1);
      ItemMeta pm = preview.getItemMeta();
      if (pm != null) {
         pm.setLore(Arrays.asList("§7Price: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(price)));
         preview.setItemMeta(pm);
      }

      inv.setItem(13, preview);
      inv.setItem(21, this.makeDye(Material.RED_STAINED_GLASS_PANE, 1, "§cᴄᴀɴᴄᴇʟ"));
      ItemStack confirm = this.makeDye(Material.LIME_STAINED_GLASS_PANE, 1, "§aᴄᴏɴғɪʀᴍ ᴘᴜʀᴄʜᴀꜱᴇ");
      ItemMeta cm = confirm.getItemMeta();
      if (cm != null) {
         cm.setLore(Collections.singletonList("§7Total: §x§5§5§F§F§B§8$" + PriceManager.formatPrice(price)));
         confirm.setItemMeta(cm);
      }

      inv.setItem(23, confirm);
      p.openInventory(inv);
   }

   @EventHandler
   public void onTotemConfirmClick(InventoryClickEvent e) {
      Material single = SINGLE_BUY.get(e.getView().getOriginalTitle());
      if (single != null && e.getWhoClicked() instanceof Player p) {
         int slot = e.getRawSlot();
         if (slot < e.getView().getTopInventory().getSize()) {
            if (slot < 0) {
               if (e.getClick() != ClickType.DROP && e.getClick() != ClickType.CONTROL_DROP) {
                  e.setCancelled(true);
               }
            } else {
               e.setCancelled(true);
               if (slot == 21) {
                  p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                  this.openGearPage(p);
               } else if (slot == 23) {
                  if (this.blockedByCombat(p)) {
                     p.closeInventory();
                     return;
                  }

                  Material mat = single;
                  double price = PriceManager.getShopPrice(mat);
                  SimpleEconomy econ = this.plugin.getEconomy();
                  if (econ == null) {
                     p.sendMessage("§cEconomy system is currently offline.");
                     p.closeInventory();
                     return;
                  }

                  if (econ.getBalance(p) < price) {
                     p.sendMessage("§cYou cannot afford this!");
                     p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                     return;
                  }

                  if (p.getInventory().firstEmpty() == -1) {
                     p.sendMessage("§cInventory full!");
                     p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                     return;
                  }

                  econ.withdrawPlayer(p, price);
                  p.getInventory().addItem(new ItemStack[]{new ItemStack(mat, 1)});
                  p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                  p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
                  p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
                  this.plugin.addPlayerTotal(p.getUniqueId(), "shop_spent", price);
               }
            }
         } else if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         }
      }
   }

   private ItemStack makeDye(Material mat, int amount, String name) {
      return com.taxsmp.ui.GuiSkin.autoButton(this.makePlainDye(mat, amount, name));
   }

   private ItemStack makePlainDye(Material mat, int amount, String name) {
      ItemStack item = new ItemStack(mat, amount);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(name);
         item.setItemMeta(meta);
      }

      return item;
   }

   private void returnToLastPage(Player p) {
      String page = this.lastPage.getOrDefault(p.getUniqueId(), "§8ꜱʜᴏᴘ - ɢᴇᴀʀ");
      if (page.equals("§8ꜱʜᴏᴘ - ᴇɴᴅ")) {
         this.openEndPage(p);
      } else if (page.equals("§8ꜱʜᴏᴘ - ɴᴇᴛʜᴇʀ")) {
         this.openNetherPage(p);
      } else if (page.equals("§8ꜱʜᴏᴘ - ғᴏᴏᴅ")) {
         this.openFoodPage(p);
      } else if (page.equals("§8ꜱʜᴏᴘ - ʀᴇᴅꜱᴛᴏɴᴇ")) {
         this.openRedstonePage(p);
      } else {
         this.openGearPage(p);
      }
   }

   @EventHandler
   public void onBuyClick(InventoryClickEvent e) {
      String title = e.getView().getOriginalTitle();
      if (title.startsWith("§8ʙᴜʏɪɴɢ ") && !SINGLE_BUY.containsKey(title) && e.getWhoClicked() instanceof Player p) {
         int slot = e.getRawSlot();
         if (slot >= e.getView().getTopInventory().getSize()) {
            if (e.isShiftClick() || e.getClick() == ClickType.DOUBLE_CLICK) {
               e.setCancelled(true);
            }
         } else if (e.getClick() == ClickType.DOUBLE_CLICK) {
            e.setCancelled(true);
         } else if (slot < 0) {
            if (e.getClick() != ClickType.DROP && e.getClick() != ClickType.CONTROL_DROP) {
               e.setCancelled(true);
            }
         } else {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked != null && !clicked.getType().isAir()) {
               ItemStack preview = e.getView().getTopInventory().getItem(13);
               if (preview != null && !preview.getType().isAir()) {
                  Material mat = preview.getType();
                  int qty = this.buyQuantity.getOrDefault(p.getUniqueId(), 1);
                  if (slot == 9 || slot == 10 || slot == 11 || slot == 15 || slot == 16 || slot == 17) {
                     int maxQty = this.getMaxQty(mat);
                     if (slot == 9) {
                        qty -= maxQty;
                     } else if (slot == 10) {
                        qty -= 10;
                     } else if (slot == 11) {
                        qty--;
                     } else if (slot == 15) {
                        qty++;
                     } else if (slot == 16) {
                        qty += 10;
                     } else {
                        qty += maxQty;
                     }

                     qty = Math.max(1, Math.min(qty, maxQty));
                     this.buyQuantity.put(p.getUniqueId(), qty);
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.updateBuyContents(e.getView().getTopInventory(), mat, qty);
                  } else if (slot == 21) {
                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     this.returnToLastPage(p);
                  } else if (slot == 23) {
                     if (this.blockedByCombat(p)) {
                        p.closeInventory();
                        return;
                     }

                     long now = System.currentTimeMillis();
                     if (this.clickCooldown.containsKey(p.getUniqueId()) && now - this.clickCooldown.get(p.getUniqueId()) < 20L) {
                        return;
                     }

                     double unitPrice = PriceManager.getShopPrice(mat);
                     SimpleEconomy econ = this.plugin.getEconomy();
                     if (econ == null) {
                        p.sendMessage("§cEconomy system is currently offline.");
                        p.closeInventory();
                        return;
                     }

                     int maxStack = mat.getMaxStackSize();
                     int availableSpace = 0;

                     for (ItemStack is : p.getInventory().getStorageContents()) {
                        if (is != null && !is.getType().isAir()) {
                           if (is.getType() == mat) {
                              availableSpace += maxStack - is.getAmount();
                           }
                        } else {
                           availableSpace += maxStack;
                        }
                     }

                     if (availableSpace <= 0) {
                        p.sendMessage("§cInventory full!");
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                        return;
                     }

                     int buyQty = Math.min(qty, availableSpace);
                     double totalPrice = unitPrice * buyQty;
                     if (econ.getBalance(p) < totalPrice) {
                        p.sendMessage("§cYou cannot afford this! Need §6$" + PriceManager.formatPrice(totalPrice));
                        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
                        return;
                     }

                     this.clickCooldown.put(p.getUniqueId(), now);
                     econ.withdrawPlayer(p, totalPrice);
                     int remaining = buyQty;

                     while (remaining > 0) {
                        int give = Math.min(remaining, maxStack);
                        ItemStack toGive = mat == Material.TIPPED_ARROW ? this.createSlowFallingArrow(give) : new ItemStack(mat, give);
                        Map<Integer, ItemStack> leftovers = p.getInventory().addItem(new ItemStack[]{toGive});

                        for (ItemStack drop : leftovers.values()) {
                           p.getWorld().dropItemNaturally(p.getLocation(), drop);
                        }

                        remaining -= give;
                     }

                     p.playSound(p.getLocation(), Sound.BLOCK_CHERRY_WOOD_BUTTON_CLICK_ON, SoundCategory.AMBIENT, 2.0F, 1.0F);
                     p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.2F);
                     p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.AMBIENT, 1.0F, 1.0F);
                     this.plugin.addPlayerTotal(p.getUniqueId(), "shop_spent", totalPrice);
                  }
               }
            }
         }
      }
   }

   private boolean blockedByCombat(Player p) {
      CombatManager combat = this.plugin.getCombatManager();
      if (combat != null && combat.isInCombat(p)) {
         p.sendMessage("§cYou cannot use /shop while in combat.");
         p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0F, 1.0F);
         return true;
      } else {
         return false;
      }
   }

   private String prettyName(Material m) {
      return m == Material.TIPPED_ARROW ? "arrow of slow falling" : m.name().toLowerCase().replace('_', ' ');
   }

   private static String toSmallCaps(String s) {
      StringBuilder sb = new StringBuilder(s.length());

      for (char c : s.toCharArray()) {
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

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p) {
         if (this.blockedByCombat(p)) {
            return true;
         }

         this.openShop(p);
      }

      return true;
   }

   private enum CrateKey {
      COMMON("Common", "Common Key", Material.TRIPWIRE_HOOK, "§f", 100, 11),
      SPAWNER("Spawner", "Spawner Key", Material.SPAWNER, "§a", 250, 12),
      CRIMSON("Crimson", "Crimson Key", Material.CRIMSON_FUNGUS, "§c", 500, 13),
      CHICKEN("Chicken", "Chicken Key", Material.EGG, "§e", 750, 14),
      TAX("Tax", "Tax Key", Material.GOLD_INGOT, "§6", 1000, 15);

      final String keyId;
      final String display;
      final Material material;
      final String color;
      final int cost;
      final int slot;

      CrateKey(String keyId, String display, Material material, String color, int cost, int slot) {
         this.keyId = keyId;
         this.display = display;
         this.material = material;
         this.color = color;
         this.cost = cost;
         this.slot = slot;
      }

      static ShopManager.CrateKey fromSlot(int slot) {
         for (ShopManager.CrateKey k : values()) {
            if (k.slot == slot) {
               return k;
            }
         }

         return null;
      }
   }

   private enum EggShopSpawner {
      PIG(9, "Pig", "Pig Spawner", "PIG", 250),
      COW(10, "Cow", "Cow Spawner", "COW", 350),
      ZOMBIE(11, "Zombie", "Zombie Spawner", "ZOMBIE", 400),
      SPIDER(12, "Spider", "Spider Spawner", "SPIDER", 450),
      SKELETON(13, "Skeleton", "Skeleton Spawner", "SKELETON", 500),
      CREEPER(14, "Creeper", "Creeper Spawner", "CREEPER", 650),
      ZOMBIFIED_PIGLIN(15, "Zombified Piglin", "Zombified Piglin Spawner", "ZOMBIFIED_PIGLIN", 750),
      BLAZE(16, "Blaze", "Blaze Spawner", "BLAZE", 1000),
      IRON_GOLEM(17, "Iron Golem", "Iron Golem Spawner", "IRON_GOLEM", 1500);

      private final int slot;
      private final String mobName;
      private final String displayName;
      private final String spawnerType;
      private final int eggPrice;

      EggShopSpawner(int slot, String mobName, String displayName, String spawnerType, int eggPrice) {
         this.slot = slot;
         this.mobName = mobName;
         this.displayName = displayName;
         this.spawnerType = spawnerType;
         this.eggPrice = eggPrice;
      }

      public static ShopManager.EggShopSpawner fromSlot(int slot) {
         for (ShopManager.EggShopSpawner value : values()) {
            if (value.slot == slot) {
               return value;
            }
         }

         return null;
      }
   }
}
