package com.taxsmp;

import com.taxsmp.bounty.BountyCommand;
import com.taxsmp.bounty.BountyManager;
import com.taxsmp.bounty.BountyMapService;
import com.taxsmp.bounty.SetBountyMapCommand;
import com.taxsmp.bounty.SkinCache;
import com.taxsmp.storage.Db;
import com.taxsmp.storage.SnapshotManager;
import com.taxsmp.ui.HomesCommandListener;
import com.taxsmp.ui.MenuClickListener;
import com.taxsmp.ui.MenuCommand;
import com.taxsmp.ui.MenuDatapack;
import com.taxsmp.ui.TaxMenus;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicBoolean;
import me.kirug.signgui.SignGUI;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public class TaxSMP extends JavaPlugin {
   private static final long CONFIG_SAVE_DEBOUNCE_TICKS = 40L;
   private final AtomicBoolean configSaveQueued = new AtomicBoolean(false);
   private static final Set<String> DEFAULT_FALSE_SETTINGS = Set.of("quick_auction_buy", "team_chat");
   private static final String[] DEFAULT_TRUE_SETTINGS = new String[]{
      "tpa_requests",
      "tpa_here_requests",
      "payments",
      "scoreboard",
      "public_chat",
      "private_messages",
      "chat_server_messages",
      "hotbar_server_messages",
      "pay_alerts",
      "bounty_alerts",
      "auction_alerts",
      "fast_crystals",
      "chainmail_on_respawn",
      "tpa_confirm_menus",
      "sound_notifications",
      "worth_display",
      "mob_spawns"
   };
   private ScheduledTask scoreboardTask;
   private Db db;
   private SnapshotManager snapshotManager;
   private static SimpleEconomy econ = null;
   private LuckPerms luckPerms;
   private AuctionManager auctionManagerInstance;
   private OrderManager orderManagerInstance;
   private CombatManager combatManagerInstance;
   private CombatLogout combatLogoutInstance;
   private EggManager eggManagerInstance;
   private KillRewardManager killRewardManagerInstance;
   private GemManager gemManagerInstance;
   private ChatTagManager chatTagManagerInstance;
   private VaultBridge vaultBridge;
   private SellWandManager sellWandManagerInstance;
   private SellMultiManager sellMultiManagerInstance;
   private KeyallManager keyallManagerInstance;
   private FtpManager ftpManagerInstance;
   private BountyManager bountyManagerInstance;
   private BountyMapService bountyMapService;
   private SkinCache bountySkinCache;
   private File pricesFile;
   private FileConfiguration pricesCfg;
   private ScoreboardManager scoreboardManager;
   private FasterCrystalsBridge fasterCrystalsBridge;
   private StatsTracker statsTracker;
   private WebApi webApi;
   private LeaderboardCommand leaderboardCommand;
   private NetworkStatusManager networkStatusManager;
   private NetworkNotificationManager networkNotificationManager;
   private NetworkTotalsManager networkTotalsManager;
   private NetworkPlayerDirectory networkPlayerDirectory;
   private SettingsSyncManager settingsSyncManager;
   private ReplyCommand replyCommandInstance;
   private static final List<TaxSMP.SettingsHook> settingsHooks = new ArrayList<>();
   private IgnoreManager ignoreManager;
   private OfflinePayManager offlinePayManager;
   private TaxMenus menus;
   private StatsCommand statsCommandInstance;
   private boolean menuPackWritten;
   private static final double SHOP_PRICE_BUMP = 150.0;
   private static final String SHOP_PRICE_BUMP_FLAG = "shop-price-bump-150-applied";

   public void requestConfigSave() {
      if (this.configSaveQueued.compareAndSet(false, true)) {
         try {
            Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> {
               this.configSaveQueued.set(false);
               this.saveConfig();
            }, 40L);
         } catch (Throwable t) {
            this.configSaveQueued.set(false);
            this.saveConfig();
         }
      }
   }

   public void flushPendingConfigSave() {
      if (this.configSaveQueued.getAndSet(false)) {
         this.saveConfig();
      }
   }

   public static void registerSettingsHook(TaxSMP.SettingsHook hook) {
      if (hook != null) {
         settingsHooks.add(hook);
      }
   }

   public static void fireSettingsHooks(UUID player, String key, boolean enabled) {
      for (TaxSMP.SettingsHook h : settingsHooks) {
         try {
            h.onSettingChanged(player, key, enabled);
         } catch (Exception var6) {
         }
      }
   }

   public void onLoad() {
      try {
         this.saveDefaultConfig();
         boolean wanted = this.getConfig().getBoolean("menus.enabled", true) && this.getConfig().getBoolean("menus.pause-menu-button", true);
         Class.forName("io.papermc.paper.dialog.Dialog");
         this.menuPackWritten = wanted ? MenuDatapack.install(this) : MenuDatapack.uninstall(this);
      } catch (Throwable var2) {
      }
   }

   public void onEnable() {
      this.saveDefaultConfig();
      this.checkConfigDefaults();
      SignGUI.init(this);
      // Sign input and worth lore need ProtocolLib; without it the classes can't even load.
      boolean protocolLib = this.getServer().getPluginManager().getPlugin("ProtocolLib") != null;
      if (protocolLib) {
         SignPrompt.init(this);
      } else {
         this.getLogger().warning("ProtocolLib is not installed: sign input and worth lore are turned off.");
      }

      this.db = new Db(this);
      this.db.init();
      this.snapshotManager = new SnapshotManager(this);
      this.networkStatusManager = new NetworkStatusManager(this);
      this.networkNotificationManager = new NetworkNotificationManager(this);
      this.networkTotalsManager = new NetworkTotalsManager(this);
      this.networkPlayerDirectory = new NetworkPlayerDirectory(this);
      this.settingsSyncManager = new SettingsSyncManager(this);
      econ = new SimpleEconomy(this, this.db);

      try {
         this.luckPerms = LuckPermsProvider.get();
      } catch (Exception | NoClassDefFoundError ex) {
         this.luckPerms = null;
         this.getLogger().warning("LuckPerms not found. Rank features will be limited.");
      }

      this.registerCommandsAndEvents();
      this.setUpMenus();
      this.setUpDataSafetyNet();
      this.loadPrices();
      this.applyShopPriceBump();
      this.applyUhcShopPrices();
      this.applyShopPrices("shop-uhc-prices-v2-applied", Map.of(Material.TOTEM_OF_UNDYING, 50000.0, Material.ENDER_PEARL, 1000.0));
      this.migrateFishingCoinsToEggs();
      this.getLogger().info("TaxSMP Plugin Enabled!");
      Plugin tpaPlugin = this.getServer().getPluginManager().getPlugin("TPAPlugin");
      if (tpaPlugin != null) {
         try {
            Class<?> hookClass = Class.forName("com.example.tpa.CombatHook");
            Class<?> providerClass = Class.forName("com.example.tpa.CombatHook$Provider");
            Object proxy = Proxy.newProxyInstance(providerClass.getClassLoader(), new Class[]{providerClass}, (proxyObj, method, args) -> {
               if (!"isInCombat".equals(method.getName())) {
                  if ("getRemainingSeconds".equals(method.getName())) {
                     Player player = (Player)args[0];
                     return this.combatManagerInstance != null ? this.combatManagerInstance.getRemainingSeconds(player) : 0;
                  } else {
                     return null;
                  }
               } else {
                  Player player = (Player)args[0];
                  return this.combatManagerInstance != null && this.combatManagerInstance.isInCombat(player);
               }
            });
            hookClass.getMethod("registerProvider", providerClass).invoke(null, proxy);
            this.getLogger().info("Hooked into TPAPlugin combat system.");
         } catch (Exception e) {
            this.getLogger().warning("Failed to hook into TPAPlugin: " + e.getMessage());
         }

         try {
            ConfigurationSection playersSection = this.getConfig().getConfigurationSection("players");
            if (playersSection != null) {
               for (String uuidStr : playersSection.getKeys(false)) {
                  UUID uuid = UUID.fromString(uuidStr);
                  boolean tpaOn = this.getConfig().getBoolean("players." + uuidStr + ".tpa_requests", true);
                  boolean tpaHereOn = this.getConfig().getBoolean("players." + uuidStr + ".tpa_here_requests", true);
                  boolean confirmOn = this.getConfig().getBoolean("players." + uuidStr + ".tpa_confirm_menus", true);
                  if (!tpaOn) {
                     tpaPlugin.getClass().getMethod("setTPAEnabled", UUID.class, boolean.class).invoke(tpaPlugin, uuid, false);
                  }

                  if (!tpaHereOn) {
                     tpaPlugin.getClass().getMethod("setTPAHereEnabled", UUID.class, boolean.class).invoke(tpaPlugin, uuid, false);
                  }

                  if (!confirmOn) {
                     tpaPlugin.getClass().getMethod("setConfirmMenuEnabled", UUID.class, boolean.class).invoke(tpaPlugin, uuid, false);
                  }
               }
            }

            this.getLogger().info("Loaded saved TPA settings into TPAPlugin.");
         } catch (Exception e) {
            this.getLogger().warning("Failed to load TPA settings into TPAPlugin: " + e.getMessage());
         }
      } else {
         this.getLogger().warning("TPAPlugin not found, combat checks for TPA will be disabled.");
      }

      SellHistory.init(this);
   }

   private void setUpDataSafetyNet() {
      if (this.snapshotManager != null) {
         if (econ != null) {
            this.snapshotManager.register(econ.snapshotSection());
         }

         if (this.eggManagerInstance != null) {
            this.snapshotManager.register(this.eggManagerInstance.snapshotSection());
         }

         if (this.gemManagerInstance != null) {
            this.snapshotManager.register(this.gemManagerInstance.snapshotSection());
         }

         if (this.sellMultiManagerInstance != null) {
            this.snapshotManager.register(this.sellMultiManagerInstance.snapshotSection());
         }

         if (this.db != null && this.db.isEnabled() && !this.db.isHealthy()) {
            this.snapshotManager.restoreNewest();
         }

         this.snapshotManager.start();
      }
   }

   private void setUpMenus() {
      if (!this.getConfig().getBoolean("menus.enabled", true)) {
         if (MenuDatapack.uninstall(this) && this.getConfig().getBoolean("menus.auto-reload-datapack", true)) {
            this.scheduleDataReload();
         }

         this.getLogger().info("The new menu screens are disabled in config.yml.");
      } else {
         try {
            Class.forName("io.papermc.paper.dialog.Dialog");
         } catch (Throwable notThere) {
            this.getLogger().info("This server is older than 1.21.6, so the new menu screens are unavailable. Using the chest menus.");
            return;
         }

         try {
            this.menus = new TaxMenus(this);
            MenuCommand menuCommand = new MenuCommand(this, this.menus);
            this.getCommand("taxsmp").setExecutor(menuCommand);
            this.getCommand("taxsmp").setTabCompleter(menuCommand);
            this.getServer().getPluginManager().registerEvents(new MenuClickListener(this, this.menus), this);
            this.getServer().getPluginManager().registerEvents(new HomesCommandListener(this, this.menus), this);
            boolean wantButton = this.getConfig().getBoolean("menus.pause-menu-button", true);
            boolean changed = (wantButton ? MenuDatapack.install(this) : MenuDatapack.uninstall(this)) || this.menuPackWritten;
            if (changed && this.getConfig().getBoolean("menus.auto-reload-datapack", true)) {
               this.scheduleDataReload();
            } else if (changed) {
               this.getLogger().info("Run /minecraft:reload (or restart) to finish installing the pause-menu button.");
            }

            this.getLogger().info("Menu screens enabled.");
         } catch (Throwable t) {
            this.menus = null;
            this.getLogger().warning("Could not start the new menu screens (" + t + "). Falling back to the chest menus.");
         }
      }
   }

   private void scheduleDataReload() {
      Bukkit.getGlobalRegionScheduler()
         .runDelayed(
            this,
            task -> {
               try {
                  Bukkit.reloadData();
                  this.getLogger().info("Reloaded server data - the TaxSMP pause-menu button is live.");
               } catch (Throwable t) {
                  this.getLogger()
                     .warning(
                        "This server would not reload data packs on its own. Run /minecraft:reload once (or restart again) and the TaxSMP pause-menu button will be there."
                     );
               }
            },
            20L
         );
   }

   public void reinstallMenuDatapack(CommandSender sender) {
      if (this.menus == null) {
         sender.sendMessage("§cThe new menu screens are not active on this server.");
      } else {
         MenuDatapack.uninstall(this);
         MenuDatapack.install(this);
         this.scheduleDataReload();
         sender.sendMessage("§7Rewrote the menu data pack. Reloading server data now.");
      }
   }

   private void checkConfigDefaults() {
      boolean changed = false;

      for (String s : DEFAULT_TRUE_SETTINGS) {
         if (!this.getConfig().contains("settings." + s)) {
            this.getConfig().set("settings." + s, true);
            changed = true;
         }
      }

      for (String s : DEFAULT_FALSE_SETTINGS) {
         if (!this.getConfig().contains("settings." + s)) {
            this.getConfig().set("settings." + s, false);
            changed = true;
         }
      }

      if (changed) {
         this.saveConfig();
      }
   }

   private void registerCommandsAndEvents() {
      ShopManager shopManager = new ShopManager(this);
      ReplyCommand replyCommand = new ReplyCommand(this);
      this.replyCommandInstance = replyCommand;
      MsgCommand msgCommand = new MsgCommand(this, replyCommand);
      this.sellMultiManagerInstance = new SellMultiManager(this);
      SellManager sellManager = new SellManager(this);
      this.auctionManagerInstance = new AuctionManager(this);
      this.orderManagerInstance = new OrderManager(this);
      this.combatLogoutInstance = new CombatLogout(this);
      this.combatManagerInstance = new CombatManager(this, this.combatLogoutInstance);
      this.eggManagerInstance = new EggManager(this);
      this.killRewardManagerInstance = new KillRewardManager(this);
      this.gemManagerInstance = new GemManager(this);
      this.chatTagManagerInstance = new ChatTagManager(this);
      this.vaultBridge = new VaultBridge(this);
      this.sellWandManagerInstance = new SellWandManager(this);
      this.keyallManagerInstance = new KeyallManager(this);
      this.ftpManagerInstance = new FtpManager(this);
      this.bountyManagerInstance = new BountyManager(this);
      this.bountySkinCache = new SkinCache(this);
      this.bountyMapService = new BountyMapService(this, this.bountyManagerInstance, this.bountySkinCache);
      this.scoreboardManager = new ScoreboardManager(this);
      this.fasterCrystalsBridge = new FasterCrystalsBridge(this);
      this.ignoreManager = new IgnoreManager(this);
      this.offlinePayManager = new OfflinePayManager(this);
      this.statsTracker = new StatsTracker(this);
      this.leaderboardCommand = new LeaderboardCommand(this, this.statsTracker);
      this.webApi = new WebApi(this);
      this.webApi.start();
      BalTopCommand balTopCommand = new BalTopCommand(this, this.leaderboardCommand);
      StatsCommand statsCommand = new StatsCommand(this);
      this.statsCommandInstance = statsCommand;
      NightVisionCommand nightVision = new NightVisionCommand(this);
      PluginManager pm = this.getServer().getPluginManager();
      pm.registerEvents(new EconomyListener(this), this);
      pm.registerEvents(new SettingsListener(this), this);
      pm.registerEvents(this.fasterCrystalsBridge, this);
      pm.registerEvents(new FastCrystalListener(this), this);
      this.getLogger().info("Using external FasterCrystals plugin for /settings fast crystal when available.");
      pm.registerEvents(replyCommand, this);
      pm.registerEvents(shopManager, this);
      pm.registerEvents(sellManager, this);
      pm.registerEvents(this.sellMultiManagerInstance, this);
      pm.registerEvents(this.leaderboardCommand, this);
      pm.registerEvents(this.statsTracker, this);
      pm.registerEvents(new ChatListener(this), this);
      pm.registerEvents(this.auctionManagerInstance, this);
      pm.registerEvents(this.orderManagerInstance, this);
      pm.registerEvents(this.combatManagerInstance, this);
      pm.registerEvents(this.combatLogoutInstance, this);
      pm.registerEvents(statsCommand, this);
      pm.registerEvents(nightVision, this);
      pm.registerEvents(new PhantomBlocker(this), this);
      pm.registerEvents(new com.taxsmp.ui.GuiSkin(this), this);
      if (this.getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
         pm.registerEvents(new WorthLoreListener(this), this);
      }
      pm.registerEvents(new HopperBooster(), this);
      pm.registerEvents(this.eggManagerInstance, this);
      pm.registerEvents(this.killRewardManagerInstance, this);
      pm.registerEvents(this.chatTagManagerInstance, this);
      pm.registerEvents(this.sellWandManagerInstance, this);
      pm.registerEvents(this.keyallManagerInstance, this);
      pm.registerEvents(this.ftpManagerInstance, this);
      pm.registerEvents(this.bountyManagerInstance, this);
      pm.registerEvents(this.scoreboardManager, this);
      pm.registerEvents(this.networkStatusManager, this);
      pm.registerEvents(this.networkNotificationManager, this);
      pm.registerEvents(this.networkPlayerDirectory, this);
      pm.registerEvents(this.settingsSyncManager, this);
      this.getCommand("shop").setExecutor(shopManager);
      this.getCommand("msg").setExecutor(msgCommand);
      this.getCommand("msg").setTabCompleter(msgCommand);
      this.getCommand("tell").setExecutor(msgCommand);
      this.getCommand("tell").setTabCompleter(msgCommand);
      this.getCommand("w").setExecutor(msgCommand);
      this.getCommand("w").setTabCompleter(msgCommand);
      this.getCommand("r").setExecutor(replyCommand);
      this.getCommand("sell").setExecutor(sellManager);
      this.getCommand("sellmulti").setExecutor(this.sellMultiManagerInstance);
      this.getCommand("ah").setExecutor(this.auctionManagerInstance);
      this.getCommand("order").setExecutor(this.orderManagerInstance);
      PayCommand payCommand = new PayCommand(this);
      this.getCommand("pay").setExecutor(payCommand);
      this.getCommand("pay").setTabCompleter(payCommand);
      this.getCommand("ignore").setExecutor(new IgnoreCommand(this));
      WorthCommand worthCommand = new WorthCommand(this);
      this.getCommand("worth").setExecutor(worthCommand);
      this.getCommand("worth").setTabCompleter(worthCommand);
      this.getCommand("bal").setExecutor(new BalCommand(this));
      this.getCommand("baltop").setExecutor(balTopCommand);
      this.getCommand("leaderboard").setExecutor(this.leaderboardCommand);
      EggsCommand eggsCommand = new EggsCommand(this);
      this.getCommand("eggs").setExecutor(eggsCommand);
      this.getCommand("eggs").setTabCompleter(eggsCommand);
      GemsCommand gemsCommand = new GemsCommand(this);
      this.getCommand("gems").setExecutor(gemsCommand);
      this.getCommand("gems").setTabCompleter(gemsCommand);
      this.getCommand("sellwand").setExecutor(this.sellWandManagerInstance);
      this.getCommand("nightvision").setExecutor(nightVision);
      this.getCommand("setprice").setExecutor(new SetPriceCommand(this));
      SetEggsCommand setEggsCommand = new SetEggsCommand(this);
      this.getCommand("seteggs").setExecutor(setEggsCommand);
      this.getCommand("seteggs").setTabCompleter(setEggsCommand);
      this.getCommand("shopsetprice").setExecutor(new ShopSetPriceCommand(this));
      this.getCommand("stats").setExecutor(statsCommand);
      this.getCommand("settings").setExecutor(new SettingsCommand(this));
      this.getCommand("clearchat").setExecutor(new ClearChatCommand(this));
      this.getCommand("removemoney").setExecutor(new RemoveMoneyCommand(this));
      TaxMoneyCommand taxMoneyCommand = new TaxMoneyCommand(this);
      this.getCommand("taxmoney").setExecutor(taxMoneyCommand);
      this.getCommand("taxmoney").setTabCompleter(taxMoneyCommand);
      this.getCommand("combatremove").setExecutor(new CombatRemoveCommand(this.combatManagerInstance, this));
      this.getCommand("ftp").setExecutor(this.ftpManagerInstance);
      this.getCommand("ftp").setTabCompleter(this.ftpManagerInstance);
      BountyCommand bountyCommand = new BountyCommand(this, this.bountyManagerInstance, this.bountyMapService);
      this.getCommand("bounty").setExecutor(bountyCommand);
      this.getCommand("bounty").setTabCompleter(bountyCommand);
      SetBountyMapCommand setBountyMapCommand = new SetBountyMapCommand(this, this.bountyMapService);
      this.getCommand("setbountymap").setExecutor(setBountyMapCommand);
      this.getCommand("setbountymap").setTabCompleter(setBountyMapCommand);
      this.bountyMapService.reattachAll();
      if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
         new TaxPlaceholders(this).register();
         this.getLogger().info("Registered TaxSMP PlaceholderAPI expansion (%taxsmp_*%).");
      }

      boolean sbEnabled = this.getConfig().getBoolean("settings.scoreboard", true);
      this.setScoreboardEnabled(sbEnabled);
   }

   private void loadPrices() {
      this.pricesFile = new File(this.getDataFolder(), "prices.yml");
      if (!this.getDataFolder().exists()) {
         this.getDataFolder().mkdirs();
      }

      this.pricesCfg = this.pricesFile.exists() ? YamlConfiguration.loadConfiguration(this.pricesFile) : new YamlConfiguration();
      this.loadPriceSection("sellprices", true);
      this.loadPriceSection("shopprices", false);
   }

   private void loadPriceSection(String section, boolean isSell) {
      if (this.pricesCfg.contains(section)) {
         for (String key : this.pricesCfg.getConfigurationSection(section).getKeys(false)) {
            try {
               Material m = Material.valueOf(key);
               double val = this.pricesCfg.getDouble(section + "." + key);
               if (isSell) {
                  PriceManager.setSellPrice(m, val);
               } else {
                  PriceManager.setShopPrice(m, val);
               }
            } catch (Exception var8) {
            }
         }
      }
   }

   private void applyUhcShopPrices() {
      if (!this.getConfig().getBoolean("shop-uhc-prices-applied", false)) {
         Map<Material, Double> prices = Map.of(Material.TOTEM_OF_UNDYING, 10000.0, Material.WATER_BUCKET, 1500.0, Material.LAVA_BUCKET, 1500.0);

         for (Entry<Material, Double> entry : prices.entrySet()) {
            PriceManager.setShopPrice(entry.getKey(), entry.getValue());
            this.pricesCfg.set("shopprices." + entry.getKey().name(), entry.getValue());
         }

         try {
            this.pricesCfg.save(this.pricesFile);
         } catch (IOException e) {
            this.getLogger().warning("Failed to save UHC shop prices: " + e.getMessage());
            return;
         }

         this.getConfig().set("shop-uhc-prices-applied", true);
         this.saveConfig();
         this.getLogger().info("Set UHC shop prices: totems $10,000, water and lava buckets $1,500 (one-time).");
      }
   }

   private void applyShopPrices(String flag, Map<Material, Double> prices) {
      if (!this.getConfig().getBoolean(flag, false)) {
         for (Entry<Material, Double> entry : prices.entrySet()) {
            PriceManager.setShopPrice(entry.getKey(), entry.getValue());
            this.pricesCfg.set("shopprices." + entry.getKey().name(), entry.getValue());
         }

         try {
            this.pricesCfg.save(this.pricesFile);
         } catch (IOException e) {
            this.getLogger().warning("Failed to save shop prices for " + flag + ": " + e.getMessage());
            return;
         }

         this.getConfig().set(flag, true);
         this.saveConfig();
         this.getLogger().info("Applied shop prices " + prices + " (" + flag + ", one-time).");
      }
   }

   private void applyShopPriceBump() {
      if (!this.getConfig().getBoolean("shop-price-bump-150-applied", false)) {
         Map<Material, Double> prices = PriceManager.getShopPrices();

         for (Entry<Material, Double> entry : prices.entrySet()) {
            double bumped = entry.getValue() + 150.0;
            PriceManager.setShopPrice(entry.getKey(), bumped);
            this.pricesCfg.set("shopprices." + entry.getKey().name(), bumped);
         }

         try {
            this.pricesCfg.save(this.pricesFile);
         } catch (IOException e) {
            this.getLogger().warning("Failed to save bumped shop prices: " + e.getMessage());
            return;
         }

         this.getConfig().set("shop-price-bump-150-applied", true);
         this.saveConfig();
         this.getLogger().info("Raised " + prices.size() + " /shop buy prices by $150 (one-time).");
      }
   }

   private void migrateFishingCoinsToEggs() {
      if (!this.getConfig().getBoolean("fishingcoins-migrated", false)) {
         Bukkit.getAsyncScheduler()
            .runNow(
               this,
               task -> {
                  Map<UUID, Integer> coinMap = new HashMap<>();
                  if (this.db != null && this.db.isEnabled()) {
                     try (
                        Connection conn = this.db.getConnection();
                        PreparedStatement ps = conn.prepareStatement("SELECT uuid, coins FROM taxsmp_fishingcoins");
                        ResultSet rs = ps.executeQuery();
                     ) {
                        while (rs.next()) {
                           try {
                              coinMap.put(UUID.fromString(rs.getString("uuid")), rs.getInt("coins"));
                           } catch (IllegalArgumentException var12) {
                           }
                        }
                     } catch (SQLException e) {
                        this.getLogger().warning("Fishing Coins -> Eggs migration: could not read coin table (will retry next start): " + e.getMessage());
                        return;
                     }
                  }

                  ConfigurationSection players = this.getConfig().getConfigurationSection("players");
                  if (players != null) {
                     for (String key : players.getKeys(false)) {
                        int c = this.getConfig().getInt("players." + key + ".fishingcoins", 0);
                        if (c > 0) {
                           try {
                              coinMap.merge(UUID.fromString(key), c, Math::max);
                           } catch (IllegalArgumentException var11) {
                           }
                        }
                     }
                  }

                  Bukkit.getGlobalRegionScheduler()
                     .execute(
                        this,
                        () -> {
                           int converted = 0;
                           long eggsAwarded = 0L;

                           for (Entry<UUID, Integer> entry : coinMap.entrySet()) {
                              int coins = entry.getValue();
                              String base = "players." + entry.getKey();
                              this.getConfig().set(base + ".fishingcoins", null);
                              if (coins > 0) {
                                 int addEggs = (int)Math.round(coins * 0.5);
                                 if (addEggs > 0) {
                                    if (this.eggManagerInstance != null) {
                                       this.eggManagerInstance.addEggs(entry.getKey(), addEggs);
                                    } else {
                                       int eggs = this.getConfig().getInt(base + ".eggs", 0);
                                       this.getConfig().set(base + ".eggs", eggs + addEggs);
                                    }

                                    converted++;
                                    eggsAwarded += addEggs;
                                 }
                              }
                           }

                           this.getConfig().set("fishingcoins-migrated", true);
                           this.saveConfig();
                           this.getLogger()
                              .info(
                                 "Fishing Coins -> Eggs migration complete: converted "
                                    + converted
                                    + " players' coins into "
                                    + eggsAwarded
                                    + " eggs (0.5 eggs per coin)."
                              );
                        }
                     );
               }
            );
      }
   }

   public TaxMenus getMenus() {
      return this.menus;
   }

   public StatsCommand getStatsCommand() {
      return this.statsCommandInstance;
   }

   public IgnoreManager getIgnoreManager() {
      return this.ignoreManager;
   }

   public OfflinePayManager getOfflinePayManager() {
      return this.offlinePayManager;
   }

   public void onDisable() {
      SellHistory.shutdown();
      this.flushPendingConfigSave();
      if (this.auctionManagerInstance != null) {
         this.auctionManagerInstance.saveAuctions();
         this.auctionManagerInstance.shutdown();
      }

      if (this.orderManagerInstance != null) {
         this.orderManagerInstance.saveOrders();
         this.orderManagerInstance.shutdown();
      }

      if (this.keyallManagerInstance != null) {
         this.keyallManagerInstance.shutdown();
      }

      if (this.bountyMapService != null) {
         this.bountyMapService.shutdown();
      }

      if (this.bountyManagerInstance != null) {
         this.bountyManagerInstance.shutdown();
      }

      if (this.webApi != null) {
         this.webApi.stop();
      }

      if (this.statsTracker != null) {
         this.statsTracker.shutdown();
      }

      if (this.networkNotificationManager != null) {
         this.networkNotificationManager.shutdown();
      }

      if (this.networkPlayerDirectory != null) {
         this.networkPlayerDirectory.shutdown();
      }

      if (this.networkStatusManager != null) {
         this.networkStatusManager.shutdown();
      }

      if (this.networkTotalsManager != null) {
         this.networkTotalsManager.shutdown();
      }

      if (this.snapshotManager != null) {
         this.snapshotManager.save("shutdown");
      }

      if (this.db != null) {
         if (this.db.isHealthy()) {
            this.db.replayJournal();
         }

         this.db.close();
      }
   }

   public Db getDb() {
      return this.db;
   }

   public SnapshotManager getSnapshotManager() {
      return this.snapshotManager;
   }

   public SimpleEconomy getEconomy() {
      return econ;
   }

   public LuckPerms getLuckPerms() {
      return this.luckPerms;
   }

   public CombatLogout getCombatLogout() {
      return this.combatLogoutInstance;
   }

   public CombatManager getCombatManager() {
      return this.combatManagerInstance;
   }

   public EggManager getEggManager() {
      return this.eggManagerInstance;
   }

   public GemManager getGemManager() {
      return this.gemManagerInstance;
   }

   public ChatTagManager getChatTagManager() {
      return this.chatTagManagerInstance;
   }

   public VaultBridge getVaultBridge() {
      return this.vaultBridge;
   }

   public SellWandManager getSellWandManager() {
      return this.sellWandManagerInstance;
   }

   public SellMultiManager getSellMultiManager() {
      return this.sellMultiManagerInstance;
   }

   public FtpManager getFtpManager() {
      return this.ftpManagerInstance;
   }

   public BountyManager getBountyManager() {
      return this.bountyManagerInstance;
   }

   public BountyMapService getBountyMaps() {
      return this.bountyMapService;
   }

   public SkinCache getBountySkinCache() {
      return this.bountySkinCache;
   }

   public KeyallManager getKeyallManager() {
      return this.keyallManagerInstance;
   }

   public ScoreboardManager getScoreboardManager() {
      return this.scoreboardManager;
   }

   public FasterCrystalsBridge getFasterCrystalsBridge() {
      return this.fasterCrystalsBridge;
   }

   public WebApi getWebApi() {
      return this.webApi;
   }

   public StatsTracker getStatsTracker() {
      return this.statsTracker;
   }

   public LeaderboardCommand getLeaderboardCommand() {
      return this.leaderboardCommand;
   }

   public NetworkStatusManager getNetworkStatusManager() {
      return this.networkStatusManager;
   }

   public NetworkNotificationManager getNetworkNotificationManager() {
      return this.networkNotificationManager;
   }

   public NetworkTotalsManager getNetworkTotalsManager() {
      return this.networkTotalsManager;
   }

   public NetworkPlayerDirectory getNetworkPlayerDirectory() {
      return this.networkPlayerDirectory;
   }

   public ReplyCommand getReplyCommand() {
      return this.replyCommandInstance;
   }

   public boolean sendAuctionAlert(UUID recipient, String message) {
      return this.networkNotificationManager != null && this.networkNotificationManager.sendAuctionAlert(recipient, message);
   }

   public double getPlayerTotal(UUID uuid, String totalKey) {
      String path = this.totalConfigPath(uuid, totalKey);
      double fallback = path == null ? 0.0 : this.getConfig().getDouble(path, 0.0);
      return this.networkTotalsManager == null ? fallback : this.networkTotalsManager.get(uuid, totalKey, fallback);
   }

   public void addPlayerTotal(UUID uuid, String totalKey, double amount) {
      if (uuid != null && !(amount <= 0.0) && (this.networkTotalsManager == null || !this.networkTotalsManager.add(uuid, totalKey, amount))) {
         String path = this.totalConfigPath(uuid, totalKey);
         if (path != null) {
            Bukkit.getGlobalRegionScheduler().run(this, task -> {
               double prev = this.getConfig().getDouble(path, 0.0);
               this.getConfig().set(path, prev + amount);
               this.saveConfig();
            });
         }
      }
   }

   private String totalConfigPath(UUID uuid, String totalKey) {
      if (uuid != null && totalKey != null) {
         return switch (totalKey) {
            case "shop_spent" -> "shop.total." + uuid;
            case "sell_made" -> "sell.total." + uuid;
            case "ah_spent" -> "ah.spent." + uuid;
            case "ah_made" -> "ah.made." + uuid;
            default -> "totals." + totalKey + "." + uuid;
         };
      } else {
         return null;
      }
   }

   public void addSellEarnings(UUID uuid, double amount) {
      if (uuid != null && !(amount <= 0.0)) {
         this.addPlayerTotal(uuid, "sell_made", amount);
      }
   }

   public double appraiseSellStack(UUID uuid, ItemStack stack) {
      return uuid != null && this.sellMultiManagerInstance != null ? this.sellMultiManagerInstance.appraiseOnly(uuid, stack) : 0.0;
   }

   public void creditSellSale(UUID uuid, List<ItemStack> items, double payout) {
      if (uuid != null && !(payout <= 0.0)) {
         SellHistory.recordExternal(uuid, items, payout);
         if (this.sellMultiManagerInstance == null) {
            this.addSellEarnings(uuid, payout);
         } else {
            this.sellMultiManagerInstance.creditExternalSale(uuid, items, payout);
         }
      }
   }

   public void saveSellPriceToFile(Material m, double price) {
      this.savePrice("sellprices", m, price);
      PriceManager.setSellPrice(m, price);
   }

   public void saveShopPriceToFile(Material m, double price) {
      this.savePrice("shopprices", m, price);
      PriceManager.setShopPrice(m, price);
   }

   private void savePrice(String section, Material m, double price) {
      if (m != null) {
         this.pricesCfg.set(section + "." + m.name(), price);
         Bukkit.getGlobalRegionScheduler().run(this, task -> {
            try {
               this.pricesCfg.save(this.pricesFile);
            } catch (IOException e) {
               this.getLogger().warning("Failed to save prices.yml: " + e.getMessage());
            }
         });
      }
   }

   public boolean isPublicChatEnabledFor(Player p) {
      return this.getPlayerSetting(p, "public_chat");
   }

   public boolean isPrivateMessagesEnabledFor(Player p) {
      return this.getPlayerSetting(p, "private_messages");
   }

   public boolean isPrivateMessagesEnabledFor(UUID uuid) {
      boolean globalDefault = this.getConfig().getBoolean("settings.private_messages", true);
      return uuid == null ? globalDefault : this.getConfig().getBoolean("players." + uuid + ".private_messages", globalDefault);
   }

   public boolean isChatServerMessagesEnabledFor(Player p) {
      return this.getPlayerSetting(p, "chat_server_messages");
   }

   public boolean isHotbarServerMessagesEnabledFor(Player p) {
      return this.getPlayerSetting(p, "hotbar_server_messages");
   }

   public boolean isPayAlertsEnabledFor(Player p) {
      return this.getPlayerSetting(p, "pay_alerts");
   }

   public boolean isAuctionAlertsEnabledFor(Player p) {
      return this.getPlayerSetting(p, "auction_alerts");
   }

   public void setScoreboardEnabled(boolean enabled) {
      if (enabled) {
         if (this.scoreboardTask == null || this.scoreboardTask.isCancelled()) {
            this.scoreboardTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, task -> this.scoreboardManager.updateAll(), 20L, 20L);
         }
      } else {
         if (this.scoreboardTask != null) {
            this.scoreboardTask.cancel();
            this.scoreboardTask = null;
         }

         Bukkit.getOnlinePlayers().forEach(p -> this.scoreboardManager.removePlayer(p));
      }
   }

   public boolean isQuickAuctionBuyEnabledFor(Player p) {
      return p != null && this.hasQuickAuctionBuyPermission(p) ? this.getPlayerSetting(p, "quick_auction_buy") : false;
   }

   public boolean isPaymentsEnabledFor(Player p) {
      return this.isPaymentsEnabledFor(p == null ? null : p.getUniqueId());
   }

   public boolean isPaymentsEnabledFor(UUID uuid) {
      boolean globalDefault = this.getConfig().getBoolean("settings.payments", true);
      return uuid == null ? globalDefault : this.getConfig().getBoolean("players." + uuid + ".payments", globalDefault);
   }

   public boolean isScoreboardEnabledFor(Player p) {
      return this.getPlayerSetting(p, "scoreboard");
   }

   public boolean hasQuickAuctionBuyPermission(Player p) {
      return p != null && (p.hasPermission("taxsmp.quickauctionbuy") || p.hasPermission("taxsmp.quickbuy"));
   }

   public boolean isMobSpawnsDisabledFor(Player p) {
      return !this.getPlayerSetting(p, "mob_spawns");
   }

   public boolean isFastCrystalsEnabledFor(Player p) {
      return p == null ? this.getConfig().getBoolean("settings.fast_crystals", true) : this.getPlayerSetting(p, "fast_crystals");
   }

   public boolean shouldUseInternalFastCrystals() {
      return this.fasterCrystalsBridge == null || !this.fasterCrystalsBridge.isExternalAvailable();
   }

   public boolean isWorthDisplayEnabledFor(Player p) {
      return this.getPlayerSetting(p, "worth_display");
   }

   public boolean isTeamChatEnabledFor(Player p) {
      return this.getPlayerSetting(p, "team_chat");
   }

   public SettingsSyncManager getSettingsSyncManager() {
      return this.settingsSyncManager;
   }

   public void applySettingSideEffects(Player player, String key, boolean next) {
      switch (key) {
         case "tpa_requests":
            Plugin tpaxx = Bukkit.getPluginManager().getPlugin("TPAPlugin");
            if (tpaxx != null) {
               try {
                  tpaxx.getClass().getMethod("setTPAEnabled", UUID.class, boolean.class).invoke(tpaxx, player.getUniqueId(), next);
               } catch (Exception var13) {
               }
            }
            break;
         case "tpa_here_requests":
            Plugin tpax = Bukkit.getPluginManager().getPlugin("TPAPlugin");
            if (tpax != null) {
               try {
                  tpax.getClass().getMethod("setTPAHereEnabled", UUID.class, boolean.class).invoke(tpax, player.getUniqueId(), next);
               } catch (Exception var12) {
               }
            }
            break;
         case "tpa_confirm_menus":
            Plugin tpa = Bukkit.getPluginManager().getPlugin("TPAPlugin");
            if (tpa != null) {
               try {
                  tpa.getClass().getMethod("setConfirmMenuEnabled", UUID.class, boolean.class).invoke(tpa, player.getUniqueId(), next);
               } catch (Exception var11) {
               }
            }
            break;
         case "scoreboard":
            ScoreboardManager manager = this.getScoreboardManager();
            if (manager != null) {
               if (!next) {
                  manager.removePlayer(player);
               } else {
                  manager.refreshPlayer(player);
               }
            }
            break;
         case "fast_crystals":
            FasterCrystalsBridge bridge = this.getFasterCrystalsBridge();
            if (bridge != null) {
               bridge.setExternalEnabled(player, next);
            }
      }
   }

   public boolean getPlayerSetting(Player p, String key) {
      boolean globalDefault = this.getConfig().getBoolean("settings." + key, this.defaultSettingValue(key));
      return p == null ? globalDefault : this.getConfig().getBoolean("players." + p.getUniqueId() + "." + key, globalDefault);
   }

   public boolean defaultSettingValue(String key) {
      return !DEFAULT_FALSE_SETTINGS.contains(key);
   }

   public interface SettingsHook {
      void onSettingChanged(UUID var1, String var2, boolean var3);
   }
}
