package com.alya.music;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.playlist.PlaylistExtractor;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Pengganti server.js: pencarian, info lagu, alamat audio (NewPipeExtractor), dan lirik (lrclib).
final class Backend {
    static final String FF = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0";
    static final String ANDROID_UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
    static final String IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
    static final Map<String, Track> cache = new ConcurrentHashMap<>();
    // Batas cache info-lagu di memori (beda dari cache lirik di disk) - lihat touch() di bawah.
    private static final int CACHE_MAX = 120, CACHE_KEEP = 80;
    private static boolean ready;

    // ---------- header untuk scraper cadangan (ytmp3.mobi) ----------
    private static final Map<String, String> YTMP3_HEADERS = new java.util.LinkedHashMap<>();
    static {
        YTMP3_HEADERS.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        YTMP3_HEADERS.put("Accept", "*/*");
        YTMP3_HEADERS.put("Accept-Language", "en-US,en;q=0.9");
        YTMP3_HEADERS.put("Origin", "https://id.ytmp3.mobi");
        YTMP3_HEADERS.put("Referer", "https://id.ytmp3.mobi/");
        YTMP3_HEADERS.put("Sec-Fetch-Dest", "empty");
        YTMP3_HEADERS.put("Sec-Fetch-Mode", "cors");
        YTMP3_HEADERS.put("Sec-Fetch-Site", "cross-site");
    }

    // ---------- index unduhan offline (disimpan di storage privat app) ----------
    private static java.io.File dlDir;
    private static java.io.File dlIndexFile;
    private static final Map<String, JSONObject> dlIndex = new ConcurrentHashMap<>();
    private static volatile boolean dlLoaded;

    static final class Track {
        String title, artist, url, mime, subUrl, kind, src = "";
        int kbps;
        boolean subAuto;
        JSONArray lyr;
        long seconds, exp, touched;
    }

    static synchronized void init() {
        if (!ready) { NewPipe.init(new Dl()); ready = true; }
    }

    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9_-]{11}"); }

    static String streamUa(String url) {
        if (url.contains("c=ANDROID")) return ANDROID_UA;
        if (url.contains("c=IOS")) return IOS_UA;
        return FF;
    }

    // ---------- pencarian ----------
    static JSONArray search(String q) throws Exception {
        init();
        StreamingService yt = ServiceList.YouTube;
        SearchExtractor se = yt.getSearchExtractor(yt.getSearchQHFactory().fromQuery(q));
        se.fetchPage();
        JSONArray out = new JSONArray();
        for (InfoItem it : se.getInitialPage().getItems()) {
            if (!(it instanceof StreamInfoItem)) continue;
            StreamInfoItem s = (StreamInfoItem) it;
            String url = s.getUrl();
            int k = url == null ? -1 : url.indexOf("v=");
            if (k < 0 || url.length() < k + 13) continue;
            String id = url.substring(k + 2, k + 13);
            if (!validId(id)) continue;
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("title", s.getName());
            o.put("author", cleanArtist(s.getUploaderName()));
            o.put("duration", fmt(s.getDuration()));
            o.put("thumb", "/thumb/" + id);
            out.put(o);
            if (out.length() >= 12) break;
        }
        return out;
    }

    // ---------- pencarian per jenis: "artists", "albums", "playlists" ----------
    private static String compact(long n) {
        if (n >= 1000000) return String.format(java.util.Locale.US, "%.1f jt", n / 1e6).replace(".0 ", " ");
        if (n >= 1000) return String.format(java.util.Locale.US, "%.1f rb", n / 1e3).replace(".0 ", " ");
        return String.valueOf(n);
    }

    // Pilih gambar yang tingginya paling dekat 240 px (cukup tajam, tidak berat).
    private static String bestThumb(List<org.schabi.newpipe.extractor.Image> imgs) {
        String best = null; int bh = -1;
        if (imgs == null) return "";
        for (org.schabi.newpipe.extractor.Image im : imgs) {
            String u = im.getUrl();
            if (u == null || !u.startsWith("https://")) continue;
            int hh = im.getHeight();
            if (best == null || (hh > 0 && (bh <= 0 || Math.abs(hh - 240) < Math.abs(bh - 240)))) { best = u; bh = hh; }
        }
        return best == null ? "" : best;
    }

    static JSONArray searchKind(String q, String kind) throws Exception {
        String filter = "artists".equals(kind) ? "music_artists" : "albums".equals(kind) ? "music_albums" : "playlists".equals(kind) ? "playlists" : null;
        if (filter == null) return search(q);
        init();
        StreamingService yt = ServiceList.YouTube;
        SearchExtractor se = yt.getSearchExtractor(yt.getSearchQHFactory().fromQuery(q, Collections.singletonList(filter), ""));
        se.fetchPage();
        JSONArray out = new JSONArray();
        for (InfoItem it : se.getInitialPage().getItems()) {
            JSONObject o = new JSONObject();
            if ("artists".equals(kind) && it instanceof ChannelInfoItem) {
                ChannelInfoItem c = (ChannelInfoItem) it;
                o.put("name", c.getName());
                long sc = c.getSubscriberCount();
                o.put("subs", sc >= 0 ? compact(sc) + " pelanggan" : "");
                o.put("img", bestThumb(c.getThumbnails()));
            } else if (!"artists".equals(kind) && it instanceof PlaylistInfoItem) {
                PlaylistInfoItem p = (PlaylistInfoItem) it;
                Matcher m = Pattern.compile("[?&]list=([A-Za-z0-9_-]{10,64})").matcher(p.getUrl() == null ? "" : p.getUrl());
                if (!m.find()) continue;
                o.put("id", m.group(1));
                o.put("title", p.getName());
                o.put("author", p.getUploaderName() == null ? "" : p.getUploaderName());
                o.put("count", p.getStreamCount());
                o.put("img", bestThumb(p.getThumbnails()));
            } else continue;
            out.put(o);
            if (out.length() >= 15) break;
        }
        // Hasil album kadang tidak membawa id playlist; cadangannya cari sebagai playlist biasa.
        if (out.length() == 0 && "albums".equals(kind)) return searchKind(q + " album", "playlists");
        return out;
    }

    // ---------- impor playlist YouTube ----------
    private static void addItem(JSONArray out, StreamInfoItem s) throws Exception {
        String url = s.getUrl();
        int k = url == null ? -1 : url.indexOf("v=");
        if (k < 0 || url.length() < k + 13) return;
        String id = url.substring(k + 2, k + 13);
        if (!validId(id)) return;
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", s.getName());
        o.put("author", cleanArtist(s.getUploaderName()));
        o.put("duration", fmt(s.getDuration()));
        o.put("thumb", "/thumb/" + id);
        out.put(o);
    }

    static JSONObject playlist(String raw) throws Exception {
        Matcher m = Pattern.compile("[?&]list=([A-Za-z0-9_-]{10,64})").matcher(raw == null ? "" : raw);
        if (!m.find()) throw new Exception("Link playlist tidak valid");
        init();
        StreamingService yt = ServiceList.YouTube;
        PlaylistExtractor pe = yt.getPlaylistExtractor("https://www.youtube.com/playlist?list=" + m.group(1));
        pe.fetchPage();
        String name = pe.getName();
        JSONArray out = new JSONArray();
        ListExtractor.InfoItemsPage<StreamInfoItem> pg = pe.getInitialPage();
        int pages = 0;
        while (pg != null) {
            for (StreamInfoItem s : pg.getItems()) { addItem(out, s); if (out.length() >= 300) break; }
            if (out.length() >= 300 || !pg.hasNextPage() || ++pages > 4) break;
            pg = pe.getPage(pg.getNextPage());
        }
        return new JSONObject().put("name", name == null ? "" : name).put("items", out);
    }

    // ---------- info + alamat audio ----------
    static Track track(String id) throws Exception { return track(id, false); }

    // Urutan: NewPipeExtractor -> (kalau gagal) sumber cadangan Piped.
    // preferFallback=true dipakai saat alamat audio dari sumber utama ditolak server (mis. HTTP 403).
    static Track track(String id, boolean preferFallback) throws Exception {
        Track t = cache.get(id);
        if (!preferFallback && t != null && t.exp > System.currentTimeMillis()) { touch(t); return t; }
        if (preferFallback) {
            try { t = fromPiped(id); cache.put(id, t); touch(t); return t; } catch (Exception e) { /* coba sumber utama */ }
        }
        try {
            t = fromNewPipe(id);
        } catch (Exception primary) {
            npFail++; lastErr = primary.getClass().getSimpleName() + ": " + primary.getMessage();
            logErr("ekstraksi", id + " · NewPipe gagal: " + lastErr);
            try { t = fromPiped(id); pipedUsed++; } catch (Exception backup) { logErr("piped", id + " · cadangan Piped gagal: " + backup.getMessage()); throw primary; }
        }
        cache.put(id, t);
        touch(t);
        return t;
    }

    // Catat kapan entri dipakai. Kalau cache mulai membengkak: buang dulu yang sudah kedaluwarsa (exp lewat),
    // lalu kalau masih kebanyakan, buang yang paling lama tidak disentuh sampai sisa CACHE_KEEP.
    private static void touch(Track t) {
        long now = System.currentTimeMillis();
        t.touched = now;
        if (cache.size() <= CACHE_MAX) return;
        cache.entrySet().removeIf(e -> e.getValue().exp < now);
        if (cache.size() <= CACHE_KEEP) return;
        List<Map.Entry<String, Track>> es = new ArrayList<>(cache.entrySet());
        es.sort((a, b) -> Long.compare(a.getValue().touched, b.getValue().touched));
        for (int i = 0; i < es.size() - CACHE_KEEP; i++) cache.remove(es.get(i).getKey());
    }

    private static Track fromNewPipe(String id) throws Exception {
        Track t;
        init();
        StreamInfo info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + id);
        AudioStream best = null;
        for (AudioStream s : info.getAudioStreams()) {
            if (s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            boolean m4a = s.getFormat() == MediaFormat.M4A;
            boolean bestM4a = best != null && best.getFormat() == MediaFormat.M4A;
            if (best == null || (m4a && !bestM4a) || (m4a == bestM4a && s.getAverageBitrate() > best.getAverageBitrate())) best = s;
        }
        if (best == null) throw new Exception("Tidak ada aliran audio yang bisa diputar");
        t = new Track();
        try {
            SubtitlesStream bs = null;
            for (SubtitlesStream ss : info.getSubtitles()) {
                if (ss.getFormat() != MediaFormat.VTT || ss.getContent() == null) continue;
                if (bs == null || (bs.isAutoGenerated() && !ss.isAutoGenerated())) bs = ss;
            }
            if (bs != null) { t.subUrl = bs.getContent(); t.subAuto = bs.isAutoGenerated(); }
        } catch (Throwable e) { /* subtitle opsional */ }
        t.src = "NewPipe";
        t.kbps = Math.max(0, best.getAverageBitrate());
        t.title = info.getName();
        t.artist = info.getUploaderName();
        t.seconds = info.getDuration();
        t.url = best.getContent();
        t.mime = best.getFormat() != null ? best.getFormat().getMimeType() : "audio/mp4";
        Matcher m = Pattern.compile("[?&]expire=(\\d+)").matcher(t.url);
        t.exp = m.find() ? Long.parseLong(m.group(1)) * 1000 - 600000 : System.currentTimeMillis() + 3600000;
        return t;
    }

    // ---------- diagnostik pemutaran ----------
    static volatile String curId = "";
    static volatile Track curTrack;
    static volatile int reqs, errs;
    static volatile long ttfbMs, initKbps;
    static volatile String lastErr = "";
    static volatile int npFail, pipedUsed;

    // ---------- catatan galat (hanya di memori, hilang saat aplikasi ditutup) ----------
    // Cincin 100 entri terakhir. Dibaca layar Pengaturan > Tentang & diagnostik lewat /api/log.
    private static final int ELOG_MAX = 100;
    private static final java.util.ArrayDeque<JSONObject> elog = new java.util.ArrayDeque<>();

    static void logErr(String tag, String msg) {
        try {
            String m = msg == null ? "" : msg;
            if (m.length() > 400) m = m.substring(0, 400) + "…";
            JSONObject e = new JSONObject().put("t", System.currentTimeMillis()).put("g", tag == null ? "" : tag).put("m", m);
            synchronized (elog) {
                elog.addLast(e);
                while (elog.size() > ELOG_MAX) elog.removeFirst();
            }
        } catch (Exception ignore) { }
    }

    static void logClear() { synchronized (elog) { elog.clear(); } }

    // Urutan: terbaru di depan.
    static JSONObject logJson(String ver) {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = new JSONArray();
            synchronized (elog) {
                java.util.Iterator<JSONObject> it = elog.descendingIterator();
                while (it.hasNext()) a.put(it.next());
            }
            o.put("extractor", ver).put("items", a);
        } catch (Exception ignore) { }
        return o;
    }

    // Dipanggil tiap permintaan audio dari pemutar
    static void diagBegin(String id, Track t, long ttfb, int http) {
        if (!id.equals(curId)) { curId = id; reqs = 0; errs = 0; initKbps = 0; }
        curTrack = t; ttfbMs = ttfb; reqs++;
        if (http != 200 && http != 206) { errs++; logErr("audio", id + " · server audio menjawab HTTP " + http); }
    }

    static JSONObject diagJson(String ver) {
        JSONObject o = new JSONObject();
        try {
            o.put("extractor", ver);
            o.put("npFail", npFail).put("pipedUsed", pipedUsed).put("lastErr", lastErr);
            Track t = curTrack;
            if (t != null) {
                o.put("id", curId).put("title", t.title == null ? "" : t.title).put("src", t.src).put("mime", t.mime == null ? "" : t.mime);
                o.put("kbps", t.kbps).put("ttfb", ttfbMs).put("initKbps", initKbps).put("reqs", reqs).put("errs", errs);
                o.put("ageMin", Math.max(0, (t.exp - System.currentTimeMillis()) / 60000));
            }
        } catch (Exception e) { }
        return o;
    }

    // Pembungkus aliran: mengukur kecepatan 256 KB pertama (saat pemutar mengisi buffer)
    static final class Meter extends java.io.FilterInputStream {
        private final String id;
        private long bytes, t0;
        private boolean done;
        Meter(InputStream in, String id) { super(in); this.id = id; }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0) tick(1); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) tick(n); return n; }
        private void tick(int n) {
            if (done) return;
            if (t0 == 0) t0 = System.nanoTime();
            bytes += n;
            if (bytes >= 262144) {
                long ms = Math.max(1, (System.nanoTime() - t0) / 1000000);
                if (id.equals(curId)) initKbps = bytes * 8 / ms;
                done = true;
            }
        }
    }

    // ---------- sumber cadangan (Piped) ----------
    // Daftar server API Piped. Isi/ganti sesuai server yang masih hidup; yang gagal dilewati 5 menit.
    static final String[] PIPED = {"https://pipedapi.kavin.rocks"};
    private static final Map<String, Long> pipedBad = new ConcurrentHashMap<>();

    static Track fromPiped(String id) throws Exception {
        long now = System.currentTimeMillis();
        Exception last = null;
        for (String base : PIPED) {
            Long until = pipedBad.get(base);
            if (until != null && until > now) continue;
            try {
                byte[] b = bytes(base + "/streams/" + id, "AlyaMusicApp/1.0", 6000);
                if (b == null) throw new Exception("Server cadangan tidak menjawab");
                JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
                JSONArray as = j.optJSONArray("audioStreams");
                JSONObject best = null;
                if (as != null) {
                    for (int i = 0; i < as.length(); i++) {
                        JSONObject x = as.optJSONObject(i);
                        if (x == null || x.optString("url", "").isEmpty()) continue;
                        String mime = x.optString("mimeType", "");
                        if (!mime.startsWith("audio/")) continue;
                        boolean m4a = mime.contains("mp4");
                        boolean bestM4a = best != null && best.optString("mimeType", "").contains("mp4");
                        if (best == null || (m4a && !bestM4a) || (m4a == bestM4a && x.optInt("bitrate", 0) > best.optInt("bitrate", 0))) best = x;
                    }
                }
                if (best == null) throw new Exception("Server cadangan tidak punya aliran audio");
                Track t = new Track();
                t.title = j.optString("title", "");
                t.artist = j.optString("uploader", "");
                t.seconds = j.optLong("duration", 0);
                t.url = best.getString("url");
                t.mime = best.optString("mimeType", "audio/mp4");
                t.src = "Piped";
                Matcher qm = Pattern.compile("(\\d+)").matcher(best.optString("quality", ""));
                int bt = best.optInt("bitrate", 0);
                t.kbps = qm.find() ? Integer.parseInt(qm.group(1)) : (bt > 1000 ? bt / 1000 : bt);
                t.exp = now + 30 * 60000L;
                return t;
            } catch (Exception e) {
                pipedBad.put(base, now + 5 * 60000L);
                last = e;
            }
        }
        throw last != null ? last : new Exception("Sumber cadangan tidak tersedia");
    }

    // ---------- sumber cadangan terakhir khusus unduhan (ytmp3.mobi) ----------
    // Dipakai hanya kalau NewPipeExtractor DAN Piped sama-sama gagal. Prosesnya lambat (polling),
    // jadi jangan dipakai untuk resolve alamat streaming biasa - hanya untuk unduhan offline.
    static Track fromYtmp3(String id) throws Exception {
        String initUrl = "https://a.ymcdn.org/api/v1/init?p=y&23=1llum1n471&_=" + Math.random();
        JSONObject initJson = httpJson(initUrl);
        if (initJson.optInt("error", 0) > 0) throw new Exception("Ytmp3 init error: " + initJson.optInt("error"));
        String convertUrl = initJson.getString("convertURL");
        String convertRequestUrl = convertUrl + "&v=" + id + "&f=mp3&_=" + Math.random();
        JSONObject convertJson;
        int guard = 0;
        while (true) {
            if (++guard > 8) throw new Exception("Ytmp3: terlalu banyak redirect");
            convertJson = httpJson(convertRequestUrl);
            if (convertJson.optInt("error", 0) > 0) throw new Exception("Ytmp3 convert error: " + convertJson.optInt("error"));
            if (convertJson.optInt("redirect", 0) > 0 && convertJson.has("redirectURL")) {
                convertRequestUrl = convertJson.getString("redirectURL") + "&v=" + id + "&f=mp3&_=" + Math.random();
                continue;
            }
            break;
        }
        String progressUrl = convertJson.optString("progressURL", "");
        String downloadUrl = convertJson.optString("downloadURL", "");
        String title = convertJson.optString("title", "Audio_Download");
        if (progressUrl.isEmpty()) throw new Exception("Ytmp3: progressURL tidak ditemukan");
        int progress = 0, poll = 0;
        while (progress < 3 && poll < 60) {
            try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new Exception("Dibatalkan"); }
            poll++;
            JSONObject pj = httpJson(progressUrl);
            if (pj.optInt("error", 0) > 0) throw new Exception("Ytmp3 progress error: " + pj.optInt("error"));
            progress = pj.optInt("progress", 0);
            if (pj.has("title")) title = pj.optString("title", title);
            if (pj.has("downloadURL")) downloadUrl = pj.optString("downloadURL", downloadUrl);
        }
        if (progress < 3) throw new Exception("Ytmp3: proses konversi timeout");
        if (downloadUrl.isEmpty()) throw new Exception("Ytmp3: downloadURL kosong");
        Track t = new Track();
        t.title = title;
        t.artist = "";
        t.url = downloadUrl;
        t.mime = "audio/mpeg";
        t.src = "Ytmp3";
        t.exp = System.currentTimeMillis() + 10 * 60000L;
        return t;
    }

    private static JSONObject httpJson(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        for (Map.Entry<String, String> h : YTMP3_HEADERS.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("Ytmp3: tidak ada respons (HTTP " + code + ")");
        byte[] b = readAll(in);
        return new JSONObject(new String(b, StandardCharsets.UTF_8));
    }

    // Koneksi "unduh penuh" generik (dipakai saat menyimpan file ke storage, bukan streaming Range per-potongan).
    static HttpURLConnection openFull(String url, String ua) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Range", "bytes=0-");
        return c;
    }

    // ---------- unduhan per potongan ----------
    // Sama seperti streaming (AlyaServer.CHUNK, 4 MB): satu unduhan panjang `Range: bytes=0-` rawan dibatasi
    // kecepatannya oleh YouTube. Matikan dengan DL_CHUNKING = false untuk kembali ke perilaku lama.
    static final boolean DL_CHUNKING = true;
    static final long DL_CHUNK = 4L << 20;

    interface DlProg { void on(int pct); }

    // to < 0 = tanpa batas akhir (perilaku lama).
    static HttpURLConnection openRange(String url, String ua, long from, long to) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Range", "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : ""));
        return c;
    }

    // Koneksi pertama untuk unduhan: potongan pertama (atau tanpa batas kalau DL_CHUNKING mati).
    static HttpURLConnection openFirst(String url, String ua) throws IOException {
        return openRange(url, ua, 0, DL_CHUNKING ? DL_CHUNK - 1 : -1);
    }

    private static long rangeTotal(String cr) {
        try { return cr == null ? -1 : Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim()); } catch (Exception e) { return -1; }
    }

    // Simpan isi `first` (sudah terbuka, kodenya sudah dicek 200/206) ke `tmp`. Kalau chunk=true dan server menjawab 206,
    // sisanya diambil potongan demi potongan sampai total; potongan yang putus diulang dari byte terakhir (maks. 2x).
    // chunk=false (sumber cadangan ytmp3): baca satu kali apa adanya, seperti dulu.
    // Melempar IOException kalau file akhirnya tidak lengkap.
    static void saveChunked(HttpURLConnection first, String url, String ua, java.io.File tmp, boolean chunk, String id, DlProg cb) throws IOException {
        HttpURLConnection c = first;
        long got = 0, total = -1;
        int last = -2, fails = 0;
        byte[] buf = new byte[65536];
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
            while (true) {
                try {
                    if (c == null) c = openRange(url, ua, got, total > 0 ? Math.min(got + DL_CHUNK, total) - 1 : got + DL_CHUNK - 1);
                    int code = c.getResponseCode();
                    if (code == 416 && got > 0) break; // sudah melewati ujung berkas
                    if (code != 200 && code != 206) throw new IOException("Potongan ditolak (HTTP " + code + ")");
                    long clen = c.getContentLengthLong();
                    if (code == 200) {
                        if (got > 0) throw new IOException("Server mengabaikan Range di tengah unduhan");
                        total = clen;
                    } else {
                        long t2 = rangeTotal(c.getHeaderField("Content-Range"));
                        if (t2 > 0) total = t2;
                    }
                    long chunkGot = 0;
                    try (java.io.InputStream in = c.getInputStream()) {
                        int r;
                        while ((r = in.read(buf)) > 0) {
                            out.write(buf, 0, r);
                            got += r; chunkGot += r;
                            int p = total > 0 ? (int) Math.min(99, got * 100 / total) : -1;
                            if (p != last) { last = p; cb.on(p); }
                        }
                    }
                    c.disconnect(); c = null;
                    if (code == 200 || !chunk) break;
                    if (clen > 0 && chunkGot < clen) throw new IOException("Potongan terpotong");
                    if (total > 0 ? got >= total : chunkGot < DL_CHUNK) break;
                    fails = 0;
                } catch (IOException e) {
                    if (c != null) { c.disconnect(); c = null; }
                    if (!chunk || ++fails > 2) throw e;
                    logErr("unduh", id + " · potongan gagal di " + got + " B, ulang " + fails + "/2: " + e);
                    try { Thread.sleep(700L * fails); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException("Dibatalkan"); }
                }
            }
        } finally {
            if (c != null) c.disconnect();
        }
        if (total > 0 && got < total) throw new IOException("Unduhan terputus di " + (got * 100 / total) + "%, coba lagi");
    }

    static String extFor(String mime) {
        if (mime == null) return "m4a";
        if (mime.contains("mpeg") || mime.contains("mp3")) return "mp3";
        if (mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac")) return "m4a";
        if (mime.contains("webm") || mime.contains("opus")) return "webm";
        return "audio";
    }

    static String mimeOf(java.io.File f) {
        String n = f.getName().toLowerCase(java.util.Locale.US);
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".webm")) return "audio/webm";
        return "audio/mp4";
    }

    static String safeName(String s) {
        if (s == null || s.trim().isEmpty()) return "audio";
        String n = s.trim().replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
        if (n.length() > 80) n = n.substring(0, 80).trim();
        return n.isEmpty() ? "audio" : n;
    }

    // ---------- index unduhan offline ----------
    private static void loadDlIndex() {
        try {
            if (dlIndexFile == null || !dlIndexFile.isFile()) return;
            byte[] b;
            try (java.io.FileInputStream in = new java.io.FileInputStream(dlIndexFile)) { b = readAll(in); }
            JSONObject root = new JSONObject(new String(b, StandardCharsets.UTF_8));
            java.util.Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject v = root.optJSONObject(k);
                if (v != null) dlIndex.put(k, v);
            }
        } catch (Throwable e) { /* index opsional */ }
    }

    private static synchronized void saveDlIndex() {
        try {
            if (dlIndexFile == null) return;
            JSONObject root = new JSONObject();
            for (Map.Entry<String, JSONObject> e : dlIndex.entrySet()) root.put(e.getKey(), e.getValue());
            java.io.File tmp = new java.io.File(dlIndexFile.getParentFile(), "downloads.tmp");
            try (java.io.FileOutputStream w = new java.io.FileOutputStream(tmp)) { w.write(root.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(dlIndexFile)) tmp.delete();
        } catch (Throwable e) { /* index opsional */ }
    }

    static void downloadPut(String id, String path, String title, String artist, String mime) {
        try {
            JSONObject o = new JSONObject();
            o.put("path", path).put("title", title == null ? "" : title).put("artist", artist == null ? "" : artist)
                    .put("mime", mime == null ? "audio/mpeg" : mime).put("addedAt", System.currentTimeMillis());
            dlIndex.put(id, o);
            saveDlIndex();
        } catch (Throwable e) { /* opsional */ }
    }

    static void downloadRemove(String id) {
        JSONObject o = dlIndex.remove(id);
        if (o != null) { try { new java.io.File(o.optString("path", "")).delete(); } catch (Throwable e) { } }
        try { java.io.File cf = coverFile(id); if (cf != null) cf.delete(); } catch (Throwable e) { }
        saveDlIndex();
    }

    // Apakah perangkat punya internet yang benar-benar aktif? Kalau tidak bisa dicek, anggap online.
    static boolean isOnline() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) appCtx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable e) { return true; }
    }

    // Sampul lagu yang diunduh disimpan di sebelah file audio supaya tetap tampil saat offline.
    static java.io.File coverFile(String id) {
        if (dlDir == null || !validId(id)) return null;
        java.io.File f = new java.io.File(dlDir, id + ".jpg");
        return f.isFile() ? f : null;
    }

    static void saveCover(String id) {
        try {
            byte[] b = bytes("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg", FF);
            if (b == null || dlDir == null || !validId(id)) return;
            try (java.io.FileOutputStream w = new java.io.FileOutputStream(new java.io.File(dlDir, id + ".jpg"))) { w.write(b); }
        } catch (Throwable e) { /* sampul opsional */ }
    }

    // Unduhan lama yang belum punya sampul dilengkapi saat online.
    static void backfillCovers() {
        try {
            if (!isOnline()) return;
            for (String id : new ArrayList<>(dlIndex.keySet())) if (downloadFile(id) != null && coverFile(id) == null) saveCover(id);
        } catch (Throwable e) { }
    }

    static java.io.File downloadFile(String id) {
        JSONObject o = dlIndex.get(id);
        if (o == null) return null;
        java.io.File f = new java.io.File(o.optString("path", ""));
        return f.isFile() ? f : null;
    }

    // id yang sedang diunduh (mencegah dua unduhan menulis ke file .part yang sama)
    static final java.util.Set<String> dlBusy = ConcurrentHashMap.newKeySet();

    static JSONObject downloadStateJson(String id) {
        JSONObject o = new JSONObject();
        try { o.put("state", downloadFile(id) != null ? "done" : dlBusy.contains(id) ? "busy" : "none"); } catch (Throwable e) { }
        return o;
    }

    static JSONArray downloadsList() {
        JSONArray out = new JSONArray();
        for (Map.Entry<String, JSONObject> e : dlIndex.entrySet()) {
            if (downloadFile(e.getKey()) == null) continue;
            try {
                JSONObject o = new JSONObject(e.getValue().toString());
                o.put("id", e.getKey());
                out.put(o);
            } catch (Throwable ex) { /* lewati entri rusak */ }
        }
        return out;
    }

    // JSON lagu dari data offline tersimpan saat jaringan/sumber online gagal total.
    static JSONObject offlineTrackJson(String id) {
        JSONObject meta = dlIndex.get(id);
        if (meta == null || downloadFile(id) == null) return null;
        try {
            Track stub = new Track();
            stub.title = meta.optString("title", "");
            stub.artist = meta.optString("artist", "");
            JSONObject o = new JSONObject();
            o.put("title", stub.title.isEmpty() ? id : stub.title);
            o.put("artist", stub.artist.isEmpty() ? "Offline" : stub.artist);
            o.put("metaDur", "");
            o.put("previewNote", "Offline (tersimpan)");
            o.put("audioUrl", "/audio/" + id);
            o.put("imageSrc", "/thumb/" + id);
            Object[] ly = lyLoad(id); // hanya cache disk, supaya lagu offline langsung mulai tanpa menunggu jaringan
            o.put("lyrics", ly != null ? ly[0] : new JSONArray());
            o.put("lyricsKind", ly != null ? ly[1] : "synced");
            o.put("lyricsDbg", "offline");
            return o;
        } catch (Exception e) { return null; }
    }

    static JSONObject trackJson(String id) throws Exception {
        Track t = track(id);
        String artist = cleanArtist(t.artist);
        JSONObject o = new JSONObject();
        o.put("title", t.title);
        o.put("artist", artist.isEmpty() ? "YouTube" : artist);
        o.put("metaDur", fmt(t.seconds));
        o.put("previewNote", "");
        o.put("audioUrl", "/audio/" + id);
        o.put("imageSrc", "/thumb/" + id);
        // Lirik TIDAK dicari di sini (bisa belasan detik). Kalau sudah ada di cache dipakai langsung,
        // kalau belum pemutar mengambilnya terpisah lewat /api/lyrics/ setelah audio mulai.
        Object[] ly = lyricsCached(id, t);
        o.put("lyrics", ly != null ? ly[0] : new JSONArray());
        o.put("lyricsKind", ly != null ? ly[1] : "synced");
        o.put("lyricsDbg", ly != null ? ly[2] : "");
        o.put("lyricsPending", ly == null);
        return o;
    }

    // Lirik dari cache memori/disk saja, tanpa jaringan. null kalau belum ada.
    static Object[] lyricsCached(String id, Track t) {
        if (t.lyr == null) {
            Object[] c = lyLoad(id);
            if (c != null) { t.lyr = (JSONArray) c[0]; t.kind = (String) c[1]; }
        }
        if (t.lyr != null) return new Object[]{t.lyr, t.kind, ""};
        if (lyNone(id)) return new Object[]{new JSONArray(), "synced", LY_NONE_MSG};
        return null;
    }

    // Pencarian lirik lengkap (boleh lambat), dipanggil terpisah oleh pemutar.
    static JSONObject lyricsJson(String id) throws Exception {
        Track t = track(id);
        Object[] ly = lyrics(id, t);
        JSONObject o = new JSONObject();
        o.put("lyrics", ly[0]);
        o.put("lyricsKind", ly[1]);
        o.put("lyricsDbg", ly[2]);
        return o;
    }

    // ---------- cache lirik di disk ----------
    private static java.io.File lyDir;
    private static final int LY_MAX = 400, LY_KEEP = 300;

    private static android.content.Context appCtx;

    static void initDirs(android.content.Context c) {
        appCtx = c;
        java.io.File d = new java.io.File(c.getFilesDir(), "lyrics");
        d.mkdirs();
        lyDir = d;
        if (!dlLoaded) {
            synchronized (Backend.class) {
                if (!dlLoaded) {
                    java.io.File ext = c.getExternalFilesDir("offline");
                    dlDir = ext != null ? ext : new java.io.File(c.getFilesDir(), "offline");
                    dlDir.mkdirs();
                    dlIndexFile = new java.io.File(c.getFilesDir(), "downloads.json");
                    loadDlIndex();
                    dlLoaded = true;
                }
            }
        }
    }

    static java.io.File downloadDir() { return dlDir; }

    // Hapus lirik tersimpan (memori + disk) supaya diambil ulang dari lrclib / subtitle
    static void lyricsReset(String id) {
        Track t = cache.get(id);
        if (t != null) { t.lyr = null; t.kind = null; }
        try { if (lyDir != null && validId(id)) new java.io.File(lyDir, id + ".json").delete(); } catch (Throwable e) { }
        lyClearNone(id);
    }

    // Lagu yang sudah dicari tapi liriknya memang tidak ada dicatat 7 hari, supaya tidak dicari ulang tiap diputar.
    // Hanya dicatat kalau layanan lirik benar-benar menjawab (bukan karena jaringan putus).
    private static final long LY_NONE_MS = 7L * 24 * 3600 * 1000;
    private static final String LY_NONE_MSG = "tidak ditemukan, dicari ulang tiap 7 hari";

    static boolean lyNone(String id) {
        try {
            if (lyDir == null || !validId(id)) return false;
            java.io.File f = new java.io.File(lyDir, id + ".none");
            return f.isFile() && System.currentTimeMillis() - f.lastModified() < LY_NONE_MS;
        } catch (Throwable e) { return false; }
    }

    private static void lyMarkNone(String id) {
        try {
            if (lyDir == null || !validId(id)) return;
            java.io.File f = new java.io.File(lyDir, id + ".none");
            if (!f.exists()) f.createNewFile();
            f.setLastModified(System.currentTimeMillis());
        } catch (Throwable e) { }
    }

    private static void lyClearNone(String id) {
        try { if (lyDir != null && validId(id)) new java.io.File(lyDir, id + ".none").delete(); } catch (Throwable e) { }
    }

    private static Object[] lyLoad(String id) {
        try {
            if (lyDir == null || !validId(id)) return null;
            java.io.File f = new java.io.File(lyDir, id + ".json");
            if (!f.isFile()) return null;
            byte[] raw;
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { raw = readAll(in); }
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            JSONArray a = o.getJSONArray("l");
            if (a.length() == 0) return null;
            f.setLastModified(System.currentTimeMillis()); // penanda "terakhir dipakai" untuk pembersihan
            return new Object[]{a, o.optString("k", "synced")};
        } catch (Throwable e) { return null; }
    }

    private static void lySave(String id, JSONArray l, String kind) {
        try {
            if (lyDir == null || !validId(id)) return;
            lyClearNone(id);
            JSONObject o = new JSONObject().put("k", kind).put("l", l);
            java.io.File tmp = new java.io.File(lyDir, id + ".tmp");
            try (java.io.FileOutputStream w = new java.io.FileOutputStream(tmp)) { w.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
            if (!tmp.renameTo(new java.io.File(lyDir, id + ".json"))) tmp.delete();
            java.io.File[] fs = lyDir.listFiles();
            if (fs != null && fs.length > LY_MAX) {
                Arrays.sort(fs, (x, y) -> Long.compare(x.lastModified(), y.lastModified()));
                for (int i = 0; i < fs.length - LY_KEEP; i++) fs[i].delete();
            }
        } catch (Throwable e) { /* cache opsional */ }
    }

    // ---------- lirik ----------
    // Urutan: lrclib (tersinkron) -> lrclib (teks biasa, waktu diperkirakan) -> subtitle YouTube
    static Object[] lyrics(String id, Track t) {
        if (t.lyr == null) {
            Object[] c = lyLoad(id);
            if (c != null) { t.lyr = (JSONArray) c[0]; t.kind = (String) c[1]; }
        }
        if (t.lyr != null) return new Object[]{t.lyr, t.kind, ""};
        if (lyNone(id)) return new Object[]{new JSONArray(), "synced", LY_NONE_MSG};
        JSONArray out = new JSONArray();
        String kind = "synced";
        long dl = System.currentTimeMillis() + 12000;
        int g = 0, sr = 0;
        boolean reached = false;
        try {
            String ti = cleanTitle(t.title), ch = cleanArtist(t.artist);
            List<String[]> cands = new ArrayList<>();
            int i = ti.indexOf(" - ");
            if (i > 0) {
                String a = ti.substring(0, i).trim(), b = ti.substring(i + 3).trim();
                cands.add(new String[]{a, b});
                cands.add(new String[]{b, a});
            }
            if (!ch.isEmpty()) cands.add(new String[]{ch, i > 0 ? ti.substring(i + 3).trim() : ti});
            String synced = "", plain = "";
            for (String[] c : cands) {
                if (c[0].isEmpty() || c[1].isEmpty()) continue;
                if (System.currentTimeMillis() > dl) break;
                g++;
                Object o = lrc("get?track_name=" + enc(c[1]) + "&artist_name=" + enc(c[0]));
                if (o instanceof JSONObject) {
                    JSONObject j = (JSONObject) o;
                    if (!synced(j).trim().isEmpty()) { synced = synced(j); break; }
                    if (plain.isEmpty()) plain = plainOf(j);
                }
            }
            if (synced.isEmpty()) {
                String[] qs = {(i > 0 ? ti.replace(" - ", " ") : ch + " " + ti).trim(), ti};
                JSONObject bestS = null, bestP = null;
                double dS = 1e9, dP = 1e9;
                for (String q : new LinkedHashSet<>(Arrays.asList(qs))) {
                    if (System.currentTimeMillis() > dl) break;
                    sr++;
                    Object o = lrc("search?q=" + enc(q));
                    if (!(o instanceof JSONArray)) continue;
                    reached = true;
                    JSONArray arr = (JSONArray) o;
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject x = arr.optJSONObject(k);
                        if (x == null) continue;
                        double d = t.seconds > 0 ? Math.abs(x.optDouble("duration", 0) - t.seconds) : 0;
                        if (d > 20) continue;
                        if (!synced(x).trim().isEmpty() && d < dS) { bestS = x; dS = d; }
                        if (!plainOf(x).isEmpty() && d < dP) { bestP = x; dP = d; }
                    }
                    if (bestS != null) break;
                }
                if (bestS != null) synced = synced(bestS);
                else if (plain.isEmpty() && bestP != null) plain = plainOf(bestP);
            }
            if (!synced.isEmpty()) {
                out = parseLrc(synced);
                // Paket 13: kalau ketemu lirik tersinkron, coba tempel waktu kata asli dari NetEase (opsional,
                // gagal diam-diam kalau gak ketemu/gak cocok — lirik & karaoke tebakan tetap jalan seperti biasa)
                if (out.length() > 0) neEnrich(out, t.title, t.artist, t.seconds);
            }
            else if (!plain.isEmpty()) { out = fromPlain(plain, t.seconds); kind = "plain"; }
            if (out.length() == 0 && t.subUrl != null) {
                byte[] b = bytes(t.subUrl, FF);
                if (b != null) { out = parseVtt(new String(b, StandardCharsets.UTF_8), t.subAuto); kind = "caption"; }
            }
        } catch (Throwable e) { /* lirik opsional */ }
        if (out.length() > 0) { t.lyr = out; t.kind = kind; lySave(id, out, kind); }
        else if (reached) lyMarkNone(id);
        String d = "get " + g + "x, cari " + sr + "x, subtitle " + (t.subUrl != null ? "ada" : "tidak ada") + ", hasil " + out.length() + " baris";
        return new Object[]{out, kind, d};
    }

    private static String plainOf(JSONObject o) { return o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "").trim(); }

    // Tag waktu per-kata di LRC "enhanced": <mm:ss.xx>kata <mm:ss.xx>kata ...
    private static final Pattern WRE = Pattern.compile("<(\\d+):(\\d+(?:\\.\\d+)?)>");

    private static JSONArray parseLrc(String raw) throws Exception {
        Pattern re = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        List<Object[]> rows = new ArrayList<>(); // {Double time, String text, List<Double> wStart, List<String> wText}
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = re.matcher(line);
            List<Double> times = new ArrayList<>();
            while (m.find()) {
                double f = m.group(3) != null ? Double.parseDouble("0." + m.group(3)) : 0;
                times.add(Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) + f);
            }
            String rest = re.matcher(line).replaceAll("").trim();
            if (times.isEmpty() || rest.isEmpty()) continue;
            // Kata berwaktu cuma dipakai kalau baris ini punya persis 1 waktu (baris berulang/reff jarang dan rumit kalau dicampur)
            List<Double> wStart = null; List<String> wText = null;
            if (times.size() == 1) {
                Matcher wm = WRE.matcher(rest);
                List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>(); List<Double> wt = new ArrayList<>();
                while (wm.find()) {
                    wt.add(Integer.parseInt(wm.group(1)) * 60 + Double.parseDouble(wm.group(2)));
                    starts.add(wm.start()); ends.add(wm.end());
                }
                if (!wt.isEmpty()) {
                    wStart = new ArrayList<>(); wText = new ArrayList<>();
                    for (int wi = 0; wi < ends.size(); wi++) {
                        int from = ends.get(wi), to = wi + 1 < ends.size() ? starts.get(wi + 1) : rest.length();
                        String wtxt = rest.substring(from, to).trim();
                        if (!wtxt.isEmpty()) { wStart.add(wt.get(wi)); wText.add(wtxt); }
                    }
                }
            }
            String text = WRE.matcher(rest).replaceAll("").trim();
            if (text.isEmpty()) continue;
            if (text.length() > 120) text = text.substring(0, 120);
            for (double d : times) rows.add(new Object[]{d, text, wStart, wText});
        }
        Collections.sort(rows, (a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        JSONArray out = new JSONArray();
        for (int ri = 0; ri < rows.size(); ri++) {
            Object[] r = rows.get(ri);
            JSONObject row = new JSONObject().put("time", (Double) r[0]).put("text", (String) r[1]);
            @SuppressWarnings("unchecked") List<Double> wS = (List<Double>) r[2];
            @SuppressWarnings("unchecked") List<String> wT = (List<String>) r[3];
            if (wS != null && !wS.isEmpty()) {
                double lineEnd = ri + 1 < rows.size() ? (Double) rows.get(ri + 1)[0] : wS.get(wS.size() - 1) + 3;
                JSONArray words = new JSONArray();
                for (int wi = 0; wi < wS.size(); wi++) {
                    double s = wS.get(wi);
                    double e = wi + 1 < wS.size() ? wS.get(wi + 1) : Math.max(s + 0.2, lineEnd);
                    words.put(new JSONObject().put("s", s).put("e", e).put("t", wT.get(wi)));
                }
                row.put("words", words);
            }
            out.put(row);
        }
        return out;
    }

    // ===== Paket 13: kata-per-kata asli dari NetEase (YRC), ditempel ke lirik lrclib yang sedang tampil =====
    // Hati-hati: ini satu-satunya bagian di berkas ini yang BELUM pernah diuji ke API aslinya.
    // Algoritmanya dikonfirmasi dari implementasi independen yang stabil, tapi tetap ada risiko kalau NetEase ubah sesuatu.
    private static final String NE_EAPI_KEY = "e82ckenh8dichen8";
    private static final Pattern NE_CJK = Pattern.compile("[\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7A3]");
    private static final Pattern NE_LINE_RE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern NE_WORD_RE = Pattern.compile("\\((\\d+),(\\d+)(?:,\\d+)?\\)([^(]*)");

    private static String neHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) { sb.append(Character.forDigit((x >> 4) & 0xF, 16)); sb.append(Character.forDigit(x & 0xF, 16)); }
        return sb.toString();
    }
    private static byte[] neAesEcb(String key, byte[] data) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        return c.doFinal(data);
    }
    private static String neEapiParams(String path, String json) throws Exception {
        String digest = neHex(java.security.MessageDigest.getInstance("MD5").digest(("nobody" + path + "use" + json + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
        String msg = path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest;
        return neHex(neAesEcb(NE_EAPI_KEY, msg.getBytes(StandardCharsets.UTF_8))).toUpperCase();
    }
    private static byte[] nePostEapi(String apiPath, String json, int ms) {
        try {
            String params = neEapiParams(apiPath, json);
            byte[] bb = ("params=" + params).getBytes(StandardCharsets.UTF_8);
            HttpURLConnection c = (HttpURLConnection) new URL("https://interface3.music.163.com" + apiPath.replaceFirst("^/api/", "/eapi/")).openConnection();
            c.setConnectTimeout(ms); c.setReadTimeout(ms);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("User-Agent", FF);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setRequestProperty("Referer", "https://music.163.com/");
            c.setRequestProperty("Cookie", "os=pc; appver=8.9.70");
            c.setFixedLengthStreamingMode(bb.length);
            try (java.io.OutputStream os = c.getOutputStream()) { os.write(bb); }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } catch (Throwable e) { return null; }
    }

    private static JSONArray neSearch(String q, int ms) {
        try {
            byte[] b = bytes("http://music.163.com/api/search/get/web?s=" + enc(q) + "&type=1&offset=0&limit=8", FF, ms);
            if (b == null) return null;
            Object o = new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
            JSONObject result = o instanceof JSONObject ? ((JSONObject) o).optJSONObject("result") : null;
            return result == null ? null : result.optJSONArray("songs");
        } catch (Throwable e) { return null; }
    }

    private static String neSimple(String s) {
        if (s == null) return "";
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) continue;
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
    private static boolean neOverlaps(String a, String b) { return !a.isEmpty() && !b.isEmpty() && (a.equals(b) || a.contains(b) || b.contains(a)); }

    private static long nePickSongId(JSONArray songs, String title, String artist, long durSec) {
        if (songs == null) return -1;
        String t = neSimple(title);
        String[] artists = artist == null || artist.isEmpty() ? new String[0] : artist.split("\\s*(?:,|&|/|\\+|\\bx\\b|\\band\\b|\\bfeat\\.?|\\bft\\.?)\\s*");
        for (int i = 0; i < songs.length(); i++) {
            JSONObject s = songs.optJSONObject(i);
            if (s == null) continue;
            long len = s.optLong("duration", 0) / 1000;
            if (durSec > 0 && len > 0 && Math.abs(len - durSec) > 3) continue;
            if (!neOverlaps(neSimple(s.optString("name", "")), t)) continue;
            JSONArray ar = s.optJSONArray("artists");
            boolean artistOk = artists.length == 0 || ar == null;
            if (!artistOk) {
                for (int ai = 0; ai < ar.length() && !artistOk; ai++) {
                    JSONObject one = ar.optJSONObject(ai);
                    String cand = neSimple(one == null ? "" : one.optString("name", ""));
                    for (String a : artists) if (neOverlaps(neSimple(a), cand)) { artistOk = true; break; }
                }
            }
            if (!artistOk) continue;
            return s.optLong("id", -1);
        }
        return -1;
    }

    private static class NeWord { String text; double start, end; }
    private static class NeLine { String text; double start, end; List<NeWord> words = new ArrayList<>(); }

    private static NeWord neFlush(NeWord w, List<NeWord> out) {
        if (w != null) { w.text = w.text.trim(); if (!w.text.isEmpty()) out.add(w); }
        return null;
    }
    private static List<NeWord> neParseWords(String rest) {
        List<NeWord> out = new ArrayList<>();
        Matcher m = NE_WORD_RE.matcher(rest);
        NeWord cur = null;
        while (m.find()) {
            double start = Double.parseDouble(m.group(1)) / 1000.0;
            double end = (Double.parseDouble(m.group(1)) + Double.parseDouble(m.group(2))) / 1000.0;
            String piece = m.group(3);
            boolean isCjk = NE_CJK.matcher(piece).find();
            if (cur != null && (isCjk || NE_CJK.matcher(cur.text).find())) cur = neFlush(cur, out);
            if (cur == null) { cur = new NeWord(); cur.text = piece; cur.start = start; cur.end = end; }
            else { cur.text += piece; cur.end = Math.max(cur.end, end); }
            if (Pattern.compile("\\s").matcher(piece).find() || isCjk) cur = neFlush(cur, out);
        }
        neFlush(cur, out);
        return out;
    }
    private static List<NeLine> neParseYrc(String text) {
        List<NeLine> lines = new ArrayList<>();
        if (text == null) return lines;
        Pattern credit = Pattern.compile("^(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|录音|监制|出品|发行)\\s*[:：]");
        for (String raw : text.split("\n")) {
            Matcher m = NE_LINE_RE.matcher(raw.trim());
            if (!m.find()) continue;
            List<NeWord> words = neParseWords(m.group(3));
            if (words.isEmpty()) continue;
            StringBuilder full = new StringBuilder();
            for (NeWord w : words) { if (full.length() > 0) full.append(' '); full.append(w.text); }
            String ft = full.toString().replaceAll("\\s+", " ").trim();
            if (ft.isEmpty() || credit.matcher(ft).find()) continue;
            NeLine ln = new NeLine();
            ln.start = Double.parseDouble(m.group(1)) / 1000.0;
            ln.end = (Double.parseDouble(m.group(1)) + Double.parseDouble(m.group(2))) / 1000.0;
            ln.text = ft; ln.words = words;
            lines.add(ln);
        }
        return lines;
    }
    private static boolean neSameWord(String a, String b) { return a.equals(b) || (a.length() >= 4 && b.length() >= 4 && (a.startsWith(b) || b.startsWith(a))); }
    private static List<String> neLineTokens(String text) {
        List<String> out = new ArrayList<>();
        String clean = text == null ? "" : text.replaceAll("<[^>]+>", "").trim();
        if (clean.isEmpty()) return out;
        for (String w : clean.split("\\s+")) {
            if (w.isEmpty()) continue;
            if (NE_CJK.matcher(w).find()) {
                w.codePoints().forEach(cp -> { String ch = new String(Character.toChars(cp)); if (!neSimple(ch).isEmpty()) out.add(ch); });
            } else out.add(w);
        }
        return out;
    }
    private static class NePoolWord { String n; double start, end; }

    private static void neAlignWordTimings(JSONArray rows, List<NeLine> neLines) {
        try {
            int n = rows.length();
            List<Integer> shown = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null || row.has("words")) continue;
                if (!row.optString("text", "").isEmpty()) shown.add(i);
            }
            List<NePoolWord> pool = new ArrayList<>();
            for (NeLine wl : neLines) for (NeWord w : wl.words) {
                String ns = neSimple(w.text);
                if (!ns.isEmpty()) { NePoolWord pw = new NePoolWord(); pw.n = ns; pw.start = w.start; pw.end = w.end; pool.add(pw); }
            }
            if (shown.isEmpty() || pool.isEmpty()) return;

            List<Object[]> matches = new ArrayList<>();
            int p = 0;
            for (int k = 0; k < shown.size(); k++) {
                int i = shown.get(k);
                JSONObject row = rows.getJSONObject(i);
                double time = row.optDouble("time", 0);
                String text = row.optString("text", "");
                double next = k + 1 < shown.size() ? rows.getJSONObject(shown.get(k + 1)).optDouble("time", time + 8) : time + 8;
                List<String> toks = neLineTokens(text);
                if (toks.isEmpty()) continue;
                NePoolWord[] hit = new NePoolWord[toks.size()];
                int found = 0;
                while (p < pool.size() && pool.get(p).start < time - 2.5) p++;
                int q = p;
                for (int j = 0; j < toks.size(); j++) {
                    String ns = neSimple(toks.get(j));
                    for (int c = q; c < pool.size() && pool.get(c).start <= next + 2.5; c++) {
                        if (!ns.isEmpty() && neSameWord(pool.get(c).n, ns)) { hit[j] = pool.get(c); q = c + 1; found++; break; }
                    }
                }
                if ((double) found / toks.size() < 0.7) continue;
                p = q;
                matches.add(new Object[]{i, next, toks, hit});
            }
            if (matches.isEmpty() || (double) matches.size() / shown.size() < 0.6) return;

            List<Double> gaps = new ArrayList<>();
            for (Object[] mm : matches) {
                NePoolWord[] hit = (NePoolWord[]) mm[3];
                if (hit.length > 0 && hit[0] != null) gaps.add(rows.getJSONObject((Integer) mm[0]).optDouble("time", 0) - hit[0].start);
            }
            Collections.sort(gaps);
            double median = gaps.isEmpty() ? 0 : gaps.get(gaps.size() / 2);
            double shift = Math.abs(median) > 0.25 ? median : 0;

            for (Object[] mm : matches) {
                int i = (Integer) mm[0];
                double next = (Double) mm[1];
                @SuppressWarnings("unchecked") List<String> toks = (List<String>) mm[2];
                NePoolWord[] hit = (NePoolWord[]) mm[3];
                double time = rows.getJSONObject(i).optDouble("time", 0);
                int m = toks.size();
                Double[] ws = new Double[m], we = new Double[m];
                for (int j = 0; j < m; j++) if (hit[j] != null) { ws[j] = hit[j].start + shift; we[j] = hit[j].end + shift; }
                int j = 0;
                while (j < m) {
                    if (ws[j] != null) { j++; continue; }
                    int e = j; while (e < m && ws[e] == null) e++;
                    double from = j > 0 ? we[j - 1] : Math.min(time, e < m ? ws[e] : time);
                    double to = e < m ? ws[e] : Math.max(from + 0.3 * (e - j), Math.min(next, from + 0.6 * (e - j)));
                    double step = Math.max(0, to - from) / (e - j);
                    for (int m2 = j; m2 < e; m2++) { ws[m2] = from + step * (m2 - j); we[m2] = from + step * (m2 - j + 1); }
                    j = e;
                }
                JSONArray words = new JSONArray();
                for (int j2 = 0; j2 < m; j2++) words.put(new JSONObject().put("s", Math.round(ws[j2] * 1000) / 1000.0).put("e", Math.round(we[j2] * 1000) / 1000.0).put("t", toks.get(j2)));
                rows.getJSONObject(i).put("words", words);
            }
        } catch (Throwable e) { /* penyelarasan opsional, gagal diam-diam */ }
    }

    private static void neEnrich(JSONArray out, String title, String artist, long durSec) {
        String dbg = (title == null ? "?" : title) + " – " + (artist == null ? "?" : artist);
        try {
            String q = (cleanArtist(artist) + " " + cleanTitle(title)).trim();
            if (q.isEmpty()) return;
            JSONArray songs = neSearch(q, 4000);
            if (songs == null) return;
            long id = nePickSongId(songs, cleanTitle(title), artist, durSec);
            if (id <= 0) return;
            String json = "{\"id\":" + id + ",\"cp\":false,\"tv\":0,\"lv\":0,\"rv\":0,\"kv\":0,\"yv\":0,\"ytv\":0,\"yrv\":0}";
            byte[] b = nePostEapi("/api/song/lyric/v1", json, 4000);
            if (b == null) return;
            Object o = new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
            JSONObject yrcObj = o instanceof JSONObject ? ((JSONObject) o).optJSONObject("yrc") : null;
            String yrcText = yrcObj == null ? "" : yrcObj.optString("lyric", "");
            if (yrcText.trim().isEmpty()) return;
            List<NeLine> neLines = neParseYrc(yrcText);
            if (neLines.isEmpty()) return;
            neAlignWordTimings(out, neLines);
        } catch (Throwable e) { logErr("lirik-ne", dbg + " · error tak terduga: " + e); }
    }

    // Teks biasa tanpa waktu: baris dibagi rata sepanjang lagu (perkiraan)
    private static JSONArray fromPlain(String plain, long sec) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String l : plain.split("\\r?\\n")) { l = l.trim(); if (!l.isEmpty()) lines.add(l.length() > 120 ? l.substring(0, 120) : l); }
        JSONArray out = new JSONArray();
        if (lines.isEmpty()) return out;
        double start = sec > 40 ? 10 : 0, span = sec > 40 ? sec - 25 : Math.max(lines.size() * 3, 30);
        for (int k = 0; k < lines.size(); k++) out.put(new JSONObject().put("time", start + span * k / lines.size()).put("text", lines.get(k)));
        return out;
    }

    private static JSONArray parseVtt(String vtt, boolean auto) throws Exception {
        JSONArray out = new JSONArray();
        Pattern tm = Pattern.compile("(?:(\\d+):)?(\\d{2}):(\\d{2})\\.(\\d{3})\\s*-->");
        String[] ln = vtt.split("\\r?\\n");
        String prev = "";
        for (int k = 0; k < ln.length; k++) {
            Matcher m = tm.matcher(ln[k]);
            if (!m.find()) continue;
            double t = (m.group(1) != null ? Integer.parseInt(m.group(1)) * 3600 : 0) + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3)) + Integer.parseInt(m.group(4)) / 1000.0;
            List<String> tx = new ArrayList<>();
            for (int j = k + 1; j < ln.length && !ln[j].trim().isEmpty(); j++) {
                String c = ln[j].replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&gt;", ">").replace("&lt;", "<").replace("&#39;", "'").replace("&quot;", "\"").trim();
                if (!c.isEmpty()) tx.add(c);
            }
            if (tx.isEmpty()) continue;
            String text = auto ? tx.get(tx.size() - 1) : String.join(" ", tx);
            if (text.matches("(?i)^[\\[(♪\\s].*[\\])♪\\s]$") && text.length() < 20) continue;
            if (text.equals(prev)) continue;
            prev = text;
            out.put(new JSONObject().put("time", t).put("text", text.length() > 120 ? text.substring(0, 120) : text));
        }
        return out;
    }

    private static String synced(JSONObject o) { return o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", ""); }

    private static Object lrc(String path) {
        try {
            byte[] b = bytes("https://lrclib.net/api/" + path, "AlyaMusicApp/1.0", 5000);
            return b == null ? null : new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
        } catch (Exception e) { return null; }
    }

    // ---------- util ----------
    static byte[] bytes(String url, String ua) { return bytes(url, ua, 8000); }

    static byte[] bytes(String url, String ua, int ms) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(ms);
            c.setReadTimeout(ms);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Accept", "*/*");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } catch (Exception e) { return null; }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    static String fmt(long sec) {
        if (sec <= 0) return "";
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    static String cleanArtist(String n) {
        if (n == null) return "";
        return n.replaceAll("(?i)\\s*-\\s*Topic$", "").replaceAll("(?i)\\s*VEVO$", "").replaceAll("(?i)\\s*Official$", "").trim();
    }

    static String cleanTitle(String t) {
        if (t == null) return "";
        return t.replaceAll("(?i)[\\(\\[\\{][^\\)\\]\\}]*(official|video|audio|lyric|lyrics|lirik|mv|m/v|visualizer|hd|hq|4k|remaster|clip)[^\\)\\]\\}]*[\\)\\]\\}]", "")
                .replaceAll("(?i)\\s*[\\(\\[]?\\s*(feat\\.?|ft\\.?)\\s[^\\)\\]]*[\\)\\]]?", "")
                .replaceAll("\\s*\\|.*$", "")
                .replaceAll("\\s{2,}", " ")
                .trim();
    }

    // ---------- pengunduh untuk NewPipeExtractor ----------
    static final class Dl extends Downloader {
        @Override
        public Response execute(Request req) throws IOException, ReCaptchaException {
            HttpURLConnection c = (HttpURLConnection) new URL(req.url()).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(25000);
            c.setRequestMethod(req.httpMethod());
            c.setRequestProperty("User-Agent", FF);
            for (Map.Entry<String, List<String>> h : req.headers().entrySet()) {
                boolean first = true;
                for (String v : h.getValue()) {
                    if (first) c.setRequestProperty(h.getKey(), v); else c.addRequestProperty(h.getKey(), v);
                    first = false;
                }
            }
            byte[] data = req.dataToSend();
            if (data != null) {
                c.setDoOutput(true);
                c.getOutputStream().write(data);
            }
            int code = c.getResponseCode();
            if (code == 429) throw new ReCaptchaException("reCaptcha Challenge requested", req.url());
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
            Map<String, List<String>> hs = new java.util.HashMap<>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) hs.put(e.getKey(), e.getValue());
            }
            return new Response(code, c.getResponseMessage(), hs, body, c.getURL().toString());
        }
    }
}
