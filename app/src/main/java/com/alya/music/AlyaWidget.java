package com.alya.music;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.widget.RemoteViews;

// Widget layar utama: judul lagu + tombol sebelumnya / putar-jeda / berikutnya.
public class AlyaWidget extends AppWidgetProvider {
    private static String title = "Alya Music", artist = "Ketuk untuk membuka";
    private static boolean playing;
    private static Bitmap art;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { push(c, m, ids); }

    static void update(Context c, String t, String a, boolean p, Bitmap b) {
        title = t; artist = a; playing = p; art = b;
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, AlyaWidget.class));
        if (ids != null && ids.length > 0) push(c, m, ids);
    }

    private static PendingIntent svc(Context c, String action, int code) {
        Intent i = new Intent(c, PlaybackService.class).setAction(action);
        int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, code, i, f) : PendingIntent.getService(c, code, i, f);
    }

    private static void push(Context c, AppWidgetManager m, int[] ids) {
        try {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
            v.setTextViewText(R.id.w_title, title);
            v.setTextViewText(R.id.w_artist, artist);
            v.setImageViewResource(R.id.w_play, playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            if (art != null) v.setImageViewBitmap(R.id.w_art, art); else v.setImageViewResource(R.id.w_art, R.mipmap.ic_launcher);
            v.setOnClickPendingIntent(R.id.w_prev, svc(c, PlaybackService.A_PREV, 11));
            v.setOnClickPendingIntent(R.id.w_play, svc(c, playing ? PlaybackService.A_PAUSE : PlaybackService.A_PLAY, 12));
            v.setOnClickPendingIntent(R.id.w_next, svc(c, PlaybackService.A_NEXT, 13));
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 10, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            m.updateAppWidget(ids, v);
        } catch (Exception e) { }
    }
}
