package com.taxsmp;

public final class PlaytimeFormatter {
   private PlaytimeFormatter() {
   }

   public static String compact(long ticks) {
      long totalMinutes = Math.max(0L, ticks) / 20L / 60L;
      long days = totalMinutes / 1440L;
      long hours = totalMinutes % 1440L / 60L;
      long minutes = totalMinutes % 60L;
      if (days > 0L) {
         return hours > 0L ? days + "d " + hours + "h" : days + "d";
      } else {
         return hours > 0L ? hours + "h " + minutes + "m" : minutes + "m";
      }
   }
}
