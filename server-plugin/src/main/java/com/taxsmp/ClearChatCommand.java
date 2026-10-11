package com.taxsmp;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class ClearChatCommand implements CommandExecutor {
   private final TaxSMP plugin;

   public ClearChatCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player && !sender.hasPermission("taxsmp.clearchat")) {
         sender.sendMessage("§cYou do not have permission.");
         return true;
      }

      String who = sender instanceof Player ? sender.getName() : "CONSOLE";

      for (Player pl : Bukkit.getOnlinePlayers()) {
         pl.getScheduler().run(this.plugin, task -> {
            for (int i = 0; i < 120; i++) {
               pl.sendMessage("");
            }
         }, null);
      }

      Bukkit.getGlobalRegionScheduler().runDelayed(this.plugin, task -> Bukkit.broadcastMessage("§aChat cleared by §f" + who), 2L);
      return true;
   }
}
