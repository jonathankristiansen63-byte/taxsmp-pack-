package com.taxsmp.ui;

import com.taxsmp.ScoreboardManager;
import com.taxsmp.TaxSMP;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The TaxSMP look for the shop, auction house and orders menus (Java players only).
 *
 * The background is drawn inside the window title with the taxsmp:gui font from the TaxSMP
 * resource pack (tools/gui.py): a panel for the rows, a slot square under every item, a dark bar
 * under a row of buttons, and a title plate sized to the title. Bedrock players keep the normal
 * look. Click handlers read InventoryView#getOriginalTitle, which this doesn't change.
 */
public final class GuiSkin implements Listener {
   private static final Key FONT = Key.key("taxsmp", "gui");
   private static final int PANEL = 0xE100;
   private static final int SLOT = 0xE110;
   private static final int BAR = 0xE120;
   private static final char PLATE_LEFT = (char)0xE130;
   private static final char PLATE_MID16 = (char)0xE131;
   private static final char PLATE_MID1 = (char)0xE132;
   private static final char PLATE_RIGHT = (char)0xE133;
   // Every font picture moves the pen by its width + 1.
   private static final int PANEL_ADVANCE = 177;
   private static final int SLOT_ADVANCE = 19;
   private static final int BAR_ADVANCE = 163;
   private static final int TITLE_X = 8;
   private static final int WINDOW_CENTER = 88;
   private static final Map<Integer, Integer> WIDTHS = loadWidths();
   private final TaxSMP plugin;

   public GuiSkin(TaxSMP plugin) {
      this.plugin = plugin;
   }

   /** Gives a menu button its TaxSMP icon (resource pack item model b/<name>); Bedrock still sees the item itself. */
   public static ItemStack button(ItemStack item, String name) {
      if (item != null) {
         ItemMeta meta = item.getItemMeta();
         if (meta != null) {
            meta.setItemModel(new NamespacedKey("taxsmp", "b/" + name));
            item.setItemMeta(meta);
         }
      }

      return item;
   }

   // Button names (small caps, as the menus write them) -> icon.
   private static final String[][] BUTTON_NAMES = {
      {"ᴘʀᴇᴠɪᴏᴜꜱ", "prev"}, {"ɴᴇxᴛ ᴘᴀɢᴇ", "next"}, {"ʀᴇғʀᴇꜱʜ", "refresh"}, {"ʀᴇꜰʀᴇꜱʜ", "refresh"},
      {"ꜱᴇᴀʀᴄʜ", "search"}, {"ʏᴏᴜʀ ɪᴛᴇᴍꜱ", "mine"}, {"ᴄᴏɴғɪʀᴍ", "yes"}, {"ᴄᴏɴꜰɪʀᴍ", "yes"},
      {"ᴄᴀɴᴄᴇʟ", "no"}, {"ʙᴀᴄᴋ", "back"}, {"ꜱᴏʀᴛ", "sort"}, {"ғɪʟᴛᴇʀ", "filter"},
      {"ʏᴏᴜʀ ᴏʀᴅᴇʀꜱ", "mine"}, {"ɴᴇᴡ ᴏʀᴅᴇʀ", "plus"}
   };

   /** Gives an item its button icon when its name is one of the menu buttons (back, next page, ...). */
   public static ItemStack autoButton(ItemStack item) {
      if (item != null && item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
         String name = ChatColor.stripColor(item.getItemMeta().getDisplayName()).trim();
         if (name.matches("\\+[0-9]+")) {
            return button(item, "plus");
         }
         if (name.matches("-[0-9]+")) {
            return button(item, "minus");
         }

         for (String[] button : BUTTON_NAMES) {
            if (name.startsWith(button[0])) {
               return button(item, button[1]);
            }
         }
      }

      return item;
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onOpen(InventoryOpenEvent event) {
      if (event.getPlayer() instanceof Player player && this.plugin.getConfig().getBoolean("menus.skin", true)) {
         ScoreboardManager scoreboard = this.plugin.getScoreboardManager();
         Inventory top = event.getInventory();
         String title = event.getView().getOriginalTitle();
         String plain = ChatColor.stripColor(title).trim();
         if ((scoreboard == null || !scoreboard.isBedrockPlayer(player))
            && top.getType() == InventoryType.CHEST
            && top.getSize() % 9 == 0
            && isSkinned(plain)) {
            event.titleOverride(build(top, title, fullGrid(plain), plain.startsWith("ʙᴜʏɪɴɢ")));
         }
      }
   }

   private static boolean isSkinned(String plain) {
      return plain.startsWith("ꜱʜᴏᴘ")
         || plain.startsWith("ɢᴇᴍ ꜱʜᴏᴘ")
         || plain.startsWith("ʙᴜʏɪɴɢ")
         || plain.startsWith("Auction")
         || plain.startsWith("Orders");
   }

   /** Listing pages fill in after they open, so every slot gets a square there. */
   private static boolean fullGrid(String plain) {
      return plain.startsWith("Auction (") || plain.startsWith("Orders (") || plain.startsWith("Auction -> Transactions")
         || plain.equals("Orders -> Your Orders") || plain.equals("Orders -> Select An Item");
   }

   private static Component build(Inventory top, String title, boolean fullGrid, boolean buying) {
      int rows = top.getSize() / 9;
      StringBuilder glyphs = new StringBuilder();
      int pen = move(glyphs, TITLE_X, 0);
      glyphs.append((char)(PANEL + rows));
      pen += PANEL_ADVANCE;
      boolean[] bars = new boolean[rows];
      bars[rows - 1] = rows > 1 && (fullGrid || hasButton(top, (rows - 1) * 9, rows * 9));
      // Quantity controls sit on their own gray strip, with an inset item preview over it.
      if (buying && rows > 1) {
         bars[1] = hasButton(top, 9, 18);
      }
      for (int row = 0; row < rows; row++) {
         if (bars[row]) {
            pen = move(glyphs, pen, 7);
            glyphs.append((char)(BAR + row));
            pen += BAR_ADVANCE;
         }
      }

      for (int slot = 0; slot < top.getSize(); slot++) {
         int row = slot / 9;
         ItemStack item = top.getItem(slot);
         boolean filled = item != null && !item.getType().isAir();
         if (!isButton(item) && (filled || fullGrid && row < rows - 1)) {
            pen = move(glyphs, pen, 7 + slot % 9 * 18);
            glyphs.append((char)(SLOT + row));
            pen += SLOT_ADVANCE;
         }
      }

      // Title plate: caps of 4 px and a middle filled in 16 px / 1 px pieces.
      int textWidth = width(title);
      int plateWidth = Math.min(168, textWidth + 14);
      int plateX = WINDOW_CENTER - plateWidth / 2;
      int plateEnd = plateX + plateWidth - 4;
      pen = move(glyphs, pen, plateX);
      glyphs.append(PLATE_LEFT);
      pen += 5;

      for (int x = plateX + 4; x < plateEnd; ) {
         int piece = plateEnd - x >= 16 ? 16 : 1;
         pen = move(glyphs, pen, x);
         glyphs.append(piece == 16 ? PLATE_MID16 : PLATE_MID1);
         pen += piece + 1;
         x += piece;
      }

      pen = move(glyphs, pen, plateEnd);
      glyphs.append(PLATE_RIGHT);
      pen += 5;
      move(glyphs, pen, WINDOW_CENTER - textWidth / 2);
      // Siblings, so the title text keeps the normal font. White keeps the pictures' own colors.
      return Component.empty()
         .append(Component.text(glyphs.toString(), NamedTextColor.WHITE).font(FONT))
         .append(LegacyComponentSerializer.legacySection().deserialize(title));
   }

   private static boolean isButton(ItemStack item) {
      if (item != null && item.hasItemMeta()) {
         ItemMeta meta = item.getItemMeta();
         return meta.hasItemModel() && "taxsmp".equals(meta.getItemModel().getNamespace());
      } else {
         return false;
      }
   }

   private static boolean hasButton(Inventory top, int from, int to) {
      for (int slot = from; slot < to; slot++) {
         if (isButton(top.getItem(slot))) {
            return true;
         }
      }

      return false;
   }

   /** Appends taxsmp:gui space characters that move the pen from {@code pen} to {@code target}. */
   private static int move(StringBuilder glyphs, int pen, int target) {
      int px = target - pen;
      int base = px < 0 ? 0xE140 : 0xE150;
      int n = Math.abs(px);

      for (int bit = 8; bit >= 0; bit--) {
         if ((n & 1 << bit) != 0) {
            glyphs.append((char)(base + bit));
         }
      }

      return target;
   }

   /** Width of a legacy (§) text in px, using Minecraft's own font widths. */
   static int width(String legacy) {
      int width = 0;
      boolean bold = false;

      for (int i = 0; i < legacy.length(); i++) {
         char c = legacy.charAt(i);
         if (c == 167 && i + 1 < legacy.length()) {
            char code = Character.toLowerCase(legacy.charAt(++i));
            if (code == 'l') {
               bold = true;
            } else if (code == 'r' || code == 'x' || code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
               bold = false;
            }
         } else {
            width += WIDTHS.getOrDefault((int)c, 6) + (bold ? 1 : 0);
         }
      }

      return width;
   }

   private static Map<Integer, Integer> loadWidths() {
      Map<Integer, Integer> widths = new HashMap<>();

      try (InputStream in = GuiSkin.class.getResourceAsStream("/gui-widths.txt")) {
         if (in != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));

            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
               String[] parts = line.trim().split(" ");
               if (parts.length == 2 && !line.startsWith("#")) {
                  widths.put(Integer.parseInt(parts[0], 16), Integer.parseInt(parts[1]));
               }
            }
         }
      } catch (Exception ignored) {
      }

      return widths;
   }
}
