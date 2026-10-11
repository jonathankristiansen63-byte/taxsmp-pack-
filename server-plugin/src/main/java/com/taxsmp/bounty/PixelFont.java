package com.taxsmp.bounty;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.HashMap;
import java.util.Map;

public final class PixelFont {
   public static final int GLYPH_W = 5;
   public static final int GLYPH_H = 7;
   private static final Map<Character, boolean[][]> GLYPHS = new HashMap<>();

   private PixelFont() {
   }

   private static void put(char c, String... rows) {
      boolean[][] bits = new boolean[7][5];

      for (int y = 0; y < 7; y++) {
         String row = rows[y];

         for (int x = 0; x < 5; x++) {
            bits[y][x] = row.charAt(x) == '#';
         }
      }

      GLYPHS.put(c, bits);
   }

   private static boolean[][] glyph(char c) {
      boolean[][] g = GLYPHS.get(Character.toUpperCase(c));
      return g != null ? g : GLYPHS.get('?');
   }

   public static int width(String text, int scale, int tracking) {
      return text != null && !text.isEmpty() ? text.length() * 5 * scale + (text.length() - 1) * tracking : 0;
   }

   public static int height(int scale) {
      return 7 * scale;
   }

   public static int fitScale(String text, int maxWidth, int startScale, int tracking) {
      int scale = Math.max(1, startScale);

      while (scale > 1 && width(text, scale, tracking) > maxWidth) {
         scale--;
      }

      return scale;
   }

   public static String ellipsize(String text, int maxWidth, int scale, int tracking) {
      if (text == null) {
         return "";
      }

      if (width(text, scale, tracking) <= maxWidth) {
         return text;
      }

      String cut = text;

      while (cut.length() > 1 && width(cut + ".", scale, tracking) > maxWidth) {
         cut = cut.substring(0, cut.length() - 1);
      }

      return cut + ".";
   }

   public static void draw(Graphics2D g, String text, int x, int y, int scale, int tracking, Color color) {
      if (text != null && !text.isEmpty()) {
         g.setColor(color);
         int cx = x;

         for (int i = 0; i < text.length(); i++) {
            boolean[][] bits = glyph(text.charAt(i));

            for (int gy = 0; gy < 7; gy++) {
               for (int gx = 0; gx < 5; gx++) {
                  if (bits[gy][gx]) {
                     g.fillRect(cx + gx * scale, y + gy * scale, scale, scale);
                  }
               }
            }

            cx += 5 * scale + tracking;
         }
      }
   }

   public static void drawBold(Graphics2D g, String text, int x, int y, int scale, int tracking, Color color) {
      draw(g, text, x, y, scale, tracking, color);
      draw(g, text, x + 1, y, scale, tracking, color);
   }

   public static void drawCentered(Graphics2D g, String text, int centerX, int y, int scale, int tracking, Color color) {
      draw(g, text, centerX - width(text, scale, tracking) / 2, y, scale, tracking, color);
   }

   public static void drawCenteredBold(Graphics2D g, String text, int centerX, int y, int scale, int tracking, Color color) {
      drawBold(g, text, centerX - width(text, scale, tracking) / 2, y, scale, tracking, color);
   }

   public static void drawCenteredShadowed(Graphics2D g, String text, int centerX, int y, int scale, int tracking, Color color, Color shadow, int offset) {
      drawCenteredBold(g, text, centerX + offset, y + offset, scale, tracking, shadow);
      drawCenteredBold(g, text, centerX, y, scale, tracking, color);
   }

   static {
      put('A', ".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
      put('B', "####.", "#...#", "#...#", "####.", "#...#", "#...#", "####.");
      put('C', ".###.", "#...#", "#....", "#....", "#....", "#...#", ".###.");
      put('D', "####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####.");
      put('E', "#####", "#....", "#....", "####.", "#....", "#....", "#####");
      put('F', "#####", "#....", "#....", "####.", "#....", "#....", "#....");
      put('G', ".###.", "#...#", "#....", "#.###", "#...#", "#...#", ".###.");
      put('H', "#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#");
      put('I', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####");
      put('J', "..###", "...#.", "...#.", "...#.", "...#.", "#..#.", ".##..");
      put('K', "#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#");
      put('L', "#....", "#....", "#....", "#....", "#....", "#....", "#####");
      put('M', "#...#", "##.##", "#.#.#", "#...#", "#...#", "#...#", "#...#");
      put('N', "#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#");
      put('O', ".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
      put('P', "####.", "#...#", "#...#", "####.", "#....", "#....", "#....");
      put('Q', ".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#");
      put('R', "####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#");
      put('S', ".####", "#....", "#....", ".###.", "....#", "....#", "####.");
      put('T', "#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#..");
      put('U', "#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###.");
      put('V', "#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#..");
      put('W', "#...#", "#...#", "#...#", "#...#", "#.#.#", "##.##", "#...#");
      put('X', "#...#", "#...#", ".#.#.", "..#..", ".#.#.", "#...#", "#...#");
      put('Y', "#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#..");
      put('Z', "#####", "....#", "...#.", "..#..", ".#...", "#....", "#####");
      put('0', ".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###.");
      put('1', "..#..", ".##..", "..#..", "..#..", "..#..", "..#..", ".###.");
      put('2', ".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####");
      put('3', "####.", "....#", "....#", ".###.", "....#", "....#", "####.");
      put('4', "...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#.");
      put('5', "#####", "#....", "####.", "....#", "....#", "#...#", ".###.");
      put('6', "..##.", ".#...", "#....", "####.", "#...#", "#...#", ".###.");
      put('7', "#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#...");
      put('8', ".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###.");
      put('9', ".###.", "#...#", "#...#", ".####", "....#", "...#.", ".##..");
      put(' ', ".....", ".....", ".....", ".....", ".....", ".....", ".....");
      put('.', ".....", ".....", ".....", ".....", ".....", ".##..", ".##..");
      put(',', ".....", ".....", ".....", ".....", ".##..", ".##..", ".#...");
      put(':', ".....", ".##..", ".##..", ".....", ".##..", ".##..", ".....");
      put(';', ".....", ".##..", ".##..", ".....", ".##..", ".##..", ".#...");
      put('-', ".....", ".....", ".....", "#####", ".....", ".....", ".....");
      put('_', ".....", ".....", ".....", ".....", ".....", ".....", "#####");
      put('\'', "..#..", "..#..", ".....", ".....", ".....", ".....", ".....");
      put('"', ".#.#.", ".#.#.", ".....", ".....", ".....", ".....", ".....");
      put('!', "..#..", "..#..", "..#..", "..#..", "..#..", ".....", "..#..");
      put('?', ".###.", "#...#", "....#", "...#.", "..#..", ".....", "..#..");
      put('$', "..#..", ".####", "#.#..", ".###.", "..#.#", "####.", "..#..");
      put('#', ".#.#.", ".#.#.", "#####", ".#.#.", "#####", ".#.#.", ".#.#.");
      put('(', "...#.", "..#..", ".#...", ".#...", ".#...", "..#..", "...#.");
      put(')', ".#...", "..#..", "...#.", "...#.", "...#.", "..#..", ".#...");
      put('[', "..##.", "..#..", "..#..", "..#..", "..#..", "..#..", "..##.");
      put(']', ".##..", "..#..", "..#..", "..#..", "..#..", "..#..", ".##..");
      put('/', "....#", "....#", "...#.", "..#..", ".#...", "#....", "#....");
      put('\\', "#....", "#....", ".#...", "..#..", "...#.", "....#", "....#");
      put('+', ".....", "..#..", "..#..", "#####", "..#..", "..#..", ".....");
      put('*', ".....", "#.#.#", ".###.", "#####", ".###.", "#.#.#", ".....");
      put('%', "##..#", "##.#.", "..#..", ".#...", "#..##", ".#.##", ".....");
      put('&', ".##..", "#..#.", "#.#..", ".#...", "#.#.#", "#..#.", ".##.#");
      put('<', "...#.", "..#..", ".#...", "#....", ".#...", "..#..", "...#.");
      put('>', ".#...", "..#..", "...#.", "....#", "...#.", "..#..", ".#...");
      put('=', ".....", ".....", "#####", ".....", "#####", ".....", ".....");
      put('@', ".###.", "#...#", "#.###", "#.#.#", "#.###", "#....", ".###.");
   }
}
