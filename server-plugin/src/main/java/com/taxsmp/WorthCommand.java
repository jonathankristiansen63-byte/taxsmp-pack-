package com.taxsmp;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class WorthCommand implements CommandExecutor, TabCompleter {
   private final TaxSMP plugin;

   public WorthCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p) {
         Material mat;
         if (args.length < 1) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
               p.sendMessage("§cUsage: /worth <item> or hold an item in your hand.");
               return true;
            }

            mat = hand.getType();
         } else {
            mat = Material.matchMaterial(args[0].toUpperCase());
            if (mat == null) {
               p.sendMessage("§cUnknown item: " + args[0]);
               return true;
            }
         }

         double sellPrice = PriceManager.getPrice(mat);
         String[] words = mat.name().toLowerCase().split("_");
         StringBuilder pretty = new StringBuilder();

         for (String w : words) {
            if (!pretty.isEmpty()) {
               pretty.append(' ');
            }

            pretty.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
         }

         String mint = "§x§5§5§F§F§B§8";
         if (sellPrice > 0.0) {
            p.sendMessage("§7" + pretty + " is worth " + mint + "$" + PriceManager.formatPrice(sellPrice));
         } else {
            p.sendMessage("§7" + pretty + " §cis not sellable");
         }

         return true;
      } else {
         sender.sendMessage("Use in-game.");
         return true;
      }
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
      if (args.length == 1) {
         String prefix = args[0].toLowerCase();
         List<String> completions = new ArrayList<>();

         for (Material m : Material.values()) {
            if (!m.isLegacy() && m.isItem()) {
               String name = m.name().toLowerCase();
               if (name.startsWith(prefix)) {
                  completions.add(name);
               }
            }
         }

         return completions;
      } else {
         return List.of();
      }
   }
}
