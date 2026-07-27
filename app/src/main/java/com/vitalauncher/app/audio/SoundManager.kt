package com.vitalauncher.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.vitalauncher.app.R

/**
 * All of the launcher's sound effects and background music, as one process-wide singleton — a
 * [SoundPool] for the short one-shot UI sounds (low-latency, several can overlap) and a looping
 * [MediaPlayer] for the menu music bed. [onActivityResume]/[onActivityPause] tie the music to the
 * activity lifecycle: it pauses whenever another app comes to the foreground and picks back up
 * when the user returns, and [notifyAppLaunched] both plays the launch chime and arms the one-shot
 * "returned home" chime for the next resume.
 */
object SoundManager {

    private var soundPool: SoundPool? = null
    private var menuMusic: MediaPlayer? = null

    private var editModeId = 0
    private var exitEditModeId = 0
    private var itemTapId = 0
    private var launchAppId = 0
    private var returnHomeId = 0
    private var pageSwipeId = 0

    /** Set right before an app is launched; consumed on the next [onActivityResume] so the "back
     * home" chime plays only when actually returning from that launch, not on cold start. */
    private var awaitingReturnHome = false

    /** Both play at full volume — the source recordings themselves are mixed so SFX already cut
     * through the music clearly, so no extra in-app ducking is needed on top of that. */
    private const val SFX_VOLUME = 1f
    private const val MUSIC_VOLUME = 1f

    fun init(context: Context) {
        if (soundPool != null) return
        val appContext = context.applicationContext

        val pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // USAGE_GAME routes through the media volume stream (same as the music
                    // MediaPlayer below) rather than the separate, often much-quieter "system
                    // sounds" stream ASSISTANCE_SONIFICATION used to map to — that mismatch was
                    // why turning the in-app gain up didn't make the SFX any louder.
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        soundPool = pool
        editModeId = pool.load(appContext, R.raw.edit_mode, 1)
        exitEditModeId = pool.load(appContext, R.raw.exit_edit_mode, 1)
        itemTapId = pool.load(appContext, R.raw.item_tap, 1)
        launchAppId = pool.load(appContext, R.raw.launch_app, 1)
        returnHomeId = pool.load(appContext, R.raw.return_home, 1)
        pageSwipeId = pool.load(appContext, R.raw.page_swipe, 1)

        menuMusic = MediaPlayer.create(appContext, R.raw.menu_music)?.apply {
            isLooping = true
            setVolume(MUSIC_VOLUME, MUSIC_VOLUME)
        }
    }

    fun playEditMode() = play(editModeId)
    fun playExitEditMode() = play(exitEditModeId)
    fun playItemTap() = play(itemTapId)
    fun playPageSwipe() = play(pageSwipeId)

    /** Call at the moment an app is actually launched (not when a folder opens). */
    fun notifyAppLaunched() {
        play(launchAppId)
        awaitingReturnHome = true
    }

    /** Resumes the menu music and, if the user is returning from a launched app rather than just
     * cold-starting, plays the "back home" chime. */
    fun onActivityResume() {
        if (awaitingReturnHome) {
            awaitingReturnHome = false
            play(returnHomeId)
        }
        menuMusic?.let { if (!it.isPlaying) it.start() }
    }

    fun onActivityPause() {
        menuMusic?.let { if (it.isPlaying) it.pause() }
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        menuMusic?.release()
        menuMusic = null
    }

    private fun play(soundId: Int) {
        if (soundId == 0) return
        soundPool?.play(soundId, SFX_VOLUME, SFX_VOLUME, 1, 0, 1f)
    }
}
