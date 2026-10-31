package com.alya.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

// Menjaga aplikasi tetap hidup + notifikasi/layar kunci dengan tombol putar, jeda, berikutnya, sebelumnya, dan seek.
public class PlaybackService extends Service {
    static final String A_META = "alya.META", A_PLAY = "alya.PLAY", A_PAUSE = "alya.PAUSE", A_NEXT = "alya.NEXT", A_PREV = "alya.PREV";
    private static final String CH = "alya_play";

    private PowerManager.WakeLock wl;
    private MediaSession session;
    private BroadcastReceiver noisy;
    private Thread updater;
    private String title = "Alya Music", artist = "Siap memutar", artId = "";
    private boolean playing;
    private long dur, pos;
    private float rate = 1f;
    private Bitmap art;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "AlyaMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.send("play"); }
            @Override public void onPause() { MainActivity.send("pause"); }
            @Override public void onStop() { MainActivity.send("pause"); }
            @Override public void onSkipToNext() { MainActivity.send("next"); }
            @Override public void onSkipToPrevious() { MainActivity.send("prev"); }
            @Override public void onSeekTo(long p) { MainActivity.send("seek:" + p); }
        });
        session.setActive(true);

        // Cabut headphone / Bluetooth terputus -> jeda otomatis
        noisy = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { if (playing) MainActivity.send("pause"); }
        };
        registerReceiver(noisy, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

        // Periksa update di GitHub saat aplikasi dibuka, lalu tiap 6 jam selama aplikasi berjalan
        final Context app = getApplicationContext();
        updater = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Updater.check(app, true); } catch (Exception e) { }
                try { Thread.sleep(6 * 3600 * 1000L); } catch (InterruptedException e) { return; }
            }
        });
        updater.setDaemon(true);
        updater.start();
    }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        String a = in == null ? null : in.getAction();
        if (A_PLAY.equals(a)) MainActivity.send("play");
        else if (A_PAUSE.equals(a)) MainActivity.send("pause");
        else if (A_NEXT.equals(a)) MainActivity.send("next");
        else if (A_PREV.equals(a)) MainActivity.send("prev");
        else if (A_META.equals(a)) {
            title = nz(in.getStringExtra("title"), "Alya Music");
            artist = nz(in.getStringExtra("artist"), "");
            playing = in.getBooleanExtra("playing", false);
            dur = in.getLongExtra("dur", 0);
            pos = in.getLongExtra("pos", 0);
            rate = in.getFloatExtra("rate", 1f);
            String id = nz(in.getStringExtra("id"), "");
            if (!id.equals(artId)) { artId = id; art = null; loadArt(id); }
        }
        refresh();
        return START_STICKY;
    }

    private static String nz(String s, String d) { return s == null || s.isEmpty() ? d : s; }

    private void loadArt(final String id) {
        if (id.isEmpty()) return;
        new Thread(() -> {
            byte[] b = Backend.bytes("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", Backend.FF);
            if (b == null) return;
            Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
            if (bm != null && id.equals(artId)) {
                art = bm;
                new Handler(Looper.getMainLooper()).post(this::refresh);
            }
        }).start();
    }

    private PendingIntent act(String action, int code) {
        Intent i = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void refresh() {
        // sesi media (layar kunci, Bluetooth, tombol headset)
        MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
        if (art != null) mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(mb.build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, pos, playing ? rate : 0f)
                .build());

        // notifikasi
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "Pemutaran musik", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        nb.setSmallIcon(android.R.drawable.stat_sys_headset)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_previous), "Sebelumnya", act(A_PREV, 1)).build())
                .addAction(playing
                        ? new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Jeda", act(A_PAUSE, 2)).build()
                        : new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_play), "Putar", act(A_PLAY, 3)).build())
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_media_next), "Berikutnya", act(A_NEXT, 4)).build())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2));
        if (art != null) nb.setLargeIcon(art);
        Notification n = nb.build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(1, n);

        // CPU hanya ditahan saat lagu sedang diputar
        if (wl == null) {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alya:play");
            wl.setReferenceCounted(false);
        }
        if (playing) { if (!wl.isHeld()) wl.acquire(6 * 60 * 60 * 1000L); }
        else if (wl.isHeld()) wl.release();
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(noisy); } catch (Exception e) { }
        if (updater != null) updater.interrupt();
        if (wl != null && wl.isHeld()) wl.release();
        if (session != null) { session.setActive(false); session.release(); }
        super.onDestroy();
    }
}
