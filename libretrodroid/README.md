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
- **Só contextos GLES são aceitos** em `SET_HW_RENDER` (Vulkan só pela ponte descrita abaixo, e só quando o app a liga). Aceitar Vulkan ou GL de desktop sem isso fazia o núcleo
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
- **Jogo carregado sem o contexto pedido falha com `ERROR_GL_NOT_COMPATIBLE`** (`libretrodroid.cpp`,
  `throwIfHwContextMissing`). O Play! ignora a recusa do `SET_HW_RENDER`, diz que carregou e caía segundos
  depois ao usar o vídeo que nunca foi criado.
- **`GLRetroViewData.relaxedGlesVersion`**: aceita o contexto abaixo da versão pedida, para núcleos que
  pedem mais do que usam (o Play! pede GLES 3.2 e roda em 3.1).
- **Captura para transmissão** (`capture.cpp`, `Video::copyForeground`/`drawCapture`, `GLRetroView.setCaptureSurface`,
  `setAudioCapture`/`readCapturedAudio`): depois de cada quadro, a área do jogo é copiada para uma textura e dela
  para a superfície de um encoder, na thread de emulação; as amostras de áudio vão também para um buffer circular.
  Usado pelo "Jogar pela rede" do app. Só GLES 3.
- **Contexto Vulkan para núcleos libretro** (`vulkan/vulkancontext.*`, `renderers/es3/vulkanrendereres3.*`, `Environment`).
  Só com `GLRetroViewData.allowVulkan` (o app liga por núcleo); sem isso `SET_HW_RENDER` Vulkan segue recusado. O
  núcleo desenha na `VkImage` dele; o frontend não tem swapchain: cada quadro é copiado (`vkCmdBlitImage`) para um
  `AHardwareBuffer` de 4 buffers, importado no GL como `EGLImage`, e daí é uma textura comum (origem no topo) para a
  cadeia de shaders de sempre, com captura, rotação e snapshot inalterados. Exige GLES 3, loader Vulkan 1.1,
  `VK_ANDROID_external_memory_android_hardware_buffer` e as extensões de EGL para importar o buffer
  (`VulkanContext::isAvailable`); sem isso o pedido é recusado e o núcleo cai para outro renderizador.
  Instância e dispositivo: pela negociação do núcleo (v1 `create_device`, v2 `create_instance` e `create_device2`,
  em que o frontend soma as extensões da ponte) ou criados aqui; o `destroy_device` dele roda antes do dispositivo e
  da instância. A interface (`GET_HW_RENDER_INTERFACE`, versão 5) tem `set_image`, índice de sincronização (3),
  `set_command_buffers` (enviados junto da cópia), `lock_queue` e `set_signal_semaphore`. A GPU pode ficar um quadro
  atrás (no máximo dois adiantados): mostra o quadro mais novo que já terminou, sem esperar o atual. O contexto é
  criado em `onSurfaceCreated`, antes do `context_reset`. Antes disso, no próprio `SET_HW_RENDER`, `isAvailable()` faz um
  autoteste completo com um contexto descartável (instância, dispositivo e um buffer Vulkan > AHardwareBuffer > EGLImage):
  se falhar, o pedido é recusado e o núcleo cai para outro renderizador, em vez de se comprometer e perder o contexto
  depois (o PPSSPP, por exemplo, não se descarrega sem abortar o processo depois de um carregamento nesse estado).
  Falha na criação do contexto, ainda assim, vira `ERROR_GL_NOT_COMPATIBLE` (o app volta ao renderizador sem Vulkan).
  O `context_destroy` do núcleo roda sempre no `destroy()`, mesmo sem `context_reset`: é ele que encerra as threads de
  vídeo do núcleo.
- **`GLRetroView.onDestroy` sempre chama `LibretroDroid.destroy()`**, mesmo depois de um erro (`isAborted`). Antes, o
  `catchExceptions` o ignorava, o núcleo carregado ficava vivo até o próximo `create()` e era descarregado sem o
  `retro_unload_game`: um núcleo com threads (PPSSPP) abortava o processo no `dlclose`.
- **Ponte Vulkan quebrada vira erro** (`libretrodroidjni.cpp`, `VulkanContext::isLost`): espera da GPU estourada,
  envio recusado ou falta de memória para os buffers deixavam a tela preta para sempre, sem aviso. Agora o `step`
  lança `ERROR_GL_NOT_COMPATIBLE`, que chega pelo `getGLRetroErrors()`. Quadro descartado ainda envia os command
  buffers e os semáforos do núcleo, que senão ficaria esperando um trabalho que nunca roda.
- **Threads e tempo de vida** (`libretrodroid.cpp`, `audio.cpp`, `video.cpp`): `onSurfaceCreated`/`onSurfaceChanged`,
  `refreshAspectRatio` e `setViewport` pegam o `coreLock` (o `destroy()` da thread principal descarregava o núcleo no
  meio deles); a entrada é lida com o `inputLock` (núcleos com thread própria a liam enquanto o `pause()` a soltava);
  a saída de áudio reaberta pelo Oboe troca fila e buffers de uma vez, e o callback nunca pede mais amostras do que o
  buffer temporário comporta (no avanço rápido o `readNow` escrevia além do fim dele). `Video` ganhou destrutor
  (renderizador, programas de shader, captura), o conteúdo do jogo, o caminho e os códigos de trapaça vivem até o
  `destroy()` em vez de vazar, e as opções e controles de um núcleo não passam mais para o seguinte.
- `#include <functional>` em `rumble.h` e `utils/javautils.h`, exigido pelos NDKs atuais.
