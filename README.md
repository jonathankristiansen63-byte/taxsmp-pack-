# TaxSMP Resource Pack

The server resource pack for TaxSMP (Java Edition 1.20 – 26.x).

**Download link** (what Velocity sends to players):

```
https://raw.githubusercontent.com/jonathankristiansen63-byte/taxsmp-pack-/main/dist/TaxSMP-Pack.zip
```

## What's in it

| Feature | How it works |
|---|---|
| TaxSMP logo at the top of the **tab list** | Font character `` draws the logo. Put it in the TAB header (see below). |
| **Pause menu** (Esc): logo right above "Back to Game", yellow "ᴄᴏᴍᴇ ʙᴀᴄᴋ ꜱᴏᴏɴ...." Disconnect button, banner under it | Drawn with font characters inside the button labels. Made by `tools/pause_menu.py`, see below. Preview: `preview/pause-menu.png` |
| Pack icon | `pack/pack.png` |

## Folder layout

```
pack/                     <- the actual resource pack (edit files here)
  pack.mcmeta
  pack.png                <- icon shown in the pack list
  assets/minecraft/font/default.json   <- logo characters (size + position)
  assets/minecraft/lang/en_us.json     <- text overrides (copied to all languages on build)
  assets/taxsmp/textures/font/logo.png <- the logo image (max 256x256)
build.py                  <- builds dist/TaxSMP-Pack.zip
dist/                     <- built zip + its SHA-1 (don't edit by hand)
src/                      <- full-size original artwork
server-plugin/            <- TaxSMP backend plugin source (shop, auction, orders, economy)
dist/TaxSMP.jar            <- built backend plugin for Paper / Folia 1.21.11
```

## Shop and orders menus

The backend plugin uses the `taxsmp:gui` font for gray menu panels, inset item slots,
button strips and centered title plates. The shop quantity controls use red minus
and green plus icons. Orders use matching navigation, search, sort, filter, refresh,
confirm and cancel icons; the existing new-order slot uses a green plus.
Inventory sizes, item positions and click actions are preserved. Bedrock keeps
the normal chest window; custom item icons and title artwork require the Java pack.

Build the backend with `mvn -f server-plugin/pom.xml package` (JDK 21 or newer).
Install `dist/TaxSMP.jar` in the backend server's `plugins/` directory and restart.
Keep the existing `plugins/TaxSMP/config.yml`; the public default config contains
example database settings, with MySQL disabled until configured.

To regenerate GUI assets, run `python tools/gui.py "path/to/client.jar"` with a
Minecraft client jar containing the vanilla `generic_54.png` texture, then `python build.py`.
The generated green plus and red minus sheet is stored in `src/gui-quantity-buttons.png`.
Its icons are exported at 32x32 using nearest-neighbor sizing to preserve pixel edges.

## Velocity plugin (TaxSMP PackLoader)

`velocity-plugin/TaxSMP-PackLoader.jar` sends this pack to players **once**, when they join.
Switching between NA and EU doesn't send it again, so there's no reload.

- Put the jar in `plugins/` on **both** proxies and restart them. Needs Velocity 4.2+ (Java 25).
- Players stay on the joining screen until the pack has loaded, so they can't play without it.
  If they decline, or don't answer within 2 minutes, they get kicked (`accept-timeout-seconds`, `kick-message`).
  Bedrock players are skipped if Floodgate is installed.
- It reads the newest pack from this repo by itself, checking every 10 minutes.
  Run `/taxpack reload` to grab a new version right away. `/taxpack` shows the version in use.
  Both commands need the `taxsmp.packloader.admin` permission.
- Settings: `plugins/taxsmp-packloader/config.yml`.
- Source code: `velocity-plugin/`. Build it with `mvn package` (JDK 25).

The NA and EU servers must **not** send a pack themselves: leave `resource-pack=` empty in their `server.properties`.

## Making changes

1. Edit or add files in `pack/`.
2. Push to `main`. GitHub Actions runs `build.py` and commits the new `dist/TaxSMP-Pack.zip` for you.
   (Or run `python build.py` yourself and commit `dist/` too.)
3. Within 10 minutes both proxies pick up the new version for new joins (or run `/taxpack reload`).

Rules the build checks for you:
- Each font image must be **256x256 or smaller**.
- In `default.json`, `ascent` can't be bigger than `height`.

## Tab list (TAB plugin)

The logo is 56 px tall, so it needs **6 empty lines** under it or the player list covers it.
Keep the `&f` in front of the character; any other color tints the logo.
The character has to be in **double quotes** so YAML turns `` into the real character.

```yaml
header-footer:
  enabled: true
  designs:
    default:
      header:
      - "&f"
      - ''
      - ''
      - ''
      - ''
      - ''
      - ''
```

To make the logo bigger or smaller, change `height` for `` in
`pack/assets/minecraft/font/default.json` and add or remove empty lines (one line = 9 px).

## Pause menu

A server pack can't add things to the pause menu, so the pictures are font characters inside
the button labels (`menu.returnToGame` and `menu.disconnect` in the lang file). Space characters
move them to exact spots relative to their button, so they stay in place on any screen size.
The "Game Menu" title is hidden.

Don't edit those lang lines or `font/default.json` by hand. Change the settings at the top of
`tools/pause_menu.py` (sizes, colors, button text) or the artwork in `src/`, then run it.
It needs a Minecraft 26.3 client jar to measure the text exactly:

```
python tools/pause_menu.py "path/to/26.3.jar"
python build.py
```

It also draws `preview/pause-menu.png` so you can check the result before pushing.
