package com.taxsmp;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent.Action;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public class NightVisionCommand implements CommandExecutor, Listener {
   private final TaxSMP plugin;
   private final Set<UUID> active = ConcurrentHashMap.newKeySet();

   public NightVisionCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p) {
         UUID id = p.getUniqueId();
         if (this.active.contains(id)) {
            this.active.remove(id);
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
            p.sendMessage("Night vision disabled.");
         } else {
            this.active.add(id);
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, true));
            p.sendMessage("Night vision enabled (persists through death).");
         }

         return true;
      } else {
         sender.sendMessage("Only players can use this command.");
         return true;
      }
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent e) {
      Player p = e.getPlayer();
      if (this.active.contains(p.getUniqueId())) {
         p.getScheduler().runDelayed(this.plugin, task -> {
            if (p.isOnline()) {
               p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, true));
            }
         }, null, 1L);
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent e) {
      Player p = e.getPlayer();
      if (this.active.contains(p.getUniqueId())) {
         p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, true));
      }
   }

   @EventHandler
   public void onPotionEffectChange(EntityPotionEffectEvent e) {
      if (e.getEntity() instanceof Player p
         && e.getModifiedType() != null
         && e.getModifiedType().equals(PotionEffectType.NIGHT_VISION)
         && e.getAction() == Action.REMOVED
         && this.active.contains(p.getUniqueId())) {
         p.getScheduler().runDelayed(this.plugin, task -> {
            if (p.isOnline() && !p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
               p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, true));
            }
         }, null, 1L);
      }
   }

   @EventHandler
   public void onResurrect(EntityResurrectEvent e) {
      if (e.getEntity() instanceof Player p && this.active.contains(p.getUniqueId())) {
         p.getScheduler().runDelayed(this.plugin, task -> {
            if (p.isOnline() && !p.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
               p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, true));
            }
         }, null, 1L);
      }
   }
}
