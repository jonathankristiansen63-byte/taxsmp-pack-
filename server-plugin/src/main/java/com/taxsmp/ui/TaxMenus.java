package com.taxsmp.ui;

import com.taxsmp.HeadProfiles;
import com.taxsmp.PlaytimeFormatter;
import com.taxsmp.PriceManager;
import com.taxsmp.ScoreboardManager;
import com.taxsmp.StatsTracker;
import com.taxsmp.TaxSMP;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.DialogBase.DialogAfterAction;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput.OptionEntry;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent.Builder;
import net.kyori.adventure.text.event.ClickCallback.Options;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

public final class TaxMenus {
   private static final Options CLICK_OPTIONS = (Options)Options.builder().uses(-1).lifetime(Duration.ofMinutes(30L)).build();
   private static final int WIDE = 200;
   private static final int NARROW = 150;
   private static final int SLIM = 100;
   private static final String DEFAULT_HOME_ICON = "YELLOW_BED";
   private final TaxSMP plugin;
   private static final int MIN_PROTOCOL = 774;
   private static Integer serverProtocol;
   private static final int HOME_COLUMNS = 5;
   private static final int ICON_COLUMNS = 6;
   private static final int ICONS_PER_PAGE = 24;

   public TaxMenus(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public boolean disabledOnServer() {
      return !this.plugin.getConfig().getBoolean("menus.enabled", true);
   }

   public boolean enabledFor(Player player) {
      if (player != null && this.plugin.getConfig().getBoolean("menus.enabled", true)) {
         ScoreboardManager scoreboard = this.plugin.getScoreboardManager();
         return scoreboard != null && scoreboard.isBedrockPlayer(player) ? false : clientProtocol(player) >= 774;
      } else {
         return false;
      }
   }

   private static int clientProtocol(Player player) {
      try {
         Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
         Object api = via.getMethod("getAPI").invoke(null);
         if (Class.forName("com.viaversion.viaversion.api.ViaAPI").getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId()) instanceof Integer protocol
            && protocol > 0) {
            return protocol;
         }
      } catch (Throwable var5) {
      }

      return serverProtocol();
   }

   private static synchronized int serverProtocol() {
      if (serverProtocol != null) {
         return serverProtocol;
      }

      serverProtocol = 774;

      for (ClassLoader loader : new ClassLoader[]{
         Bukkit.getServer().getClass().getClassLoader(), TaxMenus.class.getClassLoader(), ClassLoader.getSystemClassLoader()
      }) {
         if (loader != null) {
            try (InputStream in = loader.getResourceAsStream("version.json")) {
               if (in != null) {
                  String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                  Matcher m = Pattern.compile("\"protocol_version\"\\s*:\\s*(\\d+)").matcher(json);
                  if (m.find()) {
                     serverProtocol = Integer.parseInt(m.group(1));
                     break;
                  }
               }
            } catch (Exception | LinkageError var9) {
            }
         }
      }

      return serverProtocol;
   }

   public void openRoot(Player player) {
      List<ActionButton> buttons = new ArrayList<>();

      for (MenuLayout.Entry entry : MenuLayout.ROOT) {
         buttons.add(this.button(entry.icon(), entry.label(), entry.tooltip(), p -> this.openScreen(p, entry.screen())));
      }

      show(player, dialog("TaxSMP", List.of(), List.of(), buttons, null, 2));
   }

   public void openScreen(Player player, String screen) {
      switch (screen == null ? "" : screen.toLowerCase(Locale.ROOT)) {
         case "homes":
            this.openHomes(player);
            break;
         case "team":
            if (!TeamsBridge.openTeam(player)) {
               run(player, "team");
            }
            break;
         case "pay":
            this.openPay(player);
            break;
         case "teleport":
            this.openTeleport(player);
            break;
         case "stats":
            this.openStatsLookup(player);
            break;
         case "leaderboards":
            this.openLeaderboards(player);
            break;
         case "settings":
            this.openSettings(player, null);
            break;
         case "auction":
            run(player, "ah");
            break;
         case "orders":
            run(player, "order");
            break;
         case "sell":
            run(player, "sell");
            break;
         case "sellmulti":
            run(player, "sellmulti");
            break;
         case "shop":
            run(player, "shop");
            break;
         case "blackmarket":
            run(player, "blackmarket");
            break;
         case "rtp":
            run(player, "rtp");
            break;
         case "rtpqueue":
            run(player, "rtpqueue");
            break;
         default:
            this.openRoot(player);
      }
   }

   public void openHomes(Player player) {
      this.openHomes(player, false);
   }

   public void openHomes(Player player, boolean expanded) {
      int unlocked = Math.max(1, HomesBridge.maxHomes(player));
      int total = Math.max(unlocked, HomesBridge.totalSlots());
      int shown = expanded ? total : unlocked;
      List<ActionButton> buttons = new ArrayList<>();
      int set = 0;

      for (int slot = 1; slot <= shown; slot++) {
         int target = slot;
         if (slot > unlocked) {
            buttons.add(
               this.slim(
                  icon("item/barrier", Component.text("Locked", NamedTextColor.RED)),
                  this.lockedTooltip(),
                  p -> p.sendMessage(Component.text(this.lockedMessage(), NamedTextColor.RED))
               )
            );
         } else {
            String name = HomesBridge.homeName(player, slot);
            if (name != null) {
               set++;
               buttons.add(
                  this.slim(icon(spriteOf(player, slot), Component.text(name, NamedTextColor.WHITE)), "Open " + name, p -> this.openHome(p, target, expanded))
               );
            } else {
               buttons.add(
                  this.slim(
                     icon("item/structure_void", Component.text("Set Home", NamedTextColor.GRAY)),
                     "Set a home in slot " + target,
                     p -> this.setHome(p, target, expanded)
                  )
               );
            }
         }
      }

      String teamHome = this.plugin.getConfig().getString("menus.homes.team-home-command", "teamhome");
      buttons.add(this.slim(icon("item/bell", "Team Home", NamedTextColor.WHITE), "Your team's home", p -> {
         if (!TeamsBridge.openTeamHome(p, back -> this.openHomes(back, expanded))) {
            run(p, teamHome);
         }
      }));
      if (total > unlocked) {
         buttons.add(
            expanded
               ? this.slim(icon("item/spyglass", "Show Less", NamedTextColor.WHITE), "Just the homes you can use", p -> this.openHomes(p, false))
               : this.slim(icon("item/spyglass", "Show More", NamedTextColor.WHITE), "Every home slot", p -> this.openHomes(p, true))
         );
      }

      buttons.add(this.slim(icon("item/arrow", "Back", NamedTextColor.WHITE), "Go back", this::openRoot));
      List<DialogBody> body = List.of(line(set + " of " + unlocked + " homes set", NamedTextColor.GRAY));
      show(player, dialog("Homes", body, List.of(), buttons, null, 5));
   }

   public void openHome(Player player, int slot, boolean expanded) {
      String name = HomesBridge.homeName(player, slot);
      if (name == null) {
         this.openHomes(player, expanded);
      } else {
         List<ActionButton> buttons = List.of(
            this.button("item/ender_pearl", "Teleport", "Teleport to " + name, p -> this.teleportHome(p, slot)),
            this.button("item/item_frame", "Change Icon", "Pick the picture on this home", p -> this.openHomeIcons(p, slot, 0, null, expanded)),
            this.button("item/name_tag", "Rename", "Give this home another name", p -> this.openHomeRename(p, slot, expanded)),
            this.button(icon("item/barrier", Component.text("Delete", NamedTextColor.RED)), "Remove this home", p -> this.openHomeDelete(p, slot, expanded)),
            this.button("item/arrow", "Back", "Back to your homes", p -> this.openHomes(p, expanded))
         );
         show(player, dialog(name, List.of(), List.of(), buttons, null, 2));
      }
   }

   public void openHomeIcons(Player player, int slot, int page, String query, boolean expanded) {
      if (HomesBridge.homeName(player, slot) == null) {
         this.openHomes(player, expanded);
      } else {
         List<String> matches = HomeIconCatalog.search(query);
         if (matches.isEmpty()) {
            player.sendMessage(Component.text("Nothing matches \"" + query + "\".", NamedTextColor.RED));
            this.openHomeIcons(player, slot, 0, null, expanded);
         } else {
            int pages = (matches.size() + 24 - 1) / 24;
            int shown = Math.max(0, Math.min(page, pages - 1));
            String current = HomesBridge.icon(player, slot);
            List<ActionButton> buttons = new ArrayList<>();

            for (int i = 0; i < 24; i++) {
               int index = shown * 24 + i;
               if (index >= matches.size()) {
                  break;
               }

               String material = matches.get(index);
               boolean chosen = material.equalsIgnoreCase(current);
               String label = HomeIconCatalog.prettyName(material);
               buttons.add(
                  this.slim(
                     icon(HomeIconCatalog.sprite(material), Component.text(label, chosen ? NamedTextColor.GREEN : NamedTextColor.WHITE)),
                     chosen ? "Already on this home" : "Use " + label,
                     p -> {
                        HomesBridge.setIcon(p, slot, material);
                        this.openHome(p, slot, expanded);
                     }
                  )
               );
            }

            List<DialogInput> inputs = List.of(
               DialogInput.text("query", Component.text("Search")).width(200).maxLength(32).initial(query == null ? "" : query).build()
            );
            buttons.add(
               this.input(
                  "item/spyglass",
                  Component.text("Search", NamedTextColor.YELLOW),
                  "Filter the list by name",
                  (p, view) -> this.openHomeIcons(p, slot, 0, blankToNull(trim(view.getText("query"))), expanded)
               )
            );
            buttons.add(this.slim(icon("item/light_gray_dye", "Default", NamedTextColor.WHITE), "Go back to the default icon", p -> {
               HomesBridge.setIcon(p, slot, null);
               this.openHome(p, slot, expanded);
            }));
            buttons.add(this.slim(icon("item/arrow", "Back", NamedTextColor.WHITE), "Back to this home", p -> this.openHome(p, slot, expanded)));
            if (shown > 0) {
               buttons.add(
                  this.slim(icon("item/arrow", "Previous", NamedTextColor.WHITE), "Page " + shown, p -> this.openHomeIcons(p, slot, shown - 1, query, expanded))
               );
            }

            if (shown < pages - 1) {
               buttons.add(
                  this.slim(
                     icon("item/arrow", "Next", NamedTextColor.WHITE), "Page " + (shown + 2), p -> this.openHomeIcons(p, slot, shown + 1, query, expanded)
                  )
               );
            }

            show(player, dialog("Choose Icon " + (shown + 1) + "/" + pages, List.of(), inputs, buttons, null, 6));
         }
      }
   }

   public void openHomeRename(Player player, int slot, boolean expanded) {
      String name = HomesBridge.homeName(player, slot);
      if (name == null) {
         this.openHomes(player, expanded);
      } else {
         List<DialogInput> inputs = List.of(DialogInput.text("name", Component.text("New Name")).width(200).maxLength(32).initial(name).build());
         List<ActionButton> buttons = List.of(this.input("item/name_tag", Component.text("Save", NamedTextColor.GREEN), "Rename this home", (p, view) -> {
            String next = trim(view.getText("name"));
            if (next.isEmpty()) {
               p.sendMessage(Component.text("Type a new name first.", NamedTextColor.RED));
               this.openHomeRename(p, slot, expanded);
            } else {
               if (HomesBridge.rename(p, slot, next)) {
                  p.sendMessage(Component.text("Renamed to " + next + ".", NamedTextColor.GRAY));
               } else {
                  p.sendMessage(Component.text("Could not rename that home.", NamedTextColor.RED));
               }

               this.openHome(p, slot, expanded);
            }
         }), this.button("item/arrow", "Back", "Back to this home", p -> this.openHome(p, slot, expanded)));
         show(player, dialog("Rename Home", List.of(), inputs, buttons, null, 2));
      }
   }

   public void openHomeDelete(Player player, int slot, boolean expanded) {
      String name = HomesBridge.homeName(player, slot);
      if (name == null) {
         this.openHomes(player, expanded);
      } else {
         List<ActionButton> buttons = List.of(
            this.button(icon("item/barrier", Component.text("Delete", NamedTextColor.RED)), "Delete " + name + " for good", p -> {
               if (HomesBridge.delete(p, slot)) {
                  p.sendMessage(Component.text("Deleted " + name + ".", NamedTextColor.GRAY));
               } else {
                  p.sendMessage(Component.text("Could not delete that home.", NamedTextColor.RED));
               }

               this.openHomes(p, expanded);
            }), this.button("item/arrow", "Cancel", "Keep this home", p -> this.openHome(p, slot, expanded))
         );
         List<DialogBody> body = List.of(line("This cannot be undone.", NamedTextColor.RED));
         show(player, dialog("Delete " + name + "?", body, List.of(), buttons, null, 2));
      }
   }

   private void teleportHome(Player player, int slot) {
      player.closeDialog();
      if (!HomesBridge.teleport(player, slot)) {
         player.performCommand("home " + slot);
      }
   }

   private void setHome(Player player, int slot, boolean expanded) {
      if (!HomesBridge.setHome(player, slot)) {
         player.performCommand("sethome " + slot);
      }

      player.getScheduler().runDelayed(this.plugin, task -> this.openHomes(player, expanded), null, 3L);
   }

   private static String spriteOf(Player player, int slot) {
      String material = HomesBridge.icon(player, slot);
      return HomeIconCatalog.sprite(material == null ? "YELLOW_BED" : material);
   }

   private String lockedTooltip() {
      return this.plugin.getConfig().getString("menus.homes.locked-tooltip", "Buy Taxer+ for more home slots");
   }

   private String lockedMessage() {
      return this.plugin.getConfig().getString("menus.homes.locked-message", "Unlock more home slots in the store! /store");
   }

   public void openPay(Player player) {
      List<DialogBody> body = List.of(
         item(new ItemStack(Material.EMERALD)), line("Your balance: $" + PriceManager.formatShort(this.balanceOf(player)), NamedTextColor.GRAY)
      );
      List<DialogInput> inputs = List.of(
         DialogInput.text("player", Component.text("Player Name")).width(200).maxLength(16).build(),
         DialogInput.text("amount", Component.text("Amount")).width(200).maxLength(20).build()
      );
      List<ActionButton> buttons = List.of(this.input("item/gold_nugget", Component.text("Pay", NamedTextColor.GREEN), "Send the payment", (p, view) -> {
         String target = trim(view.getText("player"));
         String amount = trim(view.getText("amount"));
         if (!target.isEmpty() && !amount.isEmpty()) {
            run(p, "pay " + target + " " + amount);
         } else {
            p.sendMessage(Component.text("Fill in both a player and an amount.", NamedTextColor.RED));
            this.openPay(p);
         }
      }), this.back(this::openRoot));
      show(player, dialog("Send Payment", body, inputs, buttons, null, 2));
   }

   public void openTeleport(Player player) {
      List<DialogInput> inputs = List.of(
         DialogInput.text("player", Component.text("Player Name")).width(200).maxLength(16).build(),
         DialogInput.singleOption(
               "direction",
               Component.text("Direction"),
               List.of(OptionEntry.create("to", Component.text("Teleport to player"), true), OptionEntry.create("here", Component.text("Teleport here"), false))
            )
            .width(200)
            .build()
      );
      List<ActionButton> buttons = List.of(
         this.input(
            "item/ender_pearl",
            Component.text("Send Request", NamedTextColor.GREEN),
            "Ask them to teleport",
            (p, view) -> {
               String target = trim(view.getText("player"));
               if (target.isEmpty()) {
                  p.sendMessage(Component.text("Type a player name first.", NamedTextColor.RED));
                  this.openTeleport(p);
               } else {
                  boolean here = "here".equals(view.getText("direction"));
                  String template = here
                     ? this.plugin.getConfig().getString("menus.teleport.here-command", "tpahere %player%")
                     : this.plugin.getConfig().getString("menus.teleport.to-player-command", "tpa %player%");
                  run(p, template.replace("%player%", target));
               }
            }
         ),
         this.back(this::openRoot)
      );
      show(player, dialog("Teleport Request", List.of(item(new ItemStack(Material.EGG))), inputs, buttons, null, 2));
   }

   public void openStatsLookup(Player player) {
      List<DialogInput> inputs = List.of(DialogInput.text("player", Component.text("Player Name")).width(200).maxLength(16).build());
      List<ActionButton> buttons = List.of(this.input("item/book", Component.text("View Profile", NamedTextColor.GREEN), "Look this player up", (p, view) -> {
         String name = trim(view.getText("player"));
         this.openStatsFor(p, name.isEmpty() ? p.getName() : name);
      }), this.back(this::openRoot));
      show(player, dialog("Player Stats", List.of(item(new ItemStack(Material.NAME_TAG))), inputs, buttons, null, 2));
   }

   public void openStatsFor(Player viewer, String name) {
      OfflinePlayer target = resolve(name);
      if (target == null || !target.hasPlayedBefore() && !target.isOnline()) {
         viewer.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
         this.openStatsLookup(viewer);
      } else {
         Bukkit.getAsyncScheduler().runNow(this.plugin, task -> {
            ItemStack head = this.headOf(target);
            viewer.getScheduler().run(this.plugin, t -> this.renderStats(viewer, target, head), null);
         });
      }
   }

   private void renderStats(Player viewer, OfflinePlayer target, ItemStack head) {
      UUID id = target.getUniqueId();
      StatsTracker tracker = this.plugin.getStatsTracker();
      Player online = viewer.getUniqueId().equals(id) ? viewer : null;
      long kills = this.statOf(online, tracker, id, StatsTracker.StatType.PLAYER_KILLS);
      long deaths = this.statOf(online, tracker, id, StatsTracker.StatType.DEATHS);
      long playtime = this.statOf(online, tracker, id, StatsTracker.StatType.PLAYTIME);
      long mobs = this.statOf(online, tracker, id, StatsTracker.StatType.MOBS_KILLED);
      long placed = this.statOf(online, tracker, id, StatsTracker.StatType.BLOCKS_PLACED);
      long broken = this.statOf(online, tracker, id, StatsTracker.StatType.BLOCKS_BROKEN);
      List<DialogBody> body = new ArrayList<>();
      body.add(item(head));
      body.add(stat("Money", "$" + PriceManager.formatShort(this.balanceOf(target))));
      body.add(stat("Kills", PriceManager.formatShort(kills)));
      body.add(stat("Deaths", PriceManager.formatShort(deaths)));
      body.add(stat("Playtime", PlaytimeFormatter.compact(playtime)));
      body.add(stat("Blocks Placed", PriceManager.formatShort(placed)));
      body.add(stat("Blocks Broken", PriceManager.formatShort(broken)));
      body.add(stat("Mob Kills", PriceManager.formatShort(mobs)));
      body.add(stat("/Sell Earned", "$" + PriceManager.formatShort(this.plugin.getPlayerTotal(id, "sell_made"))));
      String targetName = target.getName() != null ? target.getName() : "Unknown";
      List<ActionButton> buttons = List.of(this.button("item/book", "View Full Profile", "Open the full stats menu", p -> leave(p, viewer2 -> {
         if (this.plugin.getStatsCommand() != null) {
            this.plugin.getStatsCommand().openChestStats(viewer2, target);
         }
      })), this.back(this::openStatsLookup));
      show(viewer, dialog(targetName, body, List.of(), buttons, null, 2));
   }

   public void openLeaderboards(Player player) {
      List<ActionButton> buttons = new ArrayList<>();

      for (StatsTracker.StatType type : StatsTracker.StatType.values()) {
         buttons.add(this.button(leaderboardIcon(type), prettyName(type), "See the top 10", p -> this.openTop(p, type)));
      }

      buttons.add(this.back(this::openRoot));
      show(player, dialog("Leaderboards", List.of(item(new ItemStack(Material.GOLD_INGOT))), List.of(), buttons, null, 2));
   }

   public void openTop(Player viewer, StatsTracker.StatType type) {
      StatsTracker tracker = this.plugin.getStatsTracker();
      if (tracker == null) {
         viewer.sendMessage(Component.text("Stats are still loading, try again shortly.", NamedTextColor.RED));
      } else {
         Bukkit.getAsyncScheduler()
            .runNow(
               this.plugin,
               task -> {
                  List<Entry<UUID, Long>> sorted = tracker.getSorted(type);
                  int count = Math.min(10, sorted.size());
                  List<DialogBody> body = new ArrayList<>();

                  for (int i = 0; i < count; i++) {
                     Entry<UUID, Long> entry = sorted.get(i);
                     OfflinePlayer off = Bukkit.getOfflinePlayer(entry.getKey());
                     String name = off.getName() != null ? off.getName() : tracker.getName(entry.getKey());
                     Component text = ((Builder)((Builder)((Builder)Component.text().append(Component.text("#" + (i + 1) + " ", NamedTextColor.YELLOW)))
                              .append(Component.text(name + " ", NamedTextColor.WHITE)))
                           .append(Component.text("- " + StatsTracker.formatValue(type, entry.getValue()), NamedTextColor.GRAY)))
                        .build();
                     body.add(
                        DialogBody.item(this.headOf(off)).description(DialogBody.plainMessage(text, 260)).showDecorations(false).showTooltip(false).build()
                     );
                  }

                  if (body.isEmpty()) {
                     body.add(line("Nobody is on this leaderboard yet.", NamedTextColor.GRAY));
                  }

                  long selfValue = 0L;
                  int selfRank = -1;

                  for (int i = 0; i < sorted.size(); i++) {
                     if (sorted.get(i).getKey().equals(viewer.getUniqueId())) {
                        selfValue = sorted.get(i).getValue();
                        selfRank = i + 1;
                        break;
                     }
                  }

                  body.add(line("You: " + StatsTracker.formatValue(type, selfValue) + (selfRank > 0 ? " (#" + selfRank + ")" : ""), NamedTextColor.YELLOW));
                  List<ActionButton> buttons = List.of(
                     this.button("item/nether_star", "View Full Leaderboard", "Open the paged list", p -> leave(p, viewer2 -> {
                        if (this.plugin.getLeaderboardCommand() != null) {
                           this.plugin.getLeaderboardCommand().openLeaderboard(viewer2, type, 1);
                        }
                     })), this.back(this::openLeaderboards)
                  );
                  viewer.getScheduler().run(this.plugin, t -> show(viewer, dialog("Top 10 - " + prettyName(type), body, List.of(), buttons, null, 2)), null);
               }
            );
      }
   }

   public void openSettings(Player player, TaxMenus.SettingsCategory category) {
      List<ActionButton> buttons = new ArrayList<>();

      for (TaxMenus.SettingsCategory c : TaxMenus.SettingsCategory.values()) {
         buttons.add(
            ActionButton.builder(icon(c.icon, c.title, NamedTextColor.WHITE))
               .tooltip(Component.text(c.tooltip))
               .width(200)
               .action(this.click(p -> this.openSettings(p, c == category ? null : c)))
               .build()
         );
         if (c == category) {
            for (String key : c.keys) {
               buttons.add(this.toggleButton(player, c, key));
            }
         }
      }

      buttons.add(this.back(this::openRoot));
      show(player, dialog("Settings", List.of(item(new ItemStack(Material.COMPARATOR))), List.of(), buttons, null, 1));
   }

   private ActionButton toggleButton(Player player, TaxMenus.SettingsCategory category, String key) {
      boolean locked = "quick_auction_buy".equals(key) && !this.plugin.hasQuickAuctionBuyPermission(player);
      boolean on = this.plugin.getPlayerSetting(player, key) && !locked;
      Component label = ((Builder)((Builder)Component.text().append(Component.text(settingTitle(key) + ": ", NamedTextColor.WHITE)))
            .append(
               locked
                  ? Component.text("RANK ONLY", NamedTextColor.DARK_RED)
                  : Component.text(on ? "ON" : "OFF", on ? NamedTextColor.GREEN : NamedTextColor.RED)
            ))
         .build();
      return ActionButton.builder(icon(locked ? "item/barrier" : (on ? "item/lime_dye" : "item/gray_dye"), label))
         .tooltip(Component.text(locked ? "Requires a rank - see /store" : "Click to turn " + (on ? "off" : "on")))
         .width(200)
         .action(this.click(p -> {
            if (locked) {
               p.sendMessage(Component.text("You need a rank to toggle this! Check /store.", NamedTextColor.RED));
            } else {
               this.applyToggle(p, key, !on);
            }

            this.openSettings(p, category);
         }))
         .build();
   }

   private void applyToggle(Player player, String key, boolean next) {
      this.plugin.getConfig().set("players." + player.getUniqueId() + "." + key, next);
      this.plugin.saveConfig();
      this.plugin.applySettingSideEffects(player, key, next);
      TaxSMP.fireSettingsHooks(player.getUniqueId(), key, next);
      if (this.plugin.getSettingsSyncManager() != null) {
         this.plugin.getSettingsSyncManager().push(player.getUniqueId(), key, next);
      }
   }

   private static Dialog dialog(String title, List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> buttons, ActionButton exit, int columns) {
      return dialog(title, body, inputs, buttons, exit, columns, DialogAfterAction.NONE);
   }

   private static Dialog dialog(
      String title, List<DialogBody> body, List<DialogInput> inputs, List<ActionButton> buttons, ActionButton exit, int columns, DialogAfterAction after
   ) {
      DialogBase base = DialogBase.builder(Component.text(title))
         .externalTitle(Component.text(title))
         .canCloseWithEscape(true)
         .pause(false)
         .afterAction(after)
         .body(body)
         .inputs(inputs)
         .build();
      io.papermc.paper.registry.data.dialog.type.MultiActionType.Builder typeBuilder = DialogType.multiAction(buttons).columns(columns);
      if (exit != null) {
         typeBuilder.exitAction(exit);
      }

      DialogType type = typeBuilder.build();
      return Dialog.create(factory -> ((io.papermc.paper.registry.data.dialog.DialogRegistryEntry.Builder)factory.empty()).base(base).type(type));
   }

   private static void show(Player player, Dialog dialog) {
      if (player.isOnline()) {
         player.showDialog(dialog);
      }
   }

   private static String leaderboardIcon(StatsTracker.StatType type) {
      return switch (type) {
         case MONEY -> "item/gold_ingot";
         case DEATHS -> "item/bone";
         case PLAYTIME -> "item/clock_00";
         case BLOCKS_PLACED -> "block/bricks";
         case BLOCKS_BROKEN -> "item/iron_pickaxe";
         case MOBS_KILLED -> "item/rotten_flesh";
         case PLAYER_KILLS -> "item/diamond_sword";
      };
   }

   private static Component sprite(String path) {
      String atlas = path.startsWith("block/") ? "blocks" : "items";
      return Component.object(ObjectContents.sprite(Key.key("minecraft", atlas), Key.key("minecraft", path)));
   }

   private static Component icon(String path, String label, NamedTextColor color) {
      return ((Builder)((Builder)Component.text().append(sprite(path))).append(Component.text(" " + label, color))).build();
   }

   private static Component icon(String path, Component label) {
      return ((Builder)((Builder)((Builder)Component.text().append(sprite(path))).append(Component.text(" "))).append(label)).build();
   }

   private ActionButton button(String label, String tooltip, Consumer<Player> onClick) {
      return this.button(Component.text(label), tooltip, onClick);
   }

   private ActionButton button(String iconPath, String label, String tooltip, Consumer<Player> onClick) {
      return this.button(icon(iconPath, label, NamedTextColor.WHITE), tooltip, onClick);
   }

   private ActionButton button(Component label, String tooltip, Consumer<Player> onClick) {
      return this.button(label, tooltip, 150, onClick);
   }

   private ActionButton slim(Component label, String tooltip, Consumer<Player> onClick) {
      return this.button(label, tooltip, 100, onClick);
   }

   private ActionButton button(Component label, String tooltip, int width, Consumer<Player> onClick) {
      return ActionButton.builder(label).tooltip(Component.text(tooltip)).width(width).action(this.click(onClick)).build();
   }

   private ActionButton input(String label, String tooltip, BiConsumer<Player, DialogResponseView> onClick) {
      return this.input(Component.text(label), tooltip, onClick);
   }

   private ActionButton input(String iconPath, Component label, String tooltip, BiConsumer<Player, DialogResponseView> onClick) {
      return this.input(icon(iconPath, label), tooltip, onClick);
   }

   private ActionButton input(Component label, String tooltip, BiConsumer<Player, DialogResponseView> onClick) {
      return ActionButton.builder(label).tooltip(Component.text(tooltip)).width(150).action(DialogAction.customClick((view, audience) -> {
         if (audience instanceof Player player) {
            player.getScheduler().run(this.plugin, t -> onClick.accept(player, view), null);
         }
      }, CLICK_OPTIONS)).build();
   }

   private ActionButton back(Consumer<Player> destination) {
      return ActionButton.builder(icon("item/arrow", "Back", NamedTextColor.WHITE))
         .tooltip(Component.text("Go back"))
         .width(150)
         .action(this.click(destination))
         .build();
   }

   private DialogAction click(Consumer<Player> onClick) {
      return DialogAction.customClick((view, audience) -> {
         if (audience instanceof Player player) {
            player.getScheduler().run(this.plugin, t -> onClick.accept(player), null);
         }
      }, CLICK_OPTIONS);
   }

   private static void run(Player player, String command) {
      if (command != null && !command.isBlank()) {
         player.closeDialog();
         player.performCommand(command.startsWith("/") ? command.substring(1) : command);
      }
   }

   private static void leave(Player player, Consumer<Player> action) {
      player.closeDialog();
      action.accept(player);
   }

   private static DialogBody item(ItemStack stack) {
      return DialogBody.item(stack).showDecorations(false).showTooltip(false).width(16).height(16).build();
   }

   private static DialogBody line(String text, NamedTextColor color) {
      return DialogBody.plainMessage(Component.text(text, color), 320);
   }

   private static DialogBody stat(String label, String value) {
      return DialogBody.plainMessage(
         ((Builder)((Builder)Component.text().append(Component.text(label + ": ", NamedTextColor.WHITE))).append(Component.text(value, NamedTextColor.YELLOW)))
            .build(),
         320
      );
   }

   private double balanceOf(OfflinePlayer player) {
      return this.plugin.getEconomy() != null ? this.plugin.getEconomy().getBalance(player) : 0.0;
   }

   private long statOf(Player online, StatsTracker tracker, UUID id, StatsTracker.StatType type) {
      if (online != null) {
         switch (type) {
            case DEATHS:
               return online.getStatistic(Statistic.DEATHS);
            case PLAYTIME:
               return tracker != null ? tracker.getStat(id, type) : online.getStatistic(Statistic.PLAY_ONE_MINUTE);
            case BLOCKS_PLACED:
            case BLOCKS_BROKEN:
            default:
               break;
            case MOBS_KILLED:
               return online.getStatistic(Statistic.MOB_KILLS);
            case PLAYER_KILLS:
               return online.getStatistic(Statistic.PLAYER_KILLS);
         }
      }

      return tracker != null ? tracker.getStat(id, type) : 0L;
   }

   private ItemStack headOf(OfflinePlayer var1) {
      return HeadProfiles.menuHead(var1);
   }

   private static OfflinePlayer resolve(String name) {
      if (name != null && !name.isBlank()) {
         Player online = Bukkit.getPlayerExact(name);
         return (OfflinePlayer)(online != null ? online : Bukkit.getOfflinePlayerIfCached(name));
      } else {
         return null;
      }
   }

   private static String trim(String value) {
      return value == null ? "" : value.trim();
   }

   private static String blankToNull(String value) {
      return value != null && !value.isBlank() ? value : null;
   }

   private static String prettyName(StatsTracker.StatType type) {
      return switch (type) {
         case MONEY -> "Money";
         case DEATHS -> "Deaths";
         case PLAYTIME -> "Playtime";
         case BLOCKS_PLACED -> "Blocks Placed";
         case BLOCKS_BROKEN -> "Blocks Broken";
         case MOBS_KILLED -> "Mobs Killed";
         case PLAYER_KILLS -> "Kills";
      };
   }

   private static String settingTitle(String key) {
      return switch (key) {
         case "tpa_requests" -> "Teleport Requests";
         case "tpa_here_requests" -> "Teleport-Here Requests";
         case "tpa_confirm_menus" -> "Teleport Confirm Menus";
         case "payments" -> "Allow Payments";
         case "quick_auction_buy" -> "Quick Auction Buy";
         default -> {
            StringBuilder out = new StringBuilder();

            for (String word : key.split("_")) {
               if (!word.isEmpty()) {
                  out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
               }
            }

            yield out.toString();
         }
      };
   }

   public enum SettingsCategory {
      CHAT("Chat", "Chat and messages", "item/paper", "public_chat", "private_messages", "chat_server_messages", "hotbar_server_messages", "team_chat"),
      NOTIFICATIONS("Notifications", "Alerts and sounds", "item/bell", "pay_alerts", "bounty_alerts", "auction_alerts", "sound_notifications"),
      PVP("PvP", "Combat behaviour", "item/iron_sword", "fast_crystals", "chainmail_on_respawn"),
      VISUALS("Visuals", "What you see around you", "item/spyglass", "worth_display", "tpa_confirm_menus"),
      PRIVACY("Privacy", "Who may reach you", "item/iron_door", "tpa_requests", "tpa_here_requests", "payments"),
      SCOREBOARD("Scoreboard", "The sidebar", "item/oak_sign", "scoreboard"),
      GENERAL("General", "Everything else", "item/comparator", "quick_auction_buy", "mob_spawns");

      public final String title;
      public final String tooltip;
      public final String icon;
      public final List<String> keys;

      SettingsCategory(String title, String tooltip, String icon, String... keys) {
         this.title = title;
         this.tooltip = tooltip;
         this.icon = icon;
         this.keys = List.of(keys);
      }

      public static TaxMenus.SettingsCategory byName(String name) {
         if (name != null) {
            for (TaxMenus.SettingsCategory c : values()) {
               if (c.name().equalsIgnoreCase(name) || c.title.equalsIgnoreCase(name)) {
                  return c;
               }
            }
         }

         return CHAT;
      }
   }
}
