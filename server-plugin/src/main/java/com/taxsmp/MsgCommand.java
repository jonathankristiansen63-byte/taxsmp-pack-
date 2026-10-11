package com.taxsmp;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class MsgCommand implements CommandExecutor, TabCompleter {
   private final ReplyCommand replyCommand;
   private final TaxSMP plugin;

   public MsgCommand(TaxSMP plugin, ReplyCommand replyCommand) {
      this.plugin = plugin;
      this.replyCommand = replyCommand;
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p) {
         if (args.length < 2) {
            p.sendMessage(ChatColor.RED + "Usage: /msg <player> <message>");
            return true;
         }

         String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
         Player target = Bukkit.getPlayerExact(args[0]);
         if (target != null && target.isOnline()) {
            return this.sendLocalMessage(p, target, message);
         }

         NetworkPlayerDirectory.KnownPlayer remoteTarget = this.plugin.getNetworkPlayerDirectory() == null
            ? null
            : this.plugin.getNetworkPlayerDirectory().find(args[0]);
         if (remoteTarget != null && remoteTarget.online()) {
            return this.sendRemoteMessage(p, remoteTarget, message);
         }

         p.sendMessage(ChatColor.RED + "Player not found.");
         return true;
      } else {
         sender.sendMessage(ChatColor.RED + "Only players can use this command.");
         return true;
      }
   }

   private boolean sendLocalMessage(Player sender, Player target, String message) {
      if (target.getUniqueId().equals(sender.getUniqueId())) {
         sender.sendMessage(ChatColor.RED + "You cannot message yourself.");
         return true;
      } else if (!this.plugin.isPrivateMessagesEnabledFor(target)) {
         sender.sendMessage(ChatColor.RED + "This player has private messages turned off.");
         return true;
      } else if (this.plugin.getIgnoreManager().isIgnoring(target.getUniqueId(), sender.getUniqueId())) {
         sender.sendMessage(ChatColor.RED + "This User Ignored you.");
         return true;
      } else {
         String toSender = ChatColor.YELLOW + "YOU " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + target.getName() + " " + ChatColor.WHITE + message;
         String toTarget = ChatColor.YELLOW + sender.getName() + " " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + "YOU " + ChatColor.WHITE + message;
         sender.sendMessage(toSender);
         target.sendMessage(toTarget);
         sender.playSound(sender.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0F, 2.0F);
         target.playSound(target.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0F, 2.0F);
         this.replyCommand.setLastMessaged(sender.getUniqueId(), target.getUniqueId());
         this.replyCommand.setLastMessaged(target.getUniqueId(), sender.getUniqueId());
         return true;
      }
   }

   private boolean sendRemoteMessage(Player sender, NetworkPlayerDirectory.KnownPlayer target, String message) {
      UUID targetId = target.uuid();
      if (targetId.equals(sender.getUniqueId())) {
         sender.sendMessage(ChatColor.RED + "You cannot message yourself.");
         return true;
      }

      if (!this.plugin.isPrivateMessagesEnabledFor(targetId)) {
         sender.sendMessage(ChatColor.RED + "This player has private messages turned off.");
         return true;
      }

      if (this.plugin.getIgnoreManager().isIgnoring(targetId, sender.getUniqueId())) {
         sender.sendMessage(ChatColor.RED + "This User Ignored you.");
         return true;
      }

      String toSender = ChatColor.YELLOW + "YOU " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + target.name() + " " + ChatColor.WHITE + message;
      String toTarget = ChatColor.YELLOW + sender.getName() + " " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + "YOU " + ChatColor.WHITE + message;
      sender.sendMessage(toSender);
      sender.playSound(sender.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0F, 2.0F);
      this.replyCommand.setLastMessaged(sender.getUniqueId(), targetId);
      if (this.plugin.getNetworkNotificationManager() == null
         || !this.plugin.getNetworkNotificationManager().sendPrivateMessage(targetId, sender.getUniqueId(), sender.getName(), toTarget)) {
         sender.sendMessage(ChatColor.RED + "Player not found.");
      }

      return true;
   }

   public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
      return args.length == 1 && this.plugin.getNetworkPlayerDirectory() != null ? this.plugin.getNetworkPlayerDirectory().completeNames(args[0]) : List.of();
   }
}
