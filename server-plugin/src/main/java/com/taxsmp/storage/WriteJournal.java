package com.taxsmp.storage;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.plugin.java.JavaPlugin;

public final class WriteJournal {
   private static final String FIELD = "\u0001";
   private static final String FORMAT_VERSION = "1";
   private final JavaPlugin plugin;
   private final File file;
   private final Object lock = new Object();
   private final ConcurrentHashMap<String, AtomicInteger> pendingByOwner = new ConcurrentHashMap<>();
   private final AtomicInteger pendingTotal = new AtomicInteger();

   public WriteJournal(JavaPlugin plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "pending-writes.log");
      this.plugin.getDataFolder().mkdirs();
      this.recountFromDisk();
   }

   public int pendingCount() {
      return this.pendingTotal.get();
   }

   public boolean isEmpty() {
      return this.pendingTotal.get() == 0;
   }

   public boolean hasPendingFor(String owner) {
      if (owner != null && !owner.isEmpty()) {
         AtomicInteger n = this.pendingByOwner.get(owner);
         return n != null && n.get() > 0;
      } else {
         return false;
      }
   }

   public String append(String id, String owner, boolean delta, String sql, List<Object> params) {
      String entryId = id != null ? id : UUID.randomUUID().toString();
      String line = encode(new WriteJournal.Entry(entryId, System.currentTimeMillis(), owner == null ? "" : owner, delta, sql, params));
      synchronized (this.lock) {
         try (BufferedWriter w = Files.newBufferedWriter(
               this.file.toPath(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
            )) {
            w.write(line);
            w.newLine();
         } catch (IOException e) {
            this.plugin.getLogger().severe("COULD NOT JOURNAL A FAILED DATABASE WRITE - THIS DATA IS LOST: " + e.getMessage());
            return null;
         }
      }

      this.pendingTotal.incrementAndGet();
      if (owner != null && !owner.isEmpty()) {
         this.pendingByOwner.computeIfAbsent(owner, k -> new AtomicInteger()).incrementAndGet();
      }

      return entryId;
   }

   public List<WriteJournal.Entry> readAll() {
      synchronized (this.lock) {
         if (!this.file.exists()) {
            return Collections.emptyList();
         }

         List<String> lines;
         try {
            lines = Files.readAllLines(this.file.toPath(), StandardCharsets.UTF_8);
         } catch (IOException e) {
            this.plugin.getLogger().severe("Could not read the pending-write journal: " + e.getMessage());
            return Collections.emptyList();
         }

         List<WriteJournal.Entry> out = new ArrayList<>(lines.size());
         int skipped = 0;

         for (String line : lines) {
            if (!line.isBlank()) {
               WriteJournal.Entry e = decode(line);
               if (e == null) {
                  skipped++;
               } else {
                  out.add(e);
               }
            }
         }

         if (skipped > 0) {
            this.plugin
               .getLogger()
               .warning("Discarded " + skipped + " unreadable line(s) from the pending-write journal (likely a torn write during a crash).");
         }

         return out;
      }
   }

   public void rewrite(List<WriteJournal.Entry> remaining) {
      synchronized (this.lock) {
         try {
            if (remaining.isEmpty()) {
               Files.deleteIfExists(this.file.toPath());
            } else {
               File tmp = new File(this.file.getParentFile(), this.file.getName() + ".tmp");

               try (BufferedWriter w = Files.newBufferedWriter(
                     tmp.toPath(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
                  )) {
                  for (WriteJournal.Entry e : remaining) {
                     w.write(encode(e));
                     w.newLine();
                  }
               }

               Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
         } catch (IOException e) {
            this.plugin.getLogger().severe("Could not rewrite the pending-write journal: " + e.getMessage());
            return;
         }
      }

      this.recountFromDisk();
   }

   private void recountFromDisk() {
      this.pendingByOwner.clear();
      this.pendingTotal.set(0);

      for (WriteJournal.Entry e : this.readAll()) {
         this.pendingTotal.incrementAndGet();
         if (!e.owner().isEmpty()) {
            this.pendingByOwner.computeIfAbsent(e.owner(), k -> new AtomicInteger()).incrementAndGet();
         }
      }
   }

   private static String b64(String s) {
      return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
   }

   private static String unb64(String s) {
      return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
   }

   private static String encode(WriteJournal.Entry e) {
      StringBuilder sb = new StringBuilder();
      sb.append("1")
         .append("\u0001")
         .append(e.id())
         .append("\u0001")
         .append(e.createdAt())
         .append("\u0001")
         .append(b64(e.owner()))
         .append("\u0001")
         .append(e.delta() ? "1" : "0")
         .append("\u0001")
         .append(b64(e.sql()));

      for (Object p : e.params()) {
         sb.append("\u0001").append(encodeParam(p));
      }

      return sb.toString();
   }

   private static String encodeParam(Object p) {
      if (p == null) {
         return "N:";
      } else if (p instanceof String s) {
         return "S:" + b64(s);
      } else if (p instanceof Double d) {
         return "D:" + b64(Double.toString(d));
      } else if (p instanceof Float f) {
         return "D:" + b64(Double.toString(f.doubleValue()));
      } else if (p instanceof Integer i) {
         return "I:" + b64(Integer.toString(i));
      } else if (p instanceof Long l) {
         return "L:" + b64(Long.toString(l));
      } else if (p instanceof Boolean b) {
         return "B:" + b64(Boolean.toString(b));
      } else {
         return p instanceof byte[] bytes ? "A:" + Base64.getEncoder().encodeToString(bytes) : "S:" + b64(String.valueOf(p));
      }
   }

   private static Object decodeParam(String token) {
      int idx = token.indexOf(58);
      if (idx < 0) {
         return null;
      }

      String type = token.substring(0, idx);
      String body = token.substring(idx + 1);

      return switch (type) {
         case "N" -> null;
         case "S" -> unb64(body);
         case "D" -> Double.valueOf(unb64(body));
         case "I" -> Integer.valueOf(unb64(body));
         case "L" -> Long.valueOf(unb64(body));
         case "B" -> Boolean.valueOf(unb64(body));
         case "A" -> Base64.getDecoder().decode(body);
         default -> throw new IllegalArgumentException("unknown param type " + type);
      };
   }

   private static WriteJournal.Entry decode(String line) {
      try {
         String[] parts = line.split("\u0001", -1);
         if (parts.length >= 6 && "1".equals(parts[0])) {
            String id = parts[1];
            long createdAt = Long.parseLong(parts[2]);
            String owner = unb64(parts[3]);
            boolean delta = "1".equals(parts[4]);
            String sql = unb64(parts[5]);
            List<Object> params = new ArrayList<>(parts.length - 6);

            for (int i = 6; i < parts.length; i++) {
               params.add(decodeParam(parts[i]));
            }

            return new WriteJournal.Entry(id, createdAt, owner, delta, sql, params);
         } else {
            return null;
         }
      } catch (RuntimeException ex) {
         return null;
      }
   }

   public record Entry(String id, long createdAt, String owner, boolean delta, String sql, List<Object> params) {
   }
}
