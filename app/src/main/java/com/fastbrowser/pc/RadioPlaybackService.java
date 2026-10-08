package com.fastbrowser.pc;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public class RadioPlaybackService extends MediaSessionService {
    public static final String ACTION_PLAY = "com.fastbrowser.pc.RADIO_PLAY";
    public static final String ACTION_STOP = "com.fastbrowser.pc.RADIO_STOP";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_HEADERS = "headers";
    private ExoPlayer player;
    private MediaSession mediaSession;

    @Override public void onCreate() {
        super.onCreate();
        AudioAttributes aa = new AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA).build();
        player = new ExoPlayer.Builder(this).build();
        player.setAudioAttributes(aa, true);
        player.setHandleAudioBecomingNoisy(true);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean isPlaying) {
                if (!isPlaying && player.getPlaybackState() == Player.STATE_ENDED) stopSelf();
            }
        });
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 10, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        mediaSession = new MediaSession.Builder(this, player).setSessionActivity(pi).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopRadio();
            } else if (ACTION_PLAY.equals(action)) {
                String url = intent.getStringExtra(EXTRA_URL);
                String title = intent.getStringExtra(EXTRA_TITLE);
                if (url != null && !url.trim().isEmpty()) play(url, title == null ? "Radio" : title);
            }
        }
        return START_STICKY;
    }

    private void play(String url, String title) {
        MediaItem item = new MediaItem.Builder().setUri(url).setMediaMetadata(
                new androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()).build();
        player.setMediaItem(item);
        player.prepare();
        player.play();
    }

    public void stopRadio() {
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        stopSelf();
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) { return mediaSession; }
    @Override public void onTaskRemoved(Intent rootIntent) {
        // Keep the foreground media service alive when the browser task is removed.
        super.onTaskRemoved(rootIntent);
    }
    @Override public void onDestroy() {
        if (mediaSession != null) mediaSession.release();
        if (player != null) player.release();
        super.onDestroy();
    }
    @Nullable @Override public IBinder onBind(Intent intent) { return super.onBind(intent); }
}
