/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.immortal.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.immortal.launcher.ui.theme.LocalApprovedPalette
import java.util.Locale
import kotlinx.coroutines.delay

/** One saved text note and one memo, with a draft retained by the host's SaveableStateHolder. */
@Composable
internal fun ToolsNotesContent() {
    val context = LocalContext.current
    val pageActive = LocalApprovedLauncherPageActive.current
    val audio = remember(context) { AudioNote(context.applicationContext) }
    var text by rememberSaveable { mutableStateOf(NotesConfig.loadText(context)) }
    var savedText by remember { mutableStateOf(NotesConfig.loadText(context)) }
    var savedNotice by rememberSaveable { mutableStateOf(false) }
    var hasMemo by remember { mutableStateOf(false) }
    var memoLoading by remember { mutableStateOf(true) }
    var recording by remember { mutableStateOf(false) }
    var recorderStarting by remember { mutableStateOf(false) }
    var recorderStopping by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var playbackPreparing by remember { mutableStateOf(false) }
    var memoDeleting by remember { mutableStateOf(false) }
    var micGranted by remember { mutableStateOf(toolsMicrophoneGranted(context)) }
    var permissionRequest by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var clearText by remember { mutableStateOf(false) }
    var discardText by remember { mutableStateOf(false) }
    var deleteMemo by remember { mutableStateOf(false) }

    fun stopAudio() {
        // Keep an outstanding Android permission request until its result arrives.
        // Otherwise an old result could be mistaken for a new request after re-entry.
        val wasRecording = recording || recorderStarting || recorderStopping || audio.isRecording
        recorderStopping = wasRecording
        if (wasRecording) message = "Saving voice memo…"
        else if (!memoDeleting) message = if (playing || playbackPreparing) "Playback stopped." else null
        audio.stopAsync { saved ->
            if (!memoDeleting) {
                hasMemo = saved
                recorderStopping = false
                if (wasRecording) {
                    message = if (saved) "Voice memo saved."
                        else "The recording was too short or couldn't be saved. Try recording again."
                }
            }
        }
        recording = false
        recorderStarting = false
        playing = false
        playbackPreparing = false
        memoLoading = false
        clearText = false
        discardText = false
        deleteMemo = false
    }
    val policy = rememberToolsAudioSessionPolicy { stopAudio() }
    DisposableEffect(audio) {
        onDispose { audio.release() }
    }
    LaunchedEffect(pageActive) {
        if (pageActive) {
            micGranted = toolsMicrophoneGranted(context)
            if (!recording && !recorderStarting && !recorderStopping && !memoDeleting &&
                !playing && !playbackPreparing) {
                memoLoading = true
                audio.hasMemoAsync { exists ->
                    hasMemo = exists
                    memoLoading = false
                }
            }
        }
    }

    fun beginRecording() {
        val request = policy.begin() ?: return
        // Starting audio supersedes any pending memo snapshot and its callback.
        memoLoading = false
        playing = false
        playbackPreparing = false
        recorderStarting = true
        message = "Starting recording…"
        audio.startRecordingAsync { started ->
            if (policy.accepts(request)) {
                recorderStarting = false
                recording = started
                hasMemo = NotesConfig.hasAudioNote(context)
                message = if (started) null
                    else "The microphone couldn't start. Check microphone access and try again."
            }
        }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = permissionRequest
        permissionRequest = null
        if (request != null && policy.accepts(request)) {
            micGranted = granted
            if (granted) beginRecording()
            else message = "Microphone access is needed to record a voice memo."
        }
    }
    fun requestRecording() {
        policy.begin() ?: return
        micGranted = toolsMicrophoneGranted(context)
        if (micGranted) beginRecording()
        else {
            policy.invalidate()
            audio.stopPlaying()
            playing = false
            playbackPreparing = false
            permissionRequest = policy.begin() ?: return
            message = "Allow microphone access to record a voice memo."
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun togglePlayback() {
        if (playing || playbackPreparing) {
            policy.invalidate()
            audio.stopPlaying()
            playing = false
            playbackPreparing = false
            message = "Playback stopped."
            return
        }
        val request = policy.begin() ?: return
        playbackPreparing = true
        message = "Preparing playback…"
        audio.playAsync(
            onStarted = {
                if (policy.accepts(request)) {
                    playbackPreparing = false
                    playing = true
                    message = null
                }
            },
            onDone = {
                if (policy.accepts(request)) {
                    playbackPreparing = false
                    playing = false
                    message = "Playback ended."
                }
            },
            onError = {
                if (policy.accepts(request)) {
                    playbackPreparing = false
                    playing = false
                    message = "The voice memo couldn't play. Try recording it again."
                }
            },
        )
    }

    ToolsTwoPane(
        left = {
            ToolsPanel("Your message") {
                ToolsField("Note", text, {
                    text = it
                    savedNotice = false
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp), multiline = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ToolsButton("Save note", primary = true, modifier = Modifier.weight(1f)) {
                        NotesConfig.saveText(context, text)
                        savedText = text
                        savedNotice = true
                    }
                    ToolsButton("Clear note", destructive = true,
                        enabled = text.isNotEmpty() || savedText.isNotEmpty(),
                        modifier = Modifier.weight(1f)) { clearText = true }
                }
                if (text != savedText) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        ToolsStatus("Unsaved changes")
                        ToolsButton("Discard changes") { discardText = true }
                    }
                }
                else if (savedNotice) ToolsStatus("Saved", active = true)
            }
        },
        right = {
            ToolsPanel("Voice memo") {
                Surface(
                    color = Color(0xFFF2EEE3),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
                ) {
                    Column(Modifier.padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        Text(
                            when {
                                memoDeleting -> "Deleting voice memo…"
                                recorderStopping -> "Saving your voice memo…"
                                recorderStarting -> "Starting recording…"
                                recording -> "Recording your memo…"
                                playbackPreparing -> "Preparing your voice memo…"
                                playing -> "Playing your voice memo."
                                memoLoading -> "Checking your voice memo…"
                                hasMemo -> "A voice memo is waiting."
                                else -> "Some things are easier to say."
                            },
                            color = Color(0xFF182C39), fontSize = 26.sp, lineHeight = 43.sp,
                        )
                    }
                }
                message?.let { ToolsStatus(it, active = recording || playing) }
                ToolsButton(
                    when {
                        memoDeleting -> "Deleting memo…"
                        recorderStopping -> "Saving memo…"
                        recording || recorderStarting -> "Stop recording"
                        memoLoading -> "Checking memo…"
                        permissionRequest != null -> "Waiting for microphone access…"
                        !micGranted -> "Allow microphone"
                        hasMemo -> "Re-record memo"
                        else -> "Record a memo"
                    },
                    primary = true,
                    enabled = permissionRequest == null && !recorderStopping &&
                        !memoDeleting && (!memoLoading || recording || recorderStarting),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (recording || recorderStarting) {
                        policy.invalidate()
                        stopAudio()
                    } else requestRecording()
                }
                if (hasMemo && !recording && !recorderStarting) {
                    ToolsButton(if (playing || playbackPreparing) "Stop playback" else "Play memo",
                        enabled = !recorderStopping && !memoDeleting && !memoLoading,
                        modifier = Modifier.fillMaxWidth(), onClick = ::togglePlayback)
                    ToolsButton("Delete memo", destructive = true,
                        enabled = !recorderStopping && !memoDeleting && !memoLoading,
                        modifier = Modifier.fillMaxWidth()) { deleteMemo = true }
                    ToolsText("Recording again replaces the saved memo.", secondary = true)
                }
                if (!micGranted) {
                    ToolsText("Allow microphone access before recording. Speak close to this Portal.",
                        secondary = true)
                }
            }
        },
    )
    if (discardText && pageActive) {
        ToolsConfirm("Discard changes?",
            "Your unsaved edits will be discarded. The saved note will stay unchanged.",
            "Discard changes", { discardText = false }) {
            text = savedText
            savedNotice = false
            discardText = false
        }
    }
    if (clearText && pageActive) {
        ToolsConfirm("Clear this note?", "Clear the saved note and the message in this editor?",
            "Clear note", { clearText = false }) {
            NotesConfig.clearText(context)
            text = ""
            savedText = ""
            savedNotice = true
            clearText = false
        }
    }
    if (deleteMemo && pageActive) {
        ToolsConfirm("Delete voice memo?", "The saved voice memo will be removed.", "Delete memo",
            { deleteMemo = false }) {
            policy.invalidate()
            recording = false
            recorderStarting = false
            playing = false
            playbackPreparing = false
            memoDeleting = true
            deleteMemo = false
            message = "Deleting voice memo…"
            audio.deleteMemoAsync { deleted ->
                memoDeleting = false
                recorderStopping = false
                hasMemo = !deleted
                message = if (deleted) "Voice memo deleted."
                    else "The voice memo couldn't be deleted. Try again."
            }
        }
    }
}

internal enum class IntercomMode { IDLE, STARTING_BROADCAST, BROADCAST, CONNECTING, LISTENING }

/** Continuous one-way LAN audio; LanAudio owns all sockets/mics through LanAudioSessions. */
@Composable
internal fun ToolsIntercomContent() {
    val context = LocalContext.current
    val pageActive = LocalApprovedLauncherPageActive.current
    val lan = remember { LanAudio() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var host by rememberSaveable { mutableStateOf("") }
    var mode by remember { mutableStateOf(IntercomMode.IDLE) }
    var broadcastStatus by remember { mutableStateOf("Microphone idle") }
    var listenStatus by remember { mutableStateOf("Same-network connection") }
    var micGranted by remember { mutableStateOf(toolsMicrophoneGranted(context)) }
    var permissionRequest by remember { mutableStateOf<Long?>(null) }
    var runningRequest by remember { mutableStateOf<Long?>(null) }
    var myIp by remember { mutableStateOf(toolsLocalIp(context)) }

    fun stop() {
        runningRequest = null
        lan.stop()
        mode = IntercomMode.IDLE
        broadcastStatus = "Microphone idle"
        listenStatus = "Same-network connection"
    }
    val policy = rememberToolsAudioSessionPolicy { stop() }
    LaunchedEffect(pageActive, permissionRequest) {
        if (pageActive) {
            micGranted = toolsMicrophoneGranted(context)
            myIp = toolsLocalIp(context)
        }
    }
    fun beginBroadcast() {
        val request = policy.begin() ?: return
        stop()
        runningRequest = request
        mode = IntercomMode.STARTING_BROADCAST
        broadcastStatus = "Opening broadcast…"
        lan.startBroadcast { started ->
            handler.post {
                if (policy.accepts(request)) {
                    if (started && lan.isActive) {
                        mode = IntercomMode.BROADCAST
                        // A bound socket is ready for listeners; it does not verify mic delivery.
                        broadcastStatus = "Broadcast open · ready for listeners"
                    } else {
                        mode = IntercomMode.IDLE
                        runningRequest = null
                        broadcastStatus = "Couldn't open the broadcast. Port ${lan.PORT} may be in use."
                    }
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = permissionRequest
        permissionRequest = null
        if (request != null && policy.accepts(request)) {
            micGranted = granted
            if (granted) beginBroadcast()
            else broadcastStatus = "Microphone access is needed to broadcast this room."
        }
    }
    fun requestBroadcast() {
        policy.begin() ?: return
        stop()
        micGranted = toolsMicrophoneGranted(context)
        if (micGranted) beginBroadcast()
        else {
            permissionRequest = policy.begin() ?: return
            broadcastStatus = "Allow microphone access to broadcast this room."
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    fun beginListening() {
        val address = host.trim()
        if (address.isEmpty() || address.any(Char::isWhitespace) || '/' in address) {
            listenStatus = "Enter the other Portal's IP address or hostname."
            return
        }
        val request = policy.begin() ?: return
        stop()
        runningRequest = request
        mode = IntercomMode.CONNECTING
        listenStatus = "Connecting to $address…"
        lan.startListening(address) { connected ->
            handler.post {
                if (policy.accepts(request)) {
                    if (connected && lan.isActive) {
                        mode = IntercomMode.LISTENING
                        listenStatus = "Connected to $address"
                    } else {
                        mode = IntercomMode.IDLE
                        runningRequest = null
                        listenStatus = "Couldn't connect to $address. Check its broadcast and Wi-Fi."
                    }
                }
            }
        }
    }

    // The engine has no terminal UI callback. Observe only a live foreground session,
    // so a later disconnect cannot leave "Connected" displayed indefinitely.
    LaunchedEffect(mode, runningRequest, pageActive) {
        val request = runningRequest ?: return@LaunchedEffect
        val wasBroadcast = mode == IntercomMode.BROADCAST
        if (!wasBroadcast && mode != IntercomMode.LISTENING) return@LaunchedEffect
        while (policy.accepts(request)) {
            delay(500)
            if (policy.accepts(request) && !lan.isActive) {
                policy.invalidate()
                stop()
                if (wasBroadcast) broadcastStatus = "Broadcast ended. Start again when you're ready."
                else listenStatus = "Connection ended. Check the other Portal and try Listen again."
                break
            }
        }
    }

    val broadcasting = mode == IntercomMode.BROADCAST
    val broadcastBusy = broadcasting || mode == IntercomMode.STARTING_BROADCAST ||
        permissionRequest != null
    val listening = mode == IntercomMode.LISTENING
    val listenBusy = listening || mode == IntercomMode.CONNECTING
    ToolsTwoPane(
        left = {
            ToolsPanel("Broadcast this room") {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 320.dp)
                        .padding(horizontal = 32.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                ) {
                    ApprovedIcon("megaphone", Modifier.size(64.dp),
                        LocalApprovedPalette.current.accent)
                    ToolsText(if (broadcasting) "Broadcasting" else "Let the house hear you")
                    Text("Send this Portal's microphone to another Portal on your local network.",
                        color = LocalApprovedPalette.current.secondary,
                        fontSize = 22.sp, lineHeight = 33.sp, textAlign = TextAlign.Center)
                }
                ToolsButton(
                    when {
                        permissionRequest != null -> "Waiting for microphone access…"
                        broadcastBusy -> "Stop broadcasting"
                        !micGranted -> "Allow microphone"
                        else -> "Start broadcasting"
                    }, primary = true, enabled = permissionRequest == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (broadcastBusy) {
                        policy.invalidate()
                        stop()
                    } else requestBroadcast()
                }
                ToolsStatus(broadcastStatus, active = broadcasting)
                ToolsText("Keep this tool open while the other Portal listens.", secondary = true)
            }
        },
        right = {
            ToolsPanel("Listen to another Portal") {
                ToolsField("Other Portal's address", host, { host = it },
                    modifier = Modifier.fillMaxWidth())
                ToolsButton(
                    if (listenBusy) "Stop listening" else "Listen",
                    primary = true, modifier = Modifier.fillMaxWidth(),
                ) {
                    if (listenBusy) {
                        policy.invalidate()
                        stop()
                    } else beginListening()
                }
                ToolsText(if (myIp.isNotEmpty()) "This Portal's address: $myIp"
                    else "This Portal's address is unavailable. Check its Wi-Fi connection.",
                    secondary = true)
                ToolsStatus(listenStatus, active = listening)
                ToolsText("Start broadcasting on the other Portal first. Leaving this tool stops audio.",
                    secondary = true)
            }
        },
    )
}

/** The entire bundled library stays in this workspace; picking a story never starts speech. */
@Composable
internal fun ToolsStoriesContent() {
    var selected by rememberSaveable { mutableStateOf(-1) }
    val story = Stories.ALL.getOrNull(selected)
    if (story == null) {
        ToolsPanel("Pick a story", Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            ToolsText("Read together, or let the Portal read aloud. These stories work without internet.",
                secondary = true)
            Stories.ALL.forEachIndexed { index, entry ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 108.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToolsText("${entry.emoji}  ${entry.title}")
                        ToolsText("Bundled short story", secondary = true)
                    }
                    ToolsButton("Read", primary = true) { selected = index }
                }
            }
        }
    } else {
        key(selected) { ToolsStoryReader(story, onBack = { selected = -1 }) }
    }
}

@Composable
private fun ToolsStoryReader(story: Stories.Story, onBack: () -> Unit) {
    val context = LocalContext.current
    val pageActive = LocalApprovedLauncherPageActive.current
    val narrator = remember(context) { ToolsStoryNarrator(context.applicationContext) }
    val policy = rememberToolsAudioSessionPolicy { narrator.stop() }
    DisposableEffect(narrator) {
        narrator.initialize()
        onDispose { narrator.release() }
    }
    fun backToLibrary() {
        policy.invalidate()
        narrator.stop()
        onBack()
    }
    BackHandler(enabled = pageActive, onBack = ::backToLibrary)

    Column(
        Modifier.widthIn(max = 1000.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ToolsButton("‹ All stories", modifier = Modifier.weight(1f), onClick = ::backToLibrary)
            ToolsButton(
                if (narrator.phase != ToolsNarrationPhase.IDLE) "Stop reading" else "Read aloud",
                primary = true, enabled = narrator.ready, modifier = Modifier.weight(1f),
            ) {
                if (narrator.phase != ToolsNarrationPhase.IDLE) narrator.stop()
                else narrator.read(story, policy)
            }
        }
        ToolsStatus(narrator.message, active = narrator.phase == ToolsNarrationPhase.SPEAKING)
        if (narrator.failed) {
            ToolsButton("Retry narration") { narrator.initialize() }
        }
        ToolsText("Aesop & traditional tales · bundled on your Portal", secondary = true)
        Text("${story.emoji}  ${story.title}", color = LocalApprovedPalette.current.text,
            fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
        story.paragraphs.forEach { paragraph ->
            Text(paragraph, color = LocalApprovedPalette.current.text,
                fontSize = 26.sp, lineHeight = 43.sp)
        }
    }
}

private enum class ToolsNarrationPhase { IDLE, STARTING, SPEAKING }

/** Engine readiness and utterance state come from Android results, never from the button alone. */
private class ToolsStoryNarrator(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var engineGeneration = 0L
    private var released = false
    private var utterance: String? = null
    private var readingPolicy: ToolsAudioSessionPolicy? = null
    private var readingRequest: Long? = null
    var ready by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var phase by mutableStateOf(ToolsNarrationPhase.IDLE)
        private set
    var message by mutableStateOf("Preparing narration…")
        private set

    fun initialize() {
        if (released) return
        stop()
        val generation = ++engineGeneration
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
        failed = false
        message = "Preparing narration…"
        runCatching {
            engine = TextToSpeech(context) { status ->
                handler.post {
                    if (!released && generation == engineGeneration) {
                        val current = engine
                        if (status != TextToSpeech.SUCCESS || current == null) {
                            initializationFailed("Narration is unavailable. Check this Portal's speech engine.")
                        } else {
                            runCatching {
                                val language = current.setLanguage(Locale.getDefault())
                                if (language < TextToSpeech.LANG_AVAILABLE) {
                                    initializationFailed("A voice for this Portal's language is unavailable.")
                                } else if (current.setSpeechRate(0.85f) == TextToSpeech.ERROR ||
                                    current.setOnUtteranceProgressListener(progressListener) == TextToSpeech.ERROR) {
                                    initializationFailed("Narration couldn't be prepared. Try again.")
                                } else {
                                    ready = true
                                    message = "Ready to read aloud"
                                }
                            }.onFailure {
                                initializationFailed("Narration couldn't be prepared. Try again.")
                            }
                        }
                    }
                }
            }
        }.onFailure {
            initializationFailed("Narration is unavailable. Check this Portal's speech engine.")
        }
    }

    private fun initializationFailed(reason: String) {
        ready = false
        failed = true
        message = reason
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = publish(utteranceId) {
            phase = ToolsNarrationPhase.SPEAKING
            message = "Reading aloud"
        }
        override fun onDone(utteranceId: String?) = publish(utteranceId) {
            finish("Finished reading")
        }
        @Deprecated("Android compatibility callback")
        override fun onError(utteranceId: String?) = narrationError(utteranceId)
        override fun onError(utteranceId: String?, errorCode: Int) = narrationError(utteranceId)
        override fun onStop(utteranceId: String?, interrupted: Boolean) = publish(utteranceId) {
            finish("Narration stopped")
        }
    }

    private fun narrationError(id: String?) = publish(id) {
        ready = false
        failed = true
        finish("Narration couldn't finish. Retry narration or keep reading.")
    }

    private fun publish(id: String?, update: () -> Unit) {
        handler.post {
            val request = readingRequest
            if (!released && id != null && id == utterance &&
                request != null && readingPolicy?.accepts(request) == true) update()
        }
    }

    private fun finish(status: String) {
        utterance = null
        readingRequest = null
        readingPolicy = null
        phase = ToolsNarrationPhase.IDLE
        message = status
    }

    fun read(story: Stories.Story, policy: ToolsAudioSessionPolicy) {
        val current = engine ?: return
        if (!ready || released) return
        stop()
        val request = policy.begin() ?: return
        readingPolicy = policy
        readingRequest = request
        val id = "tools-story-$engineGeneration-$request"
        utterance = id
        phase = ToolsNarrationPhase.STARTING
        message = "Starting narration…"
        val result = runCatching {
            current.speak(story.title + ". " + story.paragraphs.joinToString(" "),
                TextToSpeech.QUEUE_FLUSH, null, id)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result == TextToSpeech.ERROR) {
            ready = false
            failed = true
            finish("Narration couldn't start. Retry narration or keep reading.")
        }
    }

    fun stop() {
        readingPolicy?.invalidate()
        utterance = null
        readingRequest = null
        readingPolicy = null
        val wasReading = phase != ToolsNarrationPhase.IDLE
        phase = ToolsNarrationPhase.IDLE
        runCatching { engine?.stop() }
        if (wasReading) message = "Narration stopped"
    }

    fun release() {
        released = true
        engineGeneration++
        stop()
        runCatching { engine?.shutdown() }
        engine = null
        ready = false
    }
}

private fun toolsMicrophoneGranted(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Suppress("DEPRECATION")
private fun toolsLocalIp(context: Context): String = runCatching {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val address = wifi?.connectionInfo?.ipAddress ?: 0
    if (address == 0) "" else listOf(0, 8, 16, 24).joinToString(".") {
        ((address shr it) and 0xff).toString()
    }
}.getOrDefault("")

/** A retained hidden tab has a CREATED owner; either boundary immediately retires foreground work. */
@Composable
private fun rememberToolsAudioSessionPolicy(onInactive: () -> Unit): ToolsAudioSessionPolicy {
    val owner = LocalLifecycleOwner.current
    val pageActive = LocalApprovedLauncherPageActive.current
    val stop by rememberUpdatedState(onInactive)
    val policy = remember { ToolsAudioSessionPolicy() }
    DisposableEffect(owner, pageActive, policy) {
        fun refresh() {
            val foreground = pageActive && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            policy.updateForeground(foreground)
            if (!foreground) stop()
        }
        val observer = LifecycleEventObserver { _, _ -> refresh() }
        owner.lifecycle.addObserver(observer)
        refresh()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            policy.updateForeground(false)
            stop()
        }
    }
    DisposableEffect(policy) {
        onDispose {
            policy.dispose()
            stop()
        }
    }
    return policy
}

/** Foreground-only requests; stopping or replacing a request retires its callbacks. */
internal class ToolsAudioSessionPolicy {
    private var foreground = false
    private var disposed = false
    private var generation = 0L

    fun updateForeground(active: Boolean) {
        if (foreground && !active) invalidate()
        foreground = active && !disposed
    }

    fun begin(): Long? = if (foreground && !disposed) ++generation else null

    fun accepts(request: Long): Boolean =
        foreground && !disposed && request == generation

    fun invalidate() {
        generation++
    }

    fun dispose() {
        invalidate()
        foreground = false
        disposed = true
    }
}
