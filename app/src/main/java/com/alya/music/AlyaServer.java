package com.alya.music;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import fi.iki.elonen.NanoHTTPD;

// Server mini di dalam aplikasi (pengganti server.js). Hanya bisa diakses dari HP ini (127.0.0.1).
public class AlyaServer extends NanoHTTPD {
    private static AlyaServer inst;
    private static int port = -1;
    private final AssetManager am;
    private final Context context;

    static synchronized int ensure(Context c) {
        if (inst != null && inst.isAlive()) return port;
        for (int p = 38517; p < 38527; p++) {
            try {
                AlyaServer s = new AlyaServer(c.getApplicationContext(), p);
                s.start(SOCKET_READ_TIMEOUT, false);
                inst = s;
                port = p;
                return p;
            } catch (IOException e) { /* port terpakai, coba berikutnya */ }
        }
        return -1;
    }

    private AlyaServer(Context c, int p) {
        super("127.0.0.1", p);
        context = c;
        am = c.getAssets();
    }

    @Override
    public Response serve(IHTTPSession s) {
        String uri = s.getUri();
        String last = uri.substring(uri.lastIndexOf('/') + 1);
        try {
            if (uri.equals("/")) return asset("index.html", "text/html; charset=utf-8");
            if (uri.startsWith("/player/")) return asset("player.html", "text/html; charset=utf-8");
            if (uri.equals("/icon-192.png") || uri.equals("/mascot.png")) return asset(uri.substring(1), "image/png");
            
            // Rute API Update baru
            if ("/api/update".equals(uri)) {
                boolean force = "1".equals(s.getParms().get("force"));
                JSONObject res = Updater.check(context, force);
                return json(res.toString());
            }
            
            if (uri.equals("/api/search")) {
                String q = s.getParms().get("q");
                return json(Backend.search(q == null ? "" : q.trim()).toString());
            }
            if (uri.startsWith("/api/track/") && Backend.validId(last)) return json(Backend.trackJson(last).toString());
            if (uri.startsWith("/api/prefetch/") && Backend.validId(last)) {
                new Thread(() -> { try { Backend.track(last); } catch (Exception e) { } }).start();
                return json("{\"ok\":true}");
            }
            if (uri.startsWith("/thumb/") && Backend.validId(last)) return thumb(last);
            if (uri.startsWith("/audio/") && Backend.validId(last)) return audio(last, s.getHeaders().get("range"));
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Tidak ditemukan");
        } catch (Exception e) {
            try {
                JSONObject o = new JSONObject();
                o.put("error", String.valueOf(e.getMessage()));
                Response r = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json; charset=utf-8", o.toString());
                return r;
            } catch (Exception x) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error");
            }
        }
    }

    private Response json(String body) {
        Response r = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", body);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response asset(String name, String mime) throws IOException {
        Response r = newChunkedResponse(Response.Status.OK, mime, am.open(name));
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private Response thumb(String id) {
        byte[] b = Backend.bytes("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", Backend.FF);
        if (b == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "-");
        Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(b), b.length);
        r.addHeader("Cache-Control", "max-age=86400");
        return r;
    }

    // Teruskan audio dari YouTube ke pemutar sambil mengalir (mendukung Range / seek)
    private Response audio(String id, String range) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Backend.Track t = Backend.track(id);
            HttpURLConnection c = (HttpURLConnection) new URL(t.url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", Backend.streamUa(t.url));
            c.setRequestProperty("Range", range != null ? range : "bytes=0-");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                c.disconnect();
                Backend.cache.remove(id);
                continue;
            }
            String cr = c.getHeaderField("Content-Range");
            long len = c.getContentLengthLong();
            boolean partial = code == 206;
            if (range == null && partial) {
                partial = false;
                if (cr != null && cr.lastIndexOf('/') > 0) len = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                cr = null;
            }
            Response.IStatus st = partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
            Response r = len >= 0
                    ? newFixedLengthResponse(st, t.mime, c.getInputStream(), len)
                    : newChunkedResponse(st, t.mime, c.getInputStream());
            r.addHeader("Accept-Ranges", "bytes");
            r.addHeader("Cache-Control", "no-store");
            if (partial && cr != null) r.addHeader("Content-Range", cr);
            return r;
        }
        throw new Exception("Sumber audio menolak permintaan");
    }
}
