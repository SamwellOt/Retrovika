/*
 *     Copyright (C) 2022  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.swordfish.libretrodroid

import android.app.ActivityManager
import android.content.Context
import android.graphics.PointF
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import androidx.lifecycle.coroutineScope
import com.swordfish.libretrodroid.gamepad.GamepadsManager
import java.util.*
import java.util.concurrent.CountDownLatch
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.properties.Delegates
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class GLRetroView(
    context: Context,
    private val data: GLRetroViewData,
) : GLSurfaceView(context), LifecycleObserver {

    var audioEnabled: Boolean by Delegates.observable(true) { _, _, value ->
        LibretroDroid.setAudioEnabled(value)
    }

    var frameSpeed: Int by Delegates.observable(1) { _, _, value ->
        LibretroDroid.setFrameSpeed(value)
    }

    /**
     * Avisa o núcleo do avanço rápido do usuário (ele pode pular vídeo nos quadros intermediários). Fica
     * desligado no teste de desempenho, que usa frameSpeed > 1 mas precisa de todos os quadros.
     */
    var fastForwardHints: Boolean by Delegates.observable(false) { _, _, value ->
        LibretroDroid.setFastForwardHints(value)
    }

    var shader: ShaderConfig by Delegates.observable(data.shader) { _, _, value ->
        LibretroDroid.setShaderConfig(buildShader(value))
    }

    var viewport: RectF by Delegates.observable(RectF(0f, 0f, 1f, 1f)) { _, _, value ->
        runOnEmulationThread(true) {
            LibretroDroid.setViewport(value.left, value.top, value.width(), value.height())
        }
    }

    private val openGLESVersion: Int

    private var isGameLoaded = false
    private var isEmulationReady = false
    private var isAborted = false

    /** Protege [isDestroyed] e [loadRunning]: depois do onDestroy nenhum carregamento começa. */
    private val loadLock = Any()
    @Volatile private var isDestroyed = false
    @Volatile private var loadRunning = false

    /**
     * O retro_load_game está rodando na thread GL. Destruir nesse meio-tempo faz o onDestroy esperar o
     * carregamento terminar (o destroy nativo pega o mesmo lock): quem puder deve esperar isto virar falso.
     */
    val isLoading: Boolean get() = loadRunning

    // Nunca suspende nem descarta o último evento para quem chega tarde: os eventos saem da própria thread GL com
    // tryEmit (na ordem em que acontecem), sem uma corrotina por quadro.
    private val retroGLEventsSubject = MutableSharedFlow<GLRetroEvents>(
        replay = 1,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val retroGLIssuesErrors = MutableSharedFlow<Int>(1)

    private val rumbleEventsSubject = MutableSharedFlow<RumbleEvent>()

    private var lifecycle: Lifecycle? = null

    /** Só a thread GL mexe: o próximo quadro desenhado ainda precisa virar um [GLRetroEvents.FrameRendered]. */
    private var firstFramePending = true

    // Taxa da tela: o app pede ao sistema a taxa do jogo (setFrameRate) e acompanha a que a tela realmente usa.
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastScreenRate = 0f
    @Volatile private var appliedFrameRate = 0f
    private var drawCount = 0

    // ADPF (API 31+): a sessão de dicas de desempenho da thread GL. Tudo sob o hintLock, porque o close() vem da
    // thread principal e usar uma sessão fechada pode derrubar o processo nas versões mais antigas do sistema.
    private val hintLock = Any()
    private var hintSession: HintSession? = null
    // A thread GL em que a criação já foi tentada: aparelho sem suporte (sessão nula) não repete a tentativa a cada superfície.
    private var hintTriedTid = 0

    init {
        openGLESVersion = getGLESVersion(context)
        preserveEGLContextOnPause = true
        setEGLContextClientVersion(openGLESVersion)
        setRenderer(Renderer())
        keepScreenOn = true
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreate(lifecycleOwner: LifecycleOwner) = catchExceptions {
        lifecycle = lifecycleOwner.lifecycle
        // Antes do create: o núcleo pode pedir o contexto já no retro_init.
        LibretroDroid.setAllowVulkan(data.allowVulkan)
        LibretroDroid.create(
            openGLESVersion,
            data.coreFilePath,
            data.systemDirectory,
            data.savesDirectory,
            data.variables,
            buildShader(data.shader),
            getDefaultRefreshRate(),
            data.preferLowLatencyAudio,
            data.gameVirtualFiles.isNotEmpty(),
            data.enableMicrophone,
            data.skipDuplicateFrames,
            data.immersiveMode,
            getDeviceLanguage()
        )
        LibretroDroid.setRumbleEnabled(data.rumbleEventsEnabled)
        // Depois do create, que reinicia o Environment; o jogo só carrega no onSurfaceCreated.
        LibretroDroid.setRelaxedGlesVersion(data.relaxedGlesVersion)
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestroy() {
        unregisterDisplayListener()
        // Antes de tudo: a partir daqui a thread GL não começa mais a carregar o jogo.
        synchronized(loadLock) {
            isDestroyed = true
            // O jogo nunca chegou a carregar: os descritores do SAF ainda são nossos e vazariam.
            if (!loadRunning && !isGameLoaded) {
                data.gameVirtualFiles.forEach { runCatching { it.fileDescriptor.close() } }
            }
        }
        // Depois do isDestroyed: a thread GL não cria outra sessão em seguida.
        closeHintSession()
        // Fora do catchExceptions: depois de um erro (isAborted) ele ignora tudo, e o núcleo carregado ficava vivo
        // até o próximo create(), que o descarregava sem o retro_unload_game (um núcleo com threads, como o
        // PPSSPP, aborta o processo no dlclose). O destroy() aguenta qualquer estado, até o de um carregamento que falhou.
        try {
            LibretroDroid.destroy()
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error destroying the core", e)
        }
        lifecycle = null
    }

    private fun getDeviceLanguage() = Locale.getDefault().language

    /** A tela em que a view está (a do contexto, antes de ela entrar na janela). Nulo se não der para saber. */
    private fun currentDisplay(): Display? {
        display?.let { return it }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { context.display }.getOrNull() else null
    }

    @Suppress("DEPRECATION")
    private fun getDefaultRefreshRate(): Float {
        return currentDisplay()?.refreshRate
            ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.refreshRate
    }

    // Direto da thread de quem chama, sem o queueEvent (que esperava a thread GL e custava até um quadro de
    // latência): o lado nativo já guarda o Input com um lock, e o pause() só o descarta.
    fun sendKeyEvent(action: Int, keyCode: Int, port: Int = 0) {
        LibretroDroid.onKeyEvent(port, action, keyCode)
    }

    fun sendMotionEvent(source: Int, xAxis: Float, yAxis: Float, port: Int = 0) {
        LibretroDroid.onMotionEvent(port, source, xAxis, yAxis)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerDisplayListener()
    }

    override fun onDetachedFromWindow() {
        unregisterDisplayListener()
        super.onDetachedFromWindow()
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (display?.displayId == displayId) pushScreenRate()
            }
        }
        displayListener = listener
        runCatching { manager.registerDisplayListener(listener, Handler(Looper.getMainLooper())) }
        // A taxa do create pode já ter mudado (o sistema ajusta o modo ao abrir a janela).
        pushScreenRate()
    }

    private fun unregisterDisplayListener() {
        val listener = displayListener ?: return
        displayListener = null
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        runCatching { manager.unregisterDisplayListener(listener) }
    }

    /** Passa a taxa atual da tela ao lado nativo, que refaz o ritmo (vsync, múltiplo ou espera) e o áudio. */
    private fun pushScreenRate() {
        val rate = display?.refreshRate ?: return
        if (!(rate > 1f) || kotlin.math.abs(rate - lastScreenRate) < 0.01f) return
        lastScreenRate = rate
        runCatching { LibretroDroid.setScreenRefreshRate(rate) }
    }

    /**
     * Diz ao sistema a taxa do jogo, para a tela rodar nela (ou num múltiplo) em vez de ficar sempre no máximo.
     * Só se for sem corte: ONLY_IF_SEAMLESS. Trocar de modo sem emenda é imperceptível; o CHANGE_FRAME_RATE_ALWAYS
     * pode piscar a tela por um segundo em painéis sem essa troca e ignora a escolha do usuário de manter a taxa
     * alta. Quando o sistema fica no múltiplo (120 Hz para 60 fps), o FPSSync nativo roda um quadro a cada 2 vsyncs.
     */
    private fun applyFrameRateHint() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !isGameLoaded) return
        val fps = LibretroDroid.getContentFps().toFloat()
        if (!(fps > 0f)) return
        setSurfaceFrameRate(fps)
    }

    /** Sem jogo rodando (menu aberto, segundo plano) a superfície não tem taxa a pedir: a interface volta ao normal. */
    private fun clearFrameRateHint() {
        setSurfaceFrameRate(0f)
    }

    private fun setSurfaceFrameRate(fps: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val surface = holder.surface
        if (surface == null || !surface.isValid) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)
            } else {
                surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            }
        }
        appliedFrameRate = fps
    }

    /** O tempo, em ns, em que o trabalho de um desenho deve terminar: o intervalo do quadro do jogo. */
    private fun targetWorkNanos(): Long {
        val fps = LibretroDroid.getContentFps().takeIf { it > 1.0 } ?: 60.0
        return (1_000_000_000.0 / fps).toLong()
    }

    /** Thread GL. Cria a sessão ADPF (ou a refaz, se a thread GL mudou) com o jogo já carregado. */
    private fun ensureHintSession() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isGameLoaded) return
        val tid = Process.myTid()
        synchronized(hintLock) {
            if (isDestroyed || hintTriedTid == tid) return
            hintSession?.close()
            hintSession = null
            hintTriedTid = tid
            runCatching {
                hintSession = HintSession.create(context, tid, targetWorkNanos())
            }.onFailure { Log.w(TAG_LOG, "No performance hint session", it) }
        }
    }

    private fun closeHintSession() {
        synchronized(hintLock) {
            hintSession?.close()
            hintSession = null
        }
    }

    /** Thread GL, depois de cada desenho. [workNanos] é 0 quando só reapresentou o quadro (nada a informar). */
    private fun afterDraw(workNanos: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // O fps do núcleo pode mudar durante o jogo (SET_SYSTEM_AV_INFO): confere de vez em quando.
        if (++drawCount % CONTENT_FPS_CHECK_DRAWS == 0 && isEmulationReady) {
            val fps = LibretroDroid.getContentFps().toFloat()
            if (fps > 0f && kotlin.math.abs(fps - appliedFrameRate) > 0.01f) {
                setSurfaceFrameRate(fps)
                synchronized(hintLock) { hintSession?.updateTarget(targetWorkNanos()) }
            }
        }
        if (workNanos > 0) {
            synchronized(hintLock) { hintSession?.report(workNanos) }
        }
    }

    /** Embrulha a sessão ADPF para a classe do sistema só ser carregada na API 31+. */
    @RequiresApi(Build.VERSION_CODES.S)
    private class HintSession(
        private val session: PerformanceHintManager.Session,
        val tid: Int,
        private var targetNanos: Long
    ) {
        fun report(workNanos: Long) {
            runCatching { session.reportActualWorkDuration(workNanos) }
        }

        fun updateTarget(nanos: Long) {
            if (nanos <= 0 || nanos == targetNanos) return
            targetNanos = nanos
            runCatching { session.updateTargetWorkDuration(nanos) }
        }

        fun close() {
            runCatching { session.close() }
        }

        companion object {
            fun create(context: Context, tid: Int, targetNanos: Long): HintSession? {
                val manager = context.getSystemService(PerformanceHintManager::class.java) ?: return null
                // Alguns aparelhos devolvem nulo (sem suporte a dicas).
                val session = manager.createHintSession(intArrayOf(tid), targetNanos) ?: return null
                return HintSession(session, tid, targetNanos)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        val position = when (event?.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                normalizeTouchCoordinates(event.x, event.y)
            }

            MotionEvent.ACTION_UP -> {
                TOUCH_EVENT_OUTSIDE
            }

            else -> null
        }

        if (position != null) {
            LibretroDroid.onTouchEvent(position.x, position.y)
        }

        return true
    }

    private fun clamp(x: Float, min: Float, max: Float) = minOf(maxOf(x, min), max)

    private fun normalizeTouchCoordinates(x: Float, y: Float): PointF {
        val x = clamp(2f * x / width - 1f, -1f, +1f)
        val y = clamp(2f * y / height - 1f, -1f, +1f)
        return PointF(x, y)
    }

    fun serializeState(useEmulationThread: Boolean = true): ByteArray {
        return runOnEmulationThread(useEmulationThread) {
            LibretroDroid.serializeState()
        }
    }

    fun setCheat(index: Int, enable: Boolean, code: String, useEmulationThread: Boolean = true) {
        runOnEmulationThread(useEmulationThread) {
            // Uma exceção aqui cairia na thread GL (fechando o app) e a espera acima nunca terminaria.
            runCatching { LibretroDroid.setCheat(index, enable, code) }.onFailure { Log.e(TAG_LOG, "setCheat", it) }
        }
    }

    fun resetCheat(useEmulationThread: Boolean = true) {
        runOnEmulationThread(useEmulationThread) {
            runCatching { LibretroDroid.resetCheat() }.onFailure { Log.e(TAG_LOG, "resetCheat", it) }
        }
    }

    fun unserializeState(data: ByteArray, useEmulationThread: Boolean = true): Boolean {
        return runOnEmulationThread(useEmulationThread) {
            LibretroDroid.unserializeState(data)
        }
    }

    fun serializeSRAM(useEmulationThread: Boolean = true): ByteArray {
        return runOnEmulationThread(useEmulationThread) {
            LibretroDroid.serializeSRAM()
        }
    }

    fun unserializeSRAM(data: ByteArray, useEmulationThread: Boolean = true): Boolean {
        return runOnEmulationThread(useEmulationThread) {
            LibretroDroid.unserializeSRAM(data)
        }
    }

    fun reset(useEmulationThread: Boolean = true) = runOnEmulationThread(useEmulationThread) {
        LibretroDroid.reset()
    }

    fun getGLRetroEvents(): Flow<GLRetroEvents> {
        return retroGLEventsSubject
    }

    fun getGLRetroErrors(): Flow<Int> {
        return retroGLIssuesErrors
    }

    fun getRumbleEvents(): Flow<RumbleEvent> {
        return rumbleEventsSubject
    }

    /** A cópia do quadro para outra superfície usa glBlitFramebuffer, que só existe no GLES 3. */
    val supportsCapture: Boolean get() = openGLESVersion >= 3

    /**
     * Cada quadro novo também é copiado para [surface] (a entrada de um encoder), no tamanho dado.
     * Null para de copiar. A troca vale a partir do próximo quadro.
     */
    fun setCaptureSurface(surface: android.view.Surface?, width: Int, height: Int) {
        LibretroDroid.setCaptureSurface(surface, width, height)
    }

    /** Liga a cópia do áudio do núcleo, lida com [readCapturedAudio]. */
    fun setAudioCapture(enabled: Boolean) {
        LibretroDroid.setAudioCapture(enabled)
    }

    /** Amostras estéreo intercaladas; devolve quantas foram copiadas para [buffer]. */
    fun readCapturedAudio(buffer: ShortArray): Int = LibretroDroid.readAudioCapture(buffer)

    /** Taxa das amostras de [readCapturedAudio]; 0 antes de o jogo carregar. */
    val audioSampleRate: Int get() = LibretroDroid.getAudioSampleRate()

    fun getControllers(): Array<Array<Controller>> {
        return LibretroDroid.getControllers()
    }

    fun setControllerType(port: Int, type: Int) {
        LibretroDroid.setControllerType(port, type)
    }

    fun getVariables(): Array<Variable> {
        return LibretroDroid.getVariables()
    }

    fun updateVariables(vararg variables: Variable) {
        variables.forEach {
            LibretroDroid.updateVariable(it)
        }
    }

    /**
     * Começa a partida em rede no soquete [fd] (já conectado; passa a ser do LibretroDroid, que o fecha).
     * Na thread de emulação, entre dois quadros, logo depois do estado inicial ser carregado.
     */
    fun startNetplay(fd: Int, localPort: Int, delayFrames: Int, epoch: Int, useEmulationThread: Boolean = true) {
        runOnEmulationThread(useEmulationThread) { LibretroDroid.startNetplay(fd, localPort, delayFrames, epoch) }
    }

    /**
     * Anfitrião: captura o estado e começa a partida no mesmo passo da thread de emulação, sem nenhum
     * quadro entre os dois (o convidado parte exatamente desse estado). Nulo se o núcleo não gerou estado.
     */
    fun serializeAndStartNetplay(fd: Int, localPort: Int, delayFrames: Int, epoch: Int): ByteArray? = runOnEmulationThread(true) {
        runCatching {
            // Vazio: o núcleo não gerou estado. A partida não começa e o descritor continua de quem chamou.
            val state = LibretroDroid.serializeState()?.takeIf { it.isNotEmpty() }
            if (state != null) LibretroDroid.startNetplay(fd, localPort, delayFrames, epoch)
            state
        }.onFailure { Log.e(TAG_LOG, "serializeAndStartNetplay", it) }.getOrNull()
    }

    /** Convidado: carrega o estado do anfitrião e começa a partida no mesmo passo. */
    fun unserializeAndStartNetplay(state: ByteArray, fd: Int, localPort: Int, delayFrames: Int, epoch: Int): Boolean = runOnEmulationThread(true) {
        runCatching {
            val ok = LibretroDroid.unserializeState(state)
            if (ok) LibretroDroid.startNetplay(fd, localPort, delayFrames, epoch)
            ok
        }.onFailure { Log.e(TAG_LOG, "unserializeAndStartNetplay", it) }.getOrDefault(false)
    }

    fun stopNetplay(useEmulationThread: Boolean = true) {
        runOnEmulationThread(useEmulationThread) { runCatching { LibretroDroid.stopNetplay() } }
    }

    /** -1 sem partida; -2 conexão perdida; senão, há quantos ms a entrada do outro está atrasada. */
    fun netplayStatus(): Long = LibretroDroid.netplayStatus()

    fun netplayFrame(): Long = LibretroDroid.netplayFrame()

    /** Quadros emulados desde que o jogo carregou (teste de desempenho). */
    fun runCount(): Long = LibretroDroid.getRunCount()

    /** Quadros novos que o núcleo entregou (sem os repetidos): abaixo do runCount, ele está pulando quadros. */
    fun videoFrameCount(): Long = LibretroDroid.getVideoFrameCount()

    /** Quadros por segundo nativos do jogo (60 no NTSC, 50 no PAL…). */
    fun contentFps(): Double = LibretroDroid.getContentFps()

    fun getAvailableDisks(useEmulationThread: Boolean = true): Int {
        return runOnEmulationThread(useEmulationThread) { LibretroDroid.availableDisks() }
    }

    fun getCurrentDisk(useEmulationThread: Boolean = true): Int {
        return runOnEmulationThread(useEmulationThread) { LibretroDroid.currentDisk() }
    }

    fun changeDisk(index: Int, useEmulationThread: Boolean = true) {
        runOnEmulationThread(useEmulationThread) { LibretroDroid.changeDisk(index) }
    }

    private fun getGLESVersion(context: Context): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return if (activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000) {
            3
        } else {
            2
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mappedKey = GamepadsManager.getGamepadKeyEvent(keyCode)
        val port = (event?.device?.controllerNumber ?: 0) - 1

        if (event != null && port >= 0 && keyCode in GamepadsManager.GAMEPAD_KEYS) {
            sendKeyEvent(KeyEvent.ACTION_DOWN, mappedKey, port)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val mappedKey = GamepadsManager.getGamepadKeyEvent(keyCode)
        val port = (event?.device?.controllerNumber ?: 0) - 1

        if (event != null && port >= 0 && keyCode in GamepadsManager.GAMEPAD_KEYS) {
            sendKeyEvent(KeyEvent.ACTION_UP, mappedKey, port)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent?): Boolean {
        val port = (event?.device?.controllerNumber ?: 0) - 1
        if (port >= 0) {
            when (event?.source) {
                InputDevice.SOURCE_JOYSTICK -> {
                    sendMotionEvent(
                        MOTION_SOURCE_DPAD,
                        event.getAxisValue(MotionEvent.AXIS_HAT_X),
                        event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                        port
                    )
                    sendMotionEvent(
                        MOTION_SOURCE_ANALOG_LEFT,
                        event.getAxisValue(MotionEvent.AXIS_X),
                        event.getAxisValue(MotionEvent.AXIS_Y),
                        port
                    )
                    sendMotionEvent(
                        MOTION_SOURCE_ANALOG_RIGHT,
                        event.getAxisValue(MotionEvent.AXIS_Z),
                        event.getAxisValue(MotionEvent.AXIS_RZ),
                        port
                    )
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    // These functions are called only after the GLSurfaceView has been created.
    private inner class RenderLifecycleObserver : LifecycleObserver {
        @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
        private fun resume() = catchExceptions {
            LibretroDroid.resume()
            onResume()
            isEmulationReady = true
            applyFrameRateHint()
        }

        @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        private fun pause() = catchExceptions {
            isEmulationReady = false
            clearFrameRateHint()
            onPause()
            LibretroDroid.pause()
        }
    }

    inner class Renderer : GLSurfaceView.Renderer {
        override fun onDrawFrame(gl: GL10) = catchExceptions {
            if (isEmulationReady) {
                val workNanos = LibretroDroid.step(this@GLRetroView)
                // Só o primeiro quadro: quem espera usa first(), e a corrotina por quadro acordava a thread principal.
                // O flow guarda o último evento, então quem se inscreve depois também recebe. Se não coube (um
                // inscrito lento), tenta de novo no quadro seguinte.
                if (firstFramePending && retroGLEventsSubject.tryEmit(GLRetroEvents.FrameRendered)) {
                    firstFramePending = false
                }
                afterDraw(workNanos)
            }
        }

        override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) = catchExceptions {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            LibretroDroid.onSurfaceChanged(width, height)
            // Superfície nova (voltou do segundo plano): a taxa pedida ao sistema é por superfície.
            if (isEmulationReady) applyFrameRateHint()
            ensureHintSession()
        }


        override fun onSurfaceCreated(gl: GL10, config: EGLConfig) = catchExceptions {
            Thread.currentThread().priority = Thread.MAX_PRIORITY
            initializeCore()
            // Contexto novo: o primeiro quadro dele também conta. Emitido daqui, antes de qualquer quadro, para o
            // último evento guardado nunca ser o SurfaceCreated depois de um FrameRendered.
            firstFramePending = true
            retroGLEventsSubject.tryEmit(GLRetroEvents.SurfaceCreated)
            ensureHintSession()
        }
    }

    // These functions are called from the GL thread.
    private fun initializeCore() = catchExceptions {
        if (isGameLoaded) return@catchExceptions
        // Superfície criada depois do onDestroy: carregar agora usaria o núcleo de outra tela (é global).
        synchronized(loadLock) {
            if (isDestroyed) return@catchExceptions
            loadRunning = true
        }
        try {
            when {
                data.gameFilePath != null -> loadGameFromPath(data.gameFilePath!!)
                data.gameFileBytes != null -> loadGameFromBytes(data.gameFileBytes!!)
                data.gameVirtualFiles.isNotEmpty() -> loadGameFromVirtualFiles(data.gameVirtualFiles)
            }
            data.saveRAMState?.let {
                LibretroDroid.unserializeSRAM(data.saveRAMState)
                data.saveRAMState = null
            }
            // Como o RetroArch: entre o retro_load_game e o primeiro retro_run. Depois disso o Dolphin já
            // iniciou a thread de emulação, que recarrega a configuração dos controles e pode desfazer a chamada.
            data.controllerTypes.forEachIndexed { port, type -> LibretroDroid.setControllerType(port, type) }
            LibretroDroid.onSurfaceCreated()
            isGameLoaded = true
        } finally {
            loadRunning = false
        }

        KtUtils.runOnUIThread {
            lifecycle?.addObserver(RenderLifecycleObserver())
        }
    }

    private fun loadGameFromVirtualFiles(virtualFiles: List<VirtualFile>) {
        val detachedVirtualFiles = virtualFiles
            .map { DetachedVirtualFile(it.virtualPath, it.fileDescriptor.detachFd()) }
        LibretroDroid.loadGameFromVirtualFiles(detachedVirtualFiles)
    }

    private fun loadGameFromBytes(gameFileBytes: ByteArray) {
        LibretroDroid.loadGameFromBytes(gameFileBytes)
    }

    private fun loadGameFromPath(gameFilePath: String) {
        LibretroDroid.loadGameFromPath(gameFilePath)
    }

    private fun catchExceptions(block: () -> Unit) {
        try {
            if (isAborted) return
            block()
        } catch (e: RetroException) {
            GlobalScope.launch {
                retroGLIssuesErrors.emit(e.errorCode)
            }
            isAborted = true
        } catch (e: Exception) {
            Log.e(TAG_LOG, "Error in GLRetroView", e)
            GlobalScope.launch {
                retroGLIssuesErrors.emit(LibretroDroid.ERROR_GENERIC)
            }
        }
    }

    private fun <T> runOnEmulationThread(useEmulationThread: Boolean, block: () -> T): T {
        if (!useEmulationThread || Thread.currentThread().name.startsWith("GLThread")) {
            return block()
        }

        val latch = CountDownLatch(1)
        var result: T? = null
        var error: Throwable? = null
        queueEvent {
            // O erro (ex.: RetroException de um estado incompatível) volta para quem chamou: lançado aqui,
            // derrubaria a thread GL e o latch nunca seria liberado.
            try {
                result = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }

        // A thread GL pode terminar (superfície destruída) com o pedido ainda na fila: sem prazo, quem
        // espera ficaria parado para sempre.
        if (!latch.awaitUninterruptibly(EMULATION_THREAD_TIMEOUT_MS)) {
            throw RetroException(LibretroDroid.ERROR_GENERIC)
        }
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun CountDownLatch.awaitUninterruptibly(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var interrupted = false
        try {
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return count == 0L
                try {
                    return await(left, java.util.concurrent.TimeUnit.NANOSECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun buildShader(config: ShaderConfig): GLRetroShader {
        return when (config) {
            is ShaderConfig.Default -> GLRetroShader(LibretroDroid.SHADER_DEFAULT)
            is ShaderConfig.CRT -> GLRetroShader(LibretroDroid.SHADER_CRT)
            is ShaderConfig.LCD -> GLRetroShader(LibretroDroid.SHADER_LCD)
            is ShaderConfig.Sharp -> GLRetroShader(LibretroDroid.SHADER_SHARP)
            is ShaderConfig.CUT -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_USE_DYNAMIC_BLEND to toParam(config.useDynamicBlend),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_CONTRAST_EDGE to toParam(config.blendMinContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_CONTRAST_EDGE to toParam(config.blendMaxContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MIN_SHARPNESS to toParam(config.blendMinSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_BLEND_MAX_SHARPNESS to toParam(config.blendMaxSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_STATIC_BLEND_SHARPNESS to toParam(config.staticSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_EDGE_USE_FAST_LUMA to toParam(config.edgeUseFastLuma),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_VALUE to toParam(config.edgeMinValue),
                    LibretroDroid.SHADER_UPSCALE_CUT_PARAM_EDGE_MIN_CONTRAST to toParam(config.edgeMinContrast),
                )
            )

            is ShaderConfig.CUT2 -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT2,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_USE_DYNAMIC_BLEND to toParam(config.useDynamicBlend),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_CONTRAST_EDGE to toParam(config.blendMinContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_CONTRAST_EDGE to toParam(config.blendMaxContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MIN_SHARPNESS to toParam(config.blendMinSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_BLEND_MAX_SHARPNESS to toParam(config.blendMaxSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_STATIC_BLEND_SHARPNESS to toParam(config.staticSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_EDGE_USE_FAST_LUMA to toParam(config.edgeUseFastLuma),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING to toParam(config.softEdgesSharpening),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_SOFT_EDGES_SHARPENING_AMOUNT to toParam(config.softEdgesSharpeningAmount),
                    LibretroDroid.SHADER_UPSCALE_CUT2_PARAM_HARD_EDGES_SEARCH_MAX_ERROR to toParam(config.hardEdgesSearchMaxError),
                )
            )

            is ShaderConfig.CUT3 -> GLRetroShader(
                LibretroDroid.SHADER_UPSCALE_CUT3,
                buildParams(
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_USE_DYNAMIC_BLEND to toParam(config.useDynamicBlend),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_CONTRAST_EDGE to toParam(config.blendMinContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_CONTRAST_EDGE to toParam(config.blendMaxContrastEdge),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MIN_SHARPNESS to toParam(config.blendMinSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_BLEND_MAX_SHARPNESS to toParam(config.blendMaxSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_STATIC_BLEND_SHARPNESS to toParam(config.staticSharpness),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_EDGE_USE_FAST_LUMA to toParam(config.edgeUseFastLuma),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING to toParam(config.softEdgesSharpening),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_SOFT_EDGES_SHARPENING_AMOUNT to toParam(config.softEdgesSharpeningAmount),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_ERROR to toParam(config.hardEdgesSearchMaxError),
                    LibretroDroid.SHADER_UPSCALE_CUT3_PARAM_HARD_EDGES_SEARCH_MAX_DISTANCE to toParam(config.hardEdgesSearchMaxDistance),
                )
            )
        }
    }

    private fun toParam(param: Float): String {
        return param.toString()
    }

    private fun toParam(param: Boolean): String {
        return if (param) {
            "1"
        } else {
            "0"
        }
    }

    private fun toParam(param: Int): String {
        return param.toString()
    }

    private fun buildParams(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs
            .filter { (key, value) -> value != null }
            .associate { (key, value) -> key to value!! }
    }

    /** This function gets called from the jni side.*/
    private fun sendRumbleEvent(port: Int, strengthWeak: Float, strengthStrong: Float) {
        lifecycle?.coroutineScope?.launch {
            rumbleEventsSubject.emit(RumbleEvent(port, strengthWeak, strengthStrong))
        }
    }

    private fun refreshAspectRatio() {
        runOnEmulationThread(true) {
            LibretroDroid.refreshAspectRatio()
        }
    }

    sealed class GLRetroEvents {
        object FrameRendered : GLRetroEvents()
        object SurfaceCreated : GLRetroEvents()
    }

    companion object {
        private val TAG_LOG = GLRetroView::class.java.simpleName

        /** De quantos em quantos desenhos conferir se o fps do núcleo mudou. */
        private const val CONTENT_FPS_CHECK_DRAWS = 64

        /** Espera máxima por um pedido na thread de emulação (estados grandes de PS2/GameCube levam segundos). */
        private const val EMULATION_THREAD_TIMEOUT_MS = 20_000L

        const val MOTION_SOURCE_DPAD = LibretroDroid.MOTION_SOURCE_DPAD
        const val MOTION_SOURCE_ANALOG_LEFT = LibretroDroid.MOTION_SOURCE_ANALOG_LEFT
        const val MOTION_SOURCE_ANALOG_RIGHT = LibretroDroid.MOTION_SOURCE_ANALOG_RIGHT
        const val MOTION_SOURCE_POINTER = LibretroDroid.MOTION_SOURCE_POINTER

        const val ERROR_LOAD_LIBRARY = LibretroDroid.ERROR_LOAD_LIBRARY
        const val ERROR_LOAD_GAME = LibretroDroid.ERROR_LOAD_GAME
        const val ERROR_GL_NOT_COMPATIBLE = LibretroDroid.ERROR_GL_NOT_COMPATIBLE
        const val ERROR_SERIALIZATION = LibretroDroid.ERROR_SERIALIZATION
        const val ERROR_CHEAT = LibretroDroid.ERROR_CHEAT
        const val ERROR_GENERIC = LibretroDroid.ERROR_GENERIC

        private val TOUCH_EVENT_OUTSIDE = PointF(-10f, 10f)
    }
}
