package com.taxsmp;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;

public class FastCrystalListener implements Listener {
   private static final float CRYSTAL_POWER = 6.0F;
   private static final double CRYSTAL_REACH = 6.0;
   private static final double CRYSTAL_HIT_TOLERANCE = 0.5;
   private final TaxSMP plugin;
   private final Set<UUID> explodingCrystals = ConcurrentHashMap.newKeySet();

   public FastCrystalListener(TaxSMP plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onCrystalInteract(PlayerInteractEvent event) {
      if (this.plugin.shouldUseInternalFastCrystals()) {
         Player player = event.getPlayer();
         if (this.plugin.isFastCrystalsEnabledFor(player)) {
            if (event.getAction() == Action.LEFT_CLICK_AIR || event.getAction() == Action.LEFT_CLICK_BLOCK) {
               EnderCrystal target = this.rayTraceCrystal(player);
               if (target != null) {
                  event.setCancelled(true);
                  this.explodeCrystal(player, target);
               }
            } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK
               && event.getHand() != null
               && event.getItem() != null
               && event.getItem().getType() == Material.END_CRYSTAL) {
               Block base = event.getClickedBlock();
               if (base != null && this.isCrystalBase(base.getType()) && this.canPlaceCrystal(base)) {
                  event.setCancelled(true);
                  event.setUseInteractedBlock(Result.DENY);
                  event.setUseItemInHand(Result.DENY);
                  Location spawnLocation = base.getLocation().add(0.5, 1.0, 0.5);
                  base.getWorld().spawn(spawnLocation, EnderCrystal.class, false, crystal -> {
                     crystal.setShowingBottom(false);
                     crystal.setPersistent(false);
                  });
                  this.consumeCrystal(player, event.getHand());
               }
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onCrystalRightClick(PlayerInteractEntityEvent event) {
      if (this.plugin.shouldUseInternalFastCrystals() && event.getRightClicked() instanceof EnderCrystal crystal) {
         Player var4 = event.getPlayer();
         if (this.plugin.isFastCrystalsEnabledFor(var4)) {
            event.setCancelled(true);
            this.explodeCrystal(var4, crystal);
         }
      }
   }

   private EnderCrystal rayTraceCrystal(Player player) {
      Location eye = player.getEyeLocation();
      World world = player.getWorld();
      RayTraceResult result = world.rayTrace(
         eye, eye.getDirection(), 6.0, FluidCollisionMode.NEVER, true, 0.5, entity -> entity instanceof EnderCrystal && entity.isValid()
      );
      return (result == null ? null : result.getHitEntity()) instanceof EnderCrystal crystal ? crystal : null;
   }

   private void explodeCrystal(Player popper, EnderCrystal crystal) {
      if (crystal != null && crystal.isValid() && !crystal.isDead() && this.explodingCrystals.add(crystal.getUniqueId())) {
         CombatManager combat = this.plugin.getCombatManager();
         if (combat != null) {
            combat.noteCrystalOwner(popper, crystal);
         }

         try {
            Location location = crystal.getLocation();
            crystal.getWorld().createExplosion(crystal, location, 6.0F, false, true);
            crystal.remove();
         } finally {
            this.explodingCrystals.remove(crystal.getUniqueId());
         }
      }
   }

   private boolean canPlaceCrystal(Block base) {
      Block first = base.getRelative(BlockFace.UP);
      Block second = first.getRelative(BlockFace.UP);
      if (this.isCrystalSpace(first.getType()) && this.isCrystalSpace(second.getType())) {
         Location center = base.getLocation().add(0.5, 1.0, 0.5);
         BoundingBox crystalBox = BoundingBox.of(center, 1.0, 1.0, 1.0);
         return base.getWorld().getNearbyEntities(crystalBox, entity -> entity.isValid() && !entity.isDead()).isEmpty();
      } else {
         return false;
      }
   }

   private boolean isCrystalBase(Material material) {
      return material == Material.OBSIDIAN || material == Material.BEDROCK;
   }

   private boolean isCrystalSpace(Material material) {
      return material.isAir() || material == Material.FIRE || material == Material.SOUL_FIRE;
   }

   private void consumeCrystal(Player player, EquipmentSlot hand) {
      if (player.getGameMode() != GameMode.CREATIVE) {
         ItemStack item = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
         if (item != null && item.getType() == Material.END_CRYSTAL) {
            if (item.getAmount() <= 1) {
               ItemStack empty = new ItemStack(Material.AIR);
               if (hand == EquipmentSlot.OFF_HAND) {
                  player.getInventory().setItemInOffHand(empty);
               } else {
                  player.getInventory().setItemInMainHand(empty);
               }
            } else {
               item.setAmount(item.getAmount() - 1);
            }
         }
      }
   }
}
