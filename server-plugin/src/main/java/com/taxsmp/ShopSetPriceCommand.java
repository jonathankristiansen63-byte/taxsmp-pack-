package com.taxsmp;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public class ShopSetPriceCommand implements CommandExecutor {
   private final TaxSMP plugin;

   public ShopSetPriceCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
      if (sender instanceof Player p && !p.hasPermission("taxsmp.shopsetprice")) {
         p.sendMessage("§cYou do not have permission.");
         return true;
      } else {
         if (args.length == 0) {
            sender.sendMessage("§cUsage: /shopsetprice <price> (holding item) OR /shopsetprice <material> <price>");
            return true;
         }

         String priceStr;
         Material mat;
         if (args.length == 1) {
            if (!(sender instanceof Player p)) {
               sender.sendMessage("§cConsole must specify a material: /shopsetprice <material> <price>");
               return true;
            }

            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
               p.sendMessage("§cYou must be holding an item to use the single-argument shortcut.");
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
            this.plugin.saveShopPriceToFile(mat, price);
            sender.sendMessage("§aSet §6SHOP §aprice for §f" + mat.name() + " §ato §6$" + PriceManager.formatPrice(price));
         } catch (NumberFormatException ex) {
            sender.sendMessage("§cInvalid price format: " + priceStr);
         }

         return true;
      }
   }
}
