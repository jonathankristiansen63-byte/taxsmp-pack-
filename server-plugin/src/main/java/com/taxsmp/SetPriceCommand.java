package com.taxsmp;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class SetPriceCommand implements CommandExecutor {
   private final TaxSMP plugin;

   public SetPriceCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (sender instanceof Player p && !p.hasPermission("taxsmp.setprice")) {
         p.sendMessage("§cYou do not have permission.");
         return true;
      } else {
         if (args.length == 0) {
            sender.sendMessage("§cUsage: /setprice <price> (holding item) OR /setprice <material> <price>");
            return true;
         }

         String priceStr;
         Material mat;
         if (args.length == 1) {
            if (!(sender instanceof Player p)) {
               sender.sendMessage("§cConsole must specify a material: /setprice <material> <price>");
               return true;
            }

            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
               p.sendMessage("§cYou must be holding an item to use this shortcut.");
               return true;
            }

            mat = hand.getType();
            priceStr = args[0];
         } else {
            mat = Material.matchMaterial(args[0]);
            priceStr = args[1];
            if (mat == null) {
               sender.sendMessage("§cUnknown material: " + args[0]);
               return true;
            }
         }

         try {
            double price = PriceManager.parseAmount(priceStr);
            this.plugin.saveSellPriceToFile(mat, price);
            sender.sendMessage("§aSet sell price for §f" + mat.name() + " §ato §6$" + PriceManager.formatPrice(price));
         } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid price format: " + priceStr);
         }

         return true;
      }
   }
}
