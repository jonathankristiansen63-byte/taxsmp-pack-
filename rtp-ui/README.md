# FoliaRTP artwork menus

Install `dist/FoliaRTP.jar` on the backend in place of the original FoliaRTP jar,
and load the matching Java resource pack. Keep just one FoliaRTP jar in `plugins/`.
Keep your existing private `plugins/FoliaRTP/config.yml` and routing settings.
The distributed jar's default Redis settings are disabled examples.

Overworld and Nether open a map with EU Central and NA East. End opens the same
map with only EU Central; selecting it calls the original `/rtp end` command.
Its card and hover name say only End.
Region selectors have no back button or toolbar; press Esc to close them.
Selections call the existing executor, retaining permissions, combat checks,
cooldowns, countdowns, NA availability and cross-server routing. The EU/NA names
in the artwork do not change the configured destination servers.

Bedrock players receive native chest menus with the same five artwork item icons
and working click actions. Install the Bedrock pack and Geyser mappings described
in `bedrock/README.md` on both proxies. Java's full poster/map backgrounds use
font glyphs; Bedrock uses item icons. Set `rtp.artwork-menu: false` to use the native
menus for all clients when diagnosing a resource-pack issue.

Build from the original local jar with JDK 21+ and the backend's dependency classpath:

```
python rtp-ui/build.py ORIGINAL.jar --classpath-file CLASSPATH.txt --asm-jar ASM.jar --asm-tree-jar ASM-TREE.jar
```

Only three GUI entry points, the listener registration and the arrival listener are
patched. The arrival listener (src/dev/foliartp/listeners) spawns cross-server RTP
arrivals straight at their location through AsyncPlayerSpawnLocationEvent, because
Paper no longer fires the old PlayerSpawnLocationEvent; without it every cross-server
RTP loaded the old spot first and then teleported. TeleportHandler and the new
rtp/RtpEffects add the RTP launch: when the countdown ends the player's view is turned
straight up and they are shot about 5-6 blocks up with a riptide spin (everyone sees it;
the spin follows the player's pitch, so looking up keeps it vertical), then they are
teleported straight onto their spot. A cross-server RTP still shows the client's loading
screen, because switching servers makes the client re-join. The build asserts that the
queue and Redis classes remain byte-identical.
The original local jar and its private defaults are not published.

Artwork sources are in `src/rtp/`; generated previews are in `preview/rtp-*.png`.
Run `python tools/gui.py CLIENT.jar` to regenerate the fitted textures and font.
The artwork was generated with the built-in image tool; prompts are recorded in
`src/rtp/PROMPTS.md`.
