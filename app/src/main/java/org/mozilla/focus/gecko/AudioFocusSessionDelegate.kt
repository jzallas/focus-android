package org.mozilla.focus.gecko

import android.content.Context
import android.media.AudioManager
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.MediaSession
import java.lang.ref.WeakReference

class AudioFocusSessionDelegate(context: Context): MediaSession.Delegate, AudioManager.OnAudioFocusChangeListener {

  var shouldAllowAudioFocus = false

  private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

  private var weakSession: WeakReference<MediaSession?> = WeakReference(null)

  private var mediaSession: MediaSession?
    get() = weakSession.get()?.takeIf { it.isActive }
    set(value) {
      weakSession = WeakReference(value)
    }

  private var resumeOnFocusGain = false
  private var playbackDelayed = false

  private val focusRequest by lazy {
    val attributes = AudioAttributesCompat.Builder()
      .setUsage(AudioAttributesCompat.USAGE_MEDIA)
      .setContentType(AudioAttributesCompat.CONTENT_TYPE_MUSIC)
      .build()

    AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
      .setAudioAttributes(attributes)
      .setOnAudioFocusChangeListener(this)
      .setWillPauseWhenDucked(false)
      .build()
  }

  private fun pause(temporary: Boolean = false) {
    resumeOnFocusGain = temporary
    mediaSession?.pause()
  }

  private fun play(delayed: Boolean = false) {
    playbackDelayed = delayed
    if (delayed) {
      // pause for now and play when focus change tells we are good to go
      pause()
    } else {
      mediaSession?.play()
    }
  }

  private fun reset() {
    resumeOnFocusGain = false
    playbackDelayed = false
  }

  override fun onPlay(geckoSession: GeckoSession, mediaSession: MediaSession) {
    if (!shouldAllowAudioFocus) return

    this.mediaSession = mediaSession

    synchronized(this) {
      reset()
      when (AudioManagerCompat.requestAudioFocus(audioManager, focusRequest)) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> return // session is already playing -- no action needed
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> play(delayed = true)
        else -> pause() // all other scenarios, stop playback for now
      }
    }
  }

  override fun onStop(geckoSession: GeckoSession, mediaSession: MediaSession) {
    synchronized(this) {
      // be a good citizen and give back the audio focus
      AudioManagerCompat.abandonAudioFocusRequest(audioManager, focusRequest)
      reset()
    }
  }

  override fun onAudioFocusChange(focusChange: Int) {
    synchronized(this) {
      when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> if (playbackDelayed || resumeOnFocusGain) play()
        AudioManager.AUDIOFOCUS_LOSS -> pause()
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause(temporary = true)
      }
    }
  }
}
