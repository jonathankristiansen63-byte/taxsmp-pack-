package com.taxsmp;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public class FasterCrystalsBridge implements Listener {
   private static final String PLUGIN_NAME = "FasterCrystals";
   private static final String SETTING_KEY = "fast_crystals";
   private static final String PDC_KEY = "fastcrystals";
   private final TaxSMP plugin;
   private Plugin fasterCrystals;
   private NamespacedKey fasterCrystalsKey;
   private Object fasterCrystalsApi;

   public FasterCrystalsBridge(TaxSMP plugin) {
      this.plugin = plugin;
      this.refreshExternalPlugin();
   }

   public boolean isExternalAvailable() {
      return this.getExternalPlugin() != null;
   }

   public void syncPlayer(Player player) {
      if (player != null) {
         this.setExternalEnabled(player, this.plugin.getPlayerSetting(player, "fast_crystals"));
      }
   }

   public void setExternalEnabled(Player player, boolean enabled) {
      Plugin external = this.getExternalPlugin();
      if (external != null && player != null && this.fasterCrystalsKey != null && !this.setViaApi(player, enabled)) {
         player.getPersistentDataContainer().set(this.fasterCrystalsKey, PersistentDataType.BYTE, (byte)(enabled ? 1 : 0));
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerJoin(PlayerJoinEvent event) {
      event.getPlayer().getScheduler().run(this.plugin, task -> this.syncPlayer(event.getPlayer()), null);
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPluginEnable(PluginEnableEvent event) {
      if ("FasterCrystals".equalsIgnoreCase(event.getPlugin().getName())) {
         this.refreshExternalPlugin();
         Bukkit.getOnlinePlayers().forEach(this::syncPlayer);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPluginDisable(PluginDisableEvent event) {
      if ("FasterCrystals".equalsIgnoreCase(event.getPlugin().getName())) {
         this.fasterCrystals = null;
         this.fasterCrystalsKey = null;
         this.fasterCrystalsApi = null;
      }
   }

   private Plugin getExternalPlugin() {
      return this.fasterCrystals != null && this.fasterCrystals.isEnabled() ? this.fasterCrystals : this.refreshExternalPlugin();
   }

   private Plugin refreshExternalPlugin() {
      Plugin external = this.plugin.getServer().getPluginManager().getPlugin("FasterCrystals");
      if (external != null && external.isEnabled()) {
         this.fasterCrystals = external;
         this.fasterCrystalsKey = new NamespacedKey(external, "fastcrystals");
         this.fasterCrystalsApi = this.resolveApi();
         return external;
      } else {
         this.fasterCrystals = null;
         this.fasterCrystalsKey = null;
         this.fasterCrystalsApi = null;
         return null;
      }
   }

   private Object resolveApi() {
      try {
         Class<?> apiClass = Class.forName("xyz.reknown.fastercrystals.api.FasterCrystalsAPI");
         return apiClass.getMethod("getInstance").invoke(null);
      } catch (Throwable ignored) {
         return null;
      }
   }

   private boolean setViaApi(Player player, boolean enabled) {
      Object api = this.fasterCrystalsApi;
      if (api == null) {
         api = this.resolveApi();
         this.fasterCrystalsApi = api;
      }

      if (api == null) {
         return false;
      }

      try {
         api.getClass().getMethod("setFastCrystals", Player.class, boolean.class).invoke(api, player, enabled);
         return true;
      } catch (Throwable ignored) {
         return false;
      }
   }
}
