package com.taxsmp.bounty;

import com.taxsmp.PriceManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class PosterRenderer {
   private static final Color PAPER_LIGHT = new Color(219, 182, 155);
   private static final Color PAPER_DARK = new Color(194, 158, 132);
   private static final Color FIBRE = new Color(180, 152, 138);
   private static final Color STAIN = new Color(168, 132, 112);
   private static final Color SCORCH = new Color(150, 110, 80);
   private static final Color BURN = new Color(102, 67, 42);
   private static final Color CHAR = new Color(44, 29, 18);
   private static final Color INK = new Color(51, 34, 20);
   private static final Color INK_SOFT = new Color(90, 64, 40);
   private static final Color REWARD_INK = new Color(110, 36, 24);

   private PosterRenderer() {
   }

   public static BufferedImage poster(int width, int height, String name, double amount, BufferedImage head, String note) {
      BufferedImage img = new BufferedImage(width, height, 2);
      Graphics2D g = img.createGraphics();

      try {
         prepare(g);
         int u = unit(height);
         paintParchment(g, width, height, u, name == null ? 0L : name.hashCode());
         int cx = width / 2;
         int margin = 5 * u;
         int inner = width - margin * 2;
         String shown = safeName(name);
         String reward = berry(amount);
         String tagline = "DEAD OR ALIVE";
         boolean hasNote = note != null && !note.isBlank();
         int titleScale = fitBoth("WANTED", inner, u, height * 19 / 700);
         int tagScale = fitBoth(tagline, inner, u, Math.max(1, titleScale * 2 / 5));
         int nameScale = fitBoth(shown, inner - 10 * u, u, Math.max(1, titleScale / 2));
         int rewardScale = fitBoth(reward, inner, u, Math.max(1, titleScale / 2));
         int noteScale = Math.max(1, u * 3 / 4);
         int bottom = height - 5 * u - (hasNote ? PixelFont.height(noteScale) + 2 * u : 0);
         int rewardY = bottom - PixelFont.height(rewardScale);
         int nameY = rewardY - 2 * u - PixelFont.height(nameScale);
         int tagY = nameY - 3 * u - PixelFont.height(tagScale);
         int y = 3 * u;
         PixelFont.drawCenteredShadowed(g, "WANTED", cx, y, titleScale, u, INK, new Color(138, 102, 57, 150), Math.max(1, u));
         y += PixelFont.height(titleScale) + 2 * u;
         drawOrnament(g, cx, y, inner / 2, u, INK);
         y += 4 * u;
         int portraitRoom = tagY - y - 2 * u;
         int frame = Math.min(portraitRoom, inner - 6 * u);
         frame = Math.max(8, frame - frame % 8);
         drawFramedHead(g, head, cx - frame / 2, y + Math.max(0, (portraitRoom - frame) / 2), frame, u);
         PixelFont.drawCenteredBold(g, tagline, cx, tagY, tagScale, u, INK);
         String fittedName = PixelFont.ellipsize(shown, inner - 10 * u, nameScale, u);
         PixelFont.drawCenteredBold(g, fittedName, cx, nameY, nameScale, u, INK);
         int nameHalf = PixelFont.width(fittedName, nameScale, u) / 2;
         int curl = Math.max(6, 5 * u);
         int curlY = nameY + (PixelFont.height(nameScale) - curl) / 2;
         drawCurl(g, cx - nameHalf - curl - 2 * u, curlY, curl, u, INK_SOFT, false);
         drawCurl(g, cx + nameHalf + 2 * u, curlY, curl, u, INK_SOFT, true);
         PixelFont.drawCenteredBold(g, reward, cx, rewardY, rewardScale, u, REWARD_INK);
         if (hasNote) {
            PixelFont.drawCentered(g, PixelFont.ellipsize(note, inner, noteScale, u), cx, height - 4 * u - PixelFont.height(noteScale), noteScale, u, INK_SOFT);
         }
      } finally {
         g.dispose();
      }

      return img;
   }

   public static BufferedImage board(int width, int height, List<PosterRenderer.Row> rows) {
      BufferedImage img = new BufferedImage(width, height, 2);
      Graphics2D g = img.createGraphics();

      try {
         prepare(g);
         int u = unit(height);
         paintParchment(g, width, height, u, 23294L);
         int cx = width / 2;
         int margin = 4 * u;
         int inner = width - margin * 2;
         int titleScale = fitBoth("WANTED", inner, u, height * 12 / 700);
         int y = 2 * u;
         PixelFont.drawCenteredShadowed(g, "WANTED", cx, y, titleScale, u, INK, new Color(138, 102, 57, 150), Math.max(1, u));
         y += PixelFont.height(titleScale) + 2 * u;
         if (height >= 200) {
            int tagScale = Math.max(1, titleScale / 2);
            PixelFont.drawCenteredBold(g, "DEAD OR ALIVE", cx, y, tagScale, u, INK_SOFT);
            y += PixelFont.height(tagScale) + 2 * u;
         }

         drawOrnament(g, cx, y, inner / 2, u, INK);
         y += 4 * u;
         int listBottom = height - 6 * u;
         if (rows.isEmpty()) {
            PixelFont.drawCentered(g, "NOBODY IS WANTED", cx, (y + listBottom) / 2, Math.max(1, u), u, INK_SOFT);
            return img;
         }

         int count = rows.size();
         int rowH = Math.max(9, (listBottom - y) / Math.max(count, 4));
         int listTop = y + Math.max(0, (listBottom - y - rowH * count) / 2);
         int headSize = Math.max(8, (rowH - u) / 8 * 8);
         int textScale = Math.max(1, Math.min((rowH - u) / 7, inner / 100));
         int rankW = PixelFont.width("10.", textScale, u);

         for (int i = 0; i < count; i++) {
            PosterRenderer.Row row = rows.get(i);
            int rowY = listTop + i * rowH;
            if (i % 2 == 1) {
               g.setColor(new Color(180, 152, 138, 90));
               g.fillRect(margin, rowY, inner, rowH - 1);
            }

            int textY = rowY + Math.max(0, (rowH - PixelFont.height(textScale)) / 2);
            PixelFont.draw(g, i + 1 + ".", margin, textY, textScale, u, INK_SOFT);
            int headX = margin + rankW + u;
            int headY = rowY + Math.max(0, (rowH - headSize) / 2);
            drawHead(g, row.head(), headX, headY, headSize);
            g.setColor(INK);
            g.setStroke(new BasicStroke(Math.max(1, u / 2 + 1)));
            g.drawRect(headX - 1, headY - 1, headSize + 1, headSize + 1);
            String reward = "$" + PriceManager.formatShort(row.amount());
            int rewardW = PixelFont.width(reward, textScale, u);
            int rewardX = width - margin - rewardW;
            PixelFont.drawBold(g, reward, rewardX, textY, textScale, u, i == 0 ? REWARD_INK : INK);
            int nameX = headX + headSize + 2 * u;
            int nameWidth = rewardX - nameX - 2 * u;
            String shown = safeName(row.name());
            int nameScale = PixelFont.fitScale(shown, nameWidth, textScale, u);
            PixelFont.draw(g, PixelFont.ellipsize(shown, nameWidth, nameScale, u), nameX, textY, nameScale, u, INK);
         }
      } finally {
         g.dispose();
      }

      return img;
   }

   private static void prepare(Graphics2D g) {
      g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
   }

   private static int unit(int height) {
      return Math.max(1, height / 128);
   }

   private static int fitBoth(String text, int maxWidth, int tracking, int cap) {
      int n = Math.max(1, text.length());
      int room = (maxWidth - (n - 1) * tracking) / (n * 5);
      return Math.max(1, Math.min(room, Math.max(1, cap)));
   }

   private static String safeName(String name) {
      return name != null && !name.isBlank() ? name.trim().toUpperCase(Locale.ROOT) : "UNKNOWN";
   }

   private static String berry(double amount) {
      long whole = (long)Math.floor(Math.max(0.0, amount));
      return "$" + String.format(Locale.US, "%,d", whole) + "-";
   }

   private static void paintParchment(Graphics2D g, int w, int h, int u, long seed) {
      for (int y = 0; y < h; y++) {
         float t = (float)y / Math.max(1, h - 1);
         g.setColor(blend(PAPER_LIGHT, PAPER_DARK, t * 0.85F));
         g.fillRect(0, y, w, 1);
      }

      Random rng = new Random(seed * 31L + w * 7L + h);
      int stains = Math.max(3, w * h / 9000);

      for (int i = 0; i < stains; i++) {
         blotch(g, rng.nextInt(w), rng.nextInt(h), u * (2 + rng.nextInt(5)), STAIN, 20 + rng.nextInt(14), rng);
      }

      int fibres = w * h / 70;

      for (int i = 0; i < fibres; i++) {
         g.setColor(new Color(FIBRE.getRed(), FIBRE.getGreen(), FIBRE.getBlue(), 55 + rng.nextInt(70)));
         g.fillRect(rng.nextInt(w), rng.nextInt(h), u, u);
      }

      int scorches = Math.max(2, w * h / 45000);

      for (int i = 0; i < scorches; i++) {
         int sx = rng.nextBoolean() ? rng.nextInt(Math.max(1, w / 5)) : w - 1 - rng.nextInt(Math.max(1, w / 5));
         int sy = rng.nextInt(h);
         int r = u * (2 + rng.nextInt(3));
         blotch(g, sx, sy, r * 2, BURN, 22, rng);
         blotch(g, sx, sy, r, CHAR, 40, rng);
      }

      g.setStroke(new BasicStroke(Math.max(1, u / 2 + 1)));

      for (int i = 0; i < 2; i++) {
         int cy = h / 4 + rng.nextInt(Math.max(1, h / 2));
         g.setColor(new Color(CHAR.getRed(), CHAR.getGreen(), CHAR.getBlue(), 30));
         g.drawLine(0, cy, w, cy + rng.nextInt(3 * u) - u);
      }

      for (int i = 0; i < 4 * u; i++) {
         g.setColor(new Color(BURN.getRed(), BURN.getGreen(), BURN.getBlue(), 10));
         g.drawRect(i, i, w - 1 - i * 2, h - 1 - i * 2);
      }

      burnEdges(g, w, h, u, rng);
   }

   private static void burnEdges(Graphics2D g, int w, int h, int u, Random rng) {
      int min = u;
      int max = Math.max(min + 1, 5 * u);
      int depth = min + rng.nextInt(max - min);

      for (int x = 0; x < w; x++) {
         depth = wander(depth, min, max, u, rng);
         paintBurn(g, x, 0, depth, true, false);
      }

      depth = min + rng.nextInt(max - min);

      for (int x = 0; x < w; x++) {
         depth = wander(depth, min, max, u, rng);
         paintBurn(g, x, h - depth, depth, true, true);
      }

      depth = min + rng.nextInt(max - min);

      for (int y = 0; y < h; y++) {
         depth = wander(depth, min, max, u, rng);
         paintBurn(g, 0, y, depth, false, false);
      }

      depth = min + rng.nextInt(max - min);

      for (int y = 0; y < h; y++) {
         depth = wander(depth, min, max, u, rng);
         paintBurn(g, w - depth, y, depth, false, true);
      }

      g.setColor(CHAR);
      g.setStroke(new BasicStroke(Math.max(1, u)));
      g.drawRect(0, 0, w - 1, h - 1);
   }

   private static int wander(int depth, int min, int max, int u, Random rng) {
      int next = depth + (rng.nextInt(3) - 1) * Math.max(1, u / 2 + 1);
      return Math.max(min, Math.min(max, next));
   }

   private static void paintBurn(Graphics2D g, int x, int y, int span, boolean vertical, boolean towardsPaper) {
      for (int i = 0; i < span; i++) {
         float t = (float)i / Math.max(1, span - 1);
         float edgeness = towardsPaper ? t : 1.0F - t;
         g.setColor(edgeness > 0.66F ? CHAR : (edgeness > 0.33F ? BURN : SCORCH));
         if (vertical) {
            g.fillRect(x, y + i, 1, 1);
         } else {
            g.fillRect(x + i, y, 1, 1);
         }
      }
   }

   private static void drawOrnament(Graphics2D g, int cx, int y, int half, int u, Color color) {
      int thickness = Math.max(1, u);
      g.setColor(color);
      g.fillRect(cx - half + 4 * u, y, half * 2 - 8 * u, thickness);
      int d = Math.max(2, 2 * u);
      int mid = y + thickness / 2;
      g.fillPolygon(new Polygon(new int[]{cx, cx + d, cx, cx - d}, new int[]{mid - d, mid, mid + d, mid}, 4));
      g.setStroke(new BasicStroke(thickness));
      g.drawArc(cx - half, y - 2 * u, 4 * u, 4 * u, 200, 150);
      g.drawArc(cx + half - 4 * u, y - 2 * u, 4 * u, 4 * u, 190, 150);
   }

   private static void drawCurl(Graphics2D g, int x, int y, int size, int u, Color color, boolean mirrored) {
      g.setColor(color);
      g.setStroke(new BasicStroke(Math.max(1, u)));
      int half = Math.max(2, size / 2);
      if (mirrored) {
         g.drawArc(x, y, half, half, 90, 180);
         g.drawArc(x, y + half - 1, half, half, 180, 180);
      } else {
         g.drawArc(x, y, half, half, 270, 180);
         g.drawArc(x, y + half - 1, half, half, 0, 180);
      }
   }

   private static void drawFramedHead(Graphics2D g, BufferedImage head, int x, int y, int size, int u) {
      int border = Math.max(1, u);
      g.setColor(INK);
      g.fillRect(x - border * 3, y - border * 3, size + border * 6, size + border * 6);
      g.setColor(new Color(224, 196, 172));
      g.fillRect(x - border * 2, y - border * 2, size + border * 4, size + border * 4);
      g.setColor(INK_SOFT);
      g.fillRect(x - border, y - border, size + border * 2, size + border * 2);
      drawHead(g, head, x, y, size);
   }

   private static void drawHead(Graphics2D g, BufferedImage head, int x, int y, int size) {
      if (head == null) {
         g.setColor(new Color(58, 44, 28));
         g.fillRect(x, y, size, size);
      } else {
         g.drawImage(head, x, y, size, size, null);
      }
   }

   private static void blotch(Graphics2D g, int cx, int cy, int r, Color base, int alpha, Random rng) {
      g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
      int lobes = 3 + rng.nextInt(3);

      for (int i = 0; i < lobes; i++) {
         int ox = cx + rng.nextInt(r + 1) - r / 2;
         int oy = cy + rng.nextInt(r + 1) - r / 2;
         int rr = Math.max(1, r - rng.nextInt(Math.max(1, r / 2)));
         g.fillOval(ox - rr, oy - rr, rr * 2, rr * 2);
      }
   }

   private static Color blend(Color a, Color b, float t) {
      float clamped = Math.max(0.0F, Math.min(1.0F, t));
      return new Color(
         Math.round(a.getRed() + (b.getRed() - a.getRed()) * clamped),
         Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * clamped),
         Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * clamped)
      );
   }

   public record Row(String name, double amount, BufferedImage head) {
   }
}
