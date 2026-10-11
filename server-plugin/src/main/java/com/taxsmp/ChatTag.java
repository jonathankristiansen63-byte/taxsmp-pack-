package com.taxsmp;

public enum ChatTag {
   MACE("MACE", "\ud83d\udd28", "§7", 10),
   CPVP("CPVP", "\ud83c\udfae", "§b", 11),
   SWORD("SWORD", "\ud83d\udde1", "§3", 12),
   COOL("COOL", "\ud83d\ude0e", "§9", 13),
   HT1("HT1", "⚔", "§6", 14),
   RICH("RICH", "\ud83d\udcb8", "§a", 15),
   DANGEROUS("DANGEROUS", "⚠", "§c", 16),
   CHILL("CHILL", "\ud83e\udd19", "§b", 19),
   KING("KING", "\ud83d\udc51", "§6", 20),
   LT5("LT5", "⚡", "§e", 21),
   RIZZ("RIZZ", "\ud83d\udc8b", "§d", 22),
   SKIBIDI("SKIBIDI", "\ud83d\udebd", "§2", 23),
   AFK("AFK", "⛔", "§8", 24),
   SUS("SUS", "❓", "§c", 25),
   BALRIGHT("BALRIGHT", "\ud83d\udc4d", "§a", 28),
   SIXTYSEVEN("67", "\ud83e\udd37", "§5", 29),
   SPECIAL("SPECIAL", "\ud83d\udccc", "§d", 30),
   CUTE("CUTE", "\ud83c\udf80", "§d", 31),
   PREPPY("PREPPY", "\ud83c\udf37", "§d", 32),
   MEOW("MEOW", "\ud83d\udc3e", "§6", 33),
   GAMBLER("GAMBLER", "\ud83c\udfb0", "§e", 34),
   BUM("BUM", "\ud83d\uddd1", "§8", 37);

   public static final int COST = 250;
   private final String id;
   private final String emoji;
   private final String color;
   private final int slot;

   ChatTag(String id, String emoji, String color, int slot) {
      this.id = id;
      this.emoji = emoji;
      this.color = color;
      this.slot = slot;
   }

   public String id() {
      return this.id;
   }

   public int slot() {
      return this.slot;
   }

   public String label() {
      return this.id;
   }

   public String display() {
      return this.color + "[" + this.color + this.emoji + this.color + "§l" + this.id + "§r" + this.color + "]";
   }

   public static ChatTag fromId(String id) {
      if (id == null) {
         return null;
      }

      for (ChatTag t : values()) {
         if (t.id.equalsIgnoreCase(id)) {
            return t;
         }
      }

      return null;
   }

   public static ChatTag fromSlot(int slot) {
      for (ChatTag t : values()) {
         if (t.slot == slot) {
            return t;
         }
      }

      return null;
   }
}
