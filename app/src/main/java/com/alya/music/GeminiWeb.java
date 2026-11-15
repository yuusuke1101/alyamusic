package com.alya.music;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Paket 41: cadangan AI untuk terjemahan/romaji lewat antarmuka WEB Gemini (tanpa API key, sesi anonim).
// Port dari skrip Node yang dikirim pemilik; hanya bagian chat teks (gambar/unggah file tidak dibawa).
// PERINGATAN: ini endpoint internal tidak resmi. Format permintaan (urutan array payload, header x-goog-ext-*,
// token di halaman) bisa berubah sewaktu-waktu dan membuat ini gagal diam-diam. Karena itu hanya dipakai sebagai
// cadangan setelah OpenRouter gagal, dan tidak pernah dianggap pasti jalan.
final class GeminiWeb {
    private GeminiWeb() { }

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String APP = "https://gemini.google.com/app";
    private static final String BATCH = "https://gemini.google.com/_/BardChatUi/data/batchexecute?rpcids=maGuAc&source-path=%2F&hl=en-US&_reqid=1&rt=c";
    private static final String STREAM = "https://gemini.google.com/_/BardChatUi/data/assistant.lamda.BardFrontendService/StreamGenerate";
    private static final long SESSION_TTL = 10 * 60 * 1000L;
    private static final String FORM = "application/x-www-form-urlencoded;charset=UTF-8";

    private static final Map<String, String> cookies = new LinkedHashMap<>();
    private static String at, fSid, bl;
    private static long sessionAt = 0;
    private static int reqId = 10000;

    // Kirim satu prompt sebagai percakapan baru, kembalikan teks jawaban.
    static synchronized String ask(String prompt) throws Exception {
        if (prompt == null || prompt.trim().isEmpty()) throw new Exception("Prompt kosong");
        if (prompt.length() > 20000) throw new Exception("Prompt terlalu panjang");
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (attempt > 0 || sessionAt == 0 || System.currentTimeMillis() - sessionAt > SESSION_TTL) startSession();
                String t = stream(prompt);
                if (t.isEmpty()) throw new Exception("Gemini tidak memberi jawaban");
                return t;
            } catch (Exception e) {
                last = e;
                sessionAt = 0; // paksa sesi baru pada percobaan berikutnya
            }
        }
        throw last;
    }

    private static void startSession() throws Exception {
        cookies.clear();
        String page = http(APP, "GET", null, null, null);
        String b = find("\"cfb2h\":\\s*\"(.*?)\"", page);
        bl = b != null ? b : "boq_assistant-bard-web-server_20260709.09_p0";
        at = find("\"SNlM0e\":\"([^\"]+)\"", page);
        fSid = find("\"FdrFJe\":\"(-?\\d+)\"", page);
        try {
            http(BATCH, "POST", "f.req=[[[\"maGuAc\",\"[0]\",null,\"generic\"]]]&", FORM, null);
        } catch (Exception e) { /* cuma pemanasan cookie, boleh gagal */ }
        sessionAt = System.currentTimeMillis();
    }

    private static String stream(String prompt) throws Exception {
        reqId += 100000;
        // Urutan dan isi array persis seperti skrip asli (percakapan baru: metadata kosong).
        String payload = "[[" + JSONObject.quote(prompt) + ",0,null,null,null,null,0],[\"en-US\"],"
                + "[\"\",\"\",\"\",null,null,null,null,null,null,\"\"],"
                + "null,null,null,[1],1,null,null,1,0,null,null,null,null,null,[[0]],1,"
                + "null,null,null,null,null,"
                + "[\"\",\"\",\"\",null,null,null,null,null,0,null,1,null,null,null,[]],"
                + "null,null,1,null,null,null,null,null,null,null,"
                + "[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20],"
                + "1,null,null,null,null,[1]]";
        String freq = "[null," + JSONObject.quote(payload) + "]";

        String sid = fSid;
        if (sid == null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 19; i++) sb.append((int) (Math.random() * 10));
            sid = sb.toString();
        }
        String url = STREAM + "?hl=en-US&_reqid=" + reqId + "&rt=c"
                + (bl != null ? "&bl=" + enc(bl) : "") + "&f.sid=" + enc(sid);
        String body = "f.req=" + enc(freq) + (at != null ? "&at=" + enc(at) : "");

        Map<String, String> h = new HashMap<>();
        h.put("x-goog-ext-525001261-jspb", "[1,null,null,null,\"fbb127bbb056c959\",null,null,0,[4,6],null,null,1,null,null,1]");
        h.put("x-goog-ext-525005358-jspb", "[\"" + UUID.randomUUID().toString().toUpperCase() + "\",1]");
        h.put("x-goog-ext-73010989-jspb", "[0]");
        h.put("x-goog-ext-73010990-jspb", "[0,0,0]");
        h.put("x-same-domain", "1");
        h.put("Origin", "https://gemini.google.com");
        h.put("Referer", "https://gemini.google.com/");
        return parse(http(url, "POST", body, FORM, h));
    }

    // Balasan berbentuk bingkai: baris angka (panjang) lalu JSON; tiap item[2] berisi JSON lagi.
    // Teks jawaban ada di [4][i][1][0] dan bersifat kumulatif, jadi yang terakhir tidak kosong dipakai.
    static String parse(String raw) {
        String rem = raw == null ? "" : raw;
        if (rem.startsWith(")]}'")) rem = rem.substring(4).replaceFirst("^\\s+", "");
        String best = "";
        while (true) {
            int nl = rem.indexOf('\n');
            if (nl < 0) break;
            int size;
            try {
                size = Integer.parseInt(rem.substring(0, nl).trim());
            } catch (NumberFormatException e) {
                rem = rem.substring(nl + 1);
                continue;
            }
            if (size < 0 || rem.length() < nl + size) break;
            String frame = rem.substring(nl, nl + size);
            rem = rem.substring(nl + size);
            try {
                JSONArray fa = new JSONArray(frame.trim());
                for (int i = 0; i < fa.length(); i++) {
                    JSONArray item = fa.optJSONArray(i);
                    if (item == null) continue;
                    Object inner = item.opt(2);
                    if (!(inner instanceof String)) continue;
                    try {
                        JSONArray pj = new JSONArray((String) inner);
                        JSONArray cands = pj.optJSONArray(4);
                        if (cands == null) continue;
                        for (int k = 0; k < cands.length(); k++) {
                            JSONArray cand = cands.optJSONArray(k);
                            JSONArray c1 = cand == null ? null : cand.optJSONArray(1);
                            Object t = c1 == null ? null : c1.opt(0);
                            if (t instanceof String) {
                                String cl = clean((String) t);
                                if (!cl.isEmpty()) best = cl;
                            }
                        }
                    } catch (Exception e) { /* bingkai bukan jawaban, lewati */ }
                }
            } catch (Exception e) { /* bingkai tidak utuh, lewati */ }
        }
        return best;
    }

    private static String clean(String t) {
        return t.replaceAll("(?i)https?://[a-z0-9.-]*googleusercontent\\.com/[^\\s\"<>]+", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ")
                .trim();
    }

    private static String find(String re, String s) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private static String cookieStr() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static void saveCookies(List<String> set) {
        if (set == null) return;
        for (String line : set) {
            String pair = line.split(";", 2)[0];
            int i = pair.indexOf('=');
            if (i > 0) cookies.put(pair.substring(0, i).trim(), pair.substring(i + 1).trim());
        }
    }

    private static String http(String url, String method, String body, String ctype, Map<String, String> extra) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", UA);
        if (!cookies.isEmpty()) c.setRequestProperty("Cookie", cookieStr());
        if (ctype != null) c.setRequestProperty("Content-Type", ctype);
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (body != null) {
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
        }
        int code = c.getResponseCode();
        saveCookies(c.getHeaderFields().get("Set-Cookie"));
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String txt = "";
        if (in != null) {
            try { txt = new String(Backend.readAll(in), StandardCharsets.UTF_8); } finally { in.close(); }
        }
        if (code >= 400) throw new Exception("Gemini HTTP " + code);
        return txt;
    }
}
