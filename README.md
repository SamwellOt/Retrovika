# Retrovika

An *all-in-one* emulation frontend for Android: many consoles in a single app, with an organized library,
per-console virtual controls, save states, an online catalog of free games and a custom UI.

- **Language/UI:** Kotlin + Jetpack Compose (Material 3 with a custom "synthwave sunset" theme)
- **Emulation engine:** [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) (the same one Lemuroid uses) + libretro cores
- **Minimum Android:** 8.0 (API 26) · target API 36 · ABIs arm64-v8a, armeabi-v7a, x86_64
- **Languages:** English and Portuguese

## Download

Grab the latest APK from the [Releases](https://github.com/SamwellOt/Retrovika/releases) page and install it on your
device (allow installs from unknown sources). Emulator cores are downloaded on demand the first time you open a game
for each console.

## Supported consoles

| Manufacturer | Systems (default core → alternatives) |
|---|---|
| Nintendo | NES (FCEUmm, Nestopia, Mesen) · SNES (Snes9x, Snes9x 2010, bsnes) · N64 (Mupen64Plus-Next, ParaLLEl) · GB/GBC (Gambatte, SameBoy, mGBA) · GBA (mGBA, gpSP, VBA Next) · DS (melonDS DS, DeSmuME) · Virtual Boy · Pokémon Mini · Game & Watch · 3DS, GameCube and Wii *(experimental)* |
| Sony | PS1 (PCSX ReARMed, SwanStation) · PSP (PPSSPP) · PS2 *(experimental: Play!, LRPS2)* |
| Sega | SG-1000, Master System, Game Gear, Mega Drive, Mega CD (Genesis Plus GX) · 32X (PicoDrive) · Saturn (YabaSanshiro) · Dreamcast (Flycast) |
| Atari | 2600 (Stella) · 5200 (a5200) · 400/800/XL/XE (Atari800) · 7800 (ProSystem) · Lynx (Handy) · Jaguar (Virtual Jaguar) |
| SNK / NEC / 3DO | Arcade/Neo Geo (FinalBurn Neo, MAME 2003-Plus) · Neo Geo CD (NeoCD) · Neo Geo Pocket · PC Engine/TG-16 and CD · PC-FX · 3DO (Opera) |
| Classics | ColecoVision · Intellivision (FreeIntv) · Odyssey²/Videopac (O2EM) · Channel F (FreeChaF) · Vectrex (vecx) |
| Handhelds | WonderSwan · Watara Supervision · Mega Duck · Arduboy |
| Computers | MSX/MSX2 (blueMSX with the free C-BIOS, fMSX) · Commodore 64 (VICE) · Amiga (PUAE) · ZX Spectrum (Fuse) · Amstrad CPC (Caprice32) · MS-DOS (DOSBox Pure) |
| Fantasy | TIC-80 · PICO-8 (Retro8) |

Each system defines (in `core/systems/Systems.kt`): file extensions, cores, mobile-tuned options,
**Performance / Balanced / Quality** presets, BIOS files with MD5 hashes, orientation and the virtual pad layout.

## Features

- **Library**: link folders through the Storage Access Framework (subfolders such as `snes/`, `psx/`, `ps1/`, `megadrive/`… are recognized),
  or import files into the internal `roms/<console>/` structure. Automatic box art from libretro-thumbnails.
  Supports `.cue/.bin`, `.gdi`, `.m3u` (multi-disc) and `.chd`.
- **Explore**: searchable online catalog with download and "Play" straight from the app. It currently uses
  [Homebrew Hub](https://hh.gbdev.io) (~1,600 independent GB/GBC/GBA/NES games).
  New sources are added by implementing `CatalogSource`.
- **Downloads**: queue with progress/cancel, `.zip` extraction and "download from my link".
- **Emulation**: cores downloaded automatically on first run · save states (4 slots + autosave) with thumbnails ·
  RetroArch-compatible SRAM (`saves/<console>/<rom>.srm`) · resume where you left off · fast forward ·
  filters (CRT/LCD/smooth) · live core options · disc swapping · rumble.
- **Controls**: a dedicated layout per console (8-way D-pad, analog sticks, N64 C-buttons, 6-button Saturn/arcade…),
  multi-touch, auto-scaling in portrait/landscape, adjustable opacity/size, vibration;
  Bluetooth/USB gamepads are detected automatically (the on-screen pad hides itself).
- **BIOS**: bulk import, identification by name or MD5, per-console status check
  (including groups where any one of several BIOS files is enough, as on 3DO and Neo Geo CD).
- **Identification**: matches the file hash against No-Intro DATs and shows the canonical name and other versions.
- **Linked folders**: games removed from the library are hidden (the file is not deleted) and can be shown again in Settings.

## Building

```bash
# Requires JDK 17+ and the Android SDK (platform 36). Point to the SDK in local.properties (sdk.dir=...).
./gradlew assembleDebug                       # lightweight APK (~3 MB in release); cores downloaded on demand
./gradlew assembleRelease -PbundleCores=all   # bundles every core into the APK (works offline)
./gradlew assembleRelease -PbundleCores=snes9x,mgba,pcsx_rearmed -PbundleAbis=arm64-v8a
./gradlew testDebugUnitTest                   # unit tests (JVM, no device needed)
```

**Release signing:** copy `keystore.properties.example` to `keystore.properties` and fill it in with your
key (or set `RETROVIKA_KEYSTORE`, `RETROVIKA_KEYSTORE_PASSWORD`, `RETROVIKA_KEY_ALIAS` and `RETROVIKA_KEY_PASSWORD`).
Without it, the release is signed with the debug key and the build prints a warning.

## Architecture

```
app/src/main/java/com/retrovika/app/
├── RetrovikaApp.kt        dependency container + Coil
├── core/
│   ├── systems/            console catalog, cores, presets and BIOS
│   ├── cores/              CoreManager: downloads/installs the .so files (libretro buildbot) or uses bundled ones
│   ├── library/            Room (Game), SAF/internal scanning, No-Intro naming, box art
│   ├── catalog/            CatalogSource, Homebrew Hub, DownloadManager
│   ├── bios/               BIOS verification and import
│   ├── settings/           DataStore (preferences, core/preset per console, core options)
│   └── storage/, net/      folders, zip, HTTP
├── emulation/
│   ├── GameActivity.kt     emulator lifecycle, saves, physical input, menu
│   ├── GameScreen.kt       HUD, pause menu (states, video, core)
│   └── input/              per-console PadLayout + Compose VirtualGamepad
└── ui/                     theme, components and screens (Home, Library, Console, Explore, Downloads, Settings)
```

## Visual credits

The fonts in `app/src/main/res/font` are licensed under the SIL Open Font License 1.1: Space Grotesk (Florian Karsten), JetBrains Mono (JetBrains) and Press Start 2P (CodeMan38).

## Roadmap

- Controller layout editor (drag buttons) and physical gamepad remapping
- Rich metadata (ScreenScraper/IGDB) and more free sources in Explore
- RetroAchievements, cheats, cloud save sync
- Large downloads via WorkManager with a foreground notification
