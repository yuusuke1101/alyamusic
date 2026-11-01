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
        Backend.initDirs(c.getApplicationContext());
        new Thread(() -> { try { Thread.sleep(5000); } catch (Exception e) { } Backend.backfillCovers(); }).start();
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
            
            if (uri.equals("/api/diag")) return json(Backend.diagJson(BuildConfig.EXTRACTOR).toString());

            if (uri.equals("/api/suggest")) {
                String q = s.getParms().get("q");
                byte[] b = q == null || q.trim().isEmpty() ? null : Backend.bytes("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8"), Backend.FF);
                return json(b == null ? "[\"\",[]]" : new String(b, java.nio.charset.StandardCharsets.UTF_8));
            }
            if (uri.equals("/api/playlist")) {
                String u = s.getParms().get("url");
                return json(Backend.playlist(u == null ? "" : u.trim()).toString());
            }
            if (uri.equals("/api/search")) {
                String q = s.getParms().get("q");
                String type = s.getParms().get("type");
                String qq = q == null ? "" : q.trim();
                return json((type == null || type.isEmpty() ? Backend.search(qq) : Backend.searchKind(qq, type)).toString());
            }
            if (uri.startsWith("/api/track/") && Backend.validId(last)) {
                if ("1".equals(s.getParms().get("refresh"))) Backend.lyricsReset(last);
                // Lagu yang sudah diunduh dan perangkat sedang offline: langsung pakai data lokal, tanpa menunggu timeout jaringan.
                if (!Backend.isOnline() && Backend.downloadFile(last) != null) {
                    JSONObject offNow = Backend.offlineTrackJson(last);
                    if (offNow != null) return json(offNow.toString());
                }
                try {
                    return json(Backend.trackJson(last).toString());
                } catch (Exception liveErr) {
                    JSONObject off = Backend.offlineTrackJson(last);
                    if (off != null) return json(off.toString());
                    throw liveErr;
                }
            }
            if (uri.startsWith("/api/prefetch/") && Backend.validId(last)) {
                new Thread(() -> { try { Backend.track(last); } catch (Exception e) { } }).start();
                return json("{\"ok\":true}");
            }
            if (uri.equals("/api/downloads")) return json(Backend.downloadsList().toString());
            if (uri.startsWith("/api/downloads/") && Backend.validId(last)) {
                if ("1".equals(s.getParms().get("del"))) { Backend.downloadRemove(last); return json("{\"ok\":true}"); }
                return json(Backend.downloadStateJson(last).toString());
            }
            if (uri.startsWith("/thumb/") && Backend.validId(last)) return thumb(last);
            if (uri.startsWith("/audio/") && Backend.validId(last)) {
                // File yang sudah diunduh offline dilayani langsung dari penyimpanan, tanpa jaringan.
                java.io.File off = Backend.downloadFile(last);
                if (off != null) return localFile(off, Backend.mimeOf(off), s.getHeaders().get("range"));
                return audio(last, s.getHeaders().get("range"));
            }
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
        java.io.File cf = Backend.coverFile(id);
        if (cf != null) {
            try (java.io.FileInputStream in = new java.io.FileInputStream(cf)) {
                byte[] cb = Backend.readAll(in);
                Response r0 = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(cb), cb.length);
                r0.addHeader("Cache-Control", "max-age=86400");
                return r0;
            } catch (IOException e) { /* lanjut ke jaringan */ }
        }
        byte[] b = Backend.bytes("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", Backend.FF);
        if (b == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "-");
        Response r = newFixedLengthResponse(Response.Status.OK, "image/jpeg", new ByteArrayInputStream(b), b.length);
        r.addHeader("Cache-Control", "max-age=86400");
        return r;
    }

    // Teruskan audio dari YouTube ke pemutar sambil mengalir (mendukung Range / seek).
    // Ke YouTube, permintaan dipecah per CHUNK supaya tidak terlihat seperti "unduhan penuh" yang dibatasi kecepatannya.
    // Matikan dengan CHUNKING = false kalau perlu kembali ke perilaku lama.
    private static final boolean CHUNKING = true;
    private static final long CHUNK = 4L << 20;

    private static HttpURLConnection open(String url, String ua, String rq) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Range", rq);
        return c;
    }

    // Membaca rentang [pos..end] dari YouTube dalam potongan CHUNK, potongan pertama sudah terbuka.
    private static final class Chunked extends java.io.InputStream {
        private final String url, ua;
        private final long end;
        private long pos, curEnd;
        private java.io.InputStream cur;
        private HttpURLConnection conn;

        Chunked(String url, String ua, long start, long end, HttpURLConnection first, long firstEnd) throws IOException {
            this.url = url; this.ua = ua; this.pos = start; this.end = end;
            this.conn = first; this.cur = first.getInputStream(); this.curEnd = Math.min(firstEnd, end);
        }

        private void openNext() throws IOException {
            long ce = Math.min(pos + CHUNK - 1, end);
            HttpURLConnection c = open(url, ua, "bytes=" + pos + "-" + ce);
            int code = c.getResponseCode();
            if (code != 206) { c.disconnect(); throw new IOException("Potongan ditolak (HTTP " + code + ")"); }
            conn = c; cur = c.getInputStream(); curEnd = ce;
        }

        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (pos > end) return -1;
            if (cur == null) openNext();
            int n = cur.read(buf, off, (int) Math.min(len, curEnd - pos + 1));
            if (n < 0) throw new IOException("Aliran terputus");
            pos += n;
            if (pos > curEnd) { try { cur.close(); } catch (IOException e) { } cur = null; }
            return n;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override public void close() {
            try { if (cur != null) cur.close(); } catch (IOException e) { }
            if (conn != null) conn.disconnect();
        }
    }

    // Baca sebagian file (RandomAccessFile) sepanjang `remaining` byte - dipakai untuk melayani
    // file offline yang sudah diunduh, dengan dukungan Range (biar bisa di-seek seperti streaming).
    private static final class RafStream extends java.io.InputStream {
        private final java.io.RandomAccessFile raf;
        private long remaining;
        RafStream(java.io.RandomAccessFile raf, long len) { this.raf = raf; this.remaining = len; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = raf.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = raf.read(buf, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
        @Override public void close() { try { raf.close(); } catch (IOException e) { } }
    }

    private Response localFile(java.io.File f, String mime, String range) throws IOException {
        long len = f.length();
        long start = 0, end = len - 1;
        boolean partial = false;
        if (range != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
            if (m.find() && (!m.group(1).isEmpty() || !m.group(2).isEmpty())) {
                if (!m.group(1).isEmpty()) start = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) end = Math.min(len - 1, Long.parseLong(m.group(2)));
                partial = true;
            }
        }
        if (start < 0 || start > end) { start = 0; end = len - 1; partial = false; }
        long clen = end - start + 1;
        java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
        raf.seek(start);
        Response r = newFixedLengthResponse(partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK, mime, new RafStream(raf, clen), clen);
        r.addHeader("Accept-Ranges", "bytes");
        r.addHeader("Cache-Control", "no-store");
        if (partial) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + len);
        return r;
    }

    private Response audio(String id, String range) throws Exception {
        long a = 0, b = -1;
        boolean chunkable = CHUNKING;
        if (range != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(range);
            if (m.find() && !m.group(1).isEmpty()) {
                a = Long.parseLong(m.group(1));
                if (!m.group(2).isEmpty()) b = Long.parseLong(m.group(2));
            } else chunkable = false;
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            // percobaan kedua (alamat pertama ditolak) langsung memakai sumber cadangan
            Backend.Track t = Backend.track(id, attempt > 0);
            String ua = Backend.streamUa(t.url);
            long t0 = System.currentTimeMillis();
            String rq = chunkable
                    ? "bytes=" + a + "-" + (b >= 0 ? Math.min(b, a + CHUNK - 1) : a + CHUNK - 1)
                    : (range != null ? range : "bytes=0-");
            HttpURLConnection c = open(t.url, ua, rq);
            int code = c.getResponseCode();
            Backend.diagBegin(id, t, System.currentTimeMillis() - t0, code);
            if (code != 200 && code != 206) {
                c.disconnect();
                Backend.cache.remove(id);
                continue;
            }
            String cr = c.getHeaderField("Content-Range");
            long len = c.getContentLengthLong();
            boolean partial = code == 206, chunked = false;
            java.io.InputStream body = c.getInputStream();
            if (chunkable && partial && cr != null && cr.lastIndexOf('/') > 0) {
                try {
                    long total = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    String span = cr.substring(cr.indexOf(' ') + 1, cr.lastIndexOf('/'));
                    long firstEnd = Long.parseLong(span.substring(span.indexOf('-') + 1).trim());
                    long end = b >= 0 ? Math.min(b, total - 1) : total - 1;
                    if (a <= end) {
                        body = new Chunked(t.url, ua, a, end, c, firstEnd);
                        len = end - a + 1;
                        cr = "bytes " + a + "-" + end + "/" + total;
                        chunked = true;
                    }
                } catch (Exception e) { /* pakai aliran apa adanya */ }
            }
            if (range == null && partial) {
                partial = false;
                if (!chunked && cr != null && cr.lastIndexOf('/') > 0) len = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                cr = null;
            }
            Response.IStatus st = partial ? Response.Status.PARTIAL_CONTENT : Response.Status.OK;
            Response r = len >= 0
                    ? newFixedLengthResponse(st, t.mime, new Backend.Meter(body, id), len)
                    : newChunkedResponse(st, t.mime, new Backend.Meter(body, id));
            r.addHeader("Accept-Ranges", "bytes");
            r.addHeader("Cache-Control", "no-store");
            if (partial && cr != null) r.addHeader("Content-Range", cr);
            return r;
        }
        throw new Exception("Sumber audio menolak permintaan");
    }
}
