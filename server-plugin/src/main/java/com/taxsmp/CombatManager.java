package com.taxsmp;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class CombatManager implements Listener {
   private static final long CRYSTAL_OWNER_TTL_MS = 60000L;
   private static final long ANCHOR_OWNER_TTL_MS = 5000L;
   private static final double ANCHOR_BLAST_RADIUS_SQ = 144.0;
   private static final long KILL_CREDIT_MS = 15000L;
   private final TaxSMP plugin;
   private final CombatLogout combatLogout;
   private final Map<UUID, Integer> timers = new ConcurrentHashMap<>();
   private final Map<UUID, UUID> opponents = new ConcurrentHashMap<>();
   private final Map<UUID, Long> immuneUntil = new ConcurrentHashMap<>();
   private final Map<UUID, CombatManager.Attribution> crystalOwners = new ConcurrentHashMap<>();
   private final List<CombatManager.Blast> anchorBlasts = new CopyOnWriteArrayList<>();
   private final Map<UUID, CombatManager.Attribution> lastAttacker = new ConcurrentHashMap<>();

   public CombatManager(TaxSMP plugin, CombatLogout combatLogout) {
      this.plugin = plugin;
      this.combatLogout = combatLogout;
   }

   public boolean isInCombat(UUID uuid) {
      return uuid != null && this.timers.containsKey(uuid);
   }

   public boolean isInCombat(Player player) {
      return player != null && this.isInCombat(player.getUniqueId());
   }

   public int getRemainingSeconds(UUID uuid) {
      return uuid == null ? 0 : Math.max(this.timers.getOrDefault(uuid, 0), 0);
   }

   public int getRemainingSeconds(Player player) {
      return player == null ? 0 : this.getRemainingSeconds(player.getUniqueId());
   }

   public UUID resolveKiller(Player victim) {
      if (victim == null) {
         return null;
      }

      Player killer = victim.getKiller();
      if (killer != null) {
         return killer.getUniqueId();
      }

      UUID victimId = victim.getUniqueId();
      CombatManager.Attribution last = this.lastAttacker.get(victimId);
      if (last != null && System.currentTimeMillis() - last.at() <= 15000L && !last.player().equals(victimId)) {
         return last.player();
      }

      UUID opponentId = this.opponents.get(victimId);
      return opponentId != null && !opponentId.equals(victimId) ? opponentId : null;
   }

   public void noteCrystalOwner(Player player, Entity crystal) {
      if (player != null && crystal != null) {
         this.crystalOwners.put(crystal.getUniqueId(), new CombatManager.Attribution(player.getUniqueId(), System.currentTimeMillis()));
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onCrystalPlace(EntityPlaceEvent e) {
      if (e.getEntity() instanceof EnderCrystal crystal && e.getPlayer() != null) {
         this.noteCrystalOwner(e.getPlayer(), crystal);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onCrystalRightClick(PlayerInteractEntityEvent e) {
      if (e.getRightClicked() instanceof EnderCrystal crystal) {
         this.noteCrystalOwner(e.getPlayer(), crystal);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onAnchorUse(PlayerInteractEvent e) {
      if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
         Block block = e.getClickedBlock();
         if (block != null && block.getType() == Material.RESPAWN_ANCHOR) {
            Location loc = block.getLocation();
            this.pruneAnchorBlasts();
            this.anchorBlasts
               .add(
                  new CombatManager.Blast(
                     e.getPlayer().getUniqueId(), loc.getWorld().getName(), loc.getX() + 0.5, loc.getY() + 0.5, loc.getZ() + 0.5, System.currentTimeMillis()
                  )
               );
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onPlayerHit(EntityDamageByEntityEvent e) {
      if (!e.isCancelled() && !(e.getDamager() instanceof EnderPearl) && e.getEntity() instanceof Player victim) {
         Player attacker = this.resolveAttacker(e.getDamager());
         if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            this.tagPair(victim, attacker);
         }
      }
   }

   @EventHandler(ignoreCancelled = true)
   public void onBlockExplosionDamage(EntityDamageEvent e) {
      if (!(e instanceof EntityDamageByEntityEvent) && e.getCause() == DamageCause.BLOCK_EXPLOSION && e.getEntity() instanceof Player victim) {
         UUID ownerId = this.findAnchorOwner(victim.getLocation());
         if (ownerId != null && !ownerId.equals(victim.getUniqueId())) {
            Player attacker = Bukkit.getPlayer(ownerId);
            if (attacker != null) {
               this.tagPair(victim, attacker);
            }
         }
      }
   }

   private Player resolveAttacker(Entity damager) {
      if (damager instanceof Player player) {
         return player;
      } else if (!(damager instanceof EnderCrystal crystal)) {
         return null;
      } else {
         CombatManager.Attribution owner = this.crystalOwners.get(crystal.getUniqueId());
         return owner != null && System.currentTimeMillis() - owner.at() <= 60000L ? Bukkit.getPlayer(owner.player()) : null;
      }
   }

   private UUID findAnchorOwner(Location where) {
      this.pruneAnchorBlasts();
      if (where != null && where.getWorld() != null) {
         String world = where.getWorld().getName();
         UUID best = null;
         double bestDistance = Double.MAX_VALUE;

         for (CombatManager.Blast blast : this.anchorBlasts) {
            if (blast.world().equals(world)) {
               double dx = blast.x() - where.getX();
               double dy = blast.y() - where.getY();
               double dz = blast.z() - where.getZ();
               double distance = dx * dx + dy * dy + dz * dz;
               if (distance <= 144.0 && distance < bestDistance) {
                  bestDistance = distance;
                  best = blast.player();
               }
            }
         }

         return best;
      } else {
         return null;
      }
   }

   private void pruneAnchorBlasts() {
      long cutoff = System.currentTimeMillis() - 5000L;
      this.anchorBlasts.removeIf(blast -> blast.at() < cutoff);
   }

   private void tagPair(Player victim, Player attacker) {
      String victimWorld = victim.getWorld().getName();
      String attackerWorld = attacker.getWorld().getName();
      if (!victimWorld.equalsIgnoreCase("afk") && !attackerWorld.equalsIgnoreCase("afk")) {
         boolean inSpawn = victimWorld.equals("spawn") || attackerWorld.equals("spawn");
         if (inSpawn) {
            double healthBefore = victim.getHealth();
            victim.getScheduler().runDelayed(this.plugin, task -> {
               if (victim.isOnline() && healthBefore > victim.getHealth()) {
                  this.applyTags(victim, attacker);
               }
            }, null, 1L);
         } else {
            this.applyTags(victim, attacker);
         }
      }
   }

   private void applyTags(Player victim, Player attacker) {
      long now = System.currentTimeMillis();
      this.lastAttacker.put(victim.getUniqueId(), new CombatManager.Attribution(attacker.getUniqueId(), now));
      if (this.lastAttacker.size() > 256) {
         this.lastAttacker.values().removeIf(entry -> now - entry.at() > 15000L);
      }

      if (this.crystalOwners.size() > 512) {
         this.crystalOwners.values().removeIf(entry -> now - entry.at() > 60000L);
      }

      boolean victimTagged = !this.isImmune(victim.getUniqueId());
      boolean attackerTagged = !this.isImmune(attacker.getUniqueId());
      if (victimTagged) {
         this.startCombatTimer(victim);
      }

      if (attackerTagged) {
         this.startCombatTimer(attacker);
      }

      if (victimTagged && attackerTagged) {
         this.opponents.put(victim.getUniqueId(), attacker.getUniqueId());
         this.opponents.put(attacker.getUniqueId(), victim.getUniqueId());
      }
   }

   private boolean isImmune(UUID uuid) {
      Long until = this.immuneUntil.get(uuid);
      if (until == null) {
         return false;
      } else if (System.currentTimeMillis() > until) {
         this.immuneUntil.remove(uuid);
         return false;
      } else {
         return true;
      }
   }

   private void startCombatTimer(Player player) {
      UUID uuid = player.getUniqueId();
      if (this.timers.containsKey(uuid)) {
         this.timers.put(uuid, 20);
      } else {
         this.timers.put(uuid, 20);
         player.getScheduler().runAtFixedRate(this.plugin, task -> {
            Integer sec = this.timers.get(uuid);
            if (sec == null || !player.isOnline()) {
               this.timers.remove(uuid);
               task.cancel();
            } else if (sec <= 0) {
               this.clearCombat(player);
               this.removeOpponentLink(uuid);
               task.cancel();
            } else {
               try {
                  player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(this.combatCountdownText(sec)));
               } catch (Exception var6) {
               }

               this.timers.put(uuid, sec - 1);
            }
         }, null, 1L, 20L);
      }
   }

   public void clearAllCombatAndGrantImmunity(int seconds) {
      this.timers.clear();
      this.opponents.clear();
      long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(seconds);

      for (Player p : Bukkit.getOnlinePlayers()) {
         this.immuneUntil.put(p.getUniqueId(), until);
         p.getScheduler().run(this.plugin, t -> {
            try {
               p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent("§aCombat cleared — immunity for " + seconds + "s"));
            } catch (Exception var4) {
            }
         }, null);
      }

      Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> {
         long now = System.currentTimeMillis();
         this.immuneUntil.entrySet().removeIf(entry -> now >= entry.getValue());
      }, seconds, TimeUnit.SECONDS);
   }

   @EventHandler
   public void onPearlLand(ProjectileHitEvent e) {
      if (e.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player player) {
         this.immuneUntil.put(player.getUniqueId(), System.currentTimeMillis() + 100L);
      }
   }

   @EventHandler
   public void onPlayerQuit(PlayerQuitEvent e) {
      Player p = e.getPlayer();
      UUID uuid = p.getUniqueId();
      if (!this.timers.containsKey(uuid)) {
         this.lastAttacker.remove(uuid);
      } else {
         UUID opponentId = this.opponents.get(uuid);
         this.combatLogout.punish(p);
         this.lastAttacker.remove(uuid);
         this.clearCombat(p);
         this.removeOpponentLink(uuid);
         if (opponentId != null) {
            Player opponent = Bukkit.getPlayer(opponentId);
            if (opponent != null && this.timers.containsKey(opponentId)) {
               this.clearCombat(opponent);
               this.removeOpponentLink(opponentId);
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onPlayerDeath(PlayerDeathEvent e) {
      Player victim = e.getEntity();
      UUID victimId = victim.getUniqueId();
      UUID killerId = this.resolveKiller(victim);
      this.clearCombat(victim);
      this.removeOpponentLink(victimId);
      if (killerId != null && !killerId.equals(victimId)) {
         Player killer = Bukkit.getPlayer(killerId);
         if (killer != null) {
            this.clearCombat(killer);
            this.removeOpponentLink(killerId);
         }
      }
   }

   @EventHandler
   public void onRespawn(PlayerRespawnEvent e) {
      this.lastAttacker.remove(e.getPlayer().getUniqueId());
   }

   private void clearCombat(Player player) {
      this.timers.remove(player.getUniqueId());
   }

   private String combatCountdownText(int seconds) {
      return "§fCombat: §e" + Math.max(0, seconds) + "s";
   }

   private void removeOpponentLink(UUID uuid) {
      UUID opponentId = this.opponents.remove(uuid);
      if (opponentId != null) {
         this.opponents.remove(opponentId, uuid);
      }
   }

   private record Attribution(UUID player, long at) {
   }

   private record Blast(UUID player, String world, double x, double y, double z, long at) {
   }
}
