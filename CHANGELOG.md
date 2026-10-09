# Changelog

## 0.1.0-alpha — Initial release

- Generate LODs for `Distant Horizons`, `Voxy`, and `Voxy Server Side` without saving chunks to avoid massive world sizes.
- Optionally save chunks within a separate radius for chunk pregeneration (to avoid duplicate work).
- Set radius and choose `origin` or `x/z` coordinates as the center.
- Start, pause, resume, stop, and check generation tasks with `/lodgen` commands. (singleplayer and OPs only)
- Save progress and resume unfinished tasks after world or server restarts.
- Adjust CPU usage. (Minimum, Low, Medium, High, Maximum)
- Show overlay in action bar (singleplayer and OPs only)
- Optionally skip cave and underground for chunks that are not saved.
- Support singleplayer and dedicated servers.
- Support Fabric and NeoForge on Minecraft 1.21.1, 26.1.2, 26.2, and 26.3.
