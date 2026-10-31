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
