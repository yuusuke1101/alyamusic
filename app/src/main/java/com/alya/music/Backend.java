package com.alya.music;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
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
        String title, artist, url, mime;
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

    // ---------- info + alamat audio ----------
    static Track track(String id) throws Exception {
        Track t = cache.get(id);
        if (t != null && t.exp > System.currentTimeMillis()) return t;
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
        t.title = info.getName();
        t.artist = info.getUploaderName();
        t.seconds = info.getDuration();
        t.url = best.getContent();
        t.mime = best.getFormat() != null ? best.getFormat().getMimeType() : "audio/mp4";
        Matcher m = Pattern.compile("[?&]expire=(\\d+)").matcher(t.url);
        t.exp = m.find() ? Long.parseLong(m.group(1)) * 1000 - 600000 : System.currentTimeMillis() + 3600000;
        cache.put(id, t);
        return t;
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
        o.put("lyrics", lyrics(t.title, t.artist, t.seconds));
        return o;
    }

    // ---------- lirik ----------
    static JSONArray lyrics(String title, String channel, long sec) {
        JSONArray out = new JSONArray();
        try {
            String t = cleanTitle(title), ch = cleanArtist(channel);
            int i = t.indexOf(" - ");
            String artist = i > 0 ? t.substring(0, i).trim() : ch;
            String track = i > 0 ? t.substring(i + 3).trim() : t;
            String raw = "";
            for (String a : new LinkedHashSet<>(Arrays.asList(artist, ch))) {
                if (a.isEmpty()) continue;
                Object o = lrc("get?track_name=" + enc(track) + "&artist_name=" + enc(a) + (sec >= 1 ? "&duration=" + sec : ""));
                if (o instanceof JSONObject && !synced((JSONObject) o).trim().isEmpty()) { raw = synced((JSONObject) o); break; }
            }
            if (raw.isEmpty()) {
                Object o = lrc("search?q=" + enc((artist + " " + track).trim()));
                if (o instanceof JSONArray) {
                    JSONArray arr = (JSONArray) o;
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject x = arr.optJSONObject(k);
                        if (x == null || synced(x).trim().isEmpty()) continue;
                        if (sec > 0 && Math.abs(x.optDouble("duration", 0) - sec) > 8) continue;
                        raw = synced(x);
                        break;
                    }
                }
            }
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
            for (Object[] r : rows) {
                JSONObject o = new JSONObject();
                o.put("time", (Double) r[0]);
                o.put("text", (String) r[1]);
                out.put(o);
            }
        } catch (Exception e) { /* lirik opsional */ }
        return out;
    }

    private static String synced(JSONObject o) { return o.isNull("syncedLyrics") ? "" : o.optString("syncedLyrics", ""); }

    private static Object lrc(String path) {
        try {
            byte[] b = bytes("https://lrclib.net/api/" + path, "AlyaMusicApp/1.0");
            return b == null ? null : new JSONTokener(new String(b, StandardCharsets.UTF_8)).nextValue();
        } catch (Exception e) { return null; }
    }

    // ---------- util ----------
    static byte[] bytes(String url, String ua) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
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
