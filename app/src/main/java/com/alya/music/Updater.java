package com.alya.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

// Memeriksa rilis terbaru di GitHub (repository yang membangun aplikasi ini).
final class Updater {
    private static JSONObject last;
    private static long lastAt;

    static synchronized JSONObject check(Context c, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && last != null && now - lastAt < 600000) return last;
        JSONObject o = new JSONObject();
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            o.put("current", pi.versionCode);
            o.put("currentName", pi.versionName == null ? "" : pi.versionName);
            o.put("available", false);
            String repo = BuildConfig.REPO;
            o.put("enabled", !repo.isEmpty());
            if (!repo.isEmpty()) {
                byte[] b = Backend.bytes("https://api.github.com/repos/" + repo + "/releases/latest", "AlyaMusicApp");
                if (b == null) {
                    o.put("error", true);
                } else {
                    JSONObject r = new JSONObject(new String(b, StandardCharsets.UTF_8));
                    String tag = r.optString("tag_name", "");
                    String digits = tag.replaceAll("[^0-9]", "");
                    int latest = digits.isEmpty() ? 0 : Integer.parseInt(digits);
                    String url = r.optString("html_url", "");
                    JSONArray as = r.optJSONArray("assets");
                    if (as != null) {
                        for (int i = 0; i < as.length(); i++) {
                            JSONObject a = as.getJSONObject(i);
                            if (a.optString("name", "").endsWith(".apk")) { url = a.optString("browser_download_url", url); break; }
                        }
                    }
                    String notes = r.isNull("body") ? "" : r.optString("body", "");
                    o.put("latest", latest);
                    o.put("latestName", tag);
                    o.put("url", url);
                    o.put("notes", notes.length() > 600 ? notes.substring(0, 600) : notes);
                    boolean avail = latest > pi.versionCode;
                    o.put("available", avail);
                    if (avail) notifyNew(c, o);
                }
            }
        } catch (Exception e) {
            try { o.put("error", true); } catch (Exception x) { }
        }
        last = o;
        lastAt = now;
        return o;
    }

    private static void notifyNew(Context c, JSONObject o) {
        try {
            SharedPreferences p = c.getSharedPreferences("alya", Context.MODE_PRIVATE);
            int latest = o.getInt("latest");
            if (p.getInt("notified", 0) >= latest) return;
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel("alya_update", "Pembaruan aplikasi", NotificationManager.IMPORTANCE_DEFAULT));
            }
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(o.getString("url")));
            PendingIntent pi = PendingIntent.getActivity(c, 7, view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "alya_update") : new Notification.Builder(c);
            nm.notify(2, b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Update Alya Music tersedia")
                    .setContentText("Versi " + o.optString("latestName") + " siap diunduh. Ketuk untuk mengunduh.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
            p.edit().putInt("notified", latest).apply();
        } catch (Exception e) { /* tanpa izin notifikasi */ }
    }
}
