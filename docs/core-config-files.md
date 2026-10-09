# Config files read by the cores

What each core reads from disk besides its libretro options, found by tracing the app process on a rooted device
(`strace -f -p <every tid> -e trace=openat,newfstatat,faccessat -o out.txt`, attached right after `am start`; the
host sees the container's processes, `docker top` gives the pid). The REA decompiler (`ghidra` provider) was used
on the cores for the "why". Checked on 2026-10-09 with the x86_64 builds from the buildbot.

## What works

**Dolphin (GameCube/Wii)** — user folder is `saves/<system>/User/` (GC confirmed; Wii uses the same rule, untested).
- `User/GameSettings/<ID>.ini` is read at game start, together with the variants `G.ini`, `<ID[0:3]>.ini`, `<ID>r<rev>.ini`.
  Settings that are not libretro core options (memory card type `[Core] SlotA`, video hacks, CPU) work from there.
  Proof: `SlotA = 1` made the core open `User/GC/MemoryCardA.EUR.raw` instead of the `Card A/` folder.
- Retrovika writes it from the Core tab ("Game config file", `core/gameconfig/GameIniFiles`). The first line is
  `; Retrovika game profile`; a file without that mark (put there by the user) is never touched.
- The disc ID comes from the image header (`core/gameconfig/DiscId`: ISO/GCM/NKit at 0, WBFS at 0x200; RVZ/GCZ/WIA/CISO give none).
- Also read, all optional: `User/Config/{Dolphin,GFX,Logger,GBA,FreeLook,DSUClient,GCKeyNew,WiimoteNew,RetroAchievements}.ini`,
  `GCPadNew.ini` (read and rewritten by the core).

## What does not work

**PPSSPP** — `retro_init` calls `Config::Load` before the directories are set, so it opens `/ppsspp.ini` and `/controls.ini`
at the filesystem root; the per-game file is looked up as `/<ID>_ppsspp.ini` (also root). Nothing can be placed there
on a non-rooted phone, so settings that are not core options (`ppsspp.ini`/`<ID>_ppsspp.ini`, which accept the whole
config table) are unreachable. The core reads its assets from `system/PPSSPP/` and `saves/psp/SYSTEM/compat.ini` (optional).

**RetroAchievements through the cores** — PPSSPP has the whole rcheevos client but `SetGame`, `FrameUpdate` and `Idle` have no
direct callers (REA, reduced analysis: indirect calls may be missing). Dolphin reads `User/Config/RetroAchievements.ini`
(`[Achievements] Enabled/Username/ApiToken`), but with `Enabled = True` and a token it opened no connection in 70 s on
a device with working network. Both look compiled out of the libretro builds; the route stays rcheevos in `libretrodroid`.

## Per-game core options

`CoreOptionLayers` fixes the order: core defaults, quality preset, device options, the user's choices for the core, the
choices for this game (`game_opts_<gameId>_<coreId>` in DataStore), then `core.fixed`. The Core tab has "All games" /
"This game only"; changing an option under "All games" removes the game's own value for that key.
