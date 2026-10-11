package com.taxsmp.storage;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class SnapshotManager {
   private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
   private final JavaPlugin plugin;
   private final File dir;
   private final List<SnapshotManager.Section> sections = new CopyOnWriteArrayList<>();
   private final int intervalMinutes;
   private final int keep;

   public SnapshotManager(JavaPlugin plugin) {
      this.plugin = plugin;
      this.dir = new File(plugin.getDataFolder(), "snapshots");
      this.dir.mkdirs();
      this.intervalMinutes = Math.max(1, plugin.getConfig().getInt("mysql.snapshot-minutes", 5));
      this.keep = Math.max(4, plugin.getConfig().getInt("mysql.snapshot-keep", 288));
   }

   public void register(SnapshotManager.Section section) {
      if (section != null) {
         this.sections.add(section);
      }
   }

   public void start() {
      Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin, task -> this.save("auto"), this.intervalMinutes, this.intervalMinutes, TimeUnit.MINUTES);
      this.plugin
         .getLogger()
         .info("Local data snapshots enabled: every " + this.intervalMinutes + " minute(s), keeping the newest " + this.keep + " in plugins/TaxSMP/snapshots/.");
   }

   public void save(String reason) {
      if (!this.sections.isEmpty()) {
         YamlConfiguration out = new YamlConfiguration();
         out.set("taken-at", System.currentTimeMillis());
         out.set("taken-at-readable", STAMP.format(new Date()));
         out.set("reason", reason);
         out.set("server-region", this.plugin.getConfig().getString("region", "unknown"));

         for (SnapshotManager.Section s : this.sections) {
            try {
               s.writeSnapshot(out.createSection("data." + s.sectionName()));
            } catch (Throwable t) {
               this.plugin.getLogger().warning("Could not snapshot section '" + s.sectionName() + "': " + t.getMessage());
            }
         }

         File target = new File(this.dir, "snapshot-" + STAMP.format(new Date()) + "-" + reason + ".yml");

         try {
            out.save(target);
         } catch (IOException e) {
            this.plugin.getLogger().warning("Could not write snapshot " + target.getName() + ": " + e.getMessage());
            return;
         }

         this.prune();
      }
   }

   private void prune() {
      File[] files = this.dir.listFiles((d, name) -> name.startsWith("snapshot-") && name.endsWith(".yml"));
      if (files != null && files.length > this.keep) {
         List<File> sorted = new ArrayList<>(Arrays.asList(files));
         sorted.sort(Comparator.comparing(File::getName));

         for (int i = 0; i < sorted.size() - this.keep; i++) {
            if (!sorted.get(i).delete()) {
               this.plugin.getLogger().fine("Could not delete old snapshot " + sorted.get(i).getName());
            }
         }
      }
   }

   public File newestSnapshot() {
      File[] files = this.dir.listFiles((d, name) -> name.startsWith("snapshot-") && name.endsWith(".yml"));
      return files != null && files.length != 0 ? Arrays.stream(files).max(Comparator.comparing(File::getName)).orElse(null) : null;
   }

   public boolean restoreNewest() {
      File newest = this.newestSnapshot();
      if (newest == null) {
         this.plugin
            .getLogger()
            .severe(
               "MySQL is down and there is no local snapshot to fall back on. Balances will read as zero until the database returns - do NOT let players spend or trade until then."
            );
         return false;
      }

      YamlConfiguration in = YamlConfiguration.loadConfiguration(newest);
      int restored = 0;

      for (SnapshotManager.Section s : this.sections) {
         ConfigurationSection cs = in.getConfigurationSection("data." + s.sectionName());
         if (cs != null) {
            try {
               s.readSnapshot(cs);
               restored++;
            } catch (Throwable t) {
               this.plugin.getLogger().warning("Could not restore section '" + s.sectionName() + "': " + t.getMessage());
            }
         }
      }

      this.plugin
         .getLogger()
         .warning(
            "Restored "
               + restored
               + " data section(s) from local snapshot "
               + newest.getName()
               + " (taken "
               + in.getString("taken-at-readable", "?")
               + "). Changes made now are queued and will be pushed to MySQL when it comes back."
         );
      return true;
   }

   public interface Section {
      String sectionName();

      void writeSnapshot(ConfigurationSection var1);

      void readSnapshot(ConfigurationSection var1);
   }
}
