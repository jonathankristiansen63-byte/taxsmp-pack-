package com.taxsmp.ui;

/** Centered market grid, with padding above and a separate controls row. */
public final class MarketLayout {
   public static final int SIZE = 54;
   public static final int CAPACITY = 21;
   public static final int SEARCH = 46, SORT = 47, PREV = 48, REFRESH = 49,
      NEXT = 50, FILTER = 51, MINE = 52;

   private MarketLayout() {}

   public static int slot(int index) {
      if (index < 0 || index >= CAPACITY) throw new IllegalArgumentException("Grid index: " + index);
      return 10 + index / 7 * 9 + index % 7;
   }

   /** Returns -1 for the padding, toolbar, player inventory and outside clicks. */
   public static int index(int slot) {
      int row = slot / 9, column = slot % 9;
      return slot >= 0 && row >= 1 && row <= 3 && column >= 1 && column <= 7
         ? (row - 1) * 7 + column - 1 : -1;
   }

   public static int pages(int count) {
      return Math.max(1, (count + CAPACITY - 1) / CAPACITY);
   }

   public static String title(String name, int page, int pages) {
      return name + " [" + page + "/" + pages + "]";
   }
}
