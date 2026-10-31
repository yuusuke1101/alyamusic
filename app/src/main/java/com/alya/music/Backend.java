package com.alya.music;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.schabi.newpipe.extractor.InfoItem;
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
    private static boolean ready;

    static final class Track {
        String title, artist, url, mime, subUrl, kind, src = "";
        int kbps;
        boolean subAuto;
        JSONArray lyr;
        long seconds, exp;
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
        if (!preferFallback && t != null && t.exp > System.currentTimeMillis()) return t;
        if (preferFallback) {
            try { t = fromPiped(id); cache.put(id, t); return t; } catch (Exception e) { /* coba sumber utama */ }
        }
        try {
            t = fromNewPipe(id);
        } catch (Exception primary) {
            npFail++; lastErr = primary.getClass().getSimpleName() + ": " + primary.getMessage();
            try { t = fromPiped(id); pipedUsed++; } catch (Exception backup) { throw primary; }
        }
        cache.put(id, t);
        return t;
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

    // Dipanggil tiap permintaan audio dari pemutar
    static void diagBegin(String id, Track t, long ttfb, int http) {
        if (!id.equals(curId)) { curId = id; reqs = 0; errs = 0; initKbps = 0; }
        curTrack = t; ttfbMs = ttfb; reqs++;
        if (http != 200 && http != 206) errs++;
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
        Object[] ly = lyrics(id, t);
        o.put("lyrics", ly[0]);
        o.put("lyricsKind", ly[1]);
        o.put("lyricsDbg", ly[2]);
        return o;
    }

    // ---------- cache lirik di disk ----------
    private static java.io.File lyDir;
    private static final int LY_MAX = 400, LY_KEEP = 300;

    static void initDirs(android.content.Context c) {
        java.io.File d = new java.io.File(c.getFilesDir(), "lyrics");
        d.mkdirs();
        lyDir = d;
    }

    // Hapus lirik tersimpan (memori + disk) supaya diambil ulang dari lrclib / subtitle
    static void lyricsReset(String id) {
        Track t = cache.get(id);
        if (t != null) { t.lyr = null; t.kind = null; }
        try { if (lyDir != null && validId(id)) new java.io.File(lyDir, id + ".json").delete(); } catch (Throwable e) { }
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
        JSONArray out = new JSONArray();
        String kind = "synced";
        long dl = System.currentTimeMillis() + 12000;
        int g = 0, sr = 0;
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
            if (!synced.isEmpty()) out = parseLrc(synced);
            else if (!plain.isEmpty()) { out = fromPlain(plain, t.seconds); kind = "plain"; }
            if (out.length() == 0 && t.subUrl != null) {
                byte[] b = bytes(t.subUrl, FF);
                if (b != null) { out = parseVtt(new String(b, StandardCharsets.UTF_8), t.subAuto); kind = "caption"; }
            }
        } catch (Throwable e) { /* lirik opsional */ }
        if (out.length() > 0) { t.lyr = out; t.kind = kind; lySave(id, out, kind); }
        String d = "get " + g + "x, cari " + sr + "x, subtitle " + (t.subUrl != null ? "ada" : "tidak ada") + ", hasil " + out.length() + " baris";
        return new Object[]{out, kind, d};
    }

    private static String plainOf(JSONObject o) { return o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "").trim(); }

    private static JSONArray parseLrc(String raw) throws Exception {
        Pattern re = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        List<Object[]> rows = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = re.matcher(line);
            List<Double> times = new ArrayList<>();
            while (m.find()) {
                double f = m.group(3) != null ? Double.parseDouble("0." + m.group(3)) : 0;
                times.add(Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) + f);
            }
            String text = re.matcher(line).replaceAll("").trim();
            if (times.isEmpty() || text.isEmpty()) continue;
            if (text.length() > 120) text = text.substring(0, 120);
            for (double d : times) rows.add(new Object[]{d, text});
        }
        Collections.sort(rows, (a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        JSONArray out = new JSONArray();
        for (Object[] r : rows) out.put(new JSONObject().put("time", (Double) r[0]).put("text", (String) r[1]));
        return out;
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
