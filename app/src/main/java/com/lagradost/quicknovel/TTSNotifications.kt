package com.lagradost.quicknovel

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media.session.MediaButtonReceiver
import androidx.preference.PreferenceManager
import com.lagradost.quicknovel.mvvm.logError

object TTSNotifications {
    // Bumped from the original "QuickNovelTTS" (IMPORTANCE_DEFAULT) so the new silent
    // IMPORTANCE_LOW settings take effect on devices that already created the old channel.
    private const val TTS_CHANNEL_ID = "QuickNovelTTSPlayback"
    private const val TTS_CHANNEL_ID_LEGACY = "QuickNovelTTS"
    const val TTS_NOTIFICATION_ID = 133742

    private var hasCreateedNotificationChannel = false

    // Current "now playing" info, kept so any re-render (line change, play/pause, chapter change)
    // shows the same content. lineText = line currently read (title), upcomingText = next line (grey).
    private var coverBitmap: Bitmap? = null // the novel cover, regardless of the toggle
    private var poster: Bitmap? = null      // effective cover shown (null when the toggle is off)
    private var bookTitle: String = ""
    private var lineText: String = ""
    private var upcomingText: String? = null

    /** User setting: whether to show the novel cover in the read-aloud notification / media controls. */
    private fun showCoverEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(context.getString(R.string.tts_show_cover_key), true)

    /** Recompute the effective [poster] from the setting so the toggle takes effect live. */
    private fun refreshCover(context: Context?) {
        poster = if (context != null && showCoverEnabled(context)) coverBitmap else null
    }

    private fun createNotificationChannel(context: Context) {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.text_to_speech)
            val descriptionText = context.getString(R.string.text_to_speech_channel_description)
            // LOW = ongoing playback notification with no sound/vibration on post.
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(
                TTS_CHANNEL_ID,
                name,
                importance
            ).apply {
                description = descriptionText
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // Remove the old noisy channel so users stop hearing the start sound.
            notificationManager.deleteNotificationChannel(TTS_CHANNEL_ID_LEGACY)
            notificationManager.createNotificationChannel(channel)
        }
    }

    var mediaSession: MediaSessionCompat? = null

    fun setMediaSession(viewModel: ReadActivityViewModel, book: AbstractBook, context: Context) {
        coverBitmap = book.poster()
        refreshCover(context)
        bookTitle = book.title()
        lineText = book.title() // seed until the first line is spoken
        upcomingText = null

        val mbrIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(
            context,
            PlaybackStateCompat.ACTION_PLAY_PAUSE
        )

        mediaSession = MediaSessionCompat(
            context,
            "TTS",
            ComponentName(context, MediaButtonReceiver::class.java),
            mbrIntent
        ).apply {
            setCallback(
                object : MediaSessionCompat.Callback() {
                    override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                        val keyEvent =
                            mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as KeyEvent?
                        if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) { // NO DOUBLE SKIP
                            when (keyEvent.keyCode) {
                                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                                    viewModel.pausePlayTTS()
                                }

                                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                    viewModel.pauseTTS()
                                }

                                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                    viewModel.playTTS()
                                }

                                KeyEvent.KEYCODE_MEDIA_STOP -> {
                                    viewModel.stopTTS()
                                }

                                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                                    viewModel.forwardsTTS()
                                }

                                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                    viewModel.backwardsTTS()
                                }

                                else -> return super.onMediaButtonEvent(mediaButtonEvent)
                            }
                            return true
                        }

                        return super.onMediaButtonEvent(mediaButtonEvent)
                    }
                }
            )

            // Required so the OS routes hardware / Bluetooth media buttons to this session and so
            // the system media notification renders transport controls. Without an active session
            // carrying a PlaybackState, button routing is unreliable and the controls don't show.
            @Suppress("DEPRECATION")
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setPlaybackState(buildPlaybackState(TTSHelper.TTSStatus.IsRunning))
            isActive = true
        }
        applyMetadata()
    }

    /** The currently read line becomes the media title; the upcoming line the (greyed) subtitle. */
    fun setNowPlaying(current: String, upcoming: String?) {
        lineText = current
        upcomingText = upcoming?.takeIf { it.isNotBlank() }
    }

    /**
     * Update the now-playing line as smoothly as the platform allows.
     *
     * Notifications cannot be app-animated, but the Android 13+ system media UI reads its text live
     * from the session metadata and applies its own fade when it changes. So on 13+ we update only
     * the metadata (no full notification re-post → no flash, native transition). On older versions
     * the text lives in the notification body, so we must re-post it (no animation is possible there).
     */
    fun updateNowPlaying(
        current: String,
        upcoming: String?,
        status: TTSHelper.TTSStatus,
        context: Context?
    ) {
        setNowPlaying(current, upcoming)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            refreshCover(context)
            applyMetadata()
        } else {
            notify(status, context)
        }
    }

    /**
     * Push [lineText]/[upcomingText]/[poster] into the media session metadata. On Android 13+ the
     * system media UI is built from this metadata (title bold, artist/subtitle greyed underneath).
     */
    private fun applyMetadata() {
        val session = mediaSession ?: return
        val title = lineText.ifBlank { bookTitle }
        val builder = MediaMetadataCompat.Builder()
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, upcomingText ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, upcomingText ?: "")
        // https://stackoverflow.com/questions/72750099/android-mediastyle-notification-image-largeicon-is-pixilated
        poster?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(builder.build())
    }

    /** Actions this session advertises + the play/pause/stop state derived from [status]. */
    private fun buildPlaybackState(status: TTSHelper.TTSStatus): PlaybackStateCompat {
        val (state, speed) = when (status) {
            TTSHelper.TTSStatus.IsRunning -> PlaybackStateCompat.STATE_PLAYING to 1f
            TTSHelper.TTSStatus.IsPaused -> PlaybackStateCompat.STATE_PAUSED to 0f
            TTSHelper.TTSStatus.IsStopped -> PlaybackStateCompat.STATE_STOPPED to 0f
        }
        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, speed)
            .build()
    }

    /** Keep the session's PlaybackState and active flag in sync with the current TTS status. */
    private fun updatePlaybackState(status: TTSHelper.TTSStatus) {
        val session = mediaSession ?: return
        session.setPlaybackState(buildPlaybackState(status))
        session.isActive = status != TTSHelper.TTSStatus.IsStopped
    }

    fun releaseMediaSession() {
        mediaSession?.release()
        mediaSession = null
    }

    fun createNotification(
        status: TTSHelper.TTSStatus,
        context: Context?
    ): Notification? {
        if (context == null) return null

        // Sync the media session first so system media controls and hardware/Bluetooth buttons
        // reflect (and route to) the current play/pause state and now-playing text on every update.
        refreshCover(context)
        updatePlaybackState(status)
        applyMetadata()

        if (status == TTSHelper.TTSStatus.IsStopped) {
            NotificationManagerCompat.from(context).cancel(TTS_NOTIFICATION_ID)
            return null
        }

        if (!hasCreateedNotificationChannel) {
            hasCreateedNotificationChannel = true
            createNotificationChannel(context)
        }
        // Title = line currently being read; text = upcoming line (shown greyed beneath by the
        // system template / media UI). Subtext keeps the book title for context.
        val contentTitle = lineText.ifBlank { bookTitle }.ifBlank { context.getString(R.string.app_name) }
        val builder = NotificationCompat.Builder(context, TTS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_volume_up_24)
            .setContentTitle(contentTitle)
            .setContentText(upcomingText)
            .setSubText(bookTitle.takeIf { it.isNotBlank() })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)

        poster?.let { builder.setLargeIcon(it) }

        val cancelButton = MediaButtonReceiver.buildMediaButtonPendingIntent(
            context,
            PlaybackStateCompat.ACTION_STOP
        )
        val style = androidx.media.app.NotificationCompat.MediaStyle()
        mediaSession?.sessionToken?.let { token ->
            style.setShowCancelButton(true).setShowActionsInCompactView(1, 2)
                .setCancelButtonIntent(cancelButton)
                .setMediaSession(token)
        }

        builder.setStyle(style)

        val actionPlay = NotificationCompat.Action(
            R.drawable.ic_baseline_play_arrow_24,
            "Resume",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_PLAY
            )
        )

        val actionStop = NotificationCompat.Action(
            R.drawable.ic_baseline_stop_24,
            "Stop",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_STOP
            )
        )

        val actionPause = NotificationCompat.Action(
            R.drawable.ic_baseline_pause_24,
            "Pause",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_PAUSE
            )
        )

        val actionRewind = NotificationCompat.Action(
            R.drawable.ic_baseline_fast_rewind_24,
            "Rewind",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_REWIND
            )
        )

        val actionFastForward = NotificationCompat.Action(
            R.drawable.ic_baseline_fast_forward_24,
            "Fast Forward",
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                context,
                PlaybackStateCompat.ACTION_FAST_FORWARD
            )
        )

        when (status) {
            TTSHelper.TTSStatus.IsRunning -> {
                builder.addAction(actionRewind)
                builder.addAction(actionStop)
                builder.addAction(actionPause)
                builder.addAction(actionFastForward)
            }

            TTSHelper.TTSStatus.IsPaused -> {
                builder.addAction(actionRewind)
                builder.addAction(actionStop)
                builder.addAction(actionPlay)
                builder.addAction(actionFastForward)
            }

            else -> {
                // unreachable
            }
        }

        /*val actionTypes: MutableList<TTSHelper.TTSActionType> = ArrayList()

        if (status == TTSHelper.TTSStatus.IsPaused) {
            actionTypes.add(TTSHelper.TTSActionType.Resume)
        } else if (status == TTSHelper.TTSStatus.IsRunning) {
            actionTypes.add(TTSHelper.TTSActionType.Pause)
        }
        actionTypes.add(TTSHelper.TTSActionType.Stop)

        for ((index, i) in actionTypes.withIndex()) {
            val resultIntent = Intent(context, TTSPauseService::class.java)
            resultIntent.putExtra("id", i.ordinal)

            val pending: PendingIntent = PendingIntent.getService(
                context, 3337 + index,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_MUTABLE else 0
            )

            builder.addAction(
                NotificationCompat.Action(
                    when (i) {
                        TTSHelper.TTSActionType.Resume -> R.drawable.ic_baseline_play_arrow_24
                        TTSHelper.TTSActionType.Pause -> R.drawable.ic_baseline_pause_24
                        TTSHelper.TTSActionType.Stop -> R.drawable.ic_baseline_stop_24
                        else -> return null
                    }, when (i) {
                        TTSHelper.TTSActionType.Resume -> "Resume"
                        TTSHelper.TTSActionType.Pause -> "Pause"
                        TTSHelper.TTSActionType.Stop -> "Stop"
                        else -> return null
                    }, pending
                )
            )
        }*/
        return builder.build()
    }

    fun notify(
        status: TTSHelper.TTSStatus,
        context: Context?
    ) {
        if (context == null) return
        val notification = createNotification(status, context) ?: return

        with(NotificationManagerCompat.from(context)) {
            // notificationId is a unique int for each notification that you must define
            try {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }

                notify(TTS_NOTIFICATION_ID, notification)
            } catch (t: Throwable) {
                logError(t)
            }
        }
    }
}