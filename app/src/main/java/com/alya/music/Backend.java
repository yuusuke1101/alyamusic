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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

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
        boolean hls; // Paket 20: true kalau "url" itu manifest HLS (.m3u8), bukan file audio progresif langsung
        JSONArray lyr;
        long seconds, exp, touched;
    }

    static synchronized void init() {
        if (!ready) { NewPipe.init(new Dl()); ready = true; }
    }

    static boolean validId(String id) { return id != null && (id.matches("[A-Za-z0-9_-]{11}") || isSoundCloud(id)); }

    // ===== Paket 17: SoundCloud (Tahap 1 — cari & putar saja, BELUM bisa diunduh/offline) =====
    // NewPipeExtractor sudah punya dukungan SoundCloud bawaan (ServiceList.SoundCloud) — bukan scraper baru dari
    // nol kayak JOOX/NetEase, tinggal dipakai bareng ServiceList.YouTube yang sudah ada.
    //
    // Masalah utama: "id lagu" dipakai di MANA-MANA di app ini (unduhan, cache lirik, Firestore, berbagi ke
    // teman, widget, notifikasi) dan SELALU diasumsikan ID video YouTube 11 karakter. SoundCloud gak punya ID
    // pendek semacam itu — lagunya diidentifikasi lewat URL penuh. Supaya tetap AMAN dipakai buat nama file
    // (cache/unduhan dibangun langsung dari id, mis. `new File(dir, id + ".json")` — bisa jadi celah path
    // traversal kalau sampai ada "/" atau ".." di id), URL SoundCloud di-encode base64url TANPA padding dan
    // diberi awalan "sc". Hasilnya CUMA berisi huruf/angka/"-"/"_" — sama amannya dengan ID YouTube.
    private static final Pattern SC_ID_RE = Pattern.compile("^sc[A-Za-z0-9_-]{4,500}$");

    // true kalau "id" ini sebenarnya track SoundCloud yang di-encode (awalan "sc" + base64url URL aslinya)
    static boolean isSoundCloud(String id) { return id != null && SC_ID_RE.matcher(id).matches(); }

    // Base64url tanpa padding, dipakai buat nyimpen URL di dalam id/path yang aman buat nama file & jalur HTTP
    // (cuma huruf/angka/"-"/"_", gak ada "/" yang bisa disalahartikan sebagai pemisah folder).
    private static String b64uEnc(String s) {
        return android.util.Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }
    private static String b64uDec(String s) throws IllegalArgumentException {
        return new String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING), StandardCharsets.UTF_8);
    }
    private static String scEncode(String url) { return "sc" + b64uEnc(url); }
    // Kebalikan scEncode: dari id yang disimpan/dikirim client, balikin lagi jadi URL SoundCloud asli.
    // Null kalau bukan format SoundCloud atau gagal didecode (jangan sampai dipakai nyasar).
    private static String scDecode(String id) {
        if (!isSoundCloud(id)) return null;
        try { return b64uDec(id.substring(2)); } catch (Throwable e) { return null; }
    }
    // ===== Paket 20: proxy manifest/segmen HLS =====
    // Kenapa diproksi (bukan hls.js langsung ambil dari CDN SoundCloud): alur audio YouTube yang sudah ada
    // (/audio/<id>) juga diproksi lewat server ini, kemungkinan karena CDN-nya butuh header tertentu (User-Agent
    // dkk) yang gak bisa diset dari JS di WebView (User-Agent itu "forbidden header" buat fetch/XHR browser).
    // Daripada nebak CDN SoundCloud butuh header sama atau nggak, semua request HLS (manifest MAUPUN tiap
    // segmen/sub-manifest di dalamnya) dilewatkan proxy ini juga, pakai header yang sama (`FF`) seperti unduhan
    // lain di app ini — lebih aman walau sedikit lebih berat di server.
    static byte[] hlsProxy(String encodedUrl, String[] outMime) {
        try {
            String url = b64uDec(encodedUrl);
            byte[] b = bytes(url, FF);
            if (b == null) return null;
            String path = url.toLowerCase(java.util.Locale.ROOT);
            int q = path.indexOf('?'); if (q >= 0) path = path.substring(0, q);
            if (!path.endsWith(".m3u8")) {
                // Segmen (.ts/.aac/.mp4/dll) atau apa pun yang bukan manifest teks: teruskan apa adanya.
                outMime[0] = path.endsWith(".ts") ? "video/mp2t" : "application/octet-stream";
                return b;
            }
            // Manifest: tulis ulang tiap baris non-komentar (URL segmen ATAU sub-manifest, variant stream dkk)
            // supaya ikut lewat proxy ini juga — relatif terhadap URL manifest ini sendiri kalau bukan URL penuh.
            outMime[0] = "application/vnd.apple.mpegurl";
            String text = new String(b, StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            String[] lines = text.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                String raw = lines[i], t = raw.trim();
                if (t.isEmpty() || t.startsWith("#")) { out.append(raw); if (i < lines.length - 1) out.append('\n'); continue; }
                String abs;
                try { abs = new java.net.URL(new java.net.URL(url), t).toString(); } catch (Throwable e) { abs = t; }
                out.append("/hlsproxy/").append(b64uEnc(abs));
                if (i < lines.length - 1) out.append('\n');
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Throwable e) { logErr("hls", "proxy gagal: " + e); return null; }
    }

    // URL sampul SoundCloud gak bisa ditebak dari id-nya (beda sama YouTube yang polanya tetap `i.ytimg.com/vi/<id>/...`),
    // jadi disimpan di sini pas lagu itu muncul di hasil pencarian. Kalau server di-restart sebelum lagu diputar,
    // sampulnya cuma gak nongol (aman, bukan error) — lihat catatan buat AlyaServer.java di bawah.
    private static final Map<String, String> scThumbs = new ConcurrentHashMap<>();

    // Dipakai AlyaServer.java di rute /thumb/<id> (PERLU DIUBAH MANUAL, lihat catatan Paket 17 di CLAUDE.md) buat
    // tahu URL sampul mana yang harus diambil. Null kalau id SoundCloud yang sampulnya belum/gak diketahui
    // (server baru nyala lagi, dll) — rute itu sebaiknya balas 404 kalau null, bukan nebak-nebak bikin URL sendiri.
    static String thumbUrl(String id) {
        if (isSoundCloud(id)) return scThumbs.get(id);
        return id != null && id.matches("[A-Za-z0-9_-]{11}") ? "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg" : null;
    }

    static JSONArray searchSoundCloud(String q, int limit) {
        JSONArray out = new JSONArray();
        try {
            init();
            StreamingService sc = ServiceList.SoundCloud;
            SearchExtractor se = sc.getSearchExtractor(sc.getSearchQHFactory().fromQuery(q));
            se.fetchPage();
            for (InfoItem it : se.getInitialPage().getItems()) {
                if (!(it instanceof StreamInfoItem)) continue;
                StreamInfoItem s = (StreamInfoItem) it;
                String url = s.getUrl();
                if (url == null || url.isEmpty()) continue;
                String id = scEncode(url);
                String th = bestThumb(s.getThumbnails());
                if (!th.isEmpty()) scThumbs.put(id, th);
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("title", s.getName());
                o.put("author", cleanArtist(s.getUploaderName()));
                o.put("duration", fmt(s.getDuration()));
                o.put("thumb", th.isEmpty() ? "" : "/thumb/" + id);
                o.put("source", "soundcloud");
                out.put(o);
                if (out.length() >= limit) break;
            }
        } catch (Throwable e) { logErr("soundcloud", q + " · pencarian SoundCloud gagal: " + e); }
        return out;
    }

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
        // Paket 30: dulu CUMA ambil halaman pertama DAN dibatasi keras 12 hasil — jadi kerasa "dikit
        // banget" (laporan pemilik), padahal YouTube nawarin jauh lebih banyak. Sekarang diambil sampai
        // beberapa halaman tambahan (pola persis sama kayak paginasi playlist() di atas, yang sudah lama
        // ada dan terbukti jalan), dengan batas total dinaikkan ke 48. Dibatasi maks 4 halaman (bukan tanpa
        // batas) biar waktu tunggu pencarian tetap wajar.
        ListExtractor.InfoItemsPage<InfoItem> pg = se.getInitialPage();
        int pages = 0;
        while (pg != null) {
            for (InfoItem it : pg.getItems()) {
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
                o.put("source", "youtube");
                out.put(o);
                if (out.length() >= 48) break;
            }
            if (out.length() >= 48 || !pg.hasNextPage() || ++pages > 4) break;
            pg = se.getPage(pg.getNextPage());
        }
        return out;
    }

    // ---------- Paket 42: pencarian lagu tanpa batas (muat lagi saat pengguna menggulir) ----------
    // Satu pencarian disimpan di memori (daftar hasil + halaman terakhir) supaya halaman berikutnya bisa
    // diambil lanjutannya, bukan mulai dari awal. Disimpan maks. 4 pencarian, kedaluwarsa 10 menit.
    private static class MoreState {
        SearchExtractor se; ListExtractor.InfoItemsPage<InfoItem> last; boolean done; long t;
        final java.util.ArrayList<JSONObject> all = new java.util.ArrayList<>();
        final java.util.HashSet<String> ids = new java.util.HashSet<>();
    }
    private static final java.util.LinkedHashMap<String, MoreState> moreCache = new java.util.LinkedHashMap<>();
    private static final Object moreLock = new Object();

    static JSONArray searchMore(String q, int offset, int count) throws Exception {
        synchronized (moreLock) {
            init();
            long now = System.currentTimeMillis();
            java.util.Iterator<java.util.Map.Entry<String, MoreState>> ei = moreCache.entrySet().iterator();
            while (ei.hasNext()) if (now - ei.next().getValue().t > 600000L) ei.remove();
            MoreState ms = moreCache.get(q);
            if (ms == null) {
                ms = new MoreState();
                StreamingService yt = ServiceList.YouTube;
                ms.se = yt.getSearchExtractor(yt.getSearchQHFactory().fromQuery(q));
                moreCache.put(q, ms);
                while (moreCache.size() > 4) { String eldest = moreCache.keySet().iterator().next(); moreCache.remove(eldest); }
            }
            ms.t = now;
            int guard = 0;
            while (ms.all.size() < offset + count && !ms.done && guard++ < 6) {
                ListExtractor.InfoItemsPage<InfoItem> pg;
                if (ms.last == null) { ms.se.fetchPage(); pg = ms.se.getInitialPage(); }
                else if (ms.last.hasNextPage()) pg = ms.se.getPage(ms.last.getNextPage());
                else { ms.done = true; break; }
                ms.last = pg;
                for (InfoItem item : pg.getItems()) {
                    if (!(item instanceof StreamInfoItem)) continue;
                    StreamInfoItem s = (StreamInfoItem) item;
                    String url = s.getUrl();
                    int k = url == null ? -1 : url.indexOf("v=");
                    if (k < 0 || url.length() < k + 13) continue;
                    String id = url.substring(k + 2, k + 13);
                    if (!validId(id) || !ms.ids.add(id)) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("title", s.getName());
                    o.put("author", cleanArtist(s.getUploaderName()));
                    o.put("duration", fmt(s.getDuration()));
                    o.put("thumb", "/thumb/" + id);
                    o.put("source", "youtube");
                    ms.all.add(o);
                }
                if (!pg.hasNextPage()) ms.done = true;
            }
            JSONArray out = new JSONArray();
            for (int i = Math.max(0, offset); i < ms.all.size() && out.length() < count; i++) out.put(ms.all.get(i));
            return out;
        }
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
        // Paket 30: sama kayak search() di atas — dulu cuma halaman pertama + batas 15, sekarang sampai 2
        // halaman + batas 30 (filter artis/album/playlist biasanya hasilnya lebih sedikit relevan daripada
        // pencarian lagu biasa, jadi gak perlu sebanyak search() di atas).
        ListExtractor.InfoItemsPage<InfoItem> pg2 = se.getInitialPage();
        int pages2 = 0;
        while (pg2 != null) {
            for (InfoItem it : pg2.getItems()) {
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
                if (out.length() >= 30) break;
            }
            if (out.length() >= 30 || !pg2.hasNextPage() || ++pages2 > 2) break;
            pg2 = se.getPage(pg2.getNextPage());
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
        // Piped itu proxy YouTube-only (bukan NewPipeExtractor beneran, API situs lain), gak ada gunanya buat
        // SoundCloud — langsung lewati ke NewPipeExtractor (yang memang sudah ngerti SoundCloud lewat
        // ServiceList.SoundCloud), tanpa cadangan Piped kalau gagal.
        boolean sc = isSoundCloud(id);
        if (preferFallback && !sc) {
            try { t = fromPiped(id); cache.put(id, t); touch(t); return t; } catch (Exception e) { /* coba sumber utama */ }
        }
        try {
            t = fromNewPipe(id);
        } catch (Exception primary) {
            npFail++; lastErr = primary.getClass().getSimpleName() + ": " + primary.getMessage();
            logErr("ekstraksi", id + " · NewPipe gagal: " + lastErr);
            if (sc) throw primary;
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
        String scUrl = scDecode(id);
        StreamInfo info = scUrl != null
                ? StreamInfo.getInfo(ServiceList.SoundCloud, scUrl)
                : StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=" + id);
        // Paket 17: lebih suka progresif (file langsung, lebih simpel/murah). Paket 20: kalau progresif gak
        // ada sama sekali (umum buat SoundCloud terbaru/Go+), HLS dipakai sebagai cadangan — lewat hls.js +
        // proxy manifest (lihat hlsProxy), bukan ditolak total kayak sebelumnya.
        AudioStream best = null, bestHls = null;
        for (AudioStream s : info.getAudioStreams()) {
            if (s.getDeliveryMethod() == DeliveryMethod.HLS) { if (bestHls == null || s.getAverageBitrate() > bestHls.getAverageBitrate()) bestHls = s; continue; }
            if (s.getDeliveryMethod() != DeliveryMethod.PROGRESSIVE_HTTP) continue;
            boolean m4a = s.getFormat() == MediaFormat.M4A;
            boolean bestM4a = best != null && best.getFormat() == MediaFormat.M4A;
            if (best == null || (m4a && !bestM4a) || (m4a == bestM4a && s.getAverageBitrate() > best.getAverageBitrate())) best = s;
        }
        boolean useHls = best == null && bestHls != null;
        if (best == null && bestHls == null) throw new Exception("Tidak ada aliran audio yang bisa diputar");
        AudioStream chosen = useHls ? bestHls : best;
        t = new Track();
        t.hls = useHls;
        try {
            SubtitlesStream bs = null;
            for (SubtitlesStream ss : info.getSubtitles()) {
                if (ss.getFormat() != MediaFormat.VTT || ss.getContent() == null) continue;
                if (bs == null || (bs.isAutoGenerated() && !ss.isAutoGenerated())) bs = ss;
            }
            if (bs != null) { t.subUrl = bs.getContent(); t.subAuto = bs.isAutoGenerated(); }
        } catch (Throwable e) { /* subtitle opsional */ }
        // Paket 17 susulan: hasil pencarian SoundCloud kadang gak sertain sampul (listing ringkas), padahal info
        // lengkap begini biasanya punya. Simpan/perbarui di sini juga, jaga-jaga, biar kesempatan kedua (lagu
        // sudah pernah diputar) sampulnya kesimpan buat lain kali.
        if (scUrl != null) {
            try { String th = bestThumb(info.getThumbnails()); if (!th.isEmpty()) scThumbs.put(id, th); } catch (Throwable e) { /* opsional */ }
        }
        t.src = scUrl != null ? "SoundCloud" : "NewPipe";
        t.kbps = Math.max(0, chosen.getAverageBitrate());
        t.title = info.getName();
        t.artist = info.getUploaderName();
        t.seconds = info.getDuration();
        t.url = chosen.getContent();
        t.mime = useHls ? "application/vnd.apple.mpegurl" : (chosen.getFormat() != null ? chosen.getFormat().getMimeType() : "audio/mp4");
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
        // Paket 20: lagu HLS (manifest .m3u8) gak lewat /audio/<id> yang nge-proxy SATU file utuh dengan
        // dukungan Range — manifest berisi RUJUKAN ke banyak file segmen terpisah, jadi perlu dikasih langsung
        // ke hls.js lewat /hlsproxy/<url terenkode> (hls.js sendiri yang nanti ambilin tiap segmennya, juga
        // lewat /hlsproxy/ karena sudah ditulis ulang begitu di dalam manifestnya — lihat hlsProxy()).
        o.put("audioUrl", t.hls ? "/hlsproxy/" + b64uEnc(t.url) : "/audio/" + id);
        o.put("hls", t.hls);
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
    static JSONObject lyricsJson(String id, String pref) throws Exception {
        Track t = track(id);
        Object[] ly = lyrics(id, t, pref);
        JSONObject o = new JSONObject();
        o.put("lyrics", ly[0]);
        o.put("lyricsKind", ly[1]);
        o.put("lyricsDbg", ly[2]);
        o.put("lyricsSrc", ly[3]); // Paket 31: sumber yang beneran kepakai, dipakai player.html buat nampilin menunya
        return o;
    }

    // ===== Paket 32: terjemahan lirik otomatis =====
    // Pakai endpoint Google Translate gratis (tanpa kunci/akun) — SAMA kelasnya dengan JOOX/NetEase di proyek
    // ini: bukan API resmi, bisa berubah/berhenti sewaktu-waktu, tapi dipakai luas di banyak proyek open-source
    // dan gak butuh pemilik daftar akun apa pun. (NanzBeatles sendiri pakai OpenRouter — gerbang model AI yang
    // BUTUH API key + bisa kena biaya tiap terjemahan — sengaja TIDAK diikuti di sini, lihat catatan Paket 32.)
    // Semua baris lirik digabung jadi satu teks (dipisah baris baru) dan diterjemahkan dalam SATU permintaan
    // (bukan satu per baris) — lebih hemat & cepat, tapi ada risiko kecil jumlah baris hasil terjemahan gak
    // persis sama kalau Google gabung/pecah baris secara beda; makanya ditangani defensif (baris yang gak
    // kebagian hasil dibiarkan kosong, bukan error).
    static JSONArray translateLyrics(JSONArray lyrics, String targetLang) {
        JSONArray out = new JSONArray();
        try {
            if (lyrics == null || lyrics.length() == 0) return out;
            List<String> texts = new ArrayList<>();
            for (int i = 0; i < lyrics.length(); i++) {
                JSONObject r = lyrics.optJSONObject(i);
                texts.add(r == null ? "" : r.optString("text", ""));
            }
            String joined = String.join("\n", texts);
            byte[] b = bytes("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + enc(targetLang) + "&dt=t&q=" + enc(joined), FF, 8000);
            if (b == null) { logErr("terjemah", "permintaan gagal (jaringan/HTTP)"); return out; }
            Object v = new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
            JSONArray segs = v instanceof JSONArray ? ((JSONArray) v).optJSONArray(0) : null;
            StringBuilder full = new StringBuilder();
            if (segs != null) for (int i = 0; i < segs.length(); i++) { JSONArray seg = segs.optJSONArray(i); if (seg != null) full.append(seg.optString(0, "")); }
            String[] lines = full.toString().split("\n", -1);
            for (int i = 0; i < texts.size(); i++) out.put(i < lines.length ? lines[i] : "");
            if (lines.length != texts.size()) logErr("terjemah", "jumlah baris beda: asli " + texts.size() + ", hasil " + lines.length + " (sebagian baris mungkin kosong/meleset)");
        } catch (Throwable e) { logErr("terjemah", "gagal: " + e); }
        return out;
    }
    static JSONObject translateJson(String id, String lang) throws Exception {
        Track t = track(id);
        if (t.lyr == null) { Object[] c = lyLoad(id); if (c != null) t.lyr = (JSONArray) c[0]; }
        JSONObject o = new JSONObject();
        o.put("lines", t.lyr == null ? new JSONArray() : translateLyrics(t.lyr, lang));
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
                    lfLoadSaved(); // Paket 29: muat sesi Last.fm tersimpan (kalau sebelumnya pernah terhubung)
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
    // ---------- pencocokan hasil lrclib: judul + artis + durasi ----------
    // false = kembali ke cara lama (hanya durasi terdekat, maks. 20 dtk, tanpa memeriksa judul/artis)
    private static final boolean LY_STRICT = true;

    private static String normL(String s) {
        if (s == null) return "";
        String x = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(java.util.Locale.ROOT);
        return x.replace("'", "").replace("\u2019", "").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static java.util.Set<String> tokL(String s) {
        java.util.Set<String> o = new java.util.HashSet<>();
        for (String w : normL(s).split(" ")) if (!w.isEmpty()) o.add(w);
        return o;
    }

    private static double overlapL(String a, String b) {
        java.util.Set<String> A = tokL(a), B = tokL(b);
        if (A.isEmpty() || B.isEmpty()) return 0;
        int n = 0;
        for (String w : A) if (B.contains(w)) n++;
        return n / (double) Math.min(A.size(), B.size());
    }

    // 0 = tidak cocok; makin besar makin cocok
    private static double matchScore(JSONObject x, List<String> titles, List<String> artists, double seconds) {
        double dur = x.optDouble("duration", 0), d = seconds > 0 && dur > 0 ? Math.abs(dur - seconds) : -1;
        if (!LY_STRICT) return d < 0 ? 1.0 : d <= 20 ? 1.0 / (1 + d) : 0;
        String name = x.optString("trackName", x.optString("name", "")), art = x.optString("artistName", "");
        double ti = 0, ar = 0;
        for (String w : titles) ti = Math.max(ti, overlapL(w, name));
        for (String w : artists) ar = Math.max(ar, overlapL(w, art));
        if (ti < 0.6) return 0;
        if (ar < 0.5 && !(d >= 0 && d <= 3)) return 0;
        double score = ti * 3 + ar * 2 + (!synced(x).trim().isEmpty() ? 1 : 0);
        if (d >= 0) score += d <= 2 ? 1.5 : d <= 8 ? 0.5 : -1;
        return Math.max(score, 0.01);
    }

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
    static Object[] lyrics(String id, Track t) { return lyrics(id, t, null); }

    // Paket 31: parameter "pref" baru — null/"auto" = rantai otomatis seperti sebelumnya (JOOX → lrclib →
    // Musixmatch → subtitle). Nama sumber spesifik ("joox","lrclib","musixmatch","kugou","simpmusic",
    // "betterlyrics","caption") = CUMA sumber itu yang dicoba, gak jatuh ke sumber lain kalau gagal — ini yang
    // dipanggil dari menu pemilihan sumber di player.html. Hasil balik sekarang 4 elemen (tambah nama sumber
    // yang beneran kepakai di indeks ke-3), dipakai buat nampilin label di menu.
    static Object[] lyrics(String id, Track t, String pref) {
        boolean auto = pref == null || pref.isEmpty() || "auto".equals(pref);
        if (auto) {
            if (t.lyr == null) {
                Object[] c = lyLoad(id);
                if (c != null) { t.lyr = (JSONArray) c[0]; t.kind = (String) c[1]; }
            }
            if (t.lyr != null) return new Object[]{t.lyr, t.kind, "", "cache"};
            if (lyNone(id)) return new Object[]{new JSONArray(), "synced", LY_NONE_MSG, "none"};
        }
        JSONArray out = new JSONArray();
        String kind = "synced";
        long dl = System.currentTimeMillis() + 12000;
        int g = 0, sr = 0;
        boolean reached = false;
        String src = "lrclib";
        try {
            String ti = cleanTitle(t.title), ch = cleanArtist(t.artist);
            if (!auto) {
                String artistFallback = ch.isEmpty() ? (t.artist == null ? "" : t.artist) : ch;
                if ("joox".equals(pref)) { JSONArray jx = jxLyrics(t.title, t.artist, t.seconds); if (jx != null && jx.length() > 0) { out = jx; src = "joox"; } }
                else if ("lrclib".equals(pref)) {
                    Object o = lrc("search?q=" + enc((ch.isEmpty() ? "" : ch + " ") + ti));
                    if (o instanceof JSONArray) {
                        JSONArray arr = (JSONArray) o; JSONObject bestS = null; double scS = 0;
                        for (int k = 0; k < arr.length(); k++) {
                            JSONObject x = arr.optJSONObject(k); if (x == null || x.optBoolean("instrumental", false)) continue;
                            double sc = matchScore(x, Arrays.asList(ti), Arrays.asList(ch), t.seconds);
                            if (sc > scS && !synced(x).trim().isEmpty()) { bestS = x; scS = sc; }
                        }
                        if (bestS != null) { out = parseLrc(synced(bestS)); src = "lrclib"; }
                    }
                }
                else if ("musixmatch".equals(pref)) {
                    String[] mx = mxmLyrics(ti, artistFallback, (int) t.seconds);
                    if (mx != null) { if ("synced".equals(mx[0])) { out = parseLrc(mx[1]); src = "musixmatch"; } else { out = fromPlain(mx[1], t.seconds); kind = "plain"; src = "musixmatch-plain"; } }
                }
                else if ("kugou".equals(pref)) {
                    String[] kg = kgLyrics(ti, artistFallback, (int) t.seconds);
                    if (kg != null) { if ("synced".equals(kg[0])) { out = parseLrc(kg[1]); src = "kugou"; } else { out = fromPlain(kg[1], t.seconds); kind = "plain"; src = "kugou-plain"; } }
                }
                else if ("simpmusic".equals(pref)) {
                    String[] sm = isSoundCloud(id) ? null : smLyrics(id, (int) t.seconds);
                    if (sm != null) { if ("synced".equals(sm[0])) { out = parseLrc(sm[1]); src = "simpmusic"; } else { out = fromPlain(sm[1], t.seconds); kind = "plain"; src = "simpmusic-plain"; } }
                }
                else if ("betterlyrics".equals(pref)) {
                    String[] bl = blLyrics(ti, artistFallback, (int) t.seconds);
                    if (bl != null) { if ("synced".equals(bl[0])) { out = parseLrc(bl[1]); src = "betterlyrics"; } else { out = fromPlain(bl[1], t.seconds); kind = "plain"; src = "betterlyrics-plain"; } }
                }
                else if ("caption".equals(pref) && t.subUrl != null) {
                    byte[] b = bytes(t.subUrl, FF);
                    if (b != null) { out = parseVtt(new String(b, StandardCharsets.UTF_8), t.subAuto); kind = "caption"; src = "subtitle"; }
                }
                if (out.length() > 0 && "synced".equals(kind)) neEnrich(out, t.title, t.artist, t.seconds);
                if (out.length() > 0) { t.lyr = out; t.kind = kind; lySave(id, out, kind); }
                String dm = "sumber manual: " + pref + ", hasil " + out.length() + " baris";
                return new Object[]{out, kind, dm, out.length() > 0 ? src : "none"};
            }
            // Paket 15: JOOX sekarang jadi sumber UTAMA (dicoba duluan). Kalau ketemu, seluruh pencarian lrclib
            // di bawah ini DILEWATI sama sekali (lebih cepat, gak buang permintaan jaringan yang gak kepakai).
            // lrclib cuma jadi cadangan kalau JOOX gak ketemu/gak cocok buat lagu ini.
            JSONArray jx = jxLyrics(t.title, t.artist, t.seconds);
            if (jx != null && jx.length() > 0) { out = jx; src = "joox"; }
            if (out.length() == 0) {
                List<String[]> cands = new ArrayList<>();
                int i = ti.indexOf(" - ");
                if (i > 0) {
                    String a = ti.substring(0, i).trim(), b = ti.substring(i + 3).trim();
                    cands.add(new String[]{a, b});
                    cands.add(new String[]{b, a});
                }
                if (!ch.isEmpty()) cands.add(new String[]{ch, i > 0 ? ti.substring(i + 3).trim() : ti});
                String synced = "", plain = "", weak = "";
                for (String[] c : cands) {
                    if (c[0].isEmpty() || c[1].isEmpty()) continue;
                    if (System.currentTimeMillis() > dl) break;
                    g++;
                    Object o = lrc("get?track_name=" + enc(c[1]) + "&artist_name=" + enc(c[0]));
                    if (o instanceof JSONObject) {
                        JSONObject j = (JSONObject) o;
                        double gd = t.seconds > 0 && j.optDouble("duration", 0) > 0 ? Math.abs(j.optDouble("duration", 0) - t.seconds) : -1;
                        if (!synced(j).trim().isEmpty()) {
                            if (!LY_STRICT || gd < 0 || gd <= 8) { synced = synced(j); break; }
                            if (weak.isEmpty()) weak = synced(j); // durasi beda jauh (mungkin versi live/panjang): cadangan terakhir
                        }
                        if (plain.isEmpty()) plain = plainOf(j);
                    }
                }
                if (synced.isEmpty()) {
                    String[] qs = {(i > 0 ? ti.replace(" - ", " ") : ch + " " + ti).trim(), ti};
                    JSONObject bestS = null, bestP = null;
                    double scS = 0, scP = 0;
                    List<String> wantT = new ArrayList<>(), wantA = new ArrayList<>();
                    for (String[] c : cands) { if (!wantA.contains(c[0])) wantA.add(c[0]); if (!wantT.contains(c[1])) wantT.add(c[1]); }
                    for (String q : new LinkedHashSet<>(Arrays.asList(qs))) {
                        if (System.currentTimeMillis() > dl) break;
                        sr++;
                        Object o = lrc("search?q=" + enc(q));
                        if (!(o instanceof JSONArray)) continue;
                        reached = true;
                        JSONArray arr = (JSONArray) o;
                        for (int k = 0; k < arr.length(); k++) {
                            JSONObject x = arr.optJSONObject(k);
                            if (x == null || x.optBoolean("instrumental", false)) continue;
                            double sc = matchScore(x, wantT, wantA, t.seconds);
                            if (sc <= 0) continue;
                            if (!synced(x).trim().isEmpty() && sc > scS) { bestS = x; scS = sc; }
                            if (!plainOf(x).isEmpty() && sc > scP) { bestP = x; scP = sc; }
                        }
                        if (bestS != null) break;
                    }
                    if (bestS != null) synced = synced(bestS);
                    else if (!weak.isEmpty()) synced = weak;
                    else if (plain.isEmpty() && bestP != null) plain = plainOf(bestP);
                }
                if (!synced.isEmpty()) out = parseLrc(synced);
                else if (!plain.isEmpty()) { out = fromPlain(plain, t.seconds); kind = "plain"; src = "lrclib-plain"; }
            }
            // Paket 30: Musixmatch sebagai sumber TAMBAHAN (porting dari NanzBeatles) — dicoba cuma kalau
            // JOOX & lrclib DUA-DUANYA gak ketemu sama sekali, sebelum jatuh ke subtitle YouTube (paling
            // rendah kualitasnya, auto-generated & gak ada tanda bacanya).
            if (out.length() == 0) {
                String[] mx = mxmLyrics(ti, ch.isEmpty() ? (t.artist == null ? "" : t.artist) : ch, (int) t.seconds);
                if (mx != null) {
                    if ("synced".equals(mx[0])) { out = parseLrc(mx[1]); src = "musixmatch"; }
                    else { out = fromPlain(mx[1], t.seconds); kind = "plain"; src = "musixmatch-plain"; }
                }
            }
            // Tahap 3 (NetEase) — kalau ketemu lirik tersinkron (JOOX ATAU lrclib), coba tempel waktu kata ASLI.
            // Opsional & diam-diam kalau gagal; `realWords`/`wordSpans` di player.html otomatis makai ini kalau
            // tersedia, sama seperti LRC enhanced.
            if (out.length() > 0 && "synced".equals(kind)) neEnrich(out, t.title, t.artist, t.seconds);
            if (out.length() == 0 && t.subUrl != null) {
                byte[] b = bytes(t.subUrl, FF);
                if (b != null) { out = parseVtt(new String(b, StandardCharsets.UTF_8), t.subAuto); kind = "caption"; src = "subtitle"; }
            }
        } catch (Throwable e) { /* lirik opsional */ }
        if (out.length() > 0) { t.lyr = out; t.kind = kind; lySave(id, out, kind); }
        else if (reached) lyMarkNone(id);
        String d = "get " + g + "x, cari " + sr + "x, subtitle " + (t.subUrl != null ? "ada" : "tidak ada") + ", hasil " + out.length() + " baris";
        // Catatan ringkas SELALU ditulis (apa pun hasilnya) biar kelihatan sumbernya dari mana dan apakah NetEase
        // ("lirik-ne" lain) seharusnya ikut dicoba atau tidak.
        logErr("lirik-ne", (t.title == null ? "?" : t.title) + " – " + (t.artist == null ? "?" : t.artist)
            + " · ringkasan: sumber=" + src + ", kind=" + kind + ", " + out.length() + " baris"
            + (out.length() > 0 && "synced".equals(kind) ? " → NetEase seharusnya dicoba (cek baris lirik-ne lain)" : " → NetEase TIDAK dicoba"));
        return new Object[]{out, kind, d, out.length() > 0 ? src : "none"};
    }

    private static String plainOf(JSONObject o) { return o.isNull("plainLyrics") ? "" : o.optString("plainLyrics", "").trim(); }

    private static JSONArray parseLrc(String raw) throws Exception {
        Pattern re = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
        Pattern wre = Pattern.compile("<(\\d{1,3}):(\\d{2}(?:\\.\\d+)?)>"); // LRC "enhanced": waktu di depan tiap kata
        List<Object[]> rows = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            Matcher m = re.matcher(line);
            List<Double> times = new ArrayList<>();
            while (m.find()) {
                double f = m.group(3) != null ? Double.parseDouble("0." + m.group(3)) : 0;
                times.add(Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)) + f);
            }
            String text = re.matcher(line).replaceAll("").trim();
            List<Object[]> segs = null;
            if (text.indexOf('<') >= 0) {
                Matcher wm = wre.matcher(text);
                List<double[]> pos = new ArrayList<>(); // {waktu, awal tag, akhir tag}
                while (wm.find()) pos.add(new double[]{Integer.parseInt(wm.group(1)) * 60 + Double.parseDouble(wm.group(2)), wm.start(), wm.end()});
                if (!pos.isEmpty() && times.size() == 1) {
                    segs = new ArrayList<>();
                    String lead = text.substring(0, (int) pos.get(0)[1]).trim(); // teks sebelum tag pertama: mulai dari awal baris
                    if (!lead.isEmpty()) segs.add(new Object[]{times.get(0), lead});
                    for (int q = 0; q < pos.size(); q++) {
                        int from = (int) pos.get(q)[2], to = q + 1 < pos.size() ? (int) pos.get(q + 1)[1] : text.length();
                        String seg = wre.matcher(text.substring(from, to)).replaceAll("").trim();
                        if (!seg.isEmpty()) segs.add(new Object[]{pos.get(q)[0], seg});
                    }
                }
                text = wre.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
            }
            if (times.isEmpty() || text.isEmpty()) continue;
            if (text.length() > 120) { text = text.substring(0, 120); segs = null; }
            for (double d : times) rows.add(new Object[]{d, text, segs});
        }
        Collections.sort(rows, (a, b) -> Double.compare((Double) a[0], (Double) b[0]));
        JSONArray out = new JSONArray();
        for (int ri = 0; ri < rows.size(); ri++) {
            Object[] r = rows.get(ri);
            JSONObject o = new JSONObject().put("time", (Double) r[0]).put("text", (String) r[1]);
            @SuppressWarnings("unchecked") List<Object[]> sg = (List<Object[]>) r[2];
            if (sg != null && !sg.isEmpty()) {
                double t0 = (Double) r[0], lineEnd = ri + 1 < rows.size() ? (Double) rows.get(ri + 1)[0] : t0 + 4;
                JSONArray ws = new JSONArray();
                for (int u = 0; u < sg.size(); u++) {
                    double st = (Double) sg.get(u)[0];
                    double en = u + 1 < sg.size() ? (Double) sg.get(u + 1)[0] : Math.max(st + 0.3, Math.min(lineEnd, st + 4));
                    String[] toks = ((String) sg.get(u)[1]).split("\\s+");
                    for (int k = 0; k < toks.length; k++) {
                        double a0 = st + (en - st) * k / toks.length, a1 = st + (en - st) * (k + 1) / toks.length;
                        ws.put(new JSONObject().put("text", toks[k]).put("start", Math.round(a0 * 1000) / 1000.0).put("end", Math.round(a1 * 1000) / 1000.0));
                    }
                }
                o.put("words", ws);
            }
            out.put(o);
        }
        return out;
    }

    // Teks biasa tanpa waktu: baris dibagi rata sepanjang lagu (perkiraan)
    // ===== Paket 14: kata-per-kata asli dari NetEase (YRC), ditempel ke lirik lrclib/JOOX yang sedang tampil =====
    // Ini "Tahap 3" yang ditunda di Paket 13 (lihat catatan Paket 13 — waktu itu menunggu keputusan pemilik soal
    // API tidak resmi/hak cipta/lisensi). Pemilik sudah bilang lanjutkan di sesi lain; hasilnya ditempelkan ke
    // sini. Format kata keluarannya SENGAJA dibuat sama persis dengan yang dipakai `parseLrc` ("words":
    // [{"text":...,"start":...,"end":...}]) biar `realWords`/`wordSpans` di player.html otomatis mengenalinya
    // tanpa perlu diubah sama sekali — dari sudut pandang pemutar, ini cuma "LRC enhanced dari sumber lain".
    // Hati-hati: bagian ini BELUM pernah diuji ke API aslinya (lingkungan Claude gak bisa akses music.163.com).
    // Algoritmanya dikonfirmasi dari banyak implementasi independen yang sudah lama stabil (weapi/eapi), tapi
    // tetap ada risiko kalau NetEase ubah sesuatu — kalau gitu, semua fungsi di bawah ini diam-diam gagal
    // (try/catch) dan karaoke/tab Lirik balik ke perkiraan `lineSpan`, aman.
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
        Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
        return c.doFinal(data);
    }
    // Skema "eapi" NetEase: digest = md5("nobody"+path+"use"+json+"md5forencrypt"), lalu AES-128-ECB atas
    // path+"-36cd479b6b5-"+json+"-36cd479b6b5-"+digest, dikirim sebagai hex di field "params".
    private static String neEapiParams(String path, String json) throws Exception {
        String digest = neHex(MessageDigest.getInstance("MD5").digest(("nobody" + path + "use" + json + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
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
            try (OutputStream os = c.getOutputStream()) { os.write(bb); }
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return readAll(in); }
        } catch (Throwable e) { return null; }
    }

    // Cari lagu di NetEase (endpoint pencarian lama, GET biasa, gak perlu enkripsi) buat dapet song id
    private static JSONArray neSearch(String q, int ms) {
        try {
            byte[] b = bytes("http://music.163.com/api/search/get/web?s=" + enc(q) + "&type=1&offset=0&limit=8", FF, ms);
            if (b == null) return null;
            Object o = new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
            JSONObject result = o instanceof JSONObject ? ((JSONObject) o).optJSONObject("result") : null;
            return result == null ? null : result.optJSONArray("songs");
        } catch (Throwable e) { return null; }
    }
    // "Disederhanakan" buat dibandingkan: huruf kecil, tanpa tanda baca/diakritik. Dipakai bersama NetEase & JOOX.
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
    // Hasil pencarian NetEase yang paling cocok sama judul/artis/durasi lagu ini, atau -1 kalau gak ada yang cocok
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
    // "(mulaiMs,durasiMs,0)kata (mulaiMs,durasiMs,0)kata ..." -> daftar kata berwaktu; potongan Latin disambung
    // sampai ketemu spasi, potongan CJK berdiri sendiri (karakter per karakter biasanya 1 tag per karakter)
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
    // Format YRC NetEase: "[awalMs,durasiMs]" diikuti tag kata per kata; baris kredit (penulis/produser dkk) dibuang
    private static List<NeLine> neParseYrc(String text) {
        List<NeLine> lines = new ArrayList<>();
        if (text == null) return lines;
        Pattern credit = Pattern.compile("^(\u4f5c\u8bcd|\u4f5c\u66f2|\u7f16\u66f2|\u5236\u4f5c\u4eba|\u6df7\u97f3|\u6bcd\u5e26|\u548c\u58f0|\u5409\u4ed6|\u8d1d\u65af|\u9f13|\u5f55\u97f3|\u76d1\u5236|\u51fa\u54c1|\u53d1\u884c)\\s*[:\uff1a]");
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

    // Tempel waktu kata asli NetEase (neLines) ke baris JSON lirik yang sedang ditampilkan (dari lrclib/JOOX),
    // biar beda potongan baris pun tetap bisa disambung. Baris yang SUDAH punya "words" (dari tag LRC enhanced)
    // dilewati, tidak ditimpa. Kalau terlalu sedikit yang cocok, dibatalkan semua (lagunya mungkin beda).
    // Field katanya memakai "text"/"start"/"end" — SAMA PERSIS dengan yang dipakai parseLrc (LRC enhanced) dan
    // dibaca player.html (realWords/wordSpans), bukan nama singkatan — biar gak perlu ubah player.html sama sekali.
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

            List<Object[]> matches = new ArrayList<>(); // {Integer i, Double next, List<String> toks, NePoolWord[] hit}
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
                for (int j2 = 0; j2 < m; j2++) words.put(new JSONObject().put("text", toks.get(j2)).put("start", Math.round(ws[j2] * 1000) / 1000.0).put("end", Math.round(we[j2] * 1000) / 1000.0));
                rows.getJSONObject(i).put("words", words);
            }
        } catch (Throwable e) { /* penyelarasan opsional, gagal diam-diam */ }
    }

    // Titik masuk: cari lagu ini di NetEase, ambil YRC-nya, tempelkan ke `out` kalau ketemu & cocok.
    // Best-effort penuh — gagal di titik mana pun (gak ketemu, API berubah, timeout) dibiarkan diam-diam, tapi
    // tetap dicatat ke Catatan galat (tag "lirik-ne") biar gampang didiagnosa pemilik.
    private static void neEnrich(JSONArray out, String title, String artist, long durSec) {
        String dbg = (title == null ? "?" : title) + " – " + (artist == null ? "?" : artist);
        try {
            String q = (cleanArtist(artist) + " " + cleanTitle(title)).trim();
            if (q.isEmpty()) { logErr("lirik-ne", dbg + " · dibatalkan: judul/artis kosong setelah dibersihkan"); return; }
            JSONArray songs = neSearch(q, 4000);
            if (songs == null) { logErr("lirik-ne", dbg + " · pencarian NetEase gagal/kosong (query: " + q + ")"); return; }
            long idv = nePickSongId(songs, cleanTitle(title), artist, durSec);
            if (idv <= 0) { logErr("lirik-ne", dbg + " · " + songs.length() + " hasil pencarian, tak satu pun cocok judul/artis/durasi"); return; }
            String json = "{\"id\":" + idv + ",\"cp\":false,\"tv\":0,\"lv\":0,\"rv\":0,\"kv\":0,\"yv\":0,\"ytv\":0,\"yrv\":0}";
            byte[] b = nePostEapi("/api/song/lyric/v1", json, 4000);
            if (b == null) { logErr("lirik-ne", dbg + " · lagu id=" + idv + " ketemu, tapi permintaan lirik eapi gagal (jaringan/HTTP)"); return; }
            Object o = new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
            JSONObject yrcObj = o instanceof JSONObject ? ((JSONObject) o).optJSONObject("yrc") : null;
            String yrcText = yrcObj == null ? "" : yrcObj.optString("lyric", "");
            if (yrcText.trim().isEmpty()) { logErr("lirik-ne", dbg + " · lagu id=" + idv + " ketemu, respons eapi jalan, tapi field \"yrc\" kosong/gak ada"); return; }
            List<NeLine> neLines = neParseYrc(yrcText);
            if (neLines.isEmpty()) { logErr("lirik-ne", dbg + " · data yrc ada tapi gagal diparsing jadi baris"); return; }
            int before = countWords(out);
            neAlignWordTimings(out, neLines);
            int after = countWords(out);
            if (after > before) logErr("lirik-ne", dbg + " · berhasil: " + (after - before) + "/" + out.length() + " baris dapat waktu kata asli dari NetEase");
            else logErr("lirik-ne", dbg + " · " + neLines.size() + " baris yrc didapat, tapi penyelarasan ke lirik yang tampil gagal (kemungkinan beda versi/terjemahan)");
        } catch (Throwable e) { logErr("lirik-ne", dbg + " · error tak terduga: " + e); }
    }
    private static int countWords(JSONArray rows) {
        int n = 0;
        for (int i = 0; i < rows.length(); i++) { JSONObject r = rows.optJSONObject(i); if (r != null && r.has("words")) n++; }
        return n;
    }

    // ===== Paket 15: JOOX sebagai sumber lirik TERSINKRON tambahan (terutama lagu Indonesia yang gak ada di lrclib) =====
    // Scraping situs pihak ketiga (bukan API resmi) — kalau JOOX ubah halamannya, ini akan diam-diam gagal.
    private static final Pattern JX_NEXT_DATA = Pattern.compile("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", Pattern.DOTALL);
    private static final Pattern JX_LRC_LINE = Pattern.compile("^\\[(\\d{2}):(\\d{2}(?:\\.\\d+)?)\\](.*)$");

    private static JSONObject jxNextData(String html) {
        try {
            Matcher m = JX_NEXT_DATA.matcher(html);
            if (!m.find()) return null;
            Object o = new JSONTokener(m.group(1)).nextValue();
            return o instanceof JSONObject ? (JSONObject) o : null;
        } catch (Throwable e) { return null; }
    }
    private static JSONObject jxPageProps(JSONObject nextData) {
        JSONObject pp = nextData == null ? null : nextData.optJSONObject("props");
        return pp == null ? null : pp.optJSONObject("pageProps");
    }
    private static JSONArray jxSearch(String keyword, String region) {
        try {
            byte[] b = bytes("https://www.joox.com/" + region + "/search/" + enc(keyword), FF, 6000);
            if (b == null) return null;
            JSONObject pp = jxPageProps(jxNextData(new String(b, StandardCharsets.UTF_8)));
            JSONObject ss = pp == null ? null : pp.optJSONObject("searchSections");
            JSONObject songs = ss == null ? null : ss.optJSONObject("songs");
            return songs == null ? null : songs.optJSONArray("item_list");
        } catch (Throwable e) { return null; }
    }
    // Dari hasil pencarian JOOX, pilih lagu yang judul/artis/durasinya paling cocok -> id lagu, atau null kalau gak ada yang pas
    private static String jxPickId(JSONArray items, String title, String artist, long durSec) {
        if (items == null) return null;
        String t = neSimple(title);
        String[] artists = artist == null || artist.isEmpty() ? new String[0] : artist.split("\\s*(?:,|&|/|\\+|\\bx\\b|\\band\\b|\\bfeat\\.?|\\bft\\.?)\\s*");
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            JSONArray songArr = item == null ? null : item.optJSONArray("song");
            JSONObject song = songArr == null || songArr.length() == 0 ? null : songArr.optJSONObject(0);
            JSONObject info = song == null ? null : song.optJSONObject("song_info");
            if (info == null) continue;
            long len = info.optLong("play_duration", 0);
            if (durSec > 0 && len > 0 && Math.abs(len - durSec) > 3) continue;
            if (!neOverlaps(neSimple(info.optString("name", "")), t)) continue;
            JSONArray ar = info.optJSONArray("artist_list");
            boolean artistOk = artists.length == 0 || ar == null;
            if (!artistOk) {
                for (int ai = 0; ai < ar.length() && !artistOk; ai++) {
                    JSONObject one = ar.optJSONObject(ai);
                    String cand = neSimple(one == null ? "" : one.optString("name", ""));
                    for (String a : artists) if (neOverlaps(neSimple(a), cand)) { artistOk = true; break; }
                }
            }
            if (!artistOk) continue;
            String idv = info.optString("id", "");
            if (!idv.isEmpty()) return idv;
        }
        return null;
    }
    // Ambil lirik tersinkron dari JOOX buat satu lagu (null kalau gak ketemu/gagal); catat prosesnya ke Catatan galat
    private static JSONArray jxLyrics(String title, String artist, long durSec) {
        String dbg = (title == null ? "?" : title) + " – " + (artist == null ? "?" : artist);
        try {
            String region = "id"; // katalog Indonesia; region lain JOOX gak dicoba di sini
            String q = (cleanArtist(artist) + " " + cleanTitle(title)).trim();
            if (q.isEmpty()) { logErr("lirik-jx", dbg + " · dibatalkan: judul/artis kosong setelah dibersihkan"); return null; }
            JSONArray items = jxSearch(q, region);
            if (items == null) { logErr("lirik-jx", dbg + " · pencarian JOOX gagal/kosong (query: " + q + ")"); return null; }
            String idv = jxPickId(items, cleanTitle(title), artist, durSec);
            if (idv == null) { logErr("lirik-jx", dbg + " · " + items.length() + " hasil pencarian, tak satu pun cocok judul/artis/durasi"); return null; }
            byte[] b = bytes("https://www.joox.com/" + region + "/single/" + enc(idv), FF, 6000);
            if (b == null) { logErr("lirik-jx", dbg + " · lagu id=" + idv + " ketemu, tapi gagal buka halaman lagunya"); return null; }
            JSONObject pp = jxPageProps(jxNextData(new String(b, StandardCharsets.UTF_8)));
            String b64 = pp == null ? "" : pp.optString("singleLyric", "");
            if (b64.isEmpty()) { logErr("lirik-jx", dbg + " · lagu id=" + idv + " ketemu, tapi gak ada lirik (singleLyric kosong)"); return null; }
            String decoded = new String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), StandardCharsets.UTF_8);
            JSONArray out = new JSONArray();
            for (String raw : decoded.split("\\r?\\n")) {
                if (raw.trim().isEmpty()) continue;
                Matcher m = JX_LRC_LINE.matcher(raw.trim());
                if (!m.find()) continue;
                double tm = Integer.parseInt(m.group(1)) * 60 + Double.parseDouble(m.group(2));
                String text = m.group(3).trim();
                if (text.isEmpty()) continue;
                if (text.length() > 120) text = text.substring(0, 120);
                out.put(new JSONObject().put("time", tm).put("text", text));
            }
            if (out.length() == 0) { logErr("lirik-jx", dbg + " · lirik JOOX ada tapi gagal diparsing jadi baris"); return null; }
            logErr("lirik-jx", dbg + " · berhasil: " + out.length() + " baris tersinkron dari JOOX");
            return out;
        } catch (Throwable e) { logErr("lirik-jx", dbg + " · error tak terduga: " + e); return null; }
    }

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
        return t.replaceAll("(?i)[\\(\\[\\{][^\\)\\]\\}]*(official|video|audio|lyric|lyrics|lirik|terjemahan|resmi|klip|subtitle|mv|m/v|visualizer|hd|hq|4k|remaster|clip)[^\\)\\]\\}]*[\\)\\]\\}]", "")
                .replaceAll("(?i)\\s*[\\(\\[]?\\s*(feat\\.?|ft\\.?)\\s[^\\)\\]]*[\\)\\]]?", "")
                .replaceAll("\\s*\\|.*$", "")
                .replaceAll("(?i)\\s+(lirik|lyrics?)(\\s+(dan|&)\\s+terjemahan(\\s+indonesia)?)?\\s*$", "")
                .replaceAll("(?i)\\s+(terjemahan|translation)(\\s+indonesia)?\\s*$", "")
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

    // ===== Paket 30: Musixmatch (API tidak resmi) sebagai sumber lirik TAMBAHAN =====
    // Diterjemahkan ke Java dari modul Kotlin "musixmatch" NanzBeatles. Skema tanda tangan permintaan
    // (HMAC-SHA256, kunci berganti tiap hari lewat tanggal UTC) hasil reverse-engineering publik — Musixmatch
    // bisa mengubahnya kapan saja tanpa pemberitahuan karena ini BUKAN API resmi. SENGAJA cuma pakai kunci
    // cadangan publik (MXM_SECRET, sama yang dipakai NanzBeatles sebagai fallback mereka) — bukan skema
    // "ambil kunci terbaru dari skrip halaman web Musixmatch" yang jauh lebih rumit & rapuh buat ditiru di
    // Java. Kalau Musixmatch ganti kunci cadangan ini, sumber lirik ini diam-diam berhenti nyumbang apa pun
    // (sama kayak sumber lirik lain yang gagal) — TIDAK bikin app error, alur lyrics() tetap lanjut normal.
    private static final String MXM_APP_ID = "web-desktop-app-v1.0";
    private static final String MXM_SECRET = "IEJ5E8XFaHQvIQNfs7IC";
    private static final String MXM_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static volatile String mxmToken;

    private static String mxmSign(String method, java.util.LinkedHashMap<String, String> params) throws Exception {
        StringBuilder q = new StringBuilder();
        q.append("app_id=").append(enc(MXM_APP_ID)).append("&format=json");
        for (Map.Entry<String, String> e : params.entrySet()) q.append('&').append(e.getKey()).append('=').append(enc(e.getValue()));
        String url = "https://apic.musixmatch.com/ws/1.1/" + method + "?" + q;
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US);
        df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String dateStr = df.format(new java.util.Date());
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(MXM_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = java.util.Base64.getEncoder().encodeToString(mac.doFinal((url + dateStr).getBytes(StandardCharsets.UTF_8)));
        return url + "&signature=" + enc(sig) + "&signature_protocol=sha256";
    }

    // Lapis respons Musixmatch selalu {"message":{"header":{"status_code":...},"body":{...}}}.
    private static JSONObject mxmGet(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", MXM_UA);
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Cookie", "mxm_bab=AB");
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) return null;
            JSONObject root = new JSONObject(new String(readAll(in), StandardCharsets.UTF_8));
            JSONObject msg = root.optJSONObject("message");
            JSONObject header = msg == null ? null : msg.optJSONObject("header");
            if (header == null || header.optInt("status_code", 0) != 200) return null;
            return msg.optJSONObject("body");
        } catch (Exception e) { return null; }
    }

    private static String mxmGetToken() {
        if (mxmToken != null) return mxmToken;
        try {
            JSONObject body = mxmGet(mxmSign("token.get", new java.util.LinkedHashMap<>()));
            String t = body == null ? "" : body.optString("user_token", "");
            if (t.isEmpty() || t.startsWith("UpgradeOnly")) return null;
            mxmToken = t; return t;
        } catch (Exception e) { return null; }
    }

    private static java.util.Set<String> mxmWords(String s) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim().split(" ")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    private static double mxmSim(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        java.util.Set<String> inter = new java.util.HashSet<>(a); inter.retainAll(b);
        java.util.Set<String> uni = new java.util.HashSet<>(a); uni.addAll(b);
        return uni.isEmpty() ? 0 : (double) inter.size() / uni.size();
    }

    // Balikin {"synced", teksLrc} atau {"plain", teksBiasa}, atau null kalau gak ketemu sama sekali
    // (jaringan gagal, token gak kedapetan, gak ada kandidat yang cukup mirip, dst — semua ditelan di sini,
    // pemanggilnya (lyrics()) tinggal lanjut ke sumber berikutnya seolah Musixmatch emang gak ketemu apa-apa).
    private static String[] mxmLyrics(String title, String artist, int durationSec) {
        try {
            String token = mxmGetToken();
            if (token == null) return null;
            java.util.LinkedHashMap<String, String> sp = new java.util.LinkedHashMap<>();
            sp.put("q_track", title); sp.put("q_artist", artist); sp.put("page_size", "10");
            sp.put("s_track_rating", "desc"); sp.put("f_has_lyrics", "1"); sp.put("usertoken", token);
            JSONObject sbody = mxmGet(mxmSign("track.search", sp));
            JSONArray list = sbody == null ? null : sbody.optJSONArray("track_list");
            if (list == null || list.length() == 0) return null;
            JSONObject best = null; double bestScore = -1;
            java.util.Set<String> wantT = mxmWords(title), wantA = mxmWords(artist);
            for (int i = 0; i < list.length(); i++) {
                JSONObject tr = list.optJSONObject(i); if (tr == null) continue;
                JSONObject track = tr.optJSONObject("track"); if (track == null) continue;
                double sc = mxmSim(mxmWords(track.optString("track_name", "")), wantT) * 5
                        + mxmSim(mxmWords(track.optString("artist_name", "")), wantA) * 4;
                int len = track.optInt("track_length", 0);
                if (durationSec > 0 && len > 0) sc += Math.max(-1.0, 1.0 - Math.abs(len - durationSec) / 30.0) * 2;
                if (sc > bestScore) { bestScore = sc; best = track; }
            }
            if (best == null || bestScore <= 0) return null;
            long trackIdL = best.optLong("track_id", -1);
            if (trackIdL < 0) return null;
            String trackId = String.valueOf(trackIdL);
            int len2 = best.optInt("track_length", durationSec);
            if (best.optInt("has_subtitles", 0) != 0) {
                java.util.LinkedHashMap<String, String> p2 = new java.util.LinkedHashMap<>();
                p2.put("track_id", trackId); p2.put("subtitle_format", "lrc");
                p2.put("f_subtitle_length", String.valueOf(len2 > 0 ? len2 : durationSec));
                p2.put("f_subtitle_length_max_deviation", "10"); p2.put("usertoken", token);
                JSONObject b2 = mxmGet(mxmSign("track.subtitle.get", p2));
                JSONObject sub = b2 == null ? null : b2.optJSONObject("subtitle");
                String lrc = sub == null ? "" : sub.optString("subtitle_body", "");
                if (!lrc.trim().isEmpty()) return new String[]{"synced", lrc};
            }
            if (best.optInt("has_lyrics", 0) != 0) {
                java.util.LinkedHashMap<String, String> p3 = new java.util.LinkedHashMap<>();
                p3.put("track_id", trackId); p3.put("usertoken", token);
                JSONObject b3 = mxmGet(mxmSign("track.lyrics.get", p3));
                JSONObject ly = b3 == null ? null : b3.optJSONObject("lyrics");
                String txt = ly == null ? "" : ly.optString("lyrics_body", "");
                if (!txt.trim().isEmpty()) return new String[]{"plain", txt};
            }
            return null;
        } catch (Exception e) { return null; }
    }

    // ===== Paket 31: KuGou, SimpMusic, BetterLyrics — porting dari NanzBeatles (lihat .claude/CLAUDE.md buat
    // daftar lengkap 7 sumber NanzBeatles; Rush & Paxsenix SENGAJA dilewati, lihat catatan Paket 31) =====
    // Semua provider lirik (termasuk yang lama: lrclib, JOOX, Musixmatch) sekarang mengembalikan bentuk yang
    // sama: String[]{"synced"|"plain", teksLrcAtauEnhancedLrc} atau null. Data kata-per-kata (TTML/richsync)
    // dikonversi jadi format "LRC enhanced" (`[mm:ss.xx]` + tag `<mm:ss.xx>kata` per baris) SUPAYA bisa dipakai
    // ulang oleh `parseLrc` yang sudah ada (Paket 12) — satu parser buat semua sumber, bukan bikin lagi per sumber.

    // --- KuGou ---
    private static String kgNormTitle(String s) {
        return s.replaceAll("\\(.*\\)", "").replaceAll("（.*）", "").replaceAll("「.*」", "").replaceAll("『.*』", "")
                .replaceAll("<.*>", "").replaceAll("《.*》", "").replaceAll("〈.*〉", "").replaceAll("＜.*＞", "").trim();
    }
    private static String kgNormArtist(String s) {
        return s.replace(", ", "、").replace(" & ", "、").replace(".", "").replace("和", "、")
                .replaceAll("\\(.*\\)", "").replaceAll("（.*）", "").trim();
    }
    // Buang baris kredit (penulis/komposer/dll) yang biasanya nempel di awal/akhir lirik KuGou. Diadaptasi dari
    // logika asal NanzBeatles (cari pola "]teks:teks" dalam 30 baris pertama/terakhir) — disederhanakan sedikit
    // di bagian ekor (logika asal agak membingungkan indeksnya), niatnya sama.
    private static String kgNormalize(String raw) {
        Pattern accepted = Pattern.compile("^\\[(\\d{2}):(\\d{2})\\.(\\d{2,3})\\].*");
        Pattern banned = Pattern.compile(".+\\].+[:：].+");
        List<String> lines = new ArrayList<>();
        for (String l : raw.replace("&apos;", "'").split("\n", -1)) if (accepted.matcher(l).matches()) lines.add(l);
        int headCut = 0;
        for (int i = Math.min(30, lines.size() - 1); i >= 0; i--) if (banned.matcher(lines.get(i)).matches()) { headCut = i + 1; break; }
        List<String> filtered = new ArrayList<>(lines.subList(Math.min(headCut, lines.size()), lines.size()));
        int fn = filtered.size(), tailCut = 0;
        for (int i = Math.min(30, fn - 1); i >= 0; i--) { int idx = fn - 1 - i; if (idx >= 0 && banned.matcher(filtered.get(idx)).matches()) { tailCut = i + 1; break; } }
        return String.join("\n", filtered.subList(0, Math.max(0, fn - tailCut)));
    }
    private static String[] kgLyrics(String title, String artist, int durationSec) {
        try {
            String kt = kgNormTitle(title), ka = kgNormArtist(artist), kw = kt + " - " + ka;
            JSONObject songs = (JSONObject) new JSONTokener(new String(bytes("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=" + enc(kw), FF, 6000), StandardCharsets.UTF_8)).nextValue();
            JSONArray info = songs == null ? null : songs.optJSONObject("data") == null ? null : songs.optJSONObject("data").optJSONArray("info");
            Long candId = null; String candKey = null;
            if (info != null) {
                for (int i = 0; i < info.length() && candId == null; i++) {
                    JSONObject s = info.optJSONObject(i); if (s == null) continue;
                    int dur = s.optInt("duration", 0);
                    if (durationSec > 0 && Math.abs(dur - durationSec) > 8) continue;
                    byte[] lb = bytes("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=" + enc(s.optString("hash", "")), FF, 6000);
                    if (lb == null) continue;
                    JSONArray cands = ((JSONObject) new JSONTokener(new String(lb, StandardCharsets.UTF_8)).nextValue()).optJSONArray("candidates");
                    if (cands != null && cands.length() > 0) { candId = cands.getJSONObject(0).optLong("id"); candKey = cands.getJSONObject(0).optString("accesskey"); }
                }
            }
            if (candId == null) {
                byte[] lb = bytes("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + enc(kw) + (durationSec > 0 ? "&duration=" + (durationSec * 1000) : ""), FF, 6000);
                if (lb == null) return null;
                JSONArray cands = ((JSONObject) new JSONTokener(new String(lb, StandardCharsets.UTF_8)).nextValue()).optJSONArray("candidates");
                if (cands == null || cands.length() == 0) return null;
                candId = cands.getJSONObject(0).optLong("id"); candKey = cands.getJSONObject(0).optString("accesskey");
            }
            byte[] db = bytes("https://lyrics.kugou.com/download?ver=1&client=pc&fmt=lrc&charset=utf8&id=" + candId + "&accesskey=" + enc(candKey), FF, 6000);
            if (db == null) return null;
            String content = ((JSONObject) new JSONTokener(new String(db, StandardCharsets.UTF_8)).nextValue()).optString("content", "");
            if (content.isEmpty()) return null;
            String lrc = kgNormalize(new String(android.util.Base64.decode(content, android.util.Base64.DEFAULT), StandardCharsets.UTF_8));
            return lrc.trim().isEmpty() ? null : new String[]{"synced", lrc};
        } catch (Throwable e) { logErr("lirik-kg", title + " – " + artist + " · " + e); return null; }
    }

    // --- SimpMusic (dicari langsung pakai ID video YouTube — gak dipakai buat lagu SoundCloud) ---
    private static String[] smLyrics(String videoId, int durationSec) {
        try {
            byte[] b = bytes("https://api-lyrics.simpmusic.org/v1/" + enc(videoId), FF, 6000);
            if (b == null) return null;
            JSONObject o = (JSONObject) new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
            if (!"success".equals(o.optString("type", ""))) return null;
            JSONArray data = o.optJSONArray("data");
            if (data == null || data.length() == 0) return null;
            JSONObject best = null; int bestDiff = Integer.MAX_VALUE;
            for (int i = 0; i < data.length(); i++) {
                JSONObject tr = data.optJSONObject(i); if (tr == null) continue;
                int dur = tr.optInt("durationSeconds", 0);
                int diff = durationSec > 0 && dur > 0 ? Math.abs(dur - durationSec) : 0;
                if (durationSec > 0 && dur > 0 && diff > 10) continue;
                if (best == null || diff < bestDiff) { best = tr; bestDiff = diff; }
            }
            if (best == null) return null;
            String rich = best.optString("richSyncLyrics", ""), synced = best.optString("syncedLyrics", ""), plain = best.optString("plainLyric", "");
            if (!rich.trim().isEmpty()) { String lrc = richsyncToLrc(rich); if (lrc != null) return new String[]{"synced", lrc}; }
            if (!synced.trim().isEmpty()) return new String[]{"synced", synced};
            if (!plain.trim().isEmpty()) return new String[]{"plain", plain};
            return null;
        } catch (Throwable e) { logErr("lirik-sm", videoId + " · " + e); return null; }
    }
    // richSyncLyrics SimpMusic formatnya sama kayak "rich sync" Musixmatch: larik JSON [{"ts":mulai,"te":selesai,
    // "l":[{"c":"kata","o":offsetDetik}],"x":"teks baris"}]. Dikonversi jadi "LRC enhanced" biar lewat parseLrc.
    private static String richsyncToLrc(String json) {
        try {
            Object v = new JSONTokener(json).nextValue();
            if (!(v instanceof JSONArray)) return null;
            JSONArray arr = (JSONArray) v;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject ln = arr.optJSONObject(i); if (ln == null) continue;
                double ts = ln.optDouble("ts", -1); if (ts < 0) continue;
                out.append('[').append(lrcTime(ts)).append(']');
                JSONArray words = ln.optJSONArray("l");
                if (words != null && words.length() > 0) {
                    for (int j = 0; j < words.length(); j++) {
                        JSONObject w = words.optJSONObject(j); if (w == null) continue;
                        double wt = ts + w.optDouble("o", 0);
                        out.append('<').append(lrcTime(wt)).append('>').append(w.optString("c", ""));
                    }
                    out.append('\n');
                } else {
                    out.append(ln.optString("x", "")).append('\n');
                }
            }
            return out.length() == 0 ? null : out.toString();
        } catch (Throwable e) { return null; }
    }
    private static String lrcTime(double sec) {
        int m = (int) (sec / 60), s = (int) (sec % 60), cs = (int) Math.round((sec - Math.floor(sec)) * 100);
        return String.format(java.util.Locale.ROOT, "%02d:%02d.%02d", m, s, cs);
    }

    // --- BetterLyrics (TTML dari lyrics-api.boidu.dev, cadangan unison.boidu.dev — dua-duanya API publik gratis
    // gak butuh kunci; cadangan ke-3 di NanzBeatles adalah lrclib lagi, gak diulang di sini karena sudah ada) ---
    private static class TLine { String text; double start; List<Object[]> words = new ArrayList<>(); } // words: {String text, Double start, Double end}
    private static List<TLine> ttmlParse(String xml) {
        List<TLine> out = new ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            org.w3c.dom.Document doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            org.w3c.dom.NodeList ps = doc.getElementsByTagName("p");
            for (int i = 0; i < ps.getLength(); i++) {
                org.w3c.dom.Element p = (org.w3c.dom.Element) ps.item(i);
                String begin = p.getAttribute("begin"); if (begin == null || begin.isEmpty()) continue;
                TLine line = new TLine(); line.start = ttmlTime(begin);
                StringBuilder text = new StringBuilder();
                org.w3c.dom.NodeList kids = p.getChildNodes();
                for (int j = 0; j < kids.getLength(); j++) {
                    org.w3c.dom.Node n = kids.item(j);
                    if (n.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;
                    org.w3c.dom.Element span = (org.w3c.dom.Element) n;
                    if (!"span".equalsIgnoreCase(span.getLocalName() != null ? span.getLocalName() : span.getTagName())) continue;
                    String role = ttmlRole(span);
                    if ("x-translation".equals(role) || "x-roman".equals(role)) continue;
                    String wb = span.getAttribute("begin"), we = span.getAttribute("end"), wt = span.getTextContent();
                    wt = wt == null ? "" : wt.trim();
                    if (wt.isEmpty() || wb.isEmpty() || we.isEmpty()) continue;
                    if (text.length() > 0) text.append(' ');
                    text.append(wt);
                    line.words.add(new Object[]{wt, ttmlTime(wb), ttmlTime(we)});
                }
                line.text = text.length() > 0 ? text.toString() : (p.getTextContent() == null ? "" : p.getTextContent().trim());
                if (!line.text.isEmpty()) out.add(line);
            }
        } catch (Throwable e) { return new ArrayList<>(); }
        return out;
    }
    private static String ttmlRole(org.w3c.dom.Element span) {
        String r = span.getAttributeNS("http://www.w3.org/ns/ttml#metadata", "role");
        if (r != null && !r.isEmpty()) return r;
        r = span.getAttribute("ttm:role");
        return r == null ? "" : r;
    }
    private static double ttmlTime(String t) {
        try {
            String[] p = t.split(":");
            if (p.length == 2) return Double.parseDouble(p[0]) * 60 + Double.parseDouble(p[1]);
            if (p.length == 3) return Double.parseDouble(p[0]) * 3600 + Double.parseDouble(p[1]) * 60 + Double.parseDouble(p[2]);
            return Double.parseDouble(t.replace("s", ""));
        } catch (Throwable e) { return 0; }
    }
    private static String ttmlToLrc(List<TLine> lines) {
        StringBuilder out = new StringBuilder();
        for (TLine l : lines) {
            out.append('[').append(lrcTime(l.start)).append(']');
            for (Object[] w : l.words) out.append('<').append(lrcTime((Double) w[1])).append('>').append((String) w[0]);
            if (l.words.isEmpty()) out.append(l.text);
            out.append('\n');
        }
        return out.toString();
    }
    private static String[] blLyrics(String title, String artist, int durationSec) {
        try {
            String url = "https://lyrics-api.boidu.dev/getLyrics?s=" + enc(title) + "&a=" + enc(artist) + (durationSec > 0 ? "&d=" + durationSec : "");
            byte[] b = bytes(url, FF, 6000);
            if (b != null) {
                JSONObject o = (JSONObject) new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
                String ttml = o == null ? "" : o.optString("ttml", "").trim();
                if (!ttml.isEmpty()) { List<TLine> ls = ttmlParse(ttml); if (!ls.isEmpty()) return new String[]{"synced", ttmlToLrc(ls)}; }
            }
            byte[] b2 = bytes("https://unison.boidu.dev/lyrics?song=" + enc(title) + "&artist=" + enc(artist) + (durationSec > 0 ? "&duration=" + durationSec : ""), FF, 6000);
            if (b2 == null) return null;
            JSONObject o2 = (JSONObject) new JSONTokener(new String(b2, StandardCharsets.UTF_8)).nextValue();
            JSONObject data = o2 == null ? null : o2.optJSONObject("data");
            if (data == null) return null;
            String fmt = data.optString("format", "").toLowerCase(java.util.Locale.ROOT), lyr = data.optString("lyrics", "");
            if (lyr.trim().isEmpty()) return null;
            if ("ttml".equals(fmt)) { List<TLine> ls = ttmlParse(lyr); return ls.isEmpty() ? null : new String[]{"synced", ttmlToLrc(ls)}; }
            if ("lrc".equals(fmt)) return new String[]{"synced", lyr};
            if ("plain".equals(fmt)) return new String[]{"plain", lyr};
            return null;
        } catch (Throwable e) { logErr("lirik-bl", title + " – " + artist + " · " + e); return null; }
    }

    // ===== Paket 29: scrobbling Last.fm =====
    // Terinspirasi fitur Last.fm di NanzBeatles, ditulis ulang dari nol di sini (NanzBeatles pakai library
    // Kotlin "lastfm" yang gak bisa dipakai langsung dari Java+WebView; ini implementasi manual mengikuti
    // dokumentasi resmi Last.fm: https://www.last.fm/api/desktopauth (alur otorisasi) dan
    // https://www.last.fm/api/show/track.scrobble / track.updateNowPlaying.
    // API key & shared secret didaftarkan SENDIRI oleh pemilik di last.fm/api/account/create (gratis,
    // per-pengguna) — bukan kunci bersama/dibagi siapa pun.
    static final String LASTFM_KEY = "63a7203c2002245375326da82e5e541e";
    static final String LASTFM_SECRET = "bd8458601877bb2cf1b90ee0a3dbcbe3";
    private static final String LASTFM_PREF = "alya_lastfm";
    private static volatile String lfSessionKey, lfUsername;

    // Dipanggil sekali dari initDirs() (lihat di atas) — muat sesi yang sudah tersimpan (kalau sebelumnya
    // pernah terhubung), biar gak perlu login ulang tiap buka app.
    private static void lfLoadSaved() {
        if (appCtx == null) return;
        android.content.SharedPreferences sp = appCtx.getSharedPreferences(LASTFM_PREF, android.content.Context.MODE_PRIVATE);
        lfSessionKey = sp.getString("session", null);
        lfUsername = sp.getString("user", null);
    }

    static boolean lfConnected() { return lfSessionKey != null; }
    static String lfUsername() { return lfUsername; }

    // Tanda tangan permintaan: urutkan SEMUA parameter (kecuali "format"/"callback", yang di sini memang
    // sengaja belum dimasukkan ke map saat lfSig dipanggil) berdasar nama, gabungkan nama+nilai TANPA
    // pemisah apa pun, tempel SHARED_SECRET di paling akhir, lalu MD5 — persis spesifikasi resmi Last.fm.
    private static String lfSig(Map<String, String> params) {
        java.util.TreeMap<String, String> sorted = new java.util.TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) sb.append(e.getKey()).append(e.getValue());
        sb.append(LASTFM_SECRET);
        try { return neHex(MessageDigest.getInstance("MD5").digest(sb.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { return ""; }
    }

    private static String lfQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) { if (sb.length() > 0) sb.append('&'); sb.append(e.getKey()).append('=').append(enc(e.getValue())); }
        return sb.toString();
    }

    // Langkah 1 dari alur "desktop authentication": minta token sementara.
    static String lfGetToken() throws Exception {
        Map<String, String> p = new java.util.HashMap<>();
        p.put("method", "auth.getToken"); p.put("api_key", LASTFM_KEY);
        p.put("api_sig", lfSig(p)); p.put("format", "json");
        byte[] b = bytes("https://ws.audioscrobbler.com/2.0/?" + lfQuery(p), "AlyaMusicApp/1.0", 10000);
        if (b == null) throw new Exception("Gagal menghubungi Last.fm");
        JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
        if (o.has("error")) throw new Exception(o.optString("message", "Galat Last.fm"));
        return o.getString("token");
    }

    // URL yang perlu dibuka pengguna (di browser) buat login & menyetujui otorisasi app ini.
    static String lfAuthUrl(String token) { return "https://www.last.fm/api/auth/?api_key=" + enc(LASTFM_KEY) + "&token=" + enc(token); }

    // Langkah 2 (dipanggil SETELAH pengguna klik "Yes, allow access" di halaman Last.fm): tukar token
    // sementara jadi session key permanen, lalu simpan. Mengembalikan nama pengguna Last.fm kalau berhasil.
    static String lfConnect(String token) throws Exception {
        Map<String, String> p = new java.util.HashMap<>();
        p.put("method", "auth.getSession"); p.put("api_key", LASTFM_KEY); p.put("token", token);
        p.put("api_sig", lfSig(p)); p.put("format", "json");
        byte[] b = bytes("https://ws.audioscrobbler.com/2.0/?" + lfQuery(p), "AlyaMusicApp/1.0", 10000);
        if (b == null) throw new Exception("Gagal menghubungi Last.fm");
        JSONObject o = new JSONObject(new String(b, StandardCharsets.UTF_8));
        if (o.has("error")) throw new Exception(o.optString("message", "Otorisasi belum disetujui atau sudah kedaluwarsa, coba lagi dari awal"));
        JSONObject sess = o.getJSONObject("session");
        lfSessionKey = sess.getString("key"); lfUsername = sess.getString("name");
        if (appCtx != null) appCtx.getSharedPreferences(LASTFM_PREF, android.content.Context.MODE_PRIVATE).edit()
                .putString("session", lfSessionKey).putString("user", lfUsername).apply();
        return lfUsername;
    }

    static void lfDisconnect() {
        lfSessionKey = null; lfUsername = null;
        if (appCtx != null) appCtx.getSharedPreferences(LASTFM_PREF, android.content.Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static boolean lfPost(String method, String artist, String title, long startedAtSec) {
        if (lfSessionKey == null) return false;
        try {
            Map<String, String> p = new java.util.HashMap<>();
            p.put("method", method); p.put("api_key", LASTFM_KEY); p.put("sk", lfSessionKey);
            p.put("artist", artist); p.put("track", title);
            if (startedAtSec > 0) p.put("timestamp", String.valueOf(startedAtSec));
            p.put("api_sig", lfSig(p)); p.put("format", "json");
            byte[] bb = lfQuery(p).getBytes(StandardCharsets.UTF_8);
            HttpURLConnection c = (HttpURLConnection) new URL("https://ws.audioscrobbler.com/2.0/").openConnection();
            c.setConnectTimeout(10000); c.setReadTimeout(10000);
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("User-Agent", "AlyaMusicApp/1.0");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            c.setFixedLengthStreamingMode(bb.length);
            try (OutputStream os = c.getOutputStream()) { os.write(bb); }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in != null) in.close();
            return code < 400;
        } catch (Exception e) { return false; }
    }

    // "Now playing" TIDAK dihitung sebagai scrobble permanen di profil Last.fm — cuma nampilin "sedang
    // dengerin ..." sementara di profil, dipanggil tiap lagu MULAI diputar.
    static boolean lfNowPlaying(String artist, String title) { return lfPost("track.updateNowPlaying", artist, title, 0); }

    // Scrobble BENERAN (tercatat permanen di riwayat profil) — dipanggil dari JS (player.html) setelah lagu
    // diputar cukup lama sesuai aturan resmi Last.fm (separuh durasi atau 4 menit, mana yg lebih dulu, dan
    // lagu minimal 30 detik). startedAtSec WAJIB waktu lagu itu MULAI diputar (bukan waktu scrobble dikirim).
    static boolean lfScrobble(String artist, String title, long startedAtSec) { return lfPost("track.scrobble", artist, title, startedAtSec); }
}