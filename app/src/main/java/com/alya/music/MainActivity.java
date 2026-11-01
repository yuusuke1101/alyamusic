package com.alya.music;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends androidx.activity.ComponentActivity {
    private static WebView sWeb;
    private WebView web;
    private java.io.File pendingApk;

    // Dipakai notifikasi/layar kunci untuk mengirim perintah (play, pause, next, prev, seek) ke pemutar di dalam WebView.
    static void send(final String cmd) {
        new Handler(Looper.getMainLooper()).post(() -> {
            WebView w = sWeb;
            if (w != null) w.evaluateJavascript("window.alyaCmd&&window.alyaCmd('" + cmd + "')", null);
        });
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.parseColor("#0a0a12"));
        getWindow().setNavigationBarColor(Color.parseColor("#0a0a12"));

        web = new WebView(this);
        sWeb = web;
        web.setBackgroundColor(Color.parseColor("#0a0a12"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.addJavascriptInterface(new Bridge(), "AlyaApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("127.0.0.1".equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                if (r.isForMainFrame()) {
                    v.loadDataWithBaseURL(null, "<body style='background:#0a0a12;color:#f5f5fb;font-family:sans-serif;text-align:center;padding:60px 20px'>"
                            + "<h3>Gagal memuat aplikasi</h3><p>Tutup aplikasi lalu buka lagi.</p></body>", "text/html", "utf-8", null);
                }
            }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        try {
            Intent i = new Intent(this, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) { }

        int port = AlyaServer.ensure(this);
        if (port < 0) Toast.makeText(this, "Server internal gagal dimulai", Toast.LENGTH_LONG).show();
        else web.loadUrl("http://127.0.0.1:" + port + "/");
    }

    private void upd(String json) {
        final String js = "window.alyaUpd&&window.alyaUpd(" + json + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void dl(String id, String json) {
        final String js = "window.alyaDl&&window.alyaDl(Object.assign({id:" + org.json.JSONObject.quote(id) + "}," + json + "))";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private void launchInstall() {
        if (pendingApk == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            upd("{\"perm\":true}");
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        try {
            Uri u = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".fp", pendingApk);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            pendingApk = null;
            startActivity(i);
            upd("{\"done\":true}");
        } catch (Exception e) { upd("{\"err\":" + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingApk != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) launchInstall();
    }

    class Bridge {
        // Dipanggil pemutar: info lagu + status untuk notifikasi dan layar kunci
        @JavascriptInterface
        public void setMedia(String title, String artist, String id, boolean playing, double dur, double pos, double rate) {
            try {
                Intent i = new Intent(MainActivity.this, PlaybackService.class);
                i.setAction(PlaybackService.A_META);
                i.putExtra("title", title);
                i.putExtra("artist", artist);
                i.putExtra("id", id);
                i.putExtra("playing", playing);
                i.putExtra("dur", (long) dur);
                i.putExtra("pos", (long) pos);
                i.putExtra("rate", (float) rate);
                startService(i);
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public String cfg() {
            return "{\"apiKey\":\"" + BuildConfig.FB_KEY + "\",\"project\":\"" + BuildConfig.FB_PROJECT + "\"}";
        }

        @JavascriptInterface
        public void googleSignIn() { runOnUiThread(() -> GoogleAuth.signIn(MainActivity.this, web)); }

        @JavascriptInterface
        public void setMode(boolean shuffle, int rep) {
            try {
                Intent i = new Intent(MainActivity.this, PlaybackService.class).setAction(PlaybackService.A_MODE);
                i.putExtra("shuffle", shuffle);
                i.putExtra("rep", rep);
                startService(i);
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void installUpdate(final String url) {
            if (url == null || !url.startsWith("https://github.com/" + BuildConfig.REPO + "/releases/download/")) { upd("{\"err\":\"Alamat update tidak dikenali\"}"); return; }
            new Thread(() -> {
                try {
                    java.io.File d = new java.io.File(getCacheDir(), "updates");
                    d.mkdirs();
                    java.io.File[] old = d.listFiles();
                    if (old != null) for (java.io.File o : old) o.delete();
                    java.io.File f = new java.io.File(d, "AlyaMusic.apk");
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "AlyaMusicApp");
                    if (c.getResponseCode() != 200) throw new Exception("Server menjawab " + c.getResponseCode());
                    long total = c.getContentLengthLong(), got = 0;
                    int last = -1;
                    try (java.io.InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            got += n;
                            int p = total > 0 ? (int) (got * 100 / total) : 0;
                            if (p != last) { last = p; upd("{\"p\":" + p + "}"); }
                        }
                    }
                    if (f.length() < 100000) throw new Exception("File update tidak lengkap");
                    pendingApk = f;
                    runOnUiThread(() -> launchInstall());
                } catch (Exception e) { upd("{\"err\":" + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + "}"); }
            }).start();
        }

        // Unduh lagu untuk diputar offline. Urutan sumber: NewPipeExtractor/Piped (sama yang dipakai
        // streaming) -> kalau gagal total, scraper cadangan ytmp3.mobi. Progres dikirim ke JS lewat
        // window.alyaDl({id, p}) ... window.alyaDl({id, done:true}) / window.alyaDl({id, err:"..."})
        @JavascriptInterface
        public void downloadTrack(final String id, final String title, final String artist) {
            if (!Backend.validId(id)) { dl(String.valueOf(id), "{\"err\":\"ID tidak valid\"}"); return; }
            new Thread(() -> {
                java.net.HttpURLConnection c = null;
                Backend.Track t;
                boolean viaFallback = false;
                try {
                    try {
                        dl(id, "{\"stage\":\"Mencari sumber audio...\"}");
                        t = Backend.track(id);
                        c = Backend.openFull(t.url, Backend.streamUa(t.url));
                        int code = c.getResponseCode();
                        if (code != 200 && code != 206) { c.disconnect(); throw new Exception("HTTP " + code); }
                    } catch (Exception primaryErr) {
                        android.util.Log.w("AlyaDl", "sumber utama gagal: " + primaryErr);
                        dl(id, "{\"stage\":\"Sumber utama gagal, mencoba cadangan (bisa sampai 1 menit)...\"}");
                        t = Backend.fromYtmp3(id);
                        viaFallback = true;
                        c = Backend.openFull(t.url, Backend.FF);
                        int code = c.getResponseCode();
                        if (code != 200 && code != 206) { c.disconnect(); throw new Exception("Sumber cadangan menolak (HTTP " + code + ")"); }
                    }
                    dl(id, "{\"stage\":\"Mengunduh...\"}");
                    String ext = Backend.extFor(t.mime);
                    String nameSrc = (title != null && !title.trim().isEmpty()) ? title : t.title;
                    java.io.File dir = Backend.downloadDir();
                    dir.mkdirs();
                    java.io.File tmp = new java.io.File(dir, id + ".part");
                    long total = c.getContentLengthLong(), got = 0;
                    int last = -1;
                    try (java.io.InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[65536];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            got += n;
                            int p = total > 0 ? (int) Math.min(99, got * 100 / total) : -1;
                            if (p != last) { last = p; dl(id, "{\"p\":" + p + "}"); }
                        }
                    } finally { c.disconnect(); }
                    java.io.File dest = new java.io.File(dir, id + "." + ext);
                    if (dest.exists()) dest.delete();
                    if (!tmp.renameTo(dest)) throw new Exception("Gagal menyimpan file");
                    Backend.downloadPut(id, dest.getAbsolutePath(), Backend.safeName(nameSrc),
                            artist != null ? artist : (t.artist == null ? "" : t.artist), t.mime == null ? "audio/mpeg" : t.mime);
                    dl(id, "{\"done\":true,\"src\":" + org.json.JSONObject.quote(viaFallback ? "cadangan" : "utama") + "}");
                } catch (Exception e) {
                    android.util.Log.w("AlyaDl", "unduhan gagal: " + e);
                    dl(id, "{\"err\":" + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                }
            }).start();
        }

        @JavascriptInterface
        public void removeDownload(final String id) {
            if (!Backend.validId(id)) return;
            new Thread(() -> { Backend.downloadRemove(id); dl(id, "{\"removed\":true}"); }).start();
        }

        @JavascriptInterface
        public String downloadState(String id) {
            return Backend.validId(id) ? Backend.downloadStateJson(id).toString() : "{\"state\":\"none\"}";
        }

        @JavascriptInterface
        public void setBars(final String bg, final boolean light) {
            runOnUiThread(() -> {
                try {
                    int c = Color.parseColor(bg);
                    getWindow().setStatusBarColor(c);
                    getWindow().setNavigationBarColor(c);
                    web.setBackgroundColor(c);
                    int f = 0;
                    if (light && Build.VERSION.SDK_INT >= 23) f |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (light && Build.VERSION.SDK_INT >= 26) f |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    getWindow().getDecorView().setSystemUiVisibility(f);
                } catch (Exception e) { }
            });
        }

        @JavascriptInterface
        public void shareImage(final String b64, final String text) {
            new Thread(() -> {
                try {
                    byte[] b = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                    java.io.File d = new java.io.File(getCacheDir(), "share");
                    d.mkdirs();
                    java.io.File f = new java.io.File(d, "alya-rekap.png");
                    try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) { o.write(b); }
                    final Uri u = androidx.core.content.FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".fp", f);
                    runOnUiThread(() -> {
                        Intent i = new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, u)
                                .putExtra(Intent.EXTRA_TEXT, text).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Bagikan rekap"));
                    });
                } catch (Exception e) { }
            }).start();
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Bagikan"));
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            if (url == null || !url.startsWith("http")) return;
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { } });
        }

        @JavascriptInterface
        public void copy(final String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Alya Music", text));
                Toast.makeText(MainActivity.this, "Disalin", Toast.LENGTH_SHORT).show();
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else moveTaskToBack(true);
    }

    @Override
    protected void onDestroy() {
        sWeb = null;
        stopService(new Intent(this, PlaybackService.class));
        web.destroy();
        super.onDestroy();
    }
}
