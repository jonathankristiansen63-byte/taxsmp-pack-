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
| TaxSMP logo on the **pause menu** (Esc) | The "Game Menu" title is replaced with character ``, for every language. |
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
```

## Velocity plugin (TaxSMP PackLoader)

`velocity-plugin/TaxSMP-PackLoader.jar` sends this pack to players **once**, when they join.
Switching between NA and EU doesn't send it again, so there's no reload.

- Put the jar in `plugins/` on **both** proxies and restart them. Needs Velocity 4.2+ (Java 25).
- Players must accept the pack (forced). Bedrock players are skipped if Floodgate is installed.
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

The `` logo sits above the pause title, which Minecraft draws 40 px from the top.
`ascent: 39` moves it up so it fits above the "Back to Game" button on small screens.
To move it down, lower `ascent` (it must stay at or below `height`).
