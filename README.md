# Retrovika

Central de emulação *all-in-one* para Android: vários consoles em um só app, com biblioteca organizada,
controles virtuais específicos para cada console, save states, catálogo online de jogos livres e UI própria.

- **Linguagem/UI:** Kotlin + Jetpack Compose (Material 3 com tema próprio "Cartucho")
- **Motor de emulação:** [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) (o mesmo do Lemuroid) + núcleos libretro
- **Android mínimo:** 8.0 (API 26) · alvo API 36 · ABIs arm64-v8a, armeabi-v7a, x86_64

## Consoles suportados

| Fabricante | Sistemas (núcleo padrão → alternativas) |
|---|---|
| Nintendo | NES (FCEUmm, Nestopia, Mesen) · SNES (Snes9x, Snes9x 2010, bsnes) · N64 (Mupen64Plus-Next, ParaLLEl) · GB/GBC (Gambatte, SameBoy, mGBA) · GBA (mGBA, gpSP, VBA Next) · DS (melonDS DS, DeSmuME) · Virtual Boy · Pokémon Mini · Game & Watch · 3DS, GameCube e Wii *(experimental)* |
| Sony | PS1 (PCSX ReARMed, SwanStation) · PSP (PPSSPP) · PS2 *(experimental: Play!, LRPS2)* |
| Sega | SG-1000, Master System, Game Gear, Mega Drive, Mega CD (Genesis Plus GX) · 32X (PicoDrive) · Saturn (YabaSanshiro) · Dreamcast (Flycast) |
| Atari | 2600 (Stella) · 5200 (a5200) · 400/800/XL/XE (Atari800) · 7800 (ProSystem) · Lynx (Handy) · Jaguar (Virtual Jaguar) |
| SNK / NEC / 3DO | Arcade/Neo Geo (FinalBurn Neo, MAME 2003-Plus) · Neo Geo CD (NeoCD) · Neo Geo Pocket · PC Engine/TG-16 e CD · PC-FX · 3DO (Opera) |
| Clássicos | ColecoVision · Intellivision (FreeIntv) · Odyssey²/Videopac (O2EM) · Channel F (FreeChaF) · Vectrex (vecx) |
| Portáteis | WonderSwan · Watara Supervision · Mega Duck · Arduboy |
| Computadores | MSX/MSX2 (blueMSX com C-BIOS livre, fMSX) · Commodore 64 (VICE) · Amiga (PUAE) · ZX Spectrum (Fuse) · Amstrad CPC (Caprice32) · MS-DOS (DOSBox Pure) |
| Fantasia | TIC-80 · PICO-8 (Retro8) |

Cada sistema define (em `core/systems/Systems.kt`): extensões, núcleos, opções otimizadas para mobile,
presets **Desempenho / Equilibrado / Qualidade**, BIOS com MD5, orientação e o layout do controle virtual.

## Funcionalidades

- **Biblioteca**: vincule pastas via Storage Access Framework (subpastas `snes/`, `psx/`, `ps1/`, `megadrive/`… são reconhecidas),
  ou importe arquivos para a estrutura interna `roms/<console>/`. Capas automáticas via libretro-thumbnails.
  Suporte a `.cue/.bin`, `.gdi`, `.m3u` (multidisco), `.chd`.
- **Explorar**: catálogo online pesquisável com download e "Jogar" direto no app. Hoje usa o
  [Homebrew Hub](https://hh.gbdev.io) (~1.600 jogos independentes de GB/GBC/GBA/NES).
  Novas fontes entram implementando `CatalogSource`.
- **Downloads**: fila com progresso/cancelamento, extração de `.zip` e "baixar do meu link".
- **Emulação**: núcleo baixado automaticamente na primeira execução · save states (4 slots + automático) com miniatura ·
  SRAM compatível com RetroArch (`saves/<console>/<rom>.srm`) · continuar de onde parou · avanço rápido ·
  filtros (CRT/LCD/suavizado) · opções do núcleo em tempo real · troca de disco · vibração (rumble).
- **Controles**: layout próprio por console (D-pad 8 direções, analógicos, botões C do N64, 6 botões do Saturn/arcade…),
  multitoque, escala automática em retrato/paisagem, opacidade/tamanho ajustáveis, vibração;
  gamepads Bluetooth/USB detectados automaticamente (o controle da tela se esconde).
- **BIOS**: importação em lote, identificação por nome ou MD5, verificação de status por console
  (inclusive grupos em que basta uma de várias BIOS, como no 3DO e no Neo Geo CD).
- **Identificação**: compara o hash do arquivo com os DATs No-Intro e mostra o nome canônico e as outras versões.
- **Pastas vinculadas**: jogos removidos da biblioteca ficam ocultos (o arquivo não é apagado) e podem ser reexibidos em Ajustes.

## Compilar

```bash
# Requer JDK 17+ e Android SDK (platform 36). Aponte o SDK em local.properties (sdk.dir=...).
./gradlew assembleDebug                       # APK leve (~3 MB em release); núcleos baixados sob demanda
./gradlew assembleRelease -PbundleCores=all   # embute todos os núcleos no APK (funciona offline)
./gradlew assembleRelease -PbundleCores=snes9x,mgba,pcsx_rearmed -PbundleAbis=arm64-v8a
./gradlew testDebugUnitTest                   # testes de unidade (JVM, sem aparelho)
```

**Assinatura da release:** copie `keystore.properties.example` para `keystore.properties` e preencha com a sua
chave (ou defina `RETROVIKA_KEYSTORE`, `RETROVIKA_KEYSTORE_PASSWORD`, `RETROVIKA_KEY_ALIAS` e `RETROVIKA_KEY_PASSWORD`).
Sem isso, a release é assinada com a chave de debug e o build avisa.

## Arquitetura

```
app/src/main/java/com/retrovika/app/
├── RetrovikaApp.kt        contêiner de dependências + Coil
├── core/
│   ├── systems/            catálogo de consoles, núcleos, presets e BIOS
│   ├── cores/              CoreManager: download/instalação dos .so (buildbot libretro) ou embutidos
│   ├── library/            Room (Game), varredura SAF/interna, nomes No-Intro, capas
│   ├── catalog/            CatalogSource, Homebrew Hub, DownloadManager
│   ├── bios/               verificação e importação de BIOS
│   ├── settings/           DataStore (preferências, núcleo/preset por console, opções de núcleo)
│   └── storage/, net/      pastas, zip, HTTP
├── emulation/
│   ├── GameActivity.kt     ciclo de vida do emulador, saves, entrada física, menu
│   ├── GameScreen.kt       HUD, menu de pausa (estados, vídeo, núcleo)
│   └── input/              PadLayout por console + VirtualGamepad em Compose
└── ui/                     tema, componentes e telas (Início, Biblioteca, Console, Explorar, Downloads, Ajustes)
```

## Créditos visuais

As fontes em `app/src/main/res/font` estão sob a SIL Open Font License 1.1: Space Grotesk (Florian Karsten), JetBrains Mono (JetBrains) e Press Start 2P (CodeMan38).

## Próximos passos sugeridos

- Editor de layout do controle (arrastar botões) e remapeamento de gamepad físico
- Metadados ricos (ScreenScraper/IGDB) e mais fontes livres no Explorar
- RetroAchievements, cheats, sincronização de saves na nuvem
- Downloads grandes via WorkManager com notificação em primeiro plano
