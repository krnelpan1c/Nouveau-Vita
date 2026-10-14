package com.vitalauncher.app.audio

import android.content.Context
import android.net.Uri
import java.io.File
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

    /** Both default to full volume — the source recordings themselves are mixed so SFX already
     * cut through the music clearly. The user can rebalance them from Customize > Audio Mix. */
    private var sfxVolume = 1f
    private var musicVolume = 1f

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

        sfxVolume = prefs(appContext).getFloat("sfx_volume", 1f)
        musicVolume = prefs(appContext).getFloat("music_volume", 1f)
        menuMusic = buildMusicPlayer(appContext)
    }


    private fun customMusicFile(context: Context) = File(context.filesDir, "custom_music")

    private fun prefs(context: Context) =
        context.getSharedPreferences("sound_prefs", Context.MODE_PRIVATE)

    fun getMusicVolume(): Float = musicVolume
    fun getSfxVolume(): Float = sfxVolume

    fun setMusicVolume(context: Context, value: Float) {
        musicVolume = value.coerceIn(0f, 1f)
        menuMusic?.setVolume(musicVolume, musicVolume)
        prefs(context).edit().putFloat("music_volume", musicVolume).apply()
    }

    fun setSfxVolume(context: Context, value: Float) {
        sfxVolume = value.coerceIn(0f, 1f)
        prefs(context).edit().putFloat("sfx_volume", sfxVolume).apply()
    }

    /** Plays a sample effect so the user can hear the SFX level while adjusting it. */
    fun playSfxPreview() = play(itemTapId)

    /** True when the user has chosen their own audio file for the menu music. */
    fun isCustomMusic(context: Context): Boolean =
        prefs(context).getBoolean("use_custom_music", false) && customMusicFile(context).exists()

    /** Display name of the custom track, or null when none is set. */
    fun customMusicName(context: Context): String? =
        if (customMusicFile(context).exists()) prefs(context).getString("custom_music_name", null) else null

    private fun buildMusicPlayer(context: Context): MediaPlayer? {
        val player = if (isCustomMusic(context)) {
            try {
                MediaPlayer().apply {
                    setDataSource(customMusicFile(context).absolutePath)
                    prepare()
                }
            } catch (e: Exception) {
                MediaPlayer.create(context, R.raw.menu_music)
            }
        } else {
            MediaPlayer.create(context, R.raw.menu_music)
        }
        return player?.apply {
            isLooping = true
            setVolume(musicVolume, musicVolume)
        }
    }

    private fun swapPlayer(context: Context) {
        val old = menuMusic
        old?.release()
        menuMusic = buildMusicPlayer(context)
        menuMusic?.start()
    }

    /** Switches to the previously added custom track, if one exists. */
    fun useCustomMusic(context: Context) {
        if (!customMusicFile(context).exists()) return
        prefs(context).edit().putBoolean("use_custom_music", true).apply()
        swapPlayer(context.applicationContext)
    }

    /** Switches back to the bundled menu music. */
    fun useDefaultMusic(context: Context) {
        prefs(context).edit().putBoolean("use_custom_music", false).apply()
        swapPlayer(context.applicationContext)
    }

    /** Copies the chosen audio [uri] into app storage and makes it the menu music.
     * Returns false if the file couldn't be read or played. */
    fun setCustomMusic(context: Context, uri: Uri, displayName: String): Boolean {
        val appContext = context.applicationContext
        val target = customMusicFile(appContext)
        return try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return false
            // Validate that it is actually playable before committing.
            MediaPlayer().apply {
                setDataSource(target.absolutePath)
                prepare()
                release()
            }
            prefs(appContext).edit()
                .putBoolean("use_custom_music", true)
                .putString("custom_music_name", displayName)
                .apply()
            swapPlayer(appContext)
            true
        } catch (e: Exception) {
            target.delete()
            false
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
        soundPool?.play(soundId, sfxVolume, sfxVolume, 1, 0, 1f)
    }
}
