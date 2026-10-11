package com.taxsmp;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

public final class WebApi {
   private static final String JSON_TYPE = "application/json; charset=utf-8";
   private static final int MAX_QUERY_LENGTH = 1024;
   private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_. ]{1,24}");
   private static final Pattern UUID_PLAIN = Pattern.compile("[0-9a-fA-F]{32}");
   private static final Pattern UUID_DASHED = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
   private static final Map<String, StatsTracker.StatType> PLAYER_BOARDS = new LinkedHashMap<>();
   private final TaxSMP plugin;
   private final ConcurrentHashMap<String, WebApi.Bucket> buckets = new ConcurrentHashMap<>();
   private final AtomicBoolean cleaning = new AtomicBoolean();
   private final AtomicBoolean rebuildPending = new AtomicBoolean();
   private final Object buildLock = new Object();
   private volatile WebApiBoards.Snapshot snapshot;
   private volatile WebApiPresence presence;
   private volatile ScheduledTask rebuildTask;
   private volatile boolean running;
   private HttpServer server;
   private ExecutorService pool;
   private byte[] apiKey = new byte[0];
   private boolean requireKey;
   private String allowOrigin = "*";
   private long cacheSeconds = 30L;
   private int defaultLimit = 10;
   private int maxLimit = 500;
   private double ratePerSecond = 20.0;
   private double burst = 60.0;
   private String floodgatePrefix = "";

   public WebApi(TaxSMP plugin) {
      this.plugin = plugin;
   }

   private void ensureDefaults() {
      Object[][] defaults = new Object[][]{
         {"web-api.enabled", Boolean.TRUE},
         {"web-api.port", 8123},
         {"web-api.bind", "0.0.0.0"},
         {"web-api.key", ""},
         {"web-api.require-key", Boolean.FALSE},
         {"web-api.allow-origin", "*"},
         {"web-api.cache-seconds", 30},
         {"web-api.default-limit", 10},
         {"web-api.max-limit", 500},
         {"web-api.hide-vanished", Boolean.TRUE},
         {"web-api.floodgate-prefix", "."},
         {"web-api.rate-limit.per-second", 20},
         {"web-api.rate-limit.burst", 60}
      };
      boolean added = false;

      for (Object[] entry : defaults) {
         String path = (String)entry[0];
         if (!this.plugin.getConfig().isSet(path)) {
            this.plugin.getConfig().set(path, entry[1]);
            added = true;
         }
      }

      if (added) {
         this.plugin.saveConfig();
         this.plugin.getLogger().info("[WebApi] Added the web-api settings to config.yml.");
      }
   }

   public void start() {
      this.ensureDefaults();
      if (this.plugin.getConfig().getBoolean("web-api.enabled", true)) {
         int port = this.plugin.getConfig().getInt("web-api.port", 8123);
         String bind = this.plugin.getConfig().getString("web-api.bind", "0.0.0.0");
         String host = this.plugin.getConfig().getString("web-api.host", "");
         if (host != null && !host.isBlank()) {
            bind = host.trim();
         }

         this.requireKey = this.plugin.getConfig().getBoolean("web-api.require-key", false);
         this.allowOrigin = this.plugin.getConfig().getString("web-api.allow-origin", "*");
         this.cacheSeconds = Math.max(1L, this.plugin.getConfig().getLong("web-api.cache-seconds", 30L));
         this.defaultLimit = Math.max(1, this.plugin.getConfig().getInt("web-api.default-limit", 10));
         this.maxLimit = Math.max(this.defaultLimit, this.plugin.getConfig().getInt("web-api.max-limit", 500));
         this.ratePerSecond = Math.max(0.1, this.plugin.getConfig().getDouble("web-api.rate-limit.per-second", 20.0));
         this.burst = Math.max(1.0, this.plugin.getConfig().getDouble("web-api.rate-limit.burst", 60.0));
         String prefix = this.plugin.getConfig().getString("web-api.floodgate-prefix", ".");
         this.floodgatePrefix = prefix == null ? "" : WebApiBoards.key(prefix);
         boolean hideVanished = this.plugin.getConfig().getBoolean("web-api.hide-vanished", true);
         String configuredKey = this.plugin.getConfig().getString("web-api.key", "");
         if (configuredKey == null || configuredKey.isBlank()) {
            byte[] raw = new byte[24];
            new SecureRandom().nextBytes(raw);
            configuredKey = HexFormat.of().formatHex(raw);
            this.plugin.getConfig().set("web-api.key", configuredKey);
            this.plugin.saveConfig();
            this.plugin.getLogger().info("[WebApi] Generated a new API key in config.yml (web-api.key).");
         }

         this.apiKey = configuredKey.getBytes(StandardCharsets.UTF_8);

         try {
            this.server = HttpServer.create(new InetSocketAddress(bind, port), 128);
         } catch (IOException e) {
            this.plugin.getLogger().warning("[WebApi] Could not bind " + bind + ":" + port + " - " + e.getMessage());
            return;
         }

         AtomicInteger counter = new AtomicInteger();
         this.pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "TaxSMP-WebApi-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
         });
         this.server.setExecutor(this.pool);
         this.route("/", false, null);
         this.route("/ping", true, this::handlePing);
         this.route("/leaderboard", false, this::handleLeaderboard);
         this.route("/leaderboards", false, this::handleAll);
         this.route("/player", true, this::handlePlayer);
         this.route("/online", true, this::handleOnline);
         this.running = true;
         WebApiPresence tracked = new WebApiPresence(this.plugin, this::requestRebuild);
         this.presence = tracked;
         tracked.start(hideVanished);
         this.server.start();
         long period = Math.max(5L, this.cacheSeconds);
         this.rebuildTask = Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin, task -> this.rebuild(), 1L, period, TimeUnit.SECONDS);
         this.plugin.getLogger().info("[WebApi] API listening on " + bind + ":" + port + (this.requireKey ? " (API key required)" : " (public, read-only)"));
      }
   }

   public void restart() {
      this.stop();
      this.buckets.clear();
      this.snapshot = null;
      this.start();
   }

   public void stop() {
      this.running = false;
      ScheduledTask task = this.rebuildTask;
      if (task != null) {
         task.cancel();
         this.rebuildTask = null;
      }

      if (this.server != null) {
         this.server.stop(0);
         this.server = null;
      }

      if (this.pool != null) {
         this.pool.shutdownNow();
         this.pool = null;
      }

      WebApiPresence tracked = this.presence;
      if (tracked != null) {
         tracked.stop();
         this.presence = null;
      }
   }

   private void rebuild() {
      if (this.running) {
         try {
            synchronized (this.buildLock) {
               WebApiPresence tracked = this.presence;
               if (tracked != null) {
                  this.snapshot = WebApiBoards.build(this.plugin, tracked);
               }
            }
         } catch (RuntimeException e) {
            this.plugin.getLogger().warning("[WebApi] Could not refresh the boards: " + e);
         }
      }
   }

   private void requestRebuild() {
      if (this.running && this.rebuildPending.compareAndSet(false, true)) {
         try {
            Bukkit.getAsyncScheduler().runDelayed(this.plugin, task -> {
               this.rebuildPending.set(false);
               this.rebuild();
            }, 2L, TimeUnit.SECONDS);
         } catch (RuntimeException e) {
            this.rebuildPending.set(false);
         }
      }
   }

   private WebApiBoards.Snapshot snapshot() {
      WebApiBoards.Snapshot current = this.snapshot;
      if (current == null) {
         synchronized (this.buildLock) {
            current = this.snapshot;
            if (current == null) {
               current = WebApiBoards.build(this.plugin, this.presence);
               this.snapshot = current;
            }
         }
      }

      return current;
   }

   private void route(String path, boolean live, WebApi.Route route) {
      this.server.createContext(path, ex -> {
         try {
            Map<String, String> query = this.begin(ex, live);
            if (query != null) {
               String requested = ex.getRequestURI().getPath();
               if (route == null || !path.equals(requested) && !(path + "/").equals(requested)) {
                  this.send(ex, 404, "{\"error\":\"not found\"}");
               } else {
                  route.run(ex, query);
               }
            }
         } catch (Throwable t) {
            this.plugin.getLogger().warning("[WebApi] Request failed: " + t);

            try {
               this.send(ex, 500, "{\"error\":\"internal error\"}");
            } catch (IOException | RuntimeException var11) {
            }
         } finally {
            ex.close();
         }
      });
   }

   private Map<String, String> begin(HttpExchange ex, boolean live) throws IOException {
      Headers headers = ex.getResponseHeaders();
      headers.set("Access-Control-Allow-Origin", this.allowOrigin);
      headers.set("Access-Control-Allow-Headers", "X-API-Key");
      headers.set("Cache-Control", live ? "no-store" : "public, max-age=" + Math.min(this.cacheSeconds, 5L));
      headers.set("X-Content-Type-Options", "nosniff");
      if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
         ex.sendResponseHeaders(204, -1L);
         return null;
      }

      if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
         headers.set("Allow", "GET");
         this.send(ex, 405, "{\"error\":\"GET only\"}");
         return null;
      }

      int retryAfter = this.takeToken(ex);
      if (retryAfter > 0) {
         headers.set("Retry-After", Integer.toString(retryAfter));
         this.send(ex, 429, "{\"error\":\"slow down\"}");
         return null;
      }

      String raw = ex.getRequestURI().getRawQuery();
      boolean tooLong = raw != null && raw.length() > 1024;
      Map<String, String> query = tooLong ? Collections.emptyMap() : query(raw);
      if (this.requireKey) {
         String given = ex.getRequestHeaders().getFirst("X-API-Key");
         if (given == null) {
            given = query.get("key");
         }

         if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), this.apiKey)) {
            this.send(ex, 401, "{\"error\":\"unauthorized\"}");
            return null;
         }
      }

      if (tooLong) {
         this.send(ex, 400, "{\"error\":\"bad request\"}");
         return null;
      } else {
         return query;
      }
   }

   private int takeToken(HttpExchange ex) {
      InetSocketAddress remote = ex.getRemoteAddress();
      String ip = remote != null && remote.getAddress() != null ? remote.getAddress().getHostAddress() : "?";
      long now = System.nanoTime();
      WebApi.Bucket bucket = this.buckets.computeIfAbsent(ip, k -> new WebApi.Bucket(this.burst, now));
      if (this.buckets.size() > 4096 && this.cleaning.compareAndSet(false, true)) {
         try {
            this.buckets.values().removeIf(b -> now - b.last > 60000000000L);
            if (this.buckets.size() > 20000) {
               this.buckets.clear();
            }
         } finally {
            this.cleaning.set(false);
         }
      }

      synchronized (bucket) {
         bucket.tokens = Math.min(this.burst, bucket.tokens + (now - bucket.last) / 1.0E9 * this.ratePerSecond);
         bucket.last = now;
         if (bucket.tokens >= 1.0) {
            bucket.tokens--;
            return 0;
         } else {
            return (int)Math.max(1.0, Math.ceil((1.0 - bucket.tokens) / this.ratePerSecond));
         }
      }
   }

   private void handlePing(HttpExchange ex, Map<String, String> query) throws IOException {
      this.send(ex, 200, "{\"ok\":true,\"plugin\":\"TaxSMP\",\"time\":" + System.currentTimeMillis() + "}");
   }

   private void handleLeaderboard(HttpExchange ex, Map<String, String> query) throws IOException {
      StatsTracker.StatType type = parseType(query.get("type"));
      if (type == null) {
         this.send(ex, 400, "{\"error\":\"unknown type\",\"types\":" + typeList() + "}");
      } else {
         this.send(ex, 200, this.board(this.snapshot(), type, this.limit(query), offset(query)));
      }
   }

   private void handleAll(HttpExchange ex, Map<String, String> query) throws IOException {
      WebApiBoards.Snapshot snap = this.snapshot();
      int limit = this.limit(query);
      int offset = offset(query);
      StringBuilder out = new StringBuilder("{\"boards\":[");
      StatsTracker.StatType[] types = StatsTracker.StatType.values();

      for (int i = 0; i < types.length; i++) {
         if (i > 0) {
            out.append(',');
         }

         out.append(this.board(snap, types[i], limit, offset));
      }

      this.send(ex, 200, out.append("]}").toString());
   }

   private String board(WebApiBoards.Snapshot snap, StatsTracker.StatType type, int limit, int offset) {
      WebApiPresence tracked = this.presence;
      WebApiBoards.Row[] rows = snap.boards.get(type).rows;
      int from = Math.min(offset, rows.length);
      int to = (int)Math.min((long)from + limit, rows.length);
      StringBuilder out = new StringBuilder(256 + (to - from) * 150);
      out.append("{\"type\":\"")
         .append(type.name().toLowerCase(Locale.ROOT))
         .append('"')
         .append(",\"label\":\"")
         .append(escape(type.loreLabel))
         .append('"')
         .append(",\"updated\":")
         .append(snap.updated)
         .append(",\"total\":")
         .append(rows.length)
         .append(",\"offset\":")
         .append(offset)
         .append(",\"entries\":[");

      for (int i = from; i < to; i++) {
         WebApiBoards.Row row = rows[i];
         String name = snap.names.get(row.id);
         if (i > from) {
            out.append(',');
         }

         out.append("{\"rank\":")
            .append(i + 1)
            .append(",\"uuid\":\"")
            .append(row.idText)
            .append('"')
            .append(",\"name\":")
            .append(name == null ? "null" : "\"" + escape(name) + "\"")
            .append(",\"value\":")
            .append(row.value)
            .append(",\"display\":\"")
            .append(escape(StatsTracker.formatValue(type, row.value)))
            .append("\",\"online\":")
            .append(tracked != null && tracked.isVisiblyOnline(row.id))
            .append('}');
      }

      return out.append("]}").toString();
   }

   private void handlePlayer(HttpExchange ex, Map<String, String> query) throws IOException {
      String rawUuid = query.get("uuid");
      String rawName = query.get("name");
      UUID id = null;
      if (rawUuid != null) {
         id = parseUuid(rawUuid);
         if (id == null) {
            this.send(ex, 400, "{\"error\":\"invalid uuid\"}");
            return;
         }
      } else if (rawName == null || !NAME.matcher(rawName).matches()) {
         this.send(ex, 400, "{\"error\":\"invalid name\"}");
         return;
      }

      WebApiBoards.Snapshot snap = this.snapshot();
      WebApiPresence tracked = this.presence;
      if (id == null) {
         id = this.resolveName(snap, tracked, rawName);
      }

      if (id != null && (snap.ids.contains(id) || tracked.joinedName(id) != null)) {
         String name = tracked.joinedName(id);
         if (name == null) {
            name = snap.names.get(id);
         }

         long now = System.currentTimeMillis();
         Long lastSeen = tracked.lastSeenFor(id, now);
         StringBuilder out = new StringBuilder(1024);
         out.append("{\"found\":true,\"player\":{\"name\":")
            .append(name == null ? "null" : "\"" + escape(name) + "\"")
            .append(",\"uuid\":\"")
            .append(id)
            .append("\",\"bedrock\":")
            .append(id.getMostSignificantBits() == 0L)
            .append("},\"online\":")
            .append(tracked.isVisiblyOnline(id))
            .append(",\"lastSeen\":")
            .append(lastSeen == null ? "null" : lastSeen.toString())
            .append(",\"stats\":{");
         boolean first = true;

         for (Entry<String, StatsTracker.StatType> board : PLAYER_BOARDS.entrySet()) {
            WebApiBoards.Board data = snap.boards.get(board.getValue());
            int rank = data.rankOf(id);
            long value = rank == 0 ? 0L : data.rows[rank - 1].value;
            if (!first) {
               out.append(',');
            }

            first = false;
            out.append('"')
               .append(board.getKey())
               .append("\":{\"value\":")
               .append(value)
               .append(",\"display\":\"")
               .append(escape(StatsTracker.formatValue(board.getValue(), value)))
               .append("\",\"rank\":")
               .append(rank == 0 ? "null" : Integer.toString(rank))
               .append(",\"total\":")
               .append(data.rows.length)
               .append('}');
         }

         out.append("},\"total\":").append(snap.total).append(",\"updated\":").append(snap.updated).append('}');
         this.send(ex, 200, out.toString());
      } else {
         this.send(ex, 404, "{\"found\":false,\"error\":\"player not found\"}");
      }
   }

   private void handleOnline(HttpExchange ex, Map<String, String> query) throws IOException {
      WebApiPresence tracked = this.presence;
      List<WebApiPresence.Online> players = new ArrayList<>(tracked.visibleOnline());
      players.sort((a, b) -> {
         int byName = a.name.compareToIgnoreCase(b.name);
         return byName != 0 ? byName : a.id.compareTo(b.id);
      });
      StringBuilder out = new StringBuilder(128 + players.size() * 96);
      out.append("{\"online\":").append(players.size()).append(",\"max\":").append(tracked.maxPlayers()).append(",\"players\":[");

      for (int i = 0; i < players.size(); i++) {
         WebApiPresence.Online player = players.get(i);
         if (i > 0) {
            out.append(',');
         }

         out.append("{\"name\":\"")
            .append(escape(player.name))
            .append("\",\"uuid\":\"")
            .append(player.id)
            .append("\",\"bedrock\":")
            .append(player.bedrock)
            .append('}');
      }

      this.send(ex, 200, out.append("],\"updated\":").append(System.currentTimeMillis()).append('}').toString());
   }

   private UUID resolveName(WebApiBoards.Snapshot snap, WebApiPresence tracked, String rawName) {
      String key = WebApiBoards.key(rawName);
      UUID hit = lookup(snap, tracked, key);
      String prefix = this.floodgatePrefix;
      if (hit == null && !prefix.isEmpty()) {
         if (key.startsWith(prefix)) {
            if (key.length() > prefix.length()) {
               hit = lookup(snap, tracked, key.substring(prefix.length()));
            }
         } else {
            hit = lookup(snap, tracked, prefix + key);
         }
      }

      return hit;
   }

   private static UUID lookup(WebApiBoards.Snapshot snap, WebApiPresence tracked, String key) {
      UUID hit = tracked.joinedByKey(key);
      return hit != null ? hit : snap.byKey.get(key);
   }

   private static UUID parseUuid(String raw) {
      String text = raw.trim();
      if (UUID_PLAIN.matcher(text).matches()) {
         text = text.substring(0, 8) + "-" + text.substring(8, 12) + "-" + text.substring(12, 16) + "-" + text.substring(16, 20) + "-" + text.substring(20);
      } else if (!UUID_DASHED.matcher(text).matches()) {
         return null;
      }

      return UUID.fromString(text);
   }

   private static StatsTracker.StatType parseType(String raw) {
      if (raw != null && !raw.isBlank()) {
         String key = raw.trim().toLowerCase(Locale.ROOT);
         switch (key) {
            case "money":
            case "balance":
            case "bal":
               return StatsTracker.StatType.MONEY;
            case "deaths":
               return StatsTracker.StatType.DEATHS;
            case "playtime":
            case "time":
               return StatsTracker.StatType.PLAYTIME;
            case "placed":
            case "blocks_placed":
               return StatsTracker.StatType.BLOCKS_PLACED;
            case "broken":
            case "blocks_broken":
               return StatsTracker.StatType.BLOCKS_BROKEN;
            case "mobs":
            case "mobs_killed":
               return StatsTracker.StatType.MOBS_KILLED;
            case "kills":
            case "player_kills":
               return StatsTracker.StatType.PLAYER_KILLS;
            default:
               return null;
         }
      } else {
         return StatsTracker.StatType.MONEY;
      }
   }

   private static String typeList() {
      StringBuilder out = new StringBuilder("[");
      StatsTracker.StatType[] types = StatsTracker.StatType.values();

      for (int i = 0; i < types.length; i++) {
         out.append(i > 0 ? ",\"" : "\"").append(types[i].name().toLowerCase(Locale.ROOT)).append('"');
      }

      return out.append(']').toString();
   }

   private int limit(Map<String, String> query) {
      String raw = query.get("limit");
      if (raw == null) {
         return this.defaultLimit;
      }

      try {
         return Math.max(1, Math.min(this.maxLimit, Integer.parseInt(raw.trim())));
      } catch (NumberFormatException e) {
         return this.defaultLimit;
      }
   }

   private static int offset(Map<String, String> query) {
      String raw = query.get("offset");
      if (raw == null) {
         return 0;
      }

      try {
         return Math.max(0, Integer.parseInt(raw.trim()));
      } catch (NumberFormatException e) {
         return 0;
      }
   }

   private static Map<String, String> query(String raw) {
      Map<String, String> out = new HashMap<>();
      if (raw != null && !raw.isBlank()) {
         for (String pair : raw.split("&")) {
            int eq = pair.indexOf(61);
            if (eq > 0 && eq < pair.length() - 1) {
               try {
                  out.putIfAbsent(
                     URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
                  );
               } catch (IllegalArgumentException var8) {
               }
            }
         }
      }

      return out;
   }

   private void send(HttpExchange ex, int code, String json) throws IOException {
      byte[] body = json.getBytes(StandardCharsets.UTF_8);
      ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
      ex.sendResponseHeaders(code, body.length);

      try (OutputStream out = ex.getResponseBody()) {
         out.write(body);
      }
   }

   private static String escape(String raw) {
      if (raw == null) {
         return "";
      }

      StringBuilder out = new StringBuilder(raw.length() + 8);

      for (int i = 0; i < raw.length(); i++) {
         char c = raw.charAt(i);
         switch (c) {
            case '\t':
               out.append("\\t");
               break;
            case '\n':
               out.append("\\n");
               break;
            case '\r':
               out.append("\\r");
               break;
            case '"':
               out.append("\\\"");
               break;
            case '\\':
               out.append("\\\\");
               break;
            default:
               if (c >= ' ' && c <= '~') {
                  out.append(c);
               } else {
                  out.append(String.format("\\u%04x", Integer.valueOf(c)));
               }
         }
      }

      return out.toString();
   }

   void awaitShutdown() throws InterruptedException {
      if (this.pool != null) {
         this.pool.awaitTermination(2L, TimeUnit.SECONDS);
      }
   }

   static {
      PLAYER_BOARDS.put("money", StatsTracker.StatType.MONEY);
      PLAYER_BOARDS.put("kills", StatsTracker.StatType.PLAYER_KILLS);
      PLAYER_BOARDS.put("playtime", StatsTracker.StatType.PLAYTIME);
      PLAYER_BOARDS.put("mobs", StatsTracker.StatType.MOBS_KILLED);
      PLAYER_BOARDS.put("broken", StatsTracker.StatType.BLOCKS_BROKEN);
      PLAYER_BOARDS.put("placed", StatsTracker.StatType.BLOCKS_PLACED);
      PLAYER_BOARDS.put("deaths", StatsTracker.StatType.DEATHS);
   }

   private static final class Bucket {
      double tokens;
      long last;

      Bucket(double tokens, long last) {
         this.tokens = tokens;
         this.last = last;
      }
   }

   private interface Route {
      void run(HttpExchange var1, Map<String, String> var2) throws IOException;
   }
}
