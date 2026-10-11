package com.taxsmp.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public class Db {
   private static final long HEALTH_CHECK_SECONDS = 15L;
   private static final long APPLIED_ID_RETENTION_MILLIS = 2592000000L;
   private static final int WRITE_ATTEMPTS = 3;
   private static final long GATE_WAIT_MILLIS = 30000L;
   private final JavaPlugin plugin;
   private HikariDataSource ds;
   private WriteJournal journal;
   private int poolSize;
   private Semaphore gate;
   private ExecutorService dbExecutor;
   private volatile boolean configured;
   private volatile boolean healthy;
   private final AtomicBoolean replaying = new AtomicBoolean(false);
   private final CopyOnWriteArrayList<Runnable> reconnectListeners = new CopyOnWriteArrayList<>();
   private volatile boolean everConnected;
   private long lastPruneAt;
   private final ConcurrentHashMap<UUID, AtomicInteger> inFlight = new ConcurrentHashMap<>();

   public Db(JavaPlugin plugin) {
      this.plugin = plugin;
   }

   public boolean isEnabled() {
      return this.configured && this.ds != null && !this.ds.isClosed();
   }

   public boolean isHealthy() {
      return this.isEnabled() && this.healthy;
   }

   public boolean hasEverConnected() {
      return this.everConnected;
   }

   public int pendingWriteCount() {
      return this.journal == null ? 0 : this.journal.pendingCount();
   }

   public boolean hasPendingFor(UUID owner) {
      if (owner == null) {
         return false;
      } else {
         return this.inFlight.containsKey(owner) ? true : this.journal != null && this.journal.hasPendingFor(owner.toString());
      }
   }

   private void beginWrite(UUID owner) {
      if (owner != null) {
         this.inFlight.computeIfAbsent(owner, k -> new AtomicInteger()).incrementAndGet();
      }
   }

   private void endWrite(UUID owner) {
      if (owner != null) {
         AtomicInteger n = this.inFlight.get(owner);
         if (n != null && n.decrementAndGet() <= 0) {
            this.inFlight.remove(owner, n);
         }
      }
   }

   public void onReconnect(Runnable listener) {
      if (listener != null) {
         this.reconnectListeners.add(listener);
      }
   }

   public boolean init() {
      FileConfiguration c = this.plugin.getConfig();
      if (!c.getBoolean("mysql.enabled", false)) {
         this.plugin.getLogger().warning("MySQL disabled in config; using local files only (no cross-server sync).");
         this.configured = false;
         return false;
      }

      this.configured = true;
      this.journal = new WriteJournal(this.plugin);

      try {
         Class.forName("com.mysql.cj.jdbc.Driver");
         String host = c.getString("mysql.host", "127.0.0.1");
         int port = c.getInt("mysql.port", 3306);
         String database = c.getString("mysql.database", "taxsmp");
         String params = c.getString("mysql.params", "useSSL=false&autoReconnect=true");
         this.poolSize = Math.max(2, c.getInt("mysql.pool-size", 10));
         int hikariSize = this.poolSize + 1;
         HikariConfig hc = new HikariConfig();
         hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database + "?" + params);
         hc.setUsername(c.getString("mysql.username", "root"));
         hc.setPassword(c.getString("mysql.password", ""));
         hc.setMaximumPoolSize(hikariSize);
         hc.setMinimumIdle(hikariSize);
         hc.setPoolName("TaxSMP-Hikari");
         hc.setConnectionTimeout(10000L);
         hc.setIdleTimeout(0L);
         hc.setMaxLifetime(600000L);
         hc.setKeepaliveTime(60000L);
         hc.setValidationTimeout(5000L);
         hc.setLeakDetectionThreshold(30000L);
         hc.setInitializationFailTimeout(-1L);
         this.ds = new HikariDataSource(hc);
         this.gate = new Semaphore(this.poolSize, true);
         this.dbExecutor = newDbExecutor(this.poolSize);
      } catch (Throwable t) {
         this.plugin.getLogger().severe("Could not create the MySQL connection pool: " + t.getMessage());
         this.ds = null;
         return false;
      }

      boolean up = this.probe();
      if (up) {
         this.everConnected = true;
         this.healthy = true;
         this.ensureSupportTables();
         this.plugin.getLogger().info("Connected to MySQL - cross-server sync enabled.");
      } else {
         this.healthy = false;
         this.plugin
            .getLogger()
            .severe(
               "MySQL is NOT reachable at startup. Player data will be served from the most recent local snapshot and every change will be queued to disk until the database returns. Do not wipe the TaxSMP plugin folder while this is the case."
            );
      }

      this.startHealthCheck();
      int queued = this.journal.pendingCount();
      if (queued > 0) {
         this.plugin
            .getLogger()
            .warning(queued + " database write(s) from a previous session are still queued; they will be replayed as soon as MySQL is reachable.");
         if (up) {
            Bukkit.getAsyncScheduler().runNow(this.plugin, task -> this.replayJournal());
         }
      }

      return up;
   }

   private void startHealthCheck() {
      Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin, task -> this.healthTick(), 15L, 15L, TimeUnit.SECONDS);
   }

   private void healthTick() {
      if (this.ds != null && !this.ds.isClosed()) {
         boolean up = this.probe();
         boolean was = this.healthy;
         this.healthy = up;
         if (up && !was) {
            this.everConnected = true;
            this.plugin.getLogger().info("MySQL is reachable again - replaying queued writes.");
            this.ensureSupportTables();
            this.replayJournal();

            for (Runnable r : this.reconnectListeners) {
               try {
                  r.run();
               } catch (Throwable t) {
                  this.plugin.getLogger().warning("A reconnect handler failed: " + t.getMessage());
               }
            }
         } else if (!up && was) {
            this.plugin.getLogger().severe("Lost the connection to MySQL. Changes are being queued to disk and will be applied when it returns.");
         } else if (up) {
            this.maybePruneAppliedIds();
         }
      }
   }

   private boolean probe() {
      try (
         Connection conn = this.ds.getConnection();
         Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT 1");
      ) {
         return rs.next();
      } catch (SQLException e) {
         return false;
      }
   }

   private void ensureSupportTables() {
      try (
         Connection conn = this.ds.getConnection();
         Statement st = conn.createStatement();
      ) {
         st.executeUpdate(
            "CREATE TABLE IF NOT EXISTS taxsmp_applied_writes (id CHAR(36) NOT NULL PRIMARY KEY, applied_at BIGINT NOT NULL, INDEX idx_applied_at (applied_at))"
         );
      } catch (SQLException e) {
         this.plugin.getLogger().severe("Could not create taxsmp_applied_writes table: " + e.getMessage());
      }
   }

   private void maybePruneAppliedIds() {
      long now = System.currentTimeMillis();
      if (now - this.lastPruneAt >= TimeUnit.HOURS.toMillis(6L)) {
         this.lastPruneAt = now;

         try (
            Connection conn = this.ds.getConnection();
            PreparedStatement ps = conn.prepareStatement("DELETE FROM taxsmp_applied_writes WHERE applied_at < ?");
         ) {
            ps.setLong(1, now - 2592000000L);
            ps.executeUpdate();
         } catch (SQLException var11) {
         }
      }
   }

   public Connection getConnection() throws SQLException {
      if (this.ds == null) {
         throw new SQLException("MySQL pool is not available");
      }

      Semaphore g = this.gate;
      if (g == null) {
         return this.ds.getConnection();
      }

      try {
         if (!g.tryAcquire(30000L, TimeUnit.MILLISECONDS)) {
            throw new SQLException("Timed out waiting for a free database connection after 30000ms (pool size " + this.poolSize + ")");
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
         throw new SQLException("Interrupted while waiting for a database connection", e);
      }

      Connection real;
      try {
         real = this.ds.getConnection();
      } catch (SQLException | RuntimeException e) {
         g.release();
         throw e;
      }

      return gated(real, g);
   }

   private static Connection gated(Connection real, Semaphore gate) {
      AtomicBoolean released = new AtomicBoolean(false);
      return (Connection)Proxy.newProxyInstance(Db.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
         if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
            try {
               real.close();
            } finally {
               if (released.compareAndSet(false, true)) {
                  gate.release();
               }
            }

            return null;
         } else {
            try {
               return method.invoke(real, args);
            } catch (InvocationTargetException e) {
               throw e.getCause() == null ? e : e.getCause();
            }
         }
      });
   }

   public void async(Runnable work) {
      if (work != null) {
         ExecutorService ex = this.dbExecutor;
         if (ex != null && !ex.isShutdown()) {
            try {
               ex.execute(work);
            } catch (RejectedExecutionException e) {
               Bukkit.getAsyncScheduler().runNow(this.plugin, task -> work.run());
            }
         } else {
            Bukkit.getAsyncScheduler().runNow(this.plugin, task -> work.run());
         }
      }
   }

   private static ExecutorService newDbExecutor(int poolSize) {
      AtomicInteger n = new AtomicInteger();
      return new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
         Thread t = new Thread(r, "TaxSMP-DB-" + n.incrementAndGet());
         t.setDaemon(true);
         return t;
      });
   }

   public void close() {
      if (this.dbExecutor != null) {
         this.dbExecutor.shutdown();

         try {
            if (!this.dbExecutor.awaitTermination(10L, TimeUnit.SECONDS)) {
               this.plugin.getLogger().warning("Database work was still queued at shutdown; it will be journalled.");
               this.dbExecutor.shutdownNow();
            }
         } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            this.dbExecutor.shutdownNow();
         }

         this.dbExecutor = null;
      }

      if (this.journal != null && this.journal.pendingCount() > 0) {
         this.plugin
            .getLogger()
            .warning(
               "Shutting down with "
                  + this.journal.pendingCount()
                  + " queued database write(s). They are saved to pending-writes.log and will be applied on next start."
            );
      }

      if (this.ds != null) {
         this.ds.close();
         this.ds = null;
      }

      this.healthy = false;
   }

   public boolean writeIdempotent(UUID owner, String sql, Object... params) {
      return this.attempt(null, owner, false, sql, Arrays.asList(params));
   }

   public boolean writeDelta(UUID owner, String sql, Object... params) {
      return this.attempt(UUID.randomUUID().toString(), owner, true, sql, Arrays.asList(params));
   }

   public boolean writeIdempotentBatch(UUID owner, String sql, List<Object[]> rows) {
      if (rows.isEmpty()) {
         return true;
      }

      this.beginWrite(owner);

      try {
         if (this.isHealthy()) {
            for (int attempt = 1; attempt <= 3; attempt++) {
               try (
                  Connection conn = this.getConnection();
                  PreparedStatement ps = conn.prepareStatement(sql);
               ) {
                  for (Object[] row : rows) {
                     bind(ps, Arrays.asList(row));
                     ps.addBatch();
                  }

                  ps.executeBatch();
                  return true;
               } catch (SQLException e) {
                  this.noteFailure(e, attempt, sql);
                  sleepBackoff(attempt);
               }
            }
         }

         for (Object[] row : rows) {
            this.journalFailed(null, owner, false, sql, Arrays.asList(row));
         }

         return false;
      } finally {
         this.endWrite(owner);
      }
   }

   public boolean writeDeltaBatch(UUID owner, String sql, List<Object[]> rows) {
      boolean allOk = true;

      for (Object[] row : rows) {
         if (!this.attempt(UUID.randomUUID().toString(), owner, true, sql, Arrays.asList(row))) {
            allOk = false;
         }
      }

      return allOk;
   }

   private boolean attempt(String id, UUID owner, boolean delta, String sql, List<Object> params) {
      this.beginWrite(owner);

      try {
         if (this.isHealthy()) {
            for (int i = 1; i <= 3; i++) {
               try {
                  this.execute(id, delta, sql, params);
                  return true;
               } catch (SQLException e) {
                  this.noteFailure(e, i, sql);
                  sleepBackoff(i);
               }
            }
         }

         this.journalFailed(id, owner, delta, sql, params);
         return false;
      } finally {
         this.endWrite(owner);
      }
   }

   private boolean execute(String id, boolean delta, String sql, List<Object> params) throws SQLException {
      if (delta && id != null) {
         try (Connection conn = this.getConnection()) {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try {
               int claimed;
               try (PreparedStatement guard = conn.prepareStatement("INSERT IGNORE INTO taxsmp_applied_writes (id, applied_at) VALUES (?, ?)")) {
                  guard.setString(1, id);
                  guard.setLong(2, System.currentTimeMillis());
                  claimed = guard.executeUpdate();
               }

               if (claimed == 0) {
                  conn.rollback();
                  return false;
               }

               try (PreparedStatement ps = conn.prepareStatement(sql)) {
                  bind(ps, params);
                  ps.executeUpdate();
               }

               conn.commit();
               return true;
            } catch (SQLException e) {
               try {
                  conn.rollback();
               } catch (SQLException var31) {
               }

               throw e;
            } finally {
               try {
                  conn.setAutoCommit(previousAutoCommit);
               } catch (SQLException var30) {
               }
            }
         }
      } else {
         try (
            Connection conn = this.getConnection();
            PreparedStatement ps = conn.prepareStatement(sql);
         ) {
            bind(ps, params);
            ps.executeUpdate();
            return true;
         }
      }
   }

   private void journalFailed(String id, UUID owner, boolean delta, String sql, List<Object> params) {
      this.healthy = false;
      if (this.journal == null) {
         this.plugin.getLogger().severe("Database write failed and no journal is available - data lost: " + sql);
      } else {
         this.journal.append(id, owner == null ? null : owner.toString(), delta, sql, params);
      }
   }

   private void noteFailure(SQLException e, int attempt, String sql) {
      if (attempt == 1) {
         this.plugin.getLogger().warning("Database write failed (will retry): " + e.getMessage() + " [" + shortSql(sql) + "]");
      }
   }

   private static String shortSql(String sql) {
      String flat = sql.replaceAll("\\s+", " ").trim();
      return flat.length() <= 60 ? flat : flat.substring(0, 60) + "...";
   }

   private static void sleepBackoff(int attempt) {
      try {
         Thread.sleep(200L * attempt);
      } catch (InterruptedException ignored) {
         Thread.currentThread().interrupt();
      }
   }

   private static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
      for (int i = 0; i < params.size(); i++) {
         ps.setObject(i + 1, params.get(i));
      }
   }

   public void replayJournal() {
      if (this.journal != null && !this.journal.isEmpty()) {
         if (this.replaying.compareAndSet(false, true)) {
            try {
               List<WriteJournal.Entry> entries = this.journal.readAll();
               if (!entries.isEmpty()) {
                  this.plugin.getLogger().info("Replaying " + entries.size() + " queued database write(s)...");
                  List<WriteJournal.Entry> stillPending = new ArrayList<>();
                  int applied = 0;
                  int skipped = 0;

                  for (int i = 0; i < entries.size(); i++) {
                     WriteJournal.Entry e = entries.get(i);

                     try {
                        if (this.execute(e.id(), e.delta(), e.sql(), e.params())) {
                           applied++;
                        } else {
                           skipped++;
                        }
                     } catch (SQLException ex) {
                        this.healthy = false;
                        this.plugin.getLogger().warning("Replay interrupted (" + ex.getMessage() + "); " + (entries.size() - i) + " write(s) remain queued.");
                        stillPending.addAll(entries.subList(i, entries.size()));
                        break;
                     }
                  }

                  this.journal.rewrite(stillPending);
                  if (stillPending.isEmpty()) {
                     this.plugin
                        .getLogger()
                        .info("Replay complete: " + applied + " write(s) applied" + (skipped > 0 ? ", " + skipped + " already present" : "") + ".");
                  }

                  return;
               }
            } finally {
               this.replaying.set(false);
            }
         }
      }
   }
}
