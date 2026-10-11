package com.taxsmp;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class CombatRemoveCommand implements CommandExecutor {
   private final CombatManager combatManager;
   private final JavaPlugin plugin;

   public CombatRemoveCommand(CombatManager combatManager, JavaPlugin plugin) {
      this.combatManager = combatManager;
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p && !p.hasPermission("taxsmp.combatremove")) {
         p.sendMessage("§cYou do not have permission.");
         return true;
      } else {
         this.combatManager.clearAllCombatAndGrantImmunity(10);
         String who = sender instanceof Player ? sender.getName() : "CONSOLE";
         Bukkit.getGlobalRegionScheduler()
            .run(this.plugin, task -> Bukkit.broadcastMessage("§aCombat cleared by §f" + who + "§a — players are immune for 10s"));
         return true;
      }
   }
}
