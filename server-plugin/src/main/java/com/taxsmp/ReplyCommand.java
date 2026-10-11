package com.taxsmp;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class ReplyCommand implements CommandExecutor, Listener {
   private final TaxSMP plugin;
   private final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();

   public ReplyCommand(TaxSMP plugin) {
      this.plugin = plugin;
   }

   @EventHandler
   public void onCommand(PlayerCommandPreprocessEvent e) {
      String msg = e.getMessage().toLowerCase();
      if (msg.startsWith("/msg ") || msg.startsWith("/tell ") || msg.startsWith("/w ")) {
         String[] parts = e.getMessage().split(" ", 3);
         if (parts.length >= 2) {
            Player sender = e.getPlayer();
            Player target = Bukkit.getPlayerExact(parts[1]);
            if (target != null) {
               this.lastMessaged.put(sender.getUniqueId(), target.getUniqueId());
               this.lastMessaged.put(target.getUniqueId(), sender.getUniqueId());
            } else if (this.plugin.getNetworkPlayerDirectory() != null) {
               NetworkPlayerDirectory.KnownPlayer remote = this.plugin.getNetworkPlayerDirectory().find(parts[1]);
               if (remote != null) {
                  this.lastMessaged.put(sender.getUniqueId(), remote.uuid());
               }
            }
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent e) {
      this.lastMessaged.remove(e.getPlayer().getUniqueId());
   }

   public void setLastMessaged(UUID a, UUID b) {
      this.lastMessaged.put(a, b);
   }

   public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
      if (sender instanceof Player p) {
         if (args.length == 0) {
            p.sendMessage(ChatColor.RED + "Usage: /r <message>");
            return true;
         }

         UUID targetId = this.lastMessaged.get(p.getUniqueId());
         if (targetId == null) {
            p.sendMessage(ChatColor.RED + "You have no one to reply to.");
            return true;
         }

         String message = String.join(" ", args);
         Player target = Bukkit.getPlayer(targetId);
         if (target != null && target.isOnline()) {
            return this.sendLocalReply(p, target, message);
         }

         NetworkPlayerDirectory.KnownPlayer remoteTarget = this.plugin.getNetworkPlayerDirectory() == null
            ? null
            : this.plugin.getNetworkPlayerDirectory().find(targetId);
         if (remoteTarget != null && remoteTarget.online()) {
            return this.sendRemoteReply(p, remoteTarget, message);
         }

         p.sendMessage(ChatColor.RED + "That player is no longer online.");
         this.lastMessaged.remove(p.getUniqueId());
         return true;
      } else {
         sender.sendMessage(ChatColor.RED + "Only players can use this command.");
         return true;
      }
   }

   private boolean sendLocalReply(Player sender, Player target, String message) {
      if (!this.plugin.isPrivateMessagesEnabledFor(target)) {
         sender.sendMessage(ChatColor.RED + "This player has private messages turned off.");
         return true;
      } else if (this.plugin.getIgnoreManager().isIgnoring(target.getUniqueId(), sender.getUniqueId())) {
         sender.sendMessage(ChatColor.GRAY + "Message sent.");
         return true;
      } else {
         String toSender = ChatColor.YELLOW + "YOU " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + target.getName() + " " + ChatColor.WHITE + message;
         String toTarget = ChatColor.YELLOW + sender.getName() + " " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + "YOU " + ChatColor.WHITE + message;
         sender.sendMessage(toSender);
         target.sendMessage(toTarget);
         sender.playSound(sender.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0F, 2.0F);
         target.playSound(target.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0F, 2.0F);
         this.lastMessaged.put(target.getUniqueId(), sender.getUniqueId());
         this.lastMessaged.put(sender.getUniqueId(), target.getUniqueId());
         return true;
      }
   }

   private boolean sendRemoteReply(Player sender, NetworkPlayerDirectory.KnownPlayer target, String message) {
      if (!this.plugin.isPrivateMessagesEnabledFor(target.uuid())) {
         sender.sendMessage(ChatColor.RED + "This player has private messages turned off.");
         return true;
      }

      if (this.plugin.getIgnoreManager().isIgnoring(target.uuid(), sender.getUniqueId())) {
         sender.sendMessage(ChatColor.GRAY + "Message sent.");
         return true;
      }

      String toSender = ChatColor.YELLOW + "YOU " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + target.name() + " " + ChatColor.WHITE + message;
      String toTarget = ChatColor.YELLOW + sender.getName() + " " + ChatColor.GRAY + "-> " + ChatColor.YELLOW + "YOU " + ChatColor.WHITE + message;
      sender.sendMessage(toSender);
      sender.playSound(sender.getLocation(), Sound.ENTITY_CHICKEN_EGG, 1.0F, 2.0F);
      this.lastMessaged.put(sender.getUniqueId(), target.uuid());
      if (this.plugin.getNetworkNotificationManager() == null
         || !this.plugin.getNetworkNotificationManager().sendPrivateMessage(target.uuid(), sender.getUniqueId(), sender.getName(), toTarget)) {
         sender.sendMessage(ChatColor.RED + "That player is no longer online.");
         this.lastMessaged.remove(sender.getUniqueId());
      }

      return true;
   }
}
