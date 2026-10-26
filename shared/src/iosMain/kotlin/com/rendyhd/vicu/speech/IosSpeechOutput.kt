package com.rendyhd.vicu.speech

import kotlinx.cinterop.ObjCSignatureOverride
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.setActive
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * The device voice (`AVSpeechSynthesizer`), created on the first [speak]. Everything runs on the
 * main thread, [onDone] callbacks too, once each, when the text was spoken or stopped. The audio
 * session is switched to playback for speaking (the recorder leaves it in record mode) and given
 * back afterwards.
 *
 * The synthesizer's delegate is an Objective-C subclass, so it is built here by hand and held
 * strongly (the synthesizer's reference is weak), never by Koin (CLAUDE.md, gotcha 18).
 */
class IosSpeechOutput : SpeechOutput {
    private var synthesizer: AVSpeechSynthesizer? = null
    private var delegate: Delegate? = null
    private val callbacks = mutableMapOf<AVSpeechUtterance, () -> Unit>()

    override val isAvailable: Boolean = true

    override fun speak(text: String, languageTag: String?, onDone: () -> Unit) {
        onMain {
            if (text.isBlank()) {
                onDone()
                return@onMain
            }
            val synth = synthesizer ?: AVSpeechSynthesizer().also { created ->
                val listener = Delegate(::finished)
                created.delegate = listener
                delegate = listener
                synthesizer = created
            }
            // One answer at a time: a new one replaces what is being said.
            if (synth.speaking) synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            val session = AVAudioSession.sharedInstance()
            session.setCategory(
                AVAudioSessionCategoryPlayback,
                mode = AVAudioSessionModeSpokenAudio,
                options = AVAudioSessionCategoryOptionDuckOthers,
                error = null,
            )
            session.setActive(true, error = null)
            val utterance = AVSpeechUtterance(string = text)
            utterance.voice = languageTag?.takeIf { it.isNotBlank() }?.let { AVSpeechSynthesisVoice.voiceWithLanguage(it) }
                ?: AVSpeechSynthesisVoice.voiceWithLanguage(AVSpeechSynthesisVoice.currentLanguageCode())
            callbacks[utterance] = onDone
            synth.speakUtterance(utterance)
        }
    }

    override fun stop() {
        onMain {
            synthesizer?.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            // Cancelled utterances report through the delegate; anything it misses ends here.
            val left = callbacks.values.toList()
            callbacks.clear()
            releaseSession()
            left.forEach { it() }
        }
    }

    private fun finished(utterance: AVSpeechUtterance) {
        val done = callbacks.remove(utterance) ?: return
        if (callbacks.isEmpty()) releaseSession()
        done()
    }

    private fun releaseSession() {
        AVAudioSession.sharedInstance().setActive(
            false,
            withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation,
            error = null,
        )
    }

    private fun onMain(block: () -> Unit) {
        dispatch_async(dispatch_get_main_queue(), block)
    }

    private class Delegate(private val onFinished: (AVSpeechUtterance) -> Unit) :
        NSObject(), AVSpeechSynthesizerDelegateProtocol {

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
            onFinished(didFinishSpeechUtterance)
        }

        @ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
            onFinished(didCancelSpeechUtterance)
        }
    }
}
