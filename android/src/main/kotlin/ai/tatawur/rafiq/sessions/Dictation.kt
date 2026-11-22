package ai.tatawur.rafiq.sessions

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * On-device dictation for the composer, or null when this phone cannot recognise speech on the
 * device. Audio never leaves the phone: there is no fallback to a network recogniser, so without
 * on-device support the mic is simply not shown.
 *
 * The microphone permission is requested the first time you tap the mic, not before.
 *
 * @param onText receives each final transcript.
 */
@Composable
fun rememberDictation(onText: (String) -> Unit): DictationControl? {
    val inspecting = LocalInspectionMode.current
    val context = LocalContext.current
    val available = remember(context) { !inspecting && SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
    if (!available) return null

    var listening by remember { mutableStateOf(false) }
    val latestOnText by rememberUpdatedState(onText)
    val recognizer = remember(context) { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
    DisposableEffect(recognizer) {
        recognizer.setRecognitionListener(
            transcriptListener(
                onText = { latestOnText(it) },
                onStopped = { listening = false },
            ),
        )
        onDispose { recognizer.destroy() }
    }

    val start = {
        recognizer.startListening(dictationIntent())
        listening = true
    }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) start()
        }
    return DictationControl(listening) {
        when {
            listening -> {
                recognizer.stopListening()
                listening = false
            }

            context.hasMicrophonePermission() -> {
                start()
            }

            else -> {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

private fun Context.hasMicrophonePermission(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun dictationIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

/** Passes the final transcript on and reports when listening ended, for any reason. */
private fun transcriptListener(
    onText: (String) -> Unit,
    onStopped: () -> Unit,
): RecognitionListener =
    object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            onStopped()
            results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.let(onText)
        }

        override fun onError(error: Int) {
            // No match, silence or a busy recogniser: the mic just stops, and you can tap it again.
            onStopped()
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }
