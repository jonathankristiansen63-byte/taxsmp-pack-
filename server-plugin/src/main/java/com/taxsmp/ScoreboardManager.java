package com.taxsmp;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

public class ScoreboardManager implements Listener {
   private static final String OBJ_NAME = "taxsmp_sb";
   private static final String[] ENTRIES = new String[]{
      "§0§r", "§1§r", "§2§r", "§3§r", "§4§r", "§5§r", "§6§r", "§7§r", "§8§r", "§9§r", "§a§r", "§b§r", "§c§r", "§d§r", "§e§r"
   };
   private static boolean nmsReady;
   private static Constructor<?> scoreboardCtor;
   private static Method addObjectiveMethod;
   private static Constructor<?> teamCtor;
   private static Method teamSetPrefix;
   private static Method teamGetPlayers;
   private static Object dummyCriteria;
   private static Object integerRenderType;
   private static Object sidebarSlot;
   private static Constructor<?> objPacketCtor;
   private static Constructor<?> displayPacketCtor;
   private static Constructor<?> scorePacketCtor;
   private static Method teamCreatePacket;
   private static Method getHandleMethod;
   private static Field connectionField;
   private static Method sendMethod;
   private static Method fromStringOrNull;
   private static Object blankFormat;
   private static boolean headReady;
   private static Method componentFromJson;
   private static Method adventureToVanilla;
   private static Method componentEmpty;
   private static Method mutableAppend;
   private static volatile Object registryAccess;
   private static final String HEAD_TOKEN = "\u0001";
   private static final Object NO_HEAD = new Object();
   private static final String ICON_START = "\u0002";
   private static final String ICON_END = "\u0003";
   private static final Map<String, Object> SPRITES = new ConcurrentHashMap<>();
   private static boolean shadowReady;
   // Scoreboard frame from the TaxSMP resource pack (font taxsmp:hud, tools/hud.py in the pack repo).
   // The Java board is FRAME_BOARD_WIDTH px wide with 12 lines, matching the frame.
   private static final int FRAME_BOARD_WIDTH = 144;
   private static final int FRAME_PICTURE_WIDTH = 148;
   private static final int FRAME_INDENT = 3;
   private static final int FRAME_HEADER_WIDTH = 42; // "TaxSMP" in bold, measured with Minecraft's font
   private static Class<?> mutableComponentClass;
   private static Method componentGetStyle;
   private static Method componentGetSiblings;
   private static Method mutableSetStyle;
   private static Method styleWithShadowColor;
   private static final int SHADOW_ARGB = -16777216;
   private final TaxSMP plugin;
   private final Map<UUID, ScoreboardManager.BoardData> playerBoards = new ConcurrentHashMap<>();
   private final Map<UUID, Map<String, String>> bedrockValues = new ConcurrentHashMap<>();
   private final Map<UUID, Object> headComponents = new ConcurrentHashMap<>();
   private static volatile Boolean floodgateAvailable;
   private static volatile Method floodgateIsFloodgatePlayer;
   private static volatile Object floodgateApiInstance;
   private final Set<UUID> creatingBoards = ConcurrentHashMap.newKeySet();

   public ScoreboardManager(TaxSMP plugin) {
      this.plugin = plugin;
   }

   public void updateAll() {
      for (Player p : Bukkit.getOnlinePlayers()) {
         this.refreshPlayer(p);
      }
   }

   @EventHandler
   public void onJoin(PlayerJoinEvent event) {
      Player p = event.getPlayer();
      this.playerBoards.remove(p.getUniqueId());
      this.creatingBoards.remove(p.getUniqueId());
      p.getScheduler().runDelayed(this.plugin, task -> this.refreshPlayer(p), null, 10L);
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.playerBoards.remove(event.getPlayer().getUniqueId());
      this.bedrockValues.remove(event.getPlayer().getUniqueId());
      this.headComponents.remove(event.getPlayer().getUniqueId());
   }

   public void refreshPlayer(Player p) {
      if (p != null && p.isOnline() && this.plugin.isScoreboardEnabledFor(p)) {
         p.getScheduler().run(this.plugin, task -> {
            if (p.isOnline()) {
               try {
                  double money = this.plugin.getEconomy() != null ? this.plugin.getEconomy().getBalance(p) : 0.0;
                  int kills = p.getStatistic(Statistic.PLAYER_KILLS);
                  int deaths = p.getStatistic(Statistic.DEATHS);
                  StatsTracker stats = this.plugin.getStatsTracker();
                  if (stats != null) {
                     kills = Math.max(kills, (int)stats.getStat(p.getUniqueId(), StatsTracker.StatType.PLAYER_KILLS));
                     deaths = Math.max(deaths, (int)stats.getStat(p.getUniqueId(), StatsTracker.StatType.DEATHS));
                  }

                  if (nmsReady) {
                     if (this.isBedrockPlayer(p)) {
                        this.sendBedrockSidebar(p, money, kills, deaths);
                     } else {
                        this.sendSidebar(p, money, kills, deaths, false);
                     }
                  }
               } catch (Exception e) {
                  e.printStackTrace();
               }
            }
         }, null);
      }
   }

   private void cacheBedrockValues(Player p, double money, int kills, int deaths) {
      Map<String, String> m = new HashMap<>();

      try {
         m.put("money", PriceManager.formatShort(money));
      } catch (Throwable t) {
         m.put("money", String.valueOf((long)money));
      }

      m.put("kills", String.valueOf(kills));
      m.put("deaths", String.valueOf(deaths));

      try {
         m.put("players", String.valueOf(this.networkOnlineCount()));
      } catch (Throwable t) {
         m.put("players", "0");
      }

      try {
         m.put("ping", String.valueOf(p.getPing()));
      } catch (Throwable t) {
         m.put("ping", "0");
      }

      try {
         m.put("eggs", String.valueOf(this.plugin.getEggManager() != null ? this.plugin.getEggManager().getEggs(p) : 0));
      } catch (Throwable t) {
         m.put("eggs", "0");
      }

      try {
         m.put("gems", String.valueOf(this.plugin.getGemManager() != null ? this.plugin.getGemManager().getGems(p) : 0));
      } catch (Throwable t) {
         m.put("gems", "0");
      }

      try {
         m.put("team", this.getCleanTeamName(p));
      } catch (Throwable t) {
         m.put("team", "");
      }

      this.bedrockValues.put(p.getUniqueId(), m);
   }

   public String getBedrockValue(UUID id, String key) {
      Map<String, String> m = this.bedrockValues.get(id);
      return m == null ? "" : m.getOrDefault(key, "");
   }

   public String getCleanTeamName(Player player) {
      String teamLine = this.getTaxTeamsLine(player);
      if (teamLine != null && !teamLine.isBlank()) {
         String shown = stripHex(teamLine);
         shown = shown.replace("\ud83d\udc51", "").replace("♔", "").replace("♕", "").replace("♚", "").replace("♛", "");
         return shown.replaceAll("(?i)§x(?:§[0-9a-f]){6}", "").replaceAll("(?i)§[0-9a-fk-orx]", "").trim().replaceFirst("(?i)^team\\s*:?\\s*", "").trim();
      } else {
         return "";
      }
   }

   public boolean isBedrockPlayer(Player p) {
      if (p == null) {
         return false;
      }

      UUID id = p.getUniqueId();

      try {
         if (floodgateAvailable == null) {
            try {
               Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
               floodgateApiInstance = apiClass.getMethod("getInstance").invoke(null);
               floodgateIsFloodgatePlayer = apiClass.getMethod("isFloodgatePlayer", UUID.class);
               floodgateAvailable = Boolean.TRUE;
            } catch (Throwable t) {
               floodgateAvailable = Boolean.FALSE;
            }
         }

         if (floodgateAvailable && floodgateApiInstance != null) {
            Object result = floodgateIsFloodgatePlayer.invoke(floodgateApiInstance, id);
            if (Boolean.TRUE.equals(result)) {
               return true;
            }
         }
      } catch (Throwable var5) {
      }

      return id.getMostSignificantBits() == 0L;
   }

   private String hexColor(String hex) {
      StringBuilder sb = new StringBuilder("§x");

      for (char c : hex.toCharArray()) {
         sb.append("§").append(c);
      }

      return sb.toString();
   }

   private String yellowGradient(String text, boolean bedrock, boolean bold) {
      if (!bedrock && text != null && !text.isEmpty()) {
         String[] colors = new String[]{"FFF7A8", "FFE35C", "D99A00"};
         StringBuilder sb = new StringBuilder();
         int last = Math.max(1, text.length() - 1);

         for (int i = 0; i < text.length(); i++) {
            double position = (double)i / last;
            double scaled = position * (colors.length - 1);
            int start = Math.min(colors.length - 2, (int)Math.floor(scaled));
            double local = scaled - start;
            sb.append(this.hexColor(this.lerpHex(colors[start], colors[start + 1], local)));
            if (bold) {
               sb.append("§l");
            }

            sb.append(text.charAt(i));
         }

         return sb.toString();
      } else {
         return "§e" + (bold ? "§l" : "") + (text == null ? "" : text);
      }
   }

   private String lerpHex(String from, String to, double t) {
      int r1 = Integer.parseInt(from.substring(0, 2), 16);
      int g1 = Integer.parseInt(from.substring(2, 4), 16);
      int b1 = Integer.parseInt(from.substring(4, 6), 16);
      int r2 = Integer.parseInt(to.substring(0, 2), 16);
      int g2 = Integer.parseInt(to.substring(2, 4), 16);
      int b2 = Integer.parseInt(to.substring(4, 6), 16);
      int r = (int)Math.round(r1 + (r2 - r1) * t);
      int g = (int)Math.round(g1 + (g2 - g1) * t);
      int b = (int)Math.round(b1 + (b2 - b1) * t);
      return String.format("%02X%02X%02X", r, g, b);
   }

   private String buildTaxsmpTitle(boolean bedrock) {
      return (bedrock ? "§e" : this.hexColor("FFD83D")) + "§lTAXSMP";
   }

   private List<String> buildLines(Player player, double money, int kills, int deaths, boolean bedrock) {
      int ping = player.getPing();
      int onlineCount = this.networkOnlineCount();
      int eggs = this.plugin.getEggManager() != null ? this.plugin.getEggManager().getEggs(player) : 0;
      int gems = this.plugin.getGemManager() != null ? this.plugin.getGemManager().getGems(player) : 0;
      StatsTracker stats = this.plugin.getStatsTracker();
      long playtimeTicks = stats != null ? stats.getStat(player.getUniqueId(), StatsTracker.StatType.PLAYTIME) : player.getStatistic(Statistic.PLAY_ONE_MINUTE);
      KeyallManager keyall = this.plugin.getKeyallManager();
      String keyallLeft = keyall != null ? keyall.formatRemaining(player) : "-";
      String green = bedrock ? "§a" : this.hexColor("55FF55");
      String mint = bedrock ? "§a" : this.hexColor("55FFB8");
      String red = bedrock ? "§c" : this.hexColor("FF5555");
      String gold = bedrock ? "§e" : this.hexColor("FFD83D");
      String purple = bedrock ? "§d" : this.hexColor("D8B6FF");
      String cyan = bedrock ? "§b" : this.hexColor("55FFFF");
      String gray = bedrock ? "§7" : this.hexColor("AAAAAA");
      String white = "§f";
      String region = this.plugin.getConfig().getString("region", "EU").toUpperCase();
      String team = this.getCleanTeamName(player);
      boolean showHead = !bedrock && this.plugin.getConfig().getBoolean("scoreboards.player-head", true);
      List<String> lines = new ArrayList<>();
      lines.add((showHead ? HEAD_TOKEN + " " : icon("item/name_tag", bedrock)) + white + "Name: " + gold + player.getName());
      lines.add(icon("item/gold_ingot", bedrock) + white + "Money: " + mint + PriceManager.formatShort(money));
      lines.add(icon("item/diamond", bedrock) + white + "Gems: " + cyan + gems);
      lines.add(icon("item/egg", bedrock) + white + "Eggs: " + red + eggs);
      lines.add(icon("item/trial_key", bedrock) + white + "Keyall: " + gold + keyallLeft);
      lines.add(icon("item/clock_00", bedrock) + white + "Playtime: " + gold + PlaytimeFormatter.compact(playtimeTicks));
      lines.add(icon("item/diamond_sword", bedrock) + white + "Kills: " + red + kills);
      lines.add(icon("item/bone", bedrock) + white + "Deaths: " + gray + deaths);
      lines.add(icon("item/iron_chestplate", bedrock) + white + "Team: " + (team.isBlank() ? gray + "None" : purple + team));
      lines.add(icon("item/totem_of_undying", bedrock) + white + "Players: " + gold + onlineCount);
      lines.add(icon("item/compass_16", bedrock) + white + "Ping: " + green + ping + "ms " + gray + "| " + cyan + region);
      lines.add("");
      boolean framed = this.frameEnabled(bedrock);
      int contentWidth = FRAME_BOARD_WIDTH - 2 * FRAME_INDENT;
      int boardWidth = 0;

      for (int i = 0; i < lines.size(); i++) {
         if (framed) {
            lines.set(i, this.fit(lines.get(i), contentWidth));
         }

         boardWidth = Math.max(boardWidth, this.pixelWidth(lines.get(i)));
      }

      return lines;
   }

   /** Scoreboard artwork is Java-only; Bedrock keeps readable text without pack glyphs. */
   private static String icon(String sprite, boolean bedrock) {
      if (bedrock) {
         return "";
      } else {
         return ICON_START + sprite + ICON_END + " ";
      }
   }

   private static Object spriteComponent(String sprite) {
      return SPRITES.computeIfAbsent(sprite, path -> {
         String atlas = path.startsWith("block/") ? "blocks" : "items";
         return vanilla(
            net.kyori.adventure.text.Component.object(
               net.kyori.adventure.text.object.ObjectContents.sprite(
                  net.kyori.adventure.key.Key.key("minecraft", atlas), net.kyori.adventure.key.Key.key("minecraft", path)
               )
            )
         );
      });
   }

   /** Turns an Adventure component into Minecraft's own (works on 1.21.11 and 26.x), or NO_HEAD. */
   private static Object vanilla(net.kyori.adventure.text.Component component) {
      if (adventureToVanilla == null) {
         return NO_HEAD;
      }

      try {
         Object comp = adventureToVanilla.invoke(null, component);
         return comp == null ? NO_HEAD : comp;
      } catch (Throwable t) {
         return NO_HEAD;
      }
   }

   private static net.kyori.adventure.text.Component hud(String text) {
      return net.kyori.adventure.text.Component.text(text).font(net.kyori.adventure.key.Key.key("taxsmp", "hud"));
   }

   /** Characters of the taxsmp:hud font that move the text n px (negative = left). */
   private static String hudMove(int px) {
      StringBuilder sb = new StringBuilder();
      int base = px < 0 ? 0xE010 : 0xE020;
      int n = Math.abs(px);

      for (int bit = 8; bit >= 0; bit--) {
         if ((n & 1 << bit) != 0) {
            sb.append((char)(base + bit));
         }
      }

      return sb.toString();
   }

   /** Title: the frame picture, then "TaxSMP" centered in the header, exactly FRAME_BOARD_WIDTH px wide in total. */
   private static Object frameTitle() {
      return SPRITES.computeIfAbsent("#frame-title", k -> {
         int headerStart = (FRAME_BOARD_WIDTH - FRAME_HEADER_WIDTH) / 2;
         int afterFrame = -2 + FRAME_PICTURE_WIDTH + 1;
         String frame = String.valueOf((char)0xE000);
         return vanilla(
            net.kyori.adventure.text.Component.text()
               .append(hud(hudMove(-2) + frame + hudMove(headerStart - afterFrame)))
               .append(
                  net.kyori.adventure.text.Component.text(
                     "TaxSMP", net.kyori.adventure.text.format.TextColor.color(0xFFD83D), net.kyori.adventure.text.format.TextDecoration.BOLD
                  )
               )
               .append(hud(hudMove(FRAME_BOARD_WIDTH - headerStart - FRAME_HEADER_WIDTH)))
               .build()
         );
      });
   }

   private boolean frameEnabled(boolean bedrock) {
      return !bedrock && this.plugin.getConfig().getBoolean("scoreboards.frame", true);
   }

   /** Starts a Java line a few px in, so text doesn't touch the frame. */
   private Object framed(Object line) throws Exception {
      Object indent = this.frameEnabled(false) ? SPRITES.computeIfAbsent("#indent", k -> vanilla(hud(hudMove(FRAME_INDENT)))) : NO_HEAD;
      if (indent != NO_HEAD && line != null) {
         Object root = componentEmpty.invoke(null);
         mutableAppend.invoke(root, indent);
         mutableAppend.invoke(root, line);
         return root;
      } else {
         return line;
      }
   }

   /** Cuts a line short so the board never grows wider than the frame. */
   private String fit(String line, int maxWidth) {
      String out = line;

      while (!out.isEmpty() && this.pixelWidth(out) > maxWidth) {
         out = out.substring(0, out.length() - 1);
         if (out.endsWith("§")) {
            out = out.substring(0, out.length() - 1);
         }
      }

      return out;
   }

   private void createBoardAsync(Player player, boolean bedrock) {
      UUID id = player.getUniqueId();
      if (this.creatingBoards.add(id)) {
         Bukkit.getGlobalRegionScheduler().run(this.plugin, task -> {
            if (player.isOnline() && !this.playerBoards.containsKey(id)) {
               ScoreboardManager.BoardData data = new ScoreboardManager.BoardData();

               try {
                  data.scoreboard = scoreboardCtor.newInstance();
                  Object nmsTitle = this.frameEnabled(bedrock) ? frameTitle() : NO_HEAD;
                  if (nmsTitle == NO_HEAD) {
                     nmsTitle = fromStringOrNull.invoke(null, this.buildTaxsmpTitle(bedrock));
                     if (!bedrock) {
                        this.applyShadow(nmsTitle);
                     }
                  }

                  data.objective = addObjectiveMethod.invoke(data.scoreboard, "taxsmp_sb", dummyCriteria, nmsTitle, integerRenderType, false, null);
                  data.teams = new Object[ENTRIES.length];
               } catch (Exception e) {
                  e.printStackTrace();
                  this.creatingBoards.remove(id);
                  return;
               }

               Object obj = data.objective;
               player.getScheduler().run(this.plugin, t -> {
                  label39: {
                     try {
                        if (player.isOnline()) {
                           this.sendPacket(player, objPacketCtor.newInstance(obj, 0));
                           this.sendPacket(player, displayPacketCtor.newInstance(sidebarSlot, obj));
                           this.playerBoards.put(id, data);
                           break label39;
                        }
                     } catch (Exception e) {
                        e.printStackTrace();
                        return;
                     } finally {
                        this.creatingBoards.remove(id);
                     }

                     return;
                  }

                  this.refreshPlayer(player);
               }, () -> this.creatingBoards.remove(id));
            } else {
               this.creatingBoards.remove(id);
            }
         });
      }
   }

   private void sendSidebar(Player player, double money, int kills, int deaths, boolean bedrock) throws Exception {
      if (player != null && player.isOnline()) {
         List<String> lines = this.buildLines(player, money, kills, deaths, bedrock);
         ScoreboardManager.BoardData data = this.playerBoards.get(player.getUniqueId());
         boolean isNew = data == null;
         if (isNew) {
            this.createBoardAsync(player, bedrock);
         } else {
            Optional<Object> blankOpt = Optional.of(blankFormat);

            for (int i = 0; i < lines.size() && i < ENTRIES.length; i++) {
               Object nmsPrefix = this.framed(this.buildLineComponent(player, lines.get(i)));
               if (data.teams[i] == null) {
                  Object team = teamCtor.newInstance(data.scoreboard, "line_" + i);
                  teamSetPrefix.invoke(team, nmsPrefix);
                  ((Collection)teamGetPlayers.invoke(team)).add(ENTRIES[i]);
                  data.teams[i] = team;
                  this.sendPacket(player, teamCreatePacket.invoke(null, team, true));
                  this.sendPacket(player, scorePacketCtor.newInstance(ENTRIES[i], "taxsmp_sb", lines.size() - i, Optional.empty(), blankOpt));
               } else {
                  teamSetPrefix.invoke(data.teams[i], nmsPrefix);
                  this.sendPacket(player, teamCreatePacket.invoke(null, data.teams[i], false));
               }
            }

            for (int i = lines.size(); i < data.lastLineCount && i < ENTRIES.length; i++) {
               if (data.teams[i] != null) {
                  Object blankPrefix = fromStringOrNull.invoke(null, "");
                  teamSetPrefix.invoke(data.teams[i], blankPrefix);
                  this.sendPacket(player, teamCreatePacket.invoke(null, data.teams[i], false));
               }
            }

            data.lastLineCount = Math.min(lines.size(), ENTRIES.length);
         }
      }
   }

   private void sendBedrockSidebar(Player player, double money, int kills, int deaths) throws Exception {
      if (player != null && player.isOnline()) {
         List<String> lines = this.buildLines(player, money, kills, deaths, true);
         ScoreboardManager.BoardData data = this.playerBoards.get(player.getUniqueId());
         boolean isNew = data == null;
         if (isNew) {
            this.createBoardAsync(player, true);
         } else {
            Optional<Object> blankOpt = Optional.of(blankFormat);
            int count = Math.min(lines.size(), ENTRIES.length);

            for (int i = 0; i < count; i++) {
               Object nmsLine = fromStringOrNull.invoke(null, lines.get(i));
               this.sendPacket(player, scorePacketCtor.newInstance(ENTRIES[i], "taxsmp_sb", count - i, Optional.ofNullable(nmsLine), blankOpt));
            }

            for (int i = count; i < data.lastLineCount && i < ENTRIES.length; i++) {
               Object blank = fromStringOrNull.invoke(null, " ");
               this.sendPacket(player, scorePacketCtor.newInstance(ENTRIES[i], "taxsmp_sb", 0, Optional.ofNullable(blank), blankOpt));
            }

            data.lastLineCount = count;
         }
      }
   }

   private void applyShadow(Object component) {
      if (shadowReady && component != null) {
         try {
            this.applyShadowRecursive(component);
         } catch (Exception var3) {
         }
      }
   }

   private void applyShadowRecursive(Object component) throws Exception {
      if (component != null) {
         if (mutableComponentClass.isInstance(component)) {
            Object style = componentGetStyle.invoke(component);
            Object shadowed = styleWithShadowColor.invoke(style, -16777216);
            mutableSetStyle.invoke(component, shadowed);
         }

         Object siblings = componentGetSiblings.invoke(component);
         if (siblings instanceof List) {
            for (Object child : (List)siblings) {
               this.applyShadowRecursive(child);
            }
         }
      }
   }

   private static Object registries() {
      Object cached = registryAccess;
      if (cached != null) {
         return cached;
      }

      try {
         Object server = Bukkit.getServer();
         Object nms = server.getClass().getMethod("getServer").invoke(server);
         cached = nms.getClass().getMethod("registryAccess").invoke(nms);
         registryAccess = cached;
      } catch (Throwable t) {
         headReady = false;
      }

      return cached;
   }

   private Object headComponent(Player player) {
      if (!headReady) {
         return null;
      }

      Object cached = this.headComponents.computeIfAbsent(player.getUniqueId(), id -> {
         String name = player.getName();
         if (name != null && name.matches("[A-Za-z0-9_]{1,16}")) {
            return vanilla(
               net.kyori.adventure.text.Component.object(net.kyori.adventure.text.object.ObjectContents.playerHead().name(name).hat(true).build())
            );
         } else {
            return NO_HEAD;
         }
      });
      return cached == NO_HEAD ? null : cached;
   }

   private Object buildLineComponent(Player player, String line) throws Exception {
      if (componentEmpty == null || mutableAppend == null || line.indexOf(HEAD_TOKEN) < 0 && line.indexOf(ICON_START) < 0) {
         // No icons (or this server can't build them): plain text without the icon markers.
         Object plain = fromStringOrNull.invoke(null, line.replace(HEAD_TOKEN, "").replaceAll(ICON_START + "[^" + ICON_END + "]*" + ICON_END, ""));
         this.applyShadow(plain);
         return plain;
      } else {
         Object root = componentEmpty.invoke(null);
         StringBuilder text = new StringBuilder();

         for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            Object picture;
            if (c == HEAD_TOKEN.charAt(0)) {
               picture = this.headComponent(player);
            } else if (c == ICON_START.charAt(0)) {
               int end = line.indexOf(ICON_END, i);
               picture = spriteComponent(line.substring(i + 1, end));
               i = end;
            } else {
               text.append(c);
               continue;
            }

            this.appendText(root, text);
            if (picture != null && picture != NO_HEAD) {
               mutableAppend.invoke(root, picture);
            }
         }

         this.appendText(root, text);
         return root;
      }
   }

   private void appendText(Object root, StringBuilder text) throws Exception {
      if (!text.isEmpty()) {
         Object piece = fromStringOrNull.invoke(null, text.toString());
         if (piece != null) {
            this.applyShadow(piece);
            mutableAppend.invoke(root, piece);
         }

         text.setLength(0);
      }
   }

   private void sendPacket(Player player, Object packet) throws Exception {
      Object handle = getHandleMethod.invoke(player);
      Object connection = connectionField.get(handle);
      sendMethod.invoke(connection, packet);
   }

   private static String stripColor(String input) {
      return input.replaceAll("(?i)§[0-9a-fk-orx]", "");
   }

   private String centerLine(String text, int boardWidth) {
      int textWidth = this.pixelWidth(text);
      int leadPixels = Math.max(0, (boardWidth - textWidth) / 2);
      int spaces = Math.round(leadPixels / 4.0F);
      StringBuilder sb = new StringBuilder();

      for (int i = 0; i < spaces; i++) {
         sb.append(' ');
      }

      return sb.append(text).toString();
   }

   private int pixelWidth(String text) {
      int width = 0;
      boolean bold = false;

      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         if (c == ICON_START.charAt(0)) {
            int end = text.indexOf(ICON_END, i);
            width += 9;
            i = end < 0 ? text.length() : end;
         } else if (c == 167 && i + 1 < text.length()) {
            char code = Character.toLowerCase(text.charAt(i + 1));
            if (code == 'l') {
               bold = true;
            } else if (code == 'r' || code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
               bold = false;
            }

            i++;
         } else {
            width += this.charWidth(c) + (bold ? 1 : 0) + 1;
         }
      }

      return width;
   }

   private int charWidth(char c) {
      if (c == "\u0001".charAt(0)) {
         return 7;
      }

      switch (c) {
         case ' ':
            return 3;
         case '!':
         case '\'':
         case ',':
         case '.':
         case ':':
         case ';':
         case 'i':
         case '|':
            return 1;
         case '"':
         case 'I':
         case '[':
         case ']':
         case 't':
            return 3;
         case '#':
         case '$':
         case '%':
         case '&':
         case '+':
         case '-':
         case '/':
         case '0':
         case '1':
         case '2':
         case '3':
         case '4':
         case '5':
         case '6':
         case '7':
         case '8':
         case '9':
         case '=':
         case '?':
         case '@':
         case 'A':
         case 'B':
         case 'C':
         case 'D':
         case 'E':
         case 'F':
         case 'G':
         case 'H':
         case 'J':
         case 'K':
         case 'L':
         case 'M':
         case 'N':
         case 'O':
         case 'P':
         case 'Q':
         case 'R':
         case 'S':
         case 'T':
         case 'U':
         case 'V':
         case 'W':
         case 'X':
         case 'Y':
         case 'Z':
         case '\\':
         case '^':
         case '_':
         case '`':
         case 'a':
         case 'b':
         case 'c':
         case 'd':
         case 'e':
         case 'g':
         case 'h':
         case 'j':
         case 'm':
         case 'n':
         case 'o':
         case 'p':
         case 'q':
         case 'r':
         case 's':
         case 'u':
         case 'v':
         case 'w':
         case 'x':
         case 'y':
         case 'z':
         default:
            return 5;
         case '(':
         case ')':
         case '*':
         case '<':
         case '>':
         case 'f':
         case 'k':
         case '{':
         case '}':
            return 4;
         case 'l':
            return 2;
      }
   }

   private static String stripHex(String input) {
      return input == null ? null : input.replaceAll("(?i)§x(?:§[0-9a-f]){6}", "");
   }

   private String getTaxTeamsLine(Player player) {
      Plugin teamsPlugin = Bukkit.getPluginManager().getPlugin("TaxTeams");
      if (teamsPlugin != null && teamsPlugin.isEnabled()) {
         try {
            return teamsPlugin.getClass().getMethod("getScoreboardTeamLine", Player.class).invoke(teamsPlugin, player) instanceof String text ? text : null;
         } catch (ReflectiveOperationException | ClassCastException ignored) {
            return null;
         }
      } else {
         return null;
      }
   }

   public int networkOnlineCount() {
      NetworkStatusManager status = this.plugin.getNetworkStatusManager();
      return status == null ? Bukkit.getOnlinePlayers().size() : status.getNetworkOnline();
   }

   public void removePlayer(Player p) {
      if (p != null && p.isOnline() && nmsReady) {
         ScoreboardManager.BoardData data = this.playerBoards.remove(p.getUniqueId());
         if (data != null) {
            p.getScheduler().run(this.plugin, task -> {
               if (p.isOnline()) {
                  try {
                     this.sendPacket(p, objPacketCtor.newInstance(data.objective, 1));
                  } catch (Exception var5) {
                  }
               }
            }, null);
         }
      }
   }

   static {
      try {
         Class<?> scoreboardClass = Class.forName("net.minecraft.world.scores.Scoreboard");
         scoreboardCtor = scoreboardClass.getDeclaredConstructor();
         Class<?> criteriaClass = Class.forName("net.minecraft.world.scores.criteria.ObjectiveCriteria");
         dummyCriteria = criteriaClass.getField("DUMMY").get(null);
         Class<?> renderTypeClass = null;

         for (Class<?> inner : criteriaClass.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("RenderType")) {
               renderTypeClass = inner;
               break;
            }
         }

         integerRenderType = renderTypeClass.getField("INTEGER").get(null);
         Class<?> blankFormatClass = Class.forName("net.minecraft.network.chat.numbers.BlankFormat");
         blankFormat = blankFormatClass.getField("INSTANCE").get(null);
         Class<?> nmsComponent = Class.forName("net.minecraft.network.chat.Component");
         Class<?> numberFormat = Class.forName("net.minecraft.network.chat.numbers.NumberFormat");
         addObjectiveMethod = scoreboardClass.getMethod("addObjective", String.class, criteriaClass, nmsComponent, renderTypeClass, boolean.class, numberFormat);
         Class<?> objectiveClass = Class.forName("net.minecraft.world.scores.Objective");
         Class<?> teamClass = Class.forName("net.minecraft.world.scores.PlayerTeam");
         teamCtor = teamClass.getDeclaredConstructor(scoreboardClass, String.class);
         teamSetPrefix = teamClass.getMethod("setPlayerPrefix", nmsComponent);
         teamGetPlayers = teamClass.getMethod("getPlayers");
         Class<Enum<?>> displaySlotClass = (Class<Enum<?>>)Class.forName("net.minecraft.world.scores.DisplaySlot");

         for (Object constant : displaySlotClass.getEnumConstants()) {
            if (((Enum)constant).name().equals("SIDEBAR")) {
               sidebarSlot = constant;
               break;
            }
         }

         objPacketCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundSetObjectivePacket").getDeclaredConstructor(objectiveClass, int.class);
         displayPacketCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket")
            .getDeclaredConstructor(displaySlotClass, objectiveClass);
         scorePacketCtor = Class.forName("net.minecraft.network.protocol.game.ClientboundSetScorePacket")
            .getDeclaredConstructor(String.class, String.class, int.class, Optional.class, Optional.class);
         Class<?> teamPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
         teamCreatePacket = teamPacketClass.getMethod("createAddOrModifyPacket", teamClass, boolean.class);
         fromStringOrNull = Class.forName("org.bukkit.craftbukkit.util.CraftChatMessage").getMethod("fromStringOrNull", String.class);
         getHandleMethod = Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer").getMethod("getHandle");
         Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
         connectionField = serverPlayer.getField("connection");
         Class<?> packetClass = Class.forName("net.minecraft.network.protocol.Packet");
         sendMethod = connectionField.getType().getMethod("send", packetClass);

         try {
            Class<?> styleClass = Class.forName("net.minecraft.network.chat.Style");
            mutableComponentClass = Class.forName("net.minecraft.network.chat.MutableComponent");
            componentGetStyle = nmsComponent.getMethod("getStyle");
            componentGetSiblings = nmsComponent.getMethod("getSiblings");
            mutableSetStyle = mutableComponentClass.getMethod("setStyle", styleClass);
            styleWithShadowColor = styleClass.getMethod("withShadowColor", int.class);
            shadowReady = true;
         } catch (Throwable t) {
            shadowReady = false;
         }

         try {
            // Paper's own Adventure -> Minecraft converter (the old JSON serializer is gone in newer versions).
            Class<?> mutable = Class.forName("net.minecraft.network.chat.MutableComponent");
            componentEmpty = nmsComponent.getMethod("empty");
            mutableAppend = mutable.getMethod("append", nmsComponent);
            adventureToVanilla = Class.forName("io.papermc.paper.adventure.PaperAdventure").getMethod("asVanilla", net.kyori.adventure.text.Component.class);
            headReady = true;
         } catch (Throwable t) {
            headReady = false;
         }

         nmsReady = true;
      } catch (Exception e) {
         System.err.println("[TaxSMP] NMS scoreboard init failed: " + e);
         e.printStackTrace();
         nmsReady = false;
      }
   }

   private static class BoardData {
      Object scoreboard;
      Object objective;
      Object[] teams;
      int lastLineCount;
   }
}
