package com.taxsmp.ui;

import com.taxsmp.TaxSMP;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class MenuCommand implements CommandExecutor, TabCompleter {
   private final TaxSMP plugin;
   private final TaxMenus menus;

   public MenuCommand(TaxSMP plugin, TaxMenus menus) {
      this.plugin = plugin;
      this.menus = menus;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (args.length >= 1 && args[0].equalsIgnoreCase("reloadmenus")) {
         if (!sender.hasPermission("taxsmp.menus.reload")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
         } else {
            this.plugin.reinstallMenuDatapack(sender);
            return true;
         }
      } else if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
         if (!sender.hasPermission("taxsmp.reload")) {
            sender.sendMessage("§cYou do not have permission to use this command.");
            return true;
         }

         this.plugin.flushPendingConfigSave();
         this.plugin.reloadConfig();
         if (this.plugin.getWebApi() != null) {
            this.plugin.getWebApi().restart();
         }

         sender.sendMessage("§aTaxSMP config reloaded.");
         return true;
      } else if (sender instanceof Player player) {
         if (!this.menus.enabledFor(player)) {
            player.sendMessage(
               this.menus.disabledOnServer()
                  ? "§cThe TaxSMP menu is switched off on this server."
                  : "§cThis menu needs Minecraft 1.21.11 or newer on Java. Use the commands instead."
            );
            return true;
         }

         String screen = args.length >= 2 && args[0].equalsIgnoreCase("open")
            ? args[1].toLowerCase(Locale.ROOT)
            : (args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "");
         if (screen.isEmpty()) {
            this.menus.openRoot(player);
         } else if (screen.equals("settings") && args.length >= 3) {
            this.menus.openSettings(player, TaxMenus.SettingsCategory.byName(args[2]));
         } else {
            this.menus.openScreen(player, screen);
         }

         return true;
      } else {
         sender.sendMessage("§cOnly players can open the menu.");
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      List<String> out = new ArrayList<>();
      if (args.length == 1) {
         String prefix = args[0].toLowerCase(Locale.ROOT);

         for (String option : MenuLayout.screens()) {
            if (option.startsWith(prefix)) {
               out.add(option);
            }
         }

         if (sender.hasPermission("taxsmp.menus.reload") && "reloadmenus".startsWith(prefix)) {
            out.add("reloadmenus");
         }

         if (sender.hasPermission("taxsmp.reload") && "reload".startsWith(prefix)) {
            out.add("reload");
         }
      } else if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
         String prefix = args[1].toLowerCase(Locale.ROOT);

         for (String option : MenuLayout.screens()) {
            if (option.startsWith(prefix)) {
               out.add(option);
            }
         }
      }

      return out;
   }
}
