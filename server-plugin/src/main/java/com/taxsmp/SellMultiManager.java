package com.taxsmp;

import com.taxsmp.storage.Db;
import com.taxsmp.storage.SnapshotManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.ClickEvent.Action;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

public class SellMultiManager implements CommandExecutor, Listener {
   private static final String MINT = "§x§5§5§F§F§B§8";
   private static final String MAIN_TITLE = "§0Sell Multiplier";
   private static final int BAR_SEGMENTS = 20;
   private static final int BACK_SLOT = 45;
   private static final double[] THRESHOLDS = new double[]{
      25000.0,
      150000.0,
      500000.0,
      1000000.0,
      5000000.0,
      2.5E7,
      2.5E8,
      5.5E8,
      8.5E8,
      1.0E9,
      2.0E9,
      4.0E9,
      6.0E9,
      8.0E9,
      1.0E10,
      1.5E10,
      2.0E10,
      4.0E10,
      8.0E10,
      1.6E11
   };
   private static final int MAX_TIER = THRESHOLDS.length;
   private static final int[] TIER_SLOTS = new int[]{10, 19, 28, 37, 38, 39, 30, 21, 12, 13, 14, 23, 32, 41, 42, 43, 34, 25, 16, 7};
   private final TaxSMP plugin;
   private final Db db;
   private final ConcurrentHashMap<UUID, ConcurrentHashMap<SellMultiManager.Category, SellMultiManager.CatState>> cache = new ConcurrentHashMap<>();
   private final Map<String, SellMultiManager.Category> titleToCategory = new HashMap<>();
   private final Map<String, SellMultiManager.Category> pricesTitleToCategory = new HashMap<>();
   private final ConcurrentHashMap<UUID, Integer> pricesPage = new ConcurrentHashMap<>();
   private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
   private static final Map<String, Double> ENCHANT_BASES = Map.ofEntries(
      Map.entry("mending", 18000.0),
      Map.entry("wind_burst", 11000.0),
      Map.entry("silk_touch", 9000.0),
      Map.entry("infinity", 8000.0),
      Map.entry("fortune", 6800.0),
      Map.entry("looting", 6400.0),
      Map.entry("channeling", 6000.0),
      Map.entry("multishot", 5600.0),
      Map.entry("riptide", 5200.0),
      Map.entry("swift_sneak", 4800.0),
      Map.entry("soul_speed", 4500.0),
      Map.entry("frost_walker", 4100.0),
      Map.entry("breach", 3800.0),
      Map.entry("density", 3400.0),
      Map.entry("sharpness", 3000.0),
      Map.entry("efficiency", 2900.0),
      Map.entry("protection", 2700.0),
      Map.entry("feather_falling", 2600.0),
      Map.entry("power", 2400.0),
      Map.entry("luck_of_the_sea", 2300.0),
      Map.entry("lure", 2200.0),
      Map.entry("sweeping_edge", 2100.0),
      Map.entry("impaling", 2000.0),
      Map.entry("quick_charge", 1950.0),
      Map.entry("loyalty", 1900.0),
      Map.entry("depth_strider", 1800.0),
      Map.entry("thorns", 1700.0),
      Map.entry("unbreaking", 1650.0),
      Map.entry("piercing", 1600.0),
      Map.entry("respiration", 1500.0),
      Map.entry("blast_protection", 1400.0),
      Map.entry("projectile_protection", 1350.0),
      Map.entry("fire_protection", 1300.0),
      Map.entry("fire_aspect", 1200.0),
      Map.entry("punch", 1100.0),
      Map.entry("knockback", 1050.0),
      Map.entry("flame", 1000.0),
      Map.entry("aqua_affinity", 900.0),
      Map.entry("smite", 800.0),
      Map.entry("bane_of_arthropods", 750.0),
      Map.entry("binding_curse", 400.0),
      Map.entry("vanishing_curse", 300.0)
   );

   public SellMultiManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();

      for (SellMultiManager.Category c : SellMultiManager.Category.values()) {
         this.titleToCategory.put(this.progressTitle(c), c);
         this.pricesTitleToCategory.put(this.pricesTitle(c), c);
      }

      if (this.usingDb()) {
         if (this.db.isHealthy()) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> this.initDb());
         }

         this.db.onReconnect(this::reloadFromDb);
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   private void createTable() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_sellmulti (uuid VARCHAR(36) NOT NULL, category VARCHAR(24) NOT NULL, progress DOUBLE NOT NULL DEFAULT 0, claimed INT NOT NULL DEFAULT 0, PRIMARY KEY (uuid, category))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_sellmulti table: " + e.getMessage());
      }
   }

   private void initDb() {
      this.createTable();

      for (Player p : Bukkit.getOnlinePlayers()) {
         this.loadPlayerAsync(p.getUniqueId());
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      this.loadPlayerAsync(e.getPlayer().getUniqueId());
   }

   private void loadPlayerAsync(UUID id) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            for (int attempt = 1; attempt <= 5; attempt++) {
               if (this.loadPlayerOnce(id)) {
                  this.loaded.add(id);
                  return;
               }

               try {
                  Thread.sleep(2000L * attempt);
               } catch (InterruptedException ignored) {
                  Thread.currentThread().interrupt();
                  return;
               }
            }

            this.plugin.getLogger().warning("Gave up loading sell multi data for " + id + " — their multipliers will read as unearned until they reconnect.");
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
               p.getScheduler().run(this.plugin, t -> p.sendMessage("§cYour sell multipliers couldn't load — please reconnect before selling."), null);
            }
         });
      }
   }

   private boolean loadPlayerOnce(UUID id) {
      try (
         Connection conn = this.db.getConnection();
         PreparedStatement ps = conn.prepareStatement("SELECT category, progress, claimed FROM taxsmp_sellmulti WHERE uuid = ?");
      ) {
         ps.setString(1, id.toString());

         try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
               SellMultiManager.Category cat = SellMultiManager.Category.fromKey(rs.getString("category"));
               if (cat != null) {
                  SellMultiManager.CatState st = this.state(id, cat);
                  st.progress = Math.max(st.progress, rs.getDouble("progress"));
                  st.claimed = Math.max(st.claimed, rs.getInt("claimed"));
               }
            }
         }

         return true;
      } catch (SQLException e) {
         this.plugin.getLogger().warning("Could not load sell multi data for " + id + ": " + e.getMessage());
         return false;
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.loaded.remove(e.getPlayer().getUniqueId());
   }

   private SellMultiManager.CatState state(UUID id, SellMultiManager.Category cat) {
      return this.cache.computeIfAbsent(id, k -> {
         ConcurrentHashMap<SellMultiManager.Category, SellMultiManager.CatState> map = new ConcurrentHashMap<>();
         if (!this.usingDb()) {
            for (SellMultiManager.Category c : SellMultiManager.Category.values()) {
               SellMultiManager.CatState st = new SellMultiManager.CatState();
               String base = "sellmulti." + id + "." + c.key();
               st.progress = this.plugin.getConfig().getDouble(base + ".progress", 0.0);
               st.claimed = this.plugin.getConfig().getInt(base + ".claimed", 0);
               map.put(c, st);
            }
         }

         return map;
      }).computeIfAbsent(cat, k -> new SellMultiManager.CatState());
   }

   private void persistProgressAdds(UUID id, Map<SellMultiManager.Category, Double> deltas) {
      if (this.usingDb() && !deltas.isEmpty()) {
         Map<SellMultiManager.Category, Double> copy = new EnumMap<>(SellMultiManager.Category.class);
         copy.putAll(deltas);
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> {
                  List<Object[]> rows = new ArrayList<>(copy.size());

                  for (Entry<SellMultiManager.Category, Double> e : copy.entrySet()) {
                     rows.add(new Object[]{id.toString(), e.getKey().key(), e.getValue()});
                  }

                  this.db
                     .writeDeltaBatch(
                        id,
                        "INSERT INTO taxsmp_sellmulti (uuid, category, progress, claimed) VALUES (?, ?, ?, 0) ON DUPLICATE KEY UPDATE progress = progress + VALUES(progress)",
                        rows
                     );
               }
            );
      }
   }

   private void persistClaim(UUID id, SellMultiManager.Category cat, int claimed) {
      if (this.usingDb()) {
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> this.db
                  .writeIdempotent(
                     id,
                     "INSERT INTO taxsmp_sellmulti (uuid, category, progress, claimed) VALUES (?, ?, 0, ?) ON DUPLICATE KEY UPDATE claimed = GREATEST(claimed, VALUES(claimed))",
                     id.toString(),
                     cat.key(),
                     claimed
                  )
            );
      } else {
         this.saveToConfig(id, cat);
      }
   }

   private void saveToConfig(UUID id, SellMultiManager.Category cat) {
      SellMultiManager.CatState st = this.state(id, cat);
      Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
         String base = "sellmulti." + id + "." + cat.key();
         this.plugin.getConfig().set(base + ".progress", st.progress);
         this.plugin.getConfig().set(base + ".claimed", st.claimed);
         this.plugin.saveConfig();
      });
   }

   public static double multiplierOf(int tier) {
      return 1.0 + 0.1 * tier;
   }

   public double activeMultiplier(UUID id, SellMultiManager.Category cat) {
      return multiplierOf(this.state(id, cat).claimed);
   }

   private static int reachedTier(double progress) {
      int t = 0;

      while (t < MAX_TIER && progress >= THRESHOLDS[t]) {
         t++;
      }

      return t;
   }

   public static SellMultiManager.Category categoryOf(Material var0) {
      return SellCategories.categoryOf(var0);
   }

   public static SellMultiManager.Category categoryOf$taxOrig(Material m) {
      String n = m.name();
      switch (m) {
         case ENCHANTED_BOOK:
         case BOOK:
         case WRITABLE_BOOK:
         case WRITTEN_BOOK:
         case KNOWLEDGE_BOOK:
            return SellMultiManager.Category.ENCHANTMENTS;
         default:
            switch (m) {
               case POTION:
               case SPLASH_POTION:
               case LINGERING_POTION:
               case EXPERIENCE_BOTTLE:
               case GLASS_BOTTLE:
               case OMINOUS_BOTTLE:
               case DRAGON_BREATH:
               case FERMENTED_SPIDER_EYE:
               case BREWING_STAND:
               case CAULDRON:
                  return SellMultiManager.Category.POTIONS;
               default:
                  if (!n.endsWith("_SWORD")
                     && !n.endsWith("_PICKAXE")
                     && !n.endsWith("_SHOVEL")
                     && !n.endsWith("_HOE")
                     && !n.endsWith("_AXE")
                     && !n.endsWith("_HELMET")
                     && !n.endsWith("_CHESTPLATE")
                     && !n.endsWith("_LEGGINGS")
                     && !n.endsWith("_BOOTS")
                     && !n.endsWith("_HORSE_ARMOR")) {
                     switch (m) {
                        case BOW:
                        case CROSSBOW:
                        case TRIDENT:
                        case SHIELD:
                        case ELYTRA:
                        case FISHING_ROD:
                        case FLINT_AND_STEEL:
                        case SHEARS:
                        case MACE:
                        case WOLF_ARMOR:
                        case ARROW:
                        case TIPPED_ARROW:
                        case SPECTRAL_ARROW:
                        case CARROT_ON_A_STICK:
                        case WARPED_FUNGUS_ON_A_STICK:
                        case SADDLE:
                        case TOTEM_OF_UNDYING:
                           return SellMultiManager.Category.GEAR;
                        default:
                           switch (m) {
                              case WHEAT:
                              case WHEAT_SEEDS:
                              case BEETROOT:
                              case BEETROOT_SEEDS:
                              case CARROT:
                              case POTATO:
                              case POISONOUS_POTATO:
                              case MELON_SLICE:
                              case MELON:
                              case MELON_SEEDS:
                              case PUMPKIN:
                              case CARVED_PUMPKIN:
                              case PUMPKIN_SEEDS:
                              case SUGAR_CANE:
                              case SUGAR:
                              case CACTUS:
                              case BAMBOO:
                              case COCOA_BEANS:
                              case NETHER_WART:
                              case SWEET_BERRIES:
                              case GLOW_BERRIES:
                              case CHORUS_FRUIT:
                              case POPPED_CHORUS_FRUIT:
                              case CHORUS_FLOWER:
                              case HAY_BLOCK:
                              case TORCHFLOWER_SEEDS:
                              case PITCHER_POD:
                              case NETHER_WART_BLOCK:
                              case EGG:
                              case BROWN_MUSHROOM:
                              case RED_MUSHROOM:
                              case BROWN_MUSHROOM_BLOCK:
                              case RED_MUSHROOM_BLOCK:
                              case MUSHROOM_STEM:
                              case CRIMSON_FUNGUS:
                              case WARPED_FUNGUS:
                              case CRIMSON_ROOTS:
                              case WARPED_ROOTS:
                              case KELP:
                              case SEA_PICKLE:
                              case TWISTING_VINES:
                              case WEEPING_VINES:
                              case NETHER_SPROUTS:
                                 return SellMultiManager.Category.CROPS;
                              default:
                                 if (n.startsWith("COOKED_")) {
                                    return SellMultiManager.Category.FOOD;
                                 } else {
                                    switch (m) {
                                       case BREAD:
                                       case CAKE:
                                       case COOKIE:
                                       case PUMPKIN_PIE:
                                       case MUSHROOM_STEW:
                                       case RABBIT_STEW:
                                       case BEETROOT_SOUP:
                                       case SUSPICIOUS_STEW:
                                       case BAKED_POTATO:
                                       case GOLDEN_APPLE:
                                       case ENCHANTED_GOLDEN_APPLE:
                                       case GOLDEN_CARROT:
                                       case APPLE:
                                       case HONEY_BOTTLE:
                                       case DRIED_KELP:
                                       case BEEF:
                                       case PORKCHOP:
                                       case MUTTON:
                                       case CHICKEN:
                                       case RABBIT:
                                       case COD:
                                       case SALMON:
                                       case TROPICAL_FISH:
                                       case PUFFERFISH:
                                          return SellMultiManager.Category.FOOD;
                                       default:
                                          if (!n.endsWith("_ORE") && !n.startsWith("RAW_")) {
                                             switch (m) {
                                                case ANCIENT_DEBRIS:
                                                case COAL:
                                                case CHARCOAL:
                                                case IRON_INGOT:
                                                case IRON_NUGGET:
                                                case GOLD_INGOT:
                                                case GOLD_NUGGET:
                                                case COPPER_INGOT:
                                                case DIAMOND:
                                                case EMERALD:
                                                case LAPIS_LAZULI:
                                                case REDSTONE:
                                                case QUARTZ:
                                                case NETHERITE_SCRAP:
                                                case NETHERITE_INGOT:
                                                case AMETHYST_SHARD:
                                                case AMETHYST_CLUSTER:
                                                case COAL_BLOCK:
                                                case IRON_BLOCK:
                                                case GOLD_BLOCK:
                                                case DIAMOND_BLOCK:
                                                case EMERALD_BLOCK:
                                                case LAPIS_BLOCK:
                                                case REDSTONE_BLOCK:
                                                case COPPER_BLOCK:
                                                case NETHERITE_BLOCK:
                                                case GLOWSTONE_DUST:
                                                case GLOWSTONE:
                                                   return SellMultiManager.Category.ORES;
                                                default:
                                                   if (n.endsWith("SHULKER_BOX")) {
                                                      return SellMultiManager.Category.MOB_DROPS;
                                                   } else {
                                                      switch (m) {
                                                         case ROTTEN_FLESH:
                                                         case BONE:
                                                         case BONE_MEAL:
                                                         case BONE_BLOCK:
                                                         case STRING:
                                                         case SPIDER_EYE:
                                                         case GUNPOWDER:
                                                         case ENDER_PEARL:
                                                         case ENDER_EYE:
                                                         case BLAZE_ROD:
                                                         case BLAZE_POWDER:
                                                         case GHAST_TEAR:
                                                         case SLIME_BALL:
                                                         case SLIME_BLOCK:
                                                         case LEATHER:
                                                         case FEATHER:
                                                         case INK_SAC:
                                                         case GLOW_INK_SAC:
                                                         case PHANTOM_MEMBRANE:
                                                         case SHULKER_SHELL:
                                                         case MAGMA_CREAM:
                                                         case RABBIT_HIDE:
                                                         case RABBIT_FOOT:
                                                         case TURTLE_SCUTE:
                                                         case ARMADILLO_SCUTE:
                                                         case PRISMARINE_SHARD:
                                                         case PRISMARINE_CRYSTALS:
                                                         case NAUTILUS_SHELL:
                                                         case HEART_OF_THE_SEA:
                                                         case WITHER_SKELETON_SKULL:
                                                         case SKELETON_SKULL:
                                                         case ZOMBIE_HEAD:
                                                         case CREEPER_HEAD:
                                                         case PIGLIN_HEAD:
                                                         case DRAGON_HEAD:
                                                         case PLAYER_HEAD:
                                                         case GOAT_HORN:
                                                         case BREEZE_ROD:
                                                         case WIND_CHARGE:
                                                         case ECHO_SHARD:
                                                            return SellMultiManager.Category.MOB_DROPS;
                                                         default:
                                                            if (!n.endsWith("_LOG")
                                                               && !n.endsWith("_WOOD")
                                                               && !n.endsWith("_LEAVES")
                                                               && !n.endsWith("_SAPLING")
                                                               && !n.endsWith("_STEM")
                                                               && !n.endsWith("_HYPHAE")
                                                               && !n.endsWith("_FUNGUS")
                                                               && !n.endsWith("_ROOTS")
                                                               && !n.endsWith("_MUSHROOM")
                                                               && !n.endsWith("_TULIP")
                                                               && !n.endsWith("_PETALS")
                                                               && !n.endsWith("_VINES")) {
                                                               switch (m) {
                                                                  case DIRT:
                                                                  case GRASS_BLOCK:
                                                                  case COARSE_DIRT:
                                                                  case ROOTED_DIRT:
                                                                  case PODZOL:
                                                                  case MYCELIUM:
                                                                  case MUD:
                                                                  case CLAY:
                                                                  case CLAY_BALL:
                                                                  case SAND:
                                                                  case RED_SAND:
                                                                  case GRAVEL:
                                                                  case FLINT:
                                                                  case ICE:
                                                                  case PACKED_ICE:
                                                                  case BLUE_ICE:
                                                                  case SNOW:
                                                                  case SNOWBALL:
                                                                  case SNOW_BLOCK:
                                                                  case MOSS_BLOCK:
                                                                  case MOSS_CARPET:
                                                                  case PALE_MOSS_BLOCK:
                                                                  case PALE_MOSS_CARPET:
                                                                  case VINE:
                                                                  case GLOW_LICHEN:
                                                                  case LILY_PAD:
                                                                  case DRIED_KELP_BLOCK:
                                                                  case SEAGRASS:
                                                                  case BIG_DRIPLEAF:
                                                                  case SMALL_DRIPLEAF:
                                                                  case SPORE_BLOSSOM:
                                                                  case AZALEA:
                                                                  case FLOWERING_AZALEA:
                                                                  case STICK:
                                                                  case HONEYCOMB:
                                                                  case HONEYCOMB_BLOCK:
                                                                  case HONEY_BLOCK:
                                                                  case COBWEB:
                                                                  case DANDELION:
                                                                  case POPPY:
                                                                  case BLUE_ORCHID:
                                                                  case ALLIUM:
                                                                  case AZURE_BLUET:
                                                                  case OXEYE_DAISY:
                                                                  case CORNFLOWER:
                                                                  case LILY_OF_THE_VALLEY:
                                                                  case WITHER_ROSE:
                                                                  case TORCHFLOWER:
                                                                  case PITCHER_PLANT:
                                                                  case SUNFLOWER:
                                                                  case LILAC:
                                                                  case ROSE_BUSH:
                                                                  case PEONY:
                                                                  case SHORT_GRASS:
                                                                  case TALL_GRASS:
                                                                  case FERN:
                                                                  case LARGE_FERN:
                                                                  case DEAD_BUSH:
                                                                  case BAMBOO_BLOCK:
                                                                  case PUMPKIN_STEM:
                                                                  case MELON_STEM:
                                                                  case TURTLE_EGG:
                                                                  case SNIFFER_EGG:
                                                                  case FROGSPAWN:
                                                                  case SCULK:
                                                                  case SCULK_CATALYST:
                                                                  case SCULK_SENSOR:
                                                                  case SCULK_SHRIEKER:
                                                                  case SCULK_VEIN:
                                                                     return SellMultiManager.Category.NATURAL;
                                                                  default:
                                                                     return m.isBlock() ? SellMultiManager.Category.BLOCKS : SellMultiManager.Category.NATURAL;
                                                               }
                                                            } else {
                                                               return SellMultiManager.Category.NATURAL;
                                                            }
                                                      }
                                                   }
                                             }
                                          } else {
                                             return SellMultiManager.Category.ORES;
                                          }
                                    }
                                 }
                           }
                     }
                  } else {
                     return SellMultiManager.Category.GEAR;
                  }
            }
      }
   }

   public static double enchantValue(Enchantment ench, int level) {
      double base = ENCHANT_BASES.getOrDefault(ench.getKey().getKey(), 600.0);
      return base * (1.0 + 0.15 * (Math.max(1, level) - 1));
   }

   public static double baseValue(ItemStack item) {
      if (item != null && item.getType() != Material.AIR) {
         if (item.getType() == Material.ENCHANTED_BOOK && item.getItemMeta() instanceof EnchantmentStorageMeta esm && !esm.getStoredEnchants().isEmpty()) {
            double one = 0.0;

            for (Entry<Enchantment, Integer> e : esm.getStoredEnchants().entrySet()) {
               one += enchantValue(e.getKey(), e.getValue());
            }

            return one * item.getAmount();
         } else {
            return PriceManager.getPrice(item.getType()) * item.getAmount();
         }
      } else {
         return 0.0;
      }
   }

   public double appraiseStack(UUID id, ItemStack item, Map<SellMultiManager.Category, Double> credit) {
      if (item != null && item.getType() != Material.AIR) {
         if (item.getItemMeta() instanceof BlockStateMeta bmeta && bmeta.getBlockState() instanceof ShulkerBox sb) {
            double total = 0.0;
            double boxBase = shulkerBoxPrice(item.getType()) * item.getAmount();
            if (boxBase > 0.0) {
               SellMultiManager.Category boxCat = categoryOf(item.getType());
               double boxVal = boxBase * this.activeMultiplier(id, boxCat);
               credit.merge(boxCat, boxVal, Double::sum);
               total += boxVal;
            }

            for (ItemStack inner : sb.getInventory().getContents()) {
               total += this.appraiseStack(id, inner, credit);
            }

            return total;
         } else {
            double base = baseValue(item);
            if (base <= 0.0) {
               return 0.0;
            }

            SellMultiManager.Category cat = categoryOf(item.getType());
            double val = base * this.activeMultiplier(id, cat);
            credit.merge(cat, val, Double::sum);
            return val;
         }
      } else {
         return 0.0;
      }
   }

   private static double shulkerBoxPrice(Material material) {
      double exact = PriceManager.getPrice(material);
      return exact > 0.0 ? exact : PriceManager.getPrice(Material.SHULKER_BOX);
   }

   public void creditProgress(Player p, Map<SellMultiManager.Category, Double> credit, double scale) {
      if (p != null && !credit.isEmpty() && !(scale <= 0.0)) {
         UUID id = p.getUniqueId();
         Map<SellMultiManager.Category, Double> deltas = new EnumMap<>(SellMultiManager.Category.class);

         for (Entry<SellMultiManager.Category, Double> e : credit.entrySet()) {
            double amount = e.getValue() * scale;
            if (!(amount <= 0.0)) {
               SellMultiManager.Category cat = e.getKey();
               SellMultiManager.CatState st = this.state(id, cat);
               int reachedBefore = reachedTier(st.progress);
               st.progress += amount;
               int reachedAfter = reachedTier(st.progress);
               deltas.put(cat, amount);
               if (this.loaded.contains(id) && reachedAfter > reachedBefore && reachedAfter > st.claimed) {
                  this.sendLevelUpMessage(p, cat);
               }
            }
         }

         if (this.usingDb()) {
            this.persistProgressAdds(id, deltas);
         } else {
            for (SellMultiManager.Category cat : deltas.keySet()) {
               this.saveToConfig(id, cat);
            }
         }
      }
   }

   private void sendLevelUpMessage(Player p, SellMultiManager.Category cat) {
      p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
      String legacy = "-----------------------------------------------\n§fYou §x§5§5§F§F§B§8Leveled §fup your §x§5§5§F§F§B§8"
         + cat.displayName
         + " sell multi.\n§fClick here to §x§5§5§F§F§B§8claim!\n§f-----------------------------------------------";
      TextComponent msg = new TextComponent();

      for (BaseComponent part : TextComponent.fromLegacyText(legacy)) {
         msg.addExtra(part);
      }

      msg.setClickEvent(new ClickEvent(Action.RUN_COMMAND, "/sellmulti " + cat.key()));
      p.spigot().sendMessage(msg);
   }

   public double appraiseOnly(UUID id, ItemStack item) {
      Map<SellMultiManager.Category, Double> scratch = new EnumMap<>(SellMultiManager.Category.class);
      return this.appraiseStack(id, item, scratch);
   }

   public void creditExternalSale(UUID id, List<ItemStack> items, double payout) {
      if (id != null && items != null && !items.isEmpty() && !(payout <= 0.0)) {
         this.plugin.addPlayerTotal(id, "sell_made", payout);
         Map<SellMultiManager.Category, Double> credit = new EnumMap<>(SellMultiManager.Category.class);
         double total = 0.0;

         for (ItemStack item : items) {
            total += this.appraiseStack(id, item, credit);
         }

         if (!(total <= 0.0)) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
               this.creditProgress(p, credit, payout / total);
            }
         }
      }
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (sender instanceof Player p) {
         if (args.length > 0) {
            SellMultiManager.Category cat = SellMultiManager.Category.fromKey(args[0]);
            if (cat != null) {
               this.openProgressMenu(p, cat);
               return true;
            }
         }

         this.openMainMenu(p);
         return true;
      } else {
         return true;
      }
   }

   private void openMainMenu(Player p) {
      Inventory inv = Bukkit.createInventory(null, 27, "§0Sell Multiplier");
      this.renderMainMenu(p, inv);
      p.openInventory(inv);
   }

   private void renderMainMenu(Player p, Inventory inv) {
      UUID id = p.getUniqueId();

      for (SellMultiManager.Category cat : SellMultiManager.Category.values()) {
         SellMultiManager.CatState st = this.state(id, cat);
         int reached = reachedTier(st.progress);
         List<String> lore = new ArrayList<>();
         lore.add("§f" + cat.phrase + " to");
         lore.add("§fupgrade your sell multiplier!");
         lore.add("");
         if (reached >= MAX_TIER) {
            lore.add("§fMax multiplier " + fmtMult(MAX_TIER) + " reached!");
            lore.add(bar(1.0, "§x§5§5§F§F§B§8") + "§r §x§5§5§F§F§B§8(100.0%)");
         } else {
            double next = THRESHOLDS[reached];
            double pct = Math.min(1.0, st.progress / next);
            lore.add("§fProgress to " + fmtMult(reached + 1));
            lore.add(bar(pct, "§x§5§5§F§F§B§8") + "§r §x§5§5§F§F§B§8(" + fmtPct(pct) + ")");
         }

         if (reached > st.claimed) {
            lore.add("");
            lore.add("§x§5§5§F§F§B§8Click to claim your upgrade!");
         }

         inv.setItem(9 + cat.ordinal(), this.makeItem(cat.icon, "§x§5§5§F§F§B§8" + toSmallCaps(cat.displayName), lore, false));
      }
   }

   private String progressTitle(SellMultiManager.Category cat) {
      return "§0" + toSmallCaps(cat.displayName + " progress");
   }

   private void openProgressMenu(Player p, SellMultiManager.Category cat) {
      Inventory inv = Bukkit.createInventory(null, 54, this.progressTitle(cat));
      this.renderProgressMenu(p, cat, inv);
      p.openInventory(inv);
   }

   private void renderProgressMenu(Player p, SellMultiManager.Category cat, Inventory inv) {
      UUID id = p.getUniqueId();
      SellMultiManager.CatState st = this.state(id, cat);
      int reached = reachedTier(st.progress);
      int claimed = st.claimed;
      ItemStack filler = this.makeItem(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);

      for (int i = 0; i < 54; i++) {
         inv.setItem(i, filler);
      }

      inv.setItem(
         1,
         this.makeItem(
            cat.icon,
            "§x§5§5§F§F§B§8" + toSmallCaps(cat.displayName),
            List.of("§fSell items to upgrade", "§fyour sell multiplier!", "", "§fView your progress below"),
            false
         )
      );

      for (int t = 1; t <= MAX_TIER; t++) {
         double threshold = THRESHOLDS[t - 1];
         double pct = Math.min(1.0, st.progress / threshold);
         String amounts = "§7$" + fmtMoney(Math.min(st.progress, threshold)) + "/" + fmtMoney(threshold);
         boolean glint = false;
         Material pane;
         String name;
         List<String> lore;
         if (t <= claimed) {
            pane = Material.LIME_STAINED_GLASS_PANE;
            name = "§x§5§5§F§F§B§8" + toSmallCaps("complete");
            lore = List.of(tierBarLine(1.0, "§x§5§5§F§F§B§8", t), amounts);
         } else if (t <= reached) {
            pane = Material.LIME_STAINED_GLASS_PANE;
            name = "§x§5§5§F§F§B§8" + toSmallCaps("claim");
            if (t == claimed + 1) {
               lore = List.of(tierBarLine(1.0, "§x§5§5§F§F§B§8", t), amounts, "", "§x§5§5§F§F§B§8Click to claim " + fmtMult(t) + "!");
               glint = true;
            } else {
               lore = List.of(tierBarLine(1.0, "§x§5§5§F§F§B§8", t), amounts, "", "§7Claim your previous tier first!");
            }
         } else if (t == reached + 1) {
            pane = Material.YELLOW_STAINED_GLASS_PANE;
            name = "§e" + toSmallCaps("working");
            lore = List.of(tierBarLine(pct, "§e", t), amounts);
         } else {
            pane = Material.WHITE_STAINED_GLASS_PANE;
            name = "§f" + toSmallCaps("incomplete");
            lore = List.of(tierBarLine(pct, "§x§5§5§F§F§B§8", t), amounts);
         }

         inv.setItem(TIER_SLOTS[t - 1], this.makeItem(pane, name, lore, glint));
      }

      inv.setItem(45, this.makeItem(Material.RED_STAINED_GLASS_PANE, "§c" + toSmallCaps("back"), List.of("§7Back to the sell multiplier menu"), false));
   }

   private static String tierBarLine(double pct, String fillColor, int tier) {
      return bar(pct, fillColor) + "§r §f" + fmtMult(tier) + " §f" + fmtPct(pct);
   }

   private String pricesTitle(SellMultiManager.Category cat) {
      return "§0" + toSmallCaps(cat.pricesName);
   }

   private void openPricesMenu(Player p, SellMultiManager.Category cat, int page) {
      Inventory inv = Bukkit.createInventory(null, 54, this.pricesTitle(cat));
      this.renderPricesMenu(p, cat, inv, page);
      p.openInventory(inv);
   }

   private void renderPricesMenu(Player p, SellMultiManager.Category cat, Inventory inv, int page) {
      List<ItemStack> entries = this.buildPriceEntries(cat);
      int perPage = 45;
      int maxPage = Math.max(1, (entries.size() + perPage - 1) / perPage);
      page = Math.max(1, Math.min(page, maxPage));
      this.pricesPage.put(p.getUniqueId(), page);
      inv.clear();
      int start = (page - 1) * perPage;

      for (int i = start; i < entries.size() && i < start + perPage; i++) {
         inv.setItem(i - start, entries.get(i));
      }

      inv.setItem(45, this.makeItem(Material.RED_STAINED_GLASS_PANE, "§c" + toSmallCaps("back"), List.of("§7Back to " + cat.displayName + " progress"), false));
      if (page > 1) {
         inv.setItem(46, this.makeItem(Material.ARROW, "§e" + toSmallCaps("previous page"), List.of(), false));
      }

      if (page < maxPage) {
         inv.setItem(53, this.makeItem(Material.ARROW, "§e" + toSmallCaps("next page"), List.of(), false));
      }
   }

   private List<ItemStack> buildPriceEntries(SellMultiManager.Category cat) {
      List<ItemStack> out = new ArrayList<>();

      record Priced(Material material, double price) {
      }

      if (cat == SellMultiManager.Category.ENCHANTMENTS) {
         ENCHANT_BASES.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).forEach(entry -> {
            Enchantment ench = (Enchantment)Registry.ENCHANTMENT.get(NamespacedKey.minecraft(entry.getKey()));
            if (ench != null) {
               ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
               if (book.getItemMeta() instanceof EnchantmentStorageMeta esm) {
                  esm.addStoredEnchant(ench, 1, true);
                  esm.setDisplayName("§f" + toSmallCaps(prettyName(entry.getKey())));
                  esm.setLore(List.of("§fSell price: §x§5§5§F§F§B§8$" + fmtMoney(entry.getValue())));
                  book.setItemMeta(esm);
               }

               out.add(book);
            }
         });
      }

      List<Priced> priced = new ArrayList<>();

      for (Material m : Material.values()) {
         if (!m.name().startsWith("LEGACY_") && m.isItem() && (m != Material.ENCHANTED_BOOK || cat != SellMultiManager.Category.ENCHANTMENTS)) {
            double price = PriceManager.getPrice(m);
            if (!(price <= 0.0) && categoryOf(m) == cat) {
               priced.add(new Priced(m, price));
            }
         }
      }

      priced.sort((a, b) -> Double.compare(b.price, a.price));

      for (Priced pr : priced) {
         out.add(
            this.makeItem(pr.material, "§f" + toSmallCaps(prettyName(pr.material.name())), List.of("§fSell price: §x§5§5§F§F§B§8$" + fmtMoney(pr.price)), false)
         );
      }

      return out;
   }

   private static String prettyName(String raw) {
      return raw.toLowerCase(Locale.ROOT).replace('_', ' ');
   }

   @EventHandler
   public void onDrag(InventoryDragEvent e) {
      String title = e.getView().getOriginalTitle();
      if ("§0Sell Multiplier".equals(title) || this.titleToCategory.containsKey(title) || this.pricesTitleToCategory.containsKey(title)) {
         e.setCancelled(true);
      }
   }

   @EventHandler
   public void onClick(InventoryClickEvent e) {
      if (e.getWhoClicked() instanceof Player p) {
         String var11 = e.getView().getOriginalTitle();
         if ("§0Sell Multiplier".equals(var11)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot >= 9 && slot < 9 + SellMultiManager.Category.values().length) {
               this.openProgressMenu(p, SellMultiManager.Category.values()[slot - 9]);
            }
         } else {
            SellMultiManager.Category pricesCat = this.pricesTitleToCategory.get(var11);
            if (pricesCat != null) {
               e.setCancelled(true);
               int slot = e.getRawSlot();
               int page = this.pricesPage.getOrDefault(p.getUniqueId(), 1);
               if (slot == 45) {
                  this.openProgressMenu(p, pricesCat);
               } else if (slot == 46 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) {
                  this.renderPricesMenu(p, pricesCat, e.getView().getTopInventory(), page - 1);
               } else if (slot == 53 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) {
                  this.renderPricesMenu(p, pricesCat, e.getView().getTopInventory(), page + 1);
               }
            } else {
               SellMultiManager.Category cat = this.titleToCategory.get(var11);
               if (cat != null) {
                  e.setCancelled(true);
                  int slot = e.getRawSlot();
                  if (slot == 45) {
                     this.openMainMenu(p);
                  } else if (slot == 1) {
                     this.openPricesMenu(p, cat, 1);
                  } else {
                     int tier = -1;

                     for (int i = 0; i < TIER_SLOTS.length; i++) {
                        if (TIER_SLOTS[i] == slot) {
                           tier = i + 1;
                           break;
                        }
                     }

                     if (tier >= 0) {
                        UUID id = p.getUniqueId();
                        SellMultiManager.CatState st = this.state(id, cat);
                        int reached = reachedTier(st.progress);
                        if (tier > st.claimed && tier <= reached) {
                           if (tier != st.claimed + 1) {
                              p.sendMessage("§cClaim your previous tier first!");
                           } else {
                              st.claimed = tier;
                              this.persistClaim(id, cat, tier);
                              p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 2.0F, 2.0F);
                              p.sendMessage("§fYour §x§5§5§F§F§B§8" + cat.displayName + " §fsell multi is now §x§5§5§F§F§B§8" + fmtMult(tier) + "§f!");
                              this.renderProgressMenu(p, cat, e.getView().getTopInventory());
                           }
                        }
                     }
                  }
               }
            }
         }
      }
   }

   private ItemStack makeItem(Material mat, String name, List<String> lore, boolean glint) {
      ItemStack item = new ItemStack(mat);
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
         meta.setDisplayName(name);
         if (!lore.isEmpty()) {
            meta.setLore(lore);
         }

         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_ADDITIONAL_TOOLTIP});
         if (glint) {
            try {
               meta.setEnchantmentGlintOverride(Boolean.TRUE);
            } catch (Throwable ignored) {
               meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            }
         }

         item.setItemMeta(meta);
      }

      return item;
   }

   private static String bar(double pct, String fillColor) {
      int filled = (int)Math.round(Math.max(0.0, Math.min(1.0, pct)) * 20.0);
      StringBuilder sb = new StringBuilder();
      if (filled > 0) {
         sb.append(fillColor).append("§m");
         sb.append(" ".repeat(filled));
      }

      if (filled < 20) {
         sb.append("§8§m");
         sb.append(" ".repeat(20 - filled));
      }

      return sb.toString();
   }

   private static String fmtMult(int tier) {
      return String.format(Locale.ROOT, "%.1fx", multiplierOf(tier));
   }

   private static String fmtPct(double pct) {
      return String.format(Locale.ROOT, "%.1f%%", pct * 100.0);
   }

   private static String fmtMoney(double v) {
      if (v >= 9.99995E11) {
         return trimZeros(v / 1.0E12) + "T";
      } else if (v >= 9.99995E8) {
         return trimZeros(v / 1.0E9) + "B";
      } else if (v >= 999995.0) {
         return trimZeros(v / 1000000.0) + "M";
      } else {
         return v >= 1000.0 ? trimZeros(v / 1000.0) + "K" : trimZeros(v);
      }
   }

   private static String trimZeros(double v) {
      String s = String.format(Locale.ROOT, "%.2f", v);
      if (s.indexOf(46) >= 0) {
         s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
      }

      return s;
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

   public void reloadFromDb() {
      if (this.usingDb()) {
         this.createTable();

         for (Player p : Bukkit.getOnlinePlayers()) {
            this.loadPlayerAsync(p.getUniqueId());
         }
      }
   }

   public SnapshotManager.Section snapshotSection() {
      return new SnapshotManager.Section() {
         @Override
         public String sectionName() {
            return "sellmulti";
         }

         @Override
         public void writeSnapshot(ConfigurationSection out) {
            for (Entry<UUID, ConcurrentHashMap<SellMultiManager.Category, SellMultiManager.CatState>> player : SellMultiManager.this.cache.entrySet()) {
               for (Entry<SellMultiManager.Category, SellMultiManager.CatState> cat : player.getValue().entrySet()) {
                  String base = player.getKey() + "." + cat.getKey().key();
                  out.set(base + ".progress", cat.getValue().progress);
                  out.set(base + ".claimed", cat.getValue().claimed);
               }
            }
         }

         @Override
         public void readSnapshot(ConfigurationSection in) {
            int n = 0;

            for (String uuidKey : in.getKeys(false)) {
               UUID id;
               try {
                  id = UUID.fromString(uuidKey);
               } catch (IllegalArgumentException ignored) {
                  continue;
               }

               ConfigurationSection cats = in.getConfigurationSection(uuidKey);
               if (cats != null) {
                  for (String catKey : cats.getKeys(false)) {
                     SellMultiManager.Category cat = SellMultiManager.Category.fromKey(catKey);
                     if (cat != null) {
                        SellMultiManager.CatState st = SellMultiManager.this.state(id, cat);
                        st.progress = Math.max(st.progress, cats.getDouble(catKey + ".progress", 0.0));
                        st.claimed = Math.max(st.claimed, cats.getInt(catKey + ".claimed", 0));
                        n++;
                     }
                  }
               }
            }

            SellMultiManager.this.plugin.getLogger().warning("Restored " + n + " sell multi entries from local snapshot.");
         }
      };
   }

   private static final class CatState {
      volatile double progress;
      volatile int claimed;
   }

   public enum Category {
      CROPS(Material.WHEAT, "Crops", "Crop Prices", "Sell crops and farming materials"),
      MOB_DROPS(Material.BONE, "Mob Drops", "Mob Drop Prices", "Sell mob drops"),
      ORES(Material.DIAMOND, "Ores", "Ore Prices", "Sell ores and minerals"),
      NATURAL(Material.OAK_LEAVES, "Natural Items", "Natural Item Prices", "Sell natural items"),
      GEAR(Material.NETHERITE_HELMET, "Gear", "Gear Prices", "Sell tools, weapons and armor"),
      FOOD(Material.TROPICAL_FISH, "Food", "Food Prices", "Sell food items"),
      ENCHANTMENTS(Material.BOOK, "Enchantments", "Enchantment Prices", "Sell enchanted books"),
      POTIONS(Material.BREWING_STAND, "Potions", "Potion Prices", "Sell potions"),
      BLOCKS(Material.BRICKS, "Blocks", "Block Prices", "Sell blocks");

      final Material icon;
      final String displayName;
      final String pricesName;
      final String phrase;

      Category(Material icon, String displayName, String pricesName, String phrase) {
         this.icon = icon;
         this.displayName = displayName;
         this.pricesName = pricesName;
         this.phrase = phrase;
      }

      String key() {
         return this.name().toLowerCase(Locale.ROOT);
      }

      static SellMultiManager.Category fromKey(String s) {
         if (s == null) {
            return null;
         }

         String norm = s.trim().toLowerCase(Locale.ROOT).replace('-', '_');

         for (SellMultiManager.Category c : values()) {
            if (c.key().equals(norm) || c.displayName.replace(" ", "_").toLowerCase(Locale.ROOT).equals(norm)) {
               return c;
            }
         }

         return null;
      }
   }
}
