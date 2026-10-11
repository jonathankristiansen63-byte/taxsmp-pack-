package com.taxsmp.bounty;

import com.taxsmp.NetworkPlayerDirectory;
import com.taxsmp.PriceManager;
import com.taxsmp.SimpleEconomy;
import com.taxsmp.TaxSMP;
import com.taxsmp.storage.Db;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public class BountyManager implements Listener {
   public static final String RED = ChatColor.RED.toString();
   public static final String RED_BOLD = ChatColor.RED.toString() + ChatColor.BOLD;
   public static final String MINT = "§x§5§5§F§F§B§8";
   private static final long REFRESH_SECONDS = 15L;
   private final TaxSMP plugin;
   private final Db db;
   private final Map<UUID, Map<UUID, Double>> contributions = new ConcurrentHashMap<>();
   private final Map<UUID, String> names = new ConcurrentHashMap<>();
   private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
   private File file;
   private FileConfiguration yaml;
   private ScheduledTask refreshTask;

   public BountyManager(TaxSMP plugin) {
      this.plugin = plugin;
      this.db = plugin.getDb();
      if (this.usingDb()) {
         if (this.db.isHealthy()) {
            this.db.async(this::initDb);
         }

         this.db.onReconnect(this::reloadFromDb);
         this.refreshTask = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> this.reloadFromDb(), 15L, 15L, TimeUnit.SECONDS);
      } else {
         this.loadFromFile();
      }
   }

   public void shutdown() {
      if (this.refreshTask != null) {
         this.refreshTask.cancel();
         this.refreshTask = null;
      }

      if (!this.usingDb()) {
         this.saveToFile();
      }
   }

   private boolean usingDb() {
      return this.db != null && this.db.isEnabled();
   }

   public double minimumBounty() {
      return Math.max(0.01, this.plugin.getConfig().getDouble("bounty.min-amount", 1.0));
   }

   public double maximumBounty() {
      return this.plugin.getConfig().getDouble("bounty.max-total", 0.0);
   }

   public boolean allowSelfBounty() {
      return this.plugin.getConfig().getBoolean("bounty.allow-self", false);
   }

   public int topSize() {
      return Math.max(1, Math.min(10, this.plugin.getConfig().getInt("bounty.board-size", 10)));
   }

   public double total(UUID target) {
      Map<UUID, Double> parts = this.contributions.get(target);
      if (parts == null) {
         return 0.0;
      }

      double sum = 0.0;

      for (double v : parts.values()) {
         sum += v;
      }

      return sum;
   }

   public Map<UUID, Double> contributors(UUID target) {
      Map<UUID, Double> parts = this.contributions.get(target);
      if (parts != null && !parts.isEmpty()) {
         Map<UUID, Double> sorted = new LinkedHashMap<>();
         parts.entrySet().stream().sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()).forEach(e -> sorted.put(e.getKey(), e.getValue()));
         return sorted;
      } else {
         return Map.of();
      }
   }

   public List<BountyManager.Entry> top(int limit) {
      List<BountyManager.Entry> all = new ArrayList<>();

      for (Map.Entry<UUID, Map<UUID, Double>> e : this.contributions.entrySet()) {
         double sum = this.total(e.getKey());
         if (sum > 0.0) {
            all.add(new BountyManager.Entry(e.getKey(), this.nameOf(e.getKey()), sum));
         }
      }

      all.sort(Comparator.comparingDouble(BountyManager.Entry::amount).reversed().thenComparing(BountyManager.Entry::name, String.CASE_INSENSITIVE_ORDER));
      return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
   }

   public int activeCount() {
      int n = 0;

      for (UUID target : this.contributions.keySet()) {
         if (this.total(target) > 0.0) {
            n++;
         }
      }

      return n;
   }

   public String nameOf(UUID uuid) {
      String cached = this.names.get(uuid);
      if (cached != null && !cached.isBlank()) {
         return cached;
      }

      Player online = Bukkit.getPlayer(uuid);
      if (online != null) {
         this.names.put(uuid, online.getName());
         return online.getName();
      }

      if (this.plugin.getNetworkPlayerDirectory() != null) {
         NetworkPlayerDirectory.KnownPlayer known = this.plugin.getNetworkPlayerDirectory().find(uuid);
         if (known != null && known.name() != null) {
            this.names.put(uuid, known.name());
            return known.name();
         }
      }

      return "Unknown";
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onJoin(PlayerJoinEvent event) {
      this.rememberName(event.getPlayer().getUniqueId(), event.getPlayer().getName());
   }

   public void rememberName(UUID uuid, String name) {
      if (uuid != null && name != null && !name.isBlank()) {
         this.names.put(uuid, name);
      }
   }

   public void onChange(Runnable listener) {
      if (listener != null) {
         this.changeListeners.add(listener);
      }
   }

   private void fireChange() {
      for (Runnable r : this.changeListeners) {
         try {
            r.run();
         } catch (Throwable t) {
            this.plugin.getLogger().warning("A bounty change listener failed: " + t);
         }
      }
   }

   public void add(UUID target, String targetName, UUID placer, double amount) {
      if (target != null && placer != null && !(amount <= 0.0)) {
         this.rememberName(target, targetName);
         this.contributions.computeIfAbsent(target, k -> new ConcurrentHashMap<>()).merge(placer, amount, Double::sum);
         if (this.usingDb()) {
            long now = System.currentTimeMillis();
            String name = this.names.get(target);
            Bukkit.getAsyncScheduler()
               .runNow(
                  this.plugin,
                  task -> this.db
                     .writeDelta(
                        target,
                        "INSERT INTO taxsmp_bounties (target, placer, amount, target_name, placed_at) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + ?, target_name = VALUES(target_name)",
                        target.toString(),
                        placer.toString(),
                        amount,
                        name,
                        now,
                        amount
                     )
               );
         } else {
            this.saveToFile();
         }

         this.fireChange();
      }
   }

   private Map<UUID, Double> takeFromDatabase(UUID target) throws SQLException {
      Map<UUID, Double> taken = new HashMap<>();

      try (Connection conn = this.db.getConnection()) {
         boolean previousAutoCommit = conn.getAutoCommit();
         conn.setAutoCommit(false);

         try (PreparedStatement ps = conn.prepareStatement("SELECT placer, amount FROM taxsmp_bounties WHERE target = ? FOR UPDATE")) {
            ps.setString(1, target.toString());

            try (ResultSet rs = ps.executeQuery()) {
               while (rs.next()) {
                  try {
                     double amount = rs.getDouble("amount");
                     if (amount > 0.0) {
                        taken.merge(UUID.fromString(rs.getString("placer")), amount, Double::sum);
                     }
                  } catch (IllegalArgumentException var25) {
                  }
               }
            }

            if (!taken.isEmpty()) {
               try (PreparedStatement del = conn.prepareStatement("DELETE FROM taxsmp_bounties WHERE target = ?")) {
                  del.setString(1, target.toString());
                  del.executeUpdate();
               }
            }

            conn.commit();
            return taken;
         } catch (SQLException e) {
            conn.rollback();
            throw e;
         } finally {
            conn.setAutoCommit(previousAutoCommit);
         }
      }
   }

   private Map<UUID, Double> takeLocally(UUID target) {
      Map<UUID, Double> removed = this.contributions.remove(target);
      if (removed == null) {
         return Map.of();
      }

      this.fireChange();
      return removed;
   }

   private static double sum(Map<UUID, Double> parts) {
      double total = 0.0;

      for (double v : parts.values()) {
         total += v;
      }

      return total;
   }

   private void take(UUID target, Consumer<Map<UUID, Double>> done) {
      Map<UUID, Double> local = this.takeLocally(target);
      if (!this.usingDb()) {
         this.saveToFile();
         done.accept(local);
      } else {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            if (this.db.isHealthy()) {
               try {
                  done.accept(this.takeFromDatabase(target));
                  return;
               } catch (SQLException e) {
                  this.plugin.getLogger().warning("Could not settle the bounty on " + target + " against MySQL: " + e.getMessage());
               }
            }

            if (!local.isEmpty()) {
               this.db.writeIdempotent(target, "DELETE FROM taxsmp_bounties WHERE target = ?", target.toString());
            }

            done.accept(local);
         });
      }
   }

   public void clear(UUID target) {
      this.take(target, taken -> {});
   }

   public void refund(UUID target, Consumer<Double> done) {
      this.take(
         target,
         taken -> {
            double was = sum(taken);
            if (was <= 0.0) {
               done.accept(0.0);
            } else {
               SimpleEconomy econ = this.plugin.getEconomy();
               if (econ != null) {
                  for (Map.Entry<UUID, Double> e : taken.entrySet()) {
                     econ.depositPlayer(Bukkit.getOfflinePlayer(e.getKey()), e.getValue());
                     this.alert(
                        e.getKey(),
                        "§7The bounty on §f"
                           + this.nameOf(target)
                           + " §7was cancelled. You were refunded §x§5§5§F§F§B§8$"
                           + PriceManager.formatShort(e.getValue())
                           + "§7."
                     );
                  }
               }

               done.accept(was);
            }
         }
      );
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerDeath(PlayerDeathEvent event) {
      Player victim = event.getEntity();
      UUID victimId = victim.getUniqueId();
      if (!(this.total(victimId) <= 0.0)) {
         if (!this.plugin.getConfig().getBoolean("bounty.ignore-afk-world", true) || !victim.getWorld().getName().equalsIgnoreCase("afk")) {
            UUID killerId = this.plugin.getCombatManager() == null ? null : this.plugin.getCombatManager().resolveKiller(victim);
            if (killerId == null) {
               Player direct = victim.getKiller();
               killerId = direct == null ? null : direct.getUniqueId();
            }

            if (killerId != null && !killerId.equals(victimId)) {
               String victimName = victim.getName();
               this.rememberName(victimId, victimName);
               Player killer = Bukkit.getPlayer(killerId);
               String killerName = killer != null ? killer.getName() : this.nameOf(killerId);
               this.rememberName(killerId, killerName);
               UUID payee = killerId;
               this.take(victimId, taken -> {
                  double claimed = sum(taken);
                  if (!(claimed <= 0.0)) {
                     SimpleEconomy econ = this.plugin.getEconomy();
                     if (econ != null) {
                        econ.depositPlayer(Bukkit.getOfflinePlayer(payee), claimed);
                     }

                     String money = "§x§5§5§F§F§B§8$" + PriceManager.formatShort(claimed);
                     this.alert(payee, "§7You killed §f" + victimName + " §7and got their bounty of " + money + "§7.");
                     this.alert(victimId, "§f" + killerName + " §7killed you and took your " + money + " §7bounty.");
                     Player online = Bukkit.getPlayer(payee);
                     if (online != null && online.isOnline()) {
                        online.getScheduler().run(this.plugin, t -> online.playSound(online.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0F, 1.2F), null);
                     }

                     this.plugin.getLogger().info(killerName + " claimed the $" + PriceManager.formatShort(claimed) + " bounty on " + victimName + ".");
                  }
               });
            }
         }
      }
   }

   public void alert(UUID uuid, String message) {
      Player local = Bukkit.getPlayer(uuid);
      if (local != null && local.isOnline()) {
         if (this.plugin.getPlayerSetting(local, "bounty_alerts")) {
            local.getScheduler().run(this.plugin, t -> local.sendMessage(message), null);
         }
      } else {
         if (this.plugin.getNetworkNotificationManager() != null) {
            this.plugin.getNetworkNotificationManager().send(uuid, message, Sound.ENTITY_PLAYER_LEVELUP.name(), "bounty");
         }
      }
   }

   private void createTable() {
      try (
         Connection conn = this.db.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_bounties (target VARCHAR(36) NOT NULL,placer VARCHAR(36) NOT NULL,amount DOUBLE NOT NULL,target_name VARCHAR(32),placed_at BIGINT NOT NULL,PRIMARY KEY (target, placer))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_bounties table: " + e.getMessage());
      }
   }

   private void initDb() {
      this.createTable();
      this.reloadFromDb();
   }

   public void reloadFromDb() {
      if (this.usingDb() && this.db.isHealthy()) {
         this.createTable();
         Map<UUID, Map<UUID, Double>> loaded = new HashMap<>();
         Map<UUID, String> loadedNames = new HashMap<>();

         try (
            Connection conn = this.db.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT target, placer, amount, target_name FROM taxsmp_bounties");
            ResultSet rs = ps.executeQuery();
         ) {
            while (rs.next()) {
               try {
                  UUID target = UUID.fromString(rs.getString("target"));
                  UUID placer = UUID.fromString(rs.getString("placer"));
                  double amount = rs.getDouble("amount");
                  if (amount > 0.0) {
                     loaded.computeIfAbsent(target, k -> new HashMap<>()).merge(placer, amount, Double::sum);
                  }

                  String name = rs.getString("target_name");
                  if (name != null && !name.isBlank()) {
                     loadedNames.put(target, name);
                  }
               } catch (IllegalArgumentException var14) {
               }
            }
         } catch (SQLException e) {
            this.plugin.getLogger().warning("Could not load bounties from MySQL: " + e.getMessage());
            return;
         }

         boolean changed = false;

         for (Map.Entry<UUID, Map<UUID, Double>> e : loaded.entrySet()) {
            if (!this.db.hasPendingFor(e.getKey())) {
               Map<UUID, Double> fresh = new ConcurrentHashMap<>(e.getValue());
               Map<UUID, Double> previous = this.contributions.put(e.getKey(), fresh);
               changed |= previous == null || !previous.equals(fresh);
            }
         }

         for (UUID target : new ArrayList<>(this.contributions.keySet())) {
            if (!loaded.containsKey(target) && !this.db.hasPendingFor(target)) {
               this.contributions.remove(target);
               changed = true;
            }
         }

         this.names.putAll(loadedNames);
         if (changed) {
            this.fireChange();
         }
      }
   }

   private void loadFromFile() {
      this.file = new File(this.plugin.getDataFolder(), "bounties.yml");
      this.yaml = this.file.exists() ? YamlConfiguration.loadConfiguration(this.file) : new YamlConfiguration();
      ConfigurationSection root = this.yaml.getConfigurationSection("bounties");
      if (root != null) {
         for (String targetKey : root.getKeys(false)) {
            try {
               UUID target = UUID.fromString(targetKey);
               String name = root.getString(targetKey + ".name");
               if (name != null) {
                  this.names.put(target, name);
               }

               ConfigurationSection parts = root.getConfigurationSection(targetKey + ".placers");
               if (parts != null) {
                  Map<UUID, Double> map = new ConcurrentHashMap<>();

                  for (String placerKey : parts.getKeys(false)) {
                     try {
                        double amount = parts.getDouble(placerKey);
                        if (amount > 0.0) {
                           map.put(UUID.fromString(placerKey), amount);
                        }
                     } catch (IllegalArgumentException var12) {
                     }
                  }

                  if (!map.isEmpty()) {
                     this.contributions.put(target, map);
                  }
               }
            } catch (IllegalArgumentException var13) {
            }
         }
      }
   }

   private void saveToFile() {
      if (this.yaml == null) {
         this.file = new File(this.plugin.getDataFolder(), "bounties.yml");
         this.yaml = new YamlConfiguration();
      }

      this.yaml.set("bounties", null);

      for (Map.Entry<UUID, Map<UUID, Double>> e : this.contributions.entrySet()) {
         if (!e.getValue().isEmpty()) {
            String base = "bounties." + e.getKey();
            this.yaml.set(base + ".name", this.names.get(e.getKey()));

            for (Map.Entry<UUID, Double> part : e.getValue().entrySet()) {
               this.yaml.set(base + ".placers." + part.getKey(), part.getValue());
            }
         }
      }

      FileConfiguration snapshot = this.yaml;
      File target = this.file;
      Runnable save = () -> {
         try {
            synchronized (this) {
               snapshot.save(target);
            }
         } catch (IOException ex) {
            this.plugin.getLogger().warning("Could not save bounties.yml: " + ex.getMessage());
         }
      };
      // While the plugin shuts down, Folia refuses new async tasks, so save right here.
      if (this.plugin.isEnabled()) {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> save.run());
      } else {
         save.run();
      }
   }

   public record Entry(UUID uuid, String name, double amount) {
   }
}
