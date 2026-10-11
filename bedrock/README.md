# Bedrock GUI buttons

This pack contains the same 12 button textures as the Java pack: search, sort,
filter, refresh, your items/orders, previous, next, back, confirm, cancel, plus and
minus. It keeps the server's existing inventory slots and Bedrock's native chest
UI. The scoreboard artwork and Java title backgrounds are not part of this pack.

## Install on each Velocity proxy running Geyser

1. Use Geyser with v2 custom item mappings support (API 2.9.3+, build 1062+).
2. Copy `dist/TaxSMP-Bedrock-Buttons.mcpack` into `plugins/Geyser-Velocity/packs/`.
3. Copy `dist/taxsmp-buttons-geyser.json` into `plugins/Geyser-Velocity/custom_mappings/`.
4. Ensure `gameplay.enable-custom-content: true` in Geyser's `config.yml`.
5. Restart the proxy, then reconnect with Bedrock and accept the pack.

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
