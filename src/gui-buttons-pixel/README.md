# Approved menu buttons

All thirteen PNG files are 14x16 RGBA sprites copied
from the user's approved reference. Each has six opaque colors plus transparent
cut corners. Preserve the black outline and the dark colored shadow under the
white symbol. Search, sort, back, refresh, next, previous, mine and filter use the
second approved reference set. The surrounding interface background is not part of the image.

`tools/gui.py` adds one transparent pixel on either side and enlarges them by
exactly 2x with nearest-neighbor interpolation for Minecraft's 32x32 textures.
Java and Bedrock use identical exported pixels. Do not use smooth resizing.
