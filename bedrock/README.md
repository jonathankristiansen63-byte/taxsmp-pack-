# Bedrock pack and moderation effects

This pack contains the same 13 button textures as the Java pack: search, sort,
filter, refresh, your items/orders, previous, next, back, confirm, cancel, plus and
minus, and the green information/transactions button. It keeps the server's existing inventory slots and Bedrock's native chest
UI. It also contains five RTP artwork textures for Overworld, Nether, End,
EU Central and NA East. End's region menu offers EU Central only. It also has the
four chicken tool icons (Sell Axe, Tree Chopper, Drill, Shovel), shown without animation.
The scoreboard artwork and Java title backgrounds are not part of this pack.
Version 1.1.0 also includes native Bedrock models for the ban mace, red/black panes,
shaking floor pieces, mouth chains and padlock, with ban audio. BANNED and MUTED
titles use Bedrock's native title display.

## Install on each Velocity proxy running Geyser

1. Use Geyser with v2 custom item mappings support (API 2.9.3+, build 1062+).
2. Copy `dist/TaxSMP-Bedrock-Buttons.mcpack` into `plugins/Geyser-Velocity/packs/`.
3. Copy `dist/taxsmp-buttons-geyser.json` into `plugins/Geyser-Velocity/custom_mappings/`.
4. Ensure `gameplay.enable-custom-content: true` in Geyser's `config.yml`.
5. Restart the proxy, then reconnect with Bedrock and accept the pack.

For moderation animations, install the matching `TaxSMP-BedrockFX.jar` in **both**
`plugins/` and `plugins/Geyser-Velocity/extensions/` on each proxy. The backend
requires `minecraft-plugin-1.0.12-bedrock-banfx.jar`. The bridge mirrors the actual
Java cinematic poses to native Bedrock custom entities. The pack alone cannot
translate Java display entities. This combination was tested with Geyser
2.11.3-b1245 and a Bedrock 26.51 protocol client.

Keep `force-resource-packs: true` so clients load the matching models. The console
command `bedrockfx` reports bridge/viewer status. Reconnect after updating.

Do this on both the NA and EU Geyser proxies. The mappings only match items whose
`minecraft:item_model` component was set to a TaxSMP button model by the plugin;
regular arrows, glass panes, chests and other items keep their normal appearances.

This is separate from the Java Velocity PackLoader. Bedrock resource packs are
sent by Geyser. No behavior pack or scoreboard font pack is required.

Rebuild with `python tools/bedrock_buttons.py` after changing the Java button textures.
Bump `VERSION` in that script when changing a published Bedrock pack, keeping its
UUIDs stable so clients can identify updates.

References: [Geyser custom items](https://geysermc.org/wiki/geyser/custom-items/),
[Geyser resource packs](https://geysermc.org/wiki/geyser/packs/).
