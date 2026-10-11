package dev.foliartp.gui;

/** Click regions for the poster menu and its native Bedrock inventory variant. */
public final class ArtworkLayout {
   private ArtworkLayout() {}

   public static String dimension(int slot, boolean bedrock) {
      if (bedrock) return switch (slot) { case 11 -> "overworld"; case 13 -> "nether"; case 15 -> "end"; default -> null; };
      if (slot < 0 || slot >= 45) return null;
      return switch (slot % 9 / 3) { case 0 -> "overworld"; case 1 -> "nether"; default -> "end"; };
   }

   public static String region(int slot, boolean bedrock) {
      return region(slot, bedrock, "overworld");
   }

   public static String region(int slot, boolean bedrock, String dimension) {
      String region = regionAt(slot, bedrock);
      return "end".equals(dimension) && "na".equals(region) ? null : region;
   }

   private static String regionAt(int slot, boolean bedrock) {
      if (bedrock) return switch (slot) { case 11 -> "eu"; case 15 -> "na"; default -> null; };
      if (slot < 0 || slot >= 54) return null;
      int row = slot / 9, column = slot % 9;
      if (row < 1 || row > 3) return null;
      if (column >= 1 && column <= 3) return "na";
      if (column >= 4 && column <= 7) return "eu";
      return null;
   }

   public static int back(boolean bedrock) { return bedrock ? 22 : 49; }

   public static String[] command(String dimension, String region) {
      if ("end".equals(dimension)) return new String[]{"end"};
      if ("nether".equals(dimension)) return new String[]{region, "nether"};
      return "na".equals(region) ? new String[]{"na", "east"} : new String[]{"europe"};
   }
}
