# LibretroDroid (cópia com correções)

Código do [LibretroDroid](https://github.com/Swordfish90/LibretroDroid) **0.13.2** (GPLv3, ver `LICENSE`),
compilado aqui em vez de vir pronto do JitPack, para corrigir os núcleos que desenham com a GPU
(Mupen64Plus-Next, Flycast, Dolphin, PPSSPP, LRPS2…).

Só entrou o que a compilação usa: de `oboe` (Apache 2.0) ficaram `src/`, `include/`, `CMakeLists.txt` e a
licença; de `libretro-common` (MIT, licença no cabeçalho de cada arquivo), os arquivos listados no
`CMakeLists.txt` e os cabeçalhos que eles incluem. Se uma mudança precisar de outro arquivo, copie-o do
mesmo submódulo do LibretroDroid 0.13.2.

## O que mudou em relação ao original

- **Estado GL isolado ao apresentar o quadro** (`video.cpp`, `CoreGLState`). O núcleo e o LibretroDroid
  usam o mesmo contexto. O quadro era desenhado com o que o núcleo tinha deixado ligado (um
  `GL_ARRAY_BUFFER` fazia os vértices virem de dentro do buffer do núcleo: tela preta no Flycast), e o
  estado do LibretroDroid ficava para o núcleo, que guarda em cache o que acha que está ligado (glitches no
  Dolphin). Agora o estado é salvo, limpo e restaurado em volta do desenho.
- **Framebuffer do núcleo no tamanho máximo** (`framebufferrenderer.cpp`, `libretrodroid.cpp`,
  `environment.cpp`). O FBO era criado com `base_width`×`base_height` e mostrado inteiro. Mas o núcleo desenha
  até `max_width`×`max_height`, e o tamanho real de cada quadro vem no callback de vídeo. O Flycast
  (base fixa de 640x480, com a resolução interna em 1280x960) e o Dolphin apareciam cortados ou num canto. O
  FBO agora usa a geometria máxima, só cresce, e a área útil de cada quadro é copiada (`glBlitFramebuffer`)
  para uma textura do tamanho exato, que é a que os shaders leem.
- **Só contextos GLES são aceitos** em `SET_HW_RENDER`. Aceitar Vulkan ou GL de desktop fazia o núcleo
  achar que tinha um contexto que nunca chegaria; recusando, ele cai para GLES.
- **Ordem da inicialização igual à do RetroArch** (`libretrodroid.cpp`): `retro_set_environment`,
  `retro_init` e só depois os callbacks de vídeo, áudio e entrada. O Mesen (NES) derrubava o app ao receber
  o callback de vídeo antes do `retro_init`.
- **Versão de GLES conferida** em `SET_HW_RENDER`: se o núcleo pede uma versão maior que a do contexto
  (o Citra pede 3.2), o pedido é recusado e o carregamento falha com `ERROR_GL_NOT_COMPATIBLE`, em vez de o
  núcleo abortar ao compilar os shaders.
- **Proporção `<= 0` usa `base_width / base_height`**, como manda a API. Só `< 0` era tratado: os núcleos
  que informam 0 (Gearsystem, Fuse, Ardens) ficavam com imagem de largura zero, tela preta.
- **`SET_VARIABLES` robusto**: entradas sem valor (LRPS2) derrubavam o app no `strlen`.
- **`SET_INPUT_DESCRIPTORS` aceito** (o Ardens não carregava) e **`SET_FRAME_TIME_CALLBACK` implementado**,
  chamado antes de cada `retro_run` com a duração de referência do quadro (o TIC-80 não carregava).
- **Valor de opção fora da lista do núcleo volta ao padrão** (`SET_VARIABLES`): um preset errado ou uma
  escolha salva de uma versão antiga do núcleo chegava ao núcleo como estava.
- **VFS v3 sempre oferecida** (`vfs/`): `stat`, `mkdir` e diretórios, inclusive `stat` dos arquivos
  virtuais do SAF. O Stella só reconhece a ROM pelo `stat` da VFS e não carregava nada. Pedidos de versão
  maior que a suportada são recusados, como manda a API.
- **JavaVM entregue ao Play!** logo após o `dlopen` (`core.cpp`), pelo `Framework::CJavaVM::SetJavaVM`
  que ele exporta. Sem isso o Play! quebrava ao iniciar a thread de emulação. O `JNI_OnLoad` dos núcleos
  não é chamado, nem o RetroArch faz isso: o do Dolphin procura classes do app Dolphin e aborta o processo.
- **Códigos de erro chegam ao Java** (`libretrodroidjni.cpp`): o header da exceção era incluído dentro do
  `namespace libretrodroid`, o que criava outro tipo, e os `catch` nunca batiam. Todo erro virava o
  genérico, inclusive o "OpenGL ES 3 necessário" original.
- **Tipo de controle por porta** (`GLRetroViewData.controllerTypes`): aplicado com
  `retro_set_controller_port_device` entre o `retro_load_game` e o primeiro `retro_run`, como o RetroArch.
  O original nunca chamava a função, e vários núcleos só conectam o controle quando a recebem (Flycast,
  Dolphin, Opera, Beetle PC-FX, PUAE, VICE, Caprice32, fMSX): todos os botões eram ignorados.
- **`GET_INPUT_BITMASKS` e `RETRO_DEVICE_ID_JOYPAD_MASK`** (`environment.cpp`, `input.cpp`). O LRPS2 só lê
  os botões pela máscara e ficava sem nenhum; o caminho sem máscara do Beetle PC-FX lê os botões errados.
- `#include <functional>` em `rumble.h` e `utils/javautils.h`, exigido pelos NDKs atuais.
