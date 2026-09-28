package dza.folbol.BLABONGO;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Resuelve URLs de stream (.m3u8 HLS o .mpd DASH) enterradas bajo iframes,
 * publicidad y players que generan el stream por JavaScript.
 *
 * Carrera PARALELA OkHttp vs WebView. La WebView nunca se muestra: solo se
 * usa para resolver; ademas se maqueta a un tamano logico (1920x1080).
 *
 * NOTA DIAGNOSTICA: la tag del Log se ha puesto a "M3u8PelisResolver" para
 * que los logs salgan con el filtro que ya tiene el usuario en Logcat; ademas
 * cada paso clave tambien se imprime por System.out (aparece siempre).
 */
public class PelisStreamResolver {

    private static final String TAG = "M3u8PelisResolver";
    private static final long WEBVIEW_TIMEOUT_MS = 28_000;
    private static final int MAX_IFRAME_DEPTH = 4;

    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern AD_URL = Pattern.compile(
            ".*(popads|popcash|exoclick|propellerads|adsterra|doubleclick|googlesyndication|adservice|facebook\\.net/tr|googletagmanager|cloudflareinsights|gstatic\\.com/recaptcha).*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FAKE_M3U8 = Pattern.compile(
            ".*(?:^|[/._])(check|ping|validate|geo|ad|ads|ima|vast|preroll|tracking|beacon|monitor|heartbeat|blank|empty)(?:[/._]|$).*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STREAM_URL = Pattern.compile(
            "(https?://[^\\s\"'<>()\\\\]+?(?:\\.m3u8|\\.mpd)(?:[^\\s\"'<>()\\\\]*)?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern M3U8_B64 = Pattern.compile(
            "atob\\([\"']([A-Za-z0-9+/=]{20,})[\"']\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IFRAME_SRC = Pattern.compile(
            "<iframe[^>]*src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERIC_SRC = Pattern.compile(
            "(?:src|data-src|data-url|file|href)\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private static final Pattern PACKER_HINT = Pattern.compile(
            "eval\\(function\\(p,a,c,k,e", Pattern.CASE_INSENSITIVE);

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public static class StreamResult {
        public final String m3u8Url;
        public final String cookies;
        public final String referer;
        public final String origin;
        public final Map<String, String> headers;

        public StreamResult(String m3u8Url, String cookies, String referer, String origin,
                            Map<String, String> headers) {
            this.m3u8Url = m3u8Url;
            this.cookies = cookies != null ? cookies : "";
            this.referer = referer != null ? referer : "";
            this.origin = origin != null ? origin : "";
            this.headers = headers != null ? headers : new HashMap<String, String>();
        }

        public StreamResult(String m3u8Url, String cookies, String referer, Map<String, String> headers) {
            this(m3u8Url, cookies, referer, getBaseUrl(referer), headers);
        }
    }

    private PelisStreamResolver() { }

    public static StreamResult resolveSynchronously(Context context, String initialUrl) {
        return resolveSynchronously(context, initialUrl, null);
    }

    public static StreamResult resolveSynchronously(Context context, String initialUrl, String refererOverride) {
        System.out.println("[PELIS-DBG] resolveSynchronously url=" + initialUrl + " ref=" + refererOverride);
        if (initialUrl == null || initialUrl.isEmpty()) return null;
        long startTime = System.currentTimeMillis();

        String extracted = extractRealUrl(initialUrl);
        final String realUrl = (extracted != null) ? extracted : initialUrl;

        final String referer = (refererOverride != null && !refererOverride.isEmpty())
                ? refererOverride
                : getBaseUrl(initialUrl);

        Log.i(TAG, "==================== PelisStreamResolver ====================");
        Log.i(TAG, " URL original: " + initialUrl);
        Log.i(TAG, " URL extraida: " + realUrl + " referer=" + referer);

        if (looksLikeStream(realUrl)) {
            Log.i(TAG, " Ya es stream directo");
            System.out.println("[PELIS-DBG] ya era stream directo");
            return new StreamResult(realUrl, "", referer, getDefaultHeaders(referer));
        }

        // FAST-PATH VSEMBED (películas) - verificado con Python/Java
        if (isVsEmbed(realUrl)) {
            Log.i(TAG, "[FAST] Detectado vsembed, usando resolver dedicado WASM+Token");
            System.out.println("[PELIS-DBG] vsembed detectado, fast-path WASM");
            try {
                StreamResult vs = resolveVsEmbed(context, realUrl, referer);
                if (vs != null && vs.m3u8Url != null && !vs.m3u8Url.isEmpty()) {
                    Log.i(TAG, "[FAST] vsembed OK en " + (System.currentTimeMillis()-startTime) + "ms: " + vs.m3u8Url);
                    System.out.println("[PELIS-DBG] vsembed OK " + vs.m3u8Url);
                    return vs;
                }
            } catch (Exception e) {
                Log.w(TAG, "[FAST] vsembed falló, fallback a race: " + e.getMessage());
                System.out.println("[PELIS-DBG] vsembed fail " + e.getMessage());
            }
        }

        final Context appCtx = (context != null) ? context.getApplicationContext() : null;
        ExecutorService race = Executors.newFixedThreadPool(2);
        try {
            final String ref = referer;

            Callable<StreamResult> viaOkHttp = new Callable<StreamResult>() {
                @Override public StreamResult call() throws Exception {
                    Log.d(TAG, "[race] OkHttp: iniciando");
                    System.out.println("[PELIS-DBG] OkHttp iniciando");
                    String found = deepExtract(realUrl, 0, new HashSet<String>(), ref);
                    if (found != null) {
                        Log.i(TAG, "[race] OkHttp OK en " + (System.currentTimeMillis() - startTime) + "ms: " + found);
                        System.out.println("[PELIS-DBG] OkHttp OK " + found);
                        return new StreamResult(found, "", ref, getDefaultHeaders(ref));
                    }
                    throw new Exception("OkHttp no encontro stream");
                }
            };

            Callable<StreamResult> viaWebView = new Callable<StreamResult>() {
                @Override public StreamResult call() throws Exception {
                    Log.d(TAG, "[race] WebView: iniciando");
                    System.out.println("[PELIS-DBG] WebView iniciando");
                    StreamResult sr = resolveWithDeepWebView(appCtx, realUrl, ref);
                    if (sr != null) {
                        Log.i(TAG, "[race] WebView OK en " + (System.currentTimeMillis() - startTime) + "ms: " + sr.m3u8Url);
                        System.out.println("[PELIS-DBG] WebView OK " + sr.m3u8Url);
                        return sr;
                    }
                    throw new Exception("WebView no encontro stream");
                }
            };

            StreamResult result = race.invokeAny(
                    Arrays.asList(viaOkHttp, viaWebView),
                    WEBVIEW_TIMEOUT_MS + 3000, TimeUnit.MILLISECONDS);

            Log.i(TAG, " Resuelto en " + (System.currentTimeMillis() - startTime) + "ms: " + result.m3u8Url);
            System.out.println("[PELIS-DBG] Resuelto " + result.m3u8Url);
            return result;

        } catch (TimeoutException e) {
            Log.e(TAG, " Timeout global");
            System.out.println("[PELIS-DBG] Timeout global");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            Log.e(TAG, " Todas las estrategias fallaron: " +
                    (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            System.out.println("[PELIS-DBG] Fallo: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return null;
        } finally {
            race.shutdownNow();
        }
    }

    
    // ------------------------------------------------------------
    // VSEMBED FAST-PATH (WASM + Token) - VERIFICADO 2026-09-28 Python/Java
    // vsembed.ru NO expone .m3u8 directo: stream_urls cifrado ChaCha20
    // ------------------------------------------------------------
    private static boolean isVsEmbed(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(java.util.Locale.US);
        return l.contains("vsembed") || l.contains("ds_lang=es") || l.contains("cloudorchestranova");
    }

    private static String extractImdb(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("(tt\\d+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }
    private static String extractDsLang(String url) {
        if (url == null) return "es";
        try {
            String q = new URL(url).getQuery();
            if (q != null) for (String p : q.split("&")) if (p.startsWith("ds_lang=")) return p.split("=")[1];
        } catch (Exception ignored) {}
        return "es";
    }
    private static String extractJsonString(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        Matcher m = p.matcher(json);
        if (m.find()) return m.group(1).replace("\\u0026","&").replace("\\/","/");
        return null;
    }
    private static String resolveUrl(String base, String rel) {
        try {
            if (rel.startsWith("http")) return rel;
            URL b = new URL(base);
            if (rel.startsWith("/")) return b.getProtocol() + "://" + b.getHost() + rel;
            return new URL(b, rel).toString();
        } catch (Exception e) { return rel; }
    }
    private static String httpGet(String urlStr, String referer) throws IOException {
        Request req = new Request.Builder()
                .url(urlStr)
                .addHeader("User-Agent", DESKTOP_USER_AGENT)
                .addHeader("Referer", referer != null ? referer : getBaseUrl(urlStr))
                .addHeader("Accept", "*/*")
                .build();
        try (Response resp = httpClient.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) throw new IOException("HTTP " + resp.code() + " " + urlStr);
            return resp.body().string();
        }
    }
    private static String extractRegex(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static StreamResult resolveVsEmbed(Context context, String vsembedUrl, String referer) throws Exception {
        long t0 = System.currentTimeMillis();
        String imdb = extractImdb(vsembedUrl);
        String dsLang = extractDsLang(vsembedUrl);
        if (imdb == null) throw new IOException("vsembed sin imdb: " + vsembedUrl);
        String type = vsembedUrl.contains("/tv/") || vsembedUrl.contains("type=tv") ? "tv" : "movie";
        String vsSrcUrl = "https://vsembed.ru/vs_src.php?type=" + type + "&id=" + imdb + "&ds_lang=" + (dsLang != null ? dsLang : "es");
        Log.d(TAG, "[vsembed] 1 vs_src: " + vsSrcUrl);
        String vsSrcJson = httpGet(vsSrcUrl, vsembedUrl);
        String cloudUrl = extractJsonString(vsSrcJson, "src");
        if (cloudUrl == null || cloudUrl.isEmpty()) throw new IOException("vs_src sin src: " + vsSrcJson);
        Log.d(TAG, "[vsembed] cloudUrl: " + cloudUrl);
        String cloudHtml = httpGet(cloudUrl, vsembedUrl);
        String playerRel = extractRegex(cloudHtml, "\"playerUrl\"\\s*:\\s*\"([^\"]+)\"");
        if (playerRel == null) throw new IOException("CFG.playerUrl no encontrado");
        playerRel = playerRel.replace("\\u0026","&").replace("\\/","/");
        String playerUrl = resolveUrl(cloudUrl, playerRel);
        Log.d(TAG, "[vsembed] 2 playerUrl: " + playerUrl);
        String playerHtml = httpGet(playerUrl, cloudUrl);
        String apiUrl = extractRegex(playerHtml, "\"api\"\\s*:\\s*\"([^\"]+)\"");
        if (apiUrl == null) throw new IOException("CONFIG.api no encontrado");
        apiUrl = apiUrl.replace("\\u0026","&");
        Log.d(TAG, "[vsembed] 3 api: " + apiUrl);
        String apiJson = httpGet(apiUrl, "https://cloudorchestranova.com/");
        String encB64 = extractJsonString(apiJson, "stream_urls");
        String wasmUrl = extractJsonString(apiJson, "wasm_url");
        String w = extractRegex(apiJson, "\"w\"\\s*:\\s*(\\d+)");
        if (encB64 == null || wasmUrl == null) throw new IOException("API sin stream_urls/wasm");
        Log.d(TAG, "[vsembed] 4 enc " + encB64.length() + " w=" + w + " wasm=" + wasmUrl);
        List<String> rawUrls = decryptViaWebView(context, encB64, wasmUrl, w);
        if (rawUrls == null || rawUrls.isEmpty()) throw new IOException("WASM decrypt vacío");
        Log.d(TAG, "[vsembed] 5 rawUrls: " + rawUrls.size() + " -> " + rawUrls.get(0));
        System.out.println("[PELIS-DBG] vsembed raw " + rawUrls.get(0));
        String raw = rawUrls.get(0);
        String host = new URL(raw).getHost();
        String tokenUrl = "https://" + host + "/generate.php";
        Log.d(TAG, "[vsembed] 6 token: " + tokenUrl);
        String token = httpGet(tokenUrl, "https://cloudorchestranova.com/").trim().replace("\"","");
        if (token.length() < 50) throw new IOException("token inválido: " + token);
        Log.d(TAG, "[vsembed] token " + token.substring(0,20) + "...");
        String tokenized = raw.contains("__TOKEN__") ? raw.replace("__TOKEN__", token) : raw + (raw.contains("?") ? "&" : "?") + "token=" + token;
        Log.d(TAG, "[vsembed] 7 tokenized: " + tokenized);
        String master = httpGet(tokenized, "https://cloudorchestranova.com/");
        if (master.contains("no token") || !master.contains("#EXTM3U")) {
            throw new IOException("master.m3u8 inválido: " + master.substring(0, Math.min(100, master.length())));
        }
        Log.d(TAG, "[vsembed] 8 master OK " + master.length() + " en " + (System.currentTimeMillis()-t0) + "ms");
        Map<String,String> headers = getDefaultHeaders("https://cloudorchestranova.com/");
        headers.put("Referer", "https://cloudorchestranova.com/");
        headers.put("Origin", "https://cloudorchestranova.com");
        String cookies = "";
        try { cookies = CookieManager.getInstance().getCookie("https://cloudorchestranova.com"); } catch (Exception ignored) {}
        if (cookies == null) cookies = "";
        return new StreamResult(tokenized, cookies, "https://cloudorchestranova.com/", "https://cloudorchestranova.com", headers);
    }

    private static List<String> decryptViaWebView(Context context, String encB64, String wasmUrl, String w) throws Exception {
        if (context == null) throw new IOException("Context null para WebView decrypt");
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<String>> out = new AtomicReference<>();
        final AtomicReference<String> err = new AtomicReference<>();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                @SuppressLint("SetJavaScriptEnabled")
                WebView webView = new WebView(context);
                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(true);
                s.setAllowContentAccess(true);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    s.setMediaPlaybackRequiresUserGesture(false);
                }
                webView.addJavascriptInterface(new Object() {
                    @JavascriptInterface
                    public void onDecrypted(String jsonArray) {
                        try {
                            List<String> list = new ArrayList<>();
                            Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(jsonArray);
                            while (m.find()) {
                                String u = m.group(1).replace("\\/","/");
                                if (u.startsWith("http") && u.contains("master.m3u8")) list.add(u);
                            }
                            if (list.isEmpty() && jsonArray.contains("master.m3u8")) {
                                for (String p : jsonArray.split("\\n")) {
                                    p = p.replace("\"","").replace("[","").replace("]","").trim();
                                    if (p.startsWith("http")) list.add(p);
                                }
                            }
                            out.set(list);
                        } catch (Exception e) { err.set(e.getMessage()); }
                        latch.countDown();
                        new Handler(Looper.getMainLooper()).post(() -> { try { webView.destroy(); } catch(Exception ignored){} });
                    }
                    @JavascriptInterface
                    public void onError(String msg) {
                        err.set(msg);
                        latch.countDown();
                        new Handler(Looper.getMainLooper()).post(() -> { try { webView.destroy(); } catch(Exception ignored){} });
                    }
                }, "AndroidBridge");
                webView.setWebChromeClient(new WebChromeClient() {
                    @Override public boolean onConsoleMessage(ConsoleMessage m) {
                        Log.d(TAG, "[vsembed][JS] " + m.message());
                        return true;
                    }
                });
                webView.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {}
                    @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                        Log.w(TAG, "[vsembed] WebView error: " + error);
                    }
                });
                String html = "<html><head><meta charset='utf-8'></head><body><script>\n" +
                    "async function doDecrypt(){\n" +
                    " try{\n" +
                    "  const encB64 = \"" + encB64.replace("\"", "\\\"") + "\";\n" +
                    "  const wasmUrl = \"" + wasmUrl + "\";\n" +
                    "  const wasmBytes = await fetch(wasmUrl,{credentials:'omit'}).then(r=>r.arrayBuffer()).then(b=>new Uint8Array(b));\n" +
                    "  const mod = await WebAssembly.compile(wasmBytes);\n" +
                    "  const inst = await WebAssembly.instantiate(mod, {});\n" +
                    "  const ex = inst.exports;\n" +
                    "  function b64(s){ const bin=atob(s); const u=new Uint8Array(bin.length); for(let i=0;i<bin.length;i++) u[i]=bin.charCodeAt(i); return u; }\n" +
                    "  const enc = b64(encB64);\n" +
                    "  const ptr = ex.alloc(enc.length);\n" +
                    "  new Uint8Array(ex.memory.buffer, ptr, enc.length).set(enc);\n" +
                    "  const outLen = ex.decrypt(ptr, enc.length);\n" +
                    "  const txt = new TextDecoder().decode(new Uint8Array(ex.memory.buffer, ptr+12, outLen));\n" +
                    "  const urls = txt.split('\\n').filter(s=>s.trim().length>0);\n" +
                    "  AndroidBridge.onDecrypted(JSON.stringify(urls));\n" +
                    " }catch(e){ AndroidBridge.onError(String(e)); }\n" +
                    "}\n" +
                    "doDecrypt();\n" +
                    "</script></body></html>";
                webView.loadDataWithBaseURL("https://cloudorchestranova.com/", html, "text/html", "utf-8", null);
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (latch.getCount() > 0) { err.set("timeout WASM decrypt"); latch.countDown(); try { webView.destroy(); } catch(Exception ignored){} }
                }, 15000);
            } catch (Exception e) { err.set(e.getMessage()); latch.countDown(); }
        });
        boolean ok = latch.await(18, TimeUnit.SECONDS);
        if (!ok) throw new IOException("timeout decrypt WebView");
        if (err.get() != null) throw new IOException(err.get());
        List<String> res = out.get();
        if (res == null) throw new IOException("decrypt sin resultado");
        return res;
    }


// ------------------------------------------------------------
    // OKHTTP RECURSIVO
    // ------------------------------------------------------------
    private static String deepExtract(String url, int depth, Set<String> visited, String referer) {
        if (depth > MAX_IFRAME_DEPTH) return null;
        String norm = url.trim();
        if (visited.contains(norm)) return null;
        visited.add(norm);

        Log.d(TAG, "  [OkHttp L" + depth + "] GET " + truncate(norm, 120));

        String html;
        try {
            Request req = new Request.Builder()
                    .url(norm)
                    .addHeader("User-Agent", DESKTOP_USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Referer", (depth == 0 && referer != null && !referer.isEmpty())
                            ? referer : getBaseUrl(norm))
                    .addHeader("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                    .build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    Log.d(TAG, "  [L" + depth + "] HTTP " + resp.code());
                    return null;
                }
                html = resp.body().string();
            }
        } catch (IOException e) {
            Log.d(TAG, "  [L" + depth + "] error red: " + e.getMessage());
            return null;
        }

        Log.d(TAG, "  [L" + depth + "] HTML " + html.length() + " bytes");

        String direct = findStream(html);
        if (direct != null) {
            Log.i(TAG, "  [L" + depth + "] stream directo: " + truncate(direct, 140));
            return direct;
        }

        Matcher b64 = M3U8_B64.matcher(html);
        while (b64.find()) {
            try {
                String decoded = new String(Base64.decode(b64.group(1), Base64.DEFAULT), "UTF-8");
                String cand = findStream(decoded);
                if (cand != null) {
                    Log.i(TAG, "  [L" + depth + "] stream en base64: " + cand);
                    return cand;
                }
            } catch (Exception ignored) { }
        }

        Matcher iframeM = IFRAME_SRC.matcher(html);
        List<String> candidates = new ArrayList<String>();
        while (iframeM.find()) {
            String src = iframeM.group(1);
            if (src != null) src = src.trim();
            if (src == null || src.isEmpty()) continue;
            if (src.startsWith("//")) src = "https:" + src;
            if (AD_URL.matcher(src).find()) continue;
            if (src.startsWith("http")) candidates.add(src);
            else if (src.startsWith("/")) {
                try {
                    URL base = new URL(norm);
                    candidates.add(base.getProtocol() + "://" + base.getHost() + src);
                } catch (Exception ignored) { }
            }
        }

        if (candidates.isEmpty()) {
            Matcher genericM = GENERIC_SRC.matcher(html);
            while (genericM.find()) {
                String src = genericM.group(1);
                if (src == null || !src.contains("http")) continue;
                if (src.contains(".m3u8") || src.contains(".mpd") || src.contains("embed") || src.contains("player") || src.contains("vid")) {
                    if (!AD_URL.matcher(src).find() && !visited.contains(src)) candidates.add(src);
                }
            }
        }

        Log.d(TAG, "  [L" + depth + "] candidatos iframe: " + candidates.size());
        for (String cand : candidates) {
            String res = deepExtract(cand, depth + 1, visited, referer);
            if (res != null) return res;
        }

        if (PACKER_HINT.matcher(html).find()) {
            Log.d(TAG, "  [L" + depth + "] packer detectado (JS ofuscado).");
        }

        return null;
    }

    private static String findStream(String text) {
        if (text == null) return null;
        Matcher m = STREAM_URL.matcher(text);
        while (m.find()) {
            String candidate = m.group(1);
            if (isRealStream(candidate)) return candidate;
        }
        return null;
    }

    // ------------------------------------------------------------
    // WEBVIEW INTERNA (solo para resolver)
    // ------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private static StreamResult resolveWithDeepWebView(Context context, String targetUrl, String referer) {
        if (context == null) {
            Log.e(TAG, "[WebView] contexto null; abortando");
            System.out.println("[PELIS-DBG] contexto null");
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> found = new AtomicReference<String>(null);
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final Handler main = new Handler(Looper.getMainLooper());
        final WebView[] holder = new WebView[1];

        main.post(new Runnable() {
            @Override public void run() {
                try {
                    Log.i(TAG, "[WebView] creando");
                    System.out.println("[PELIS-DBG] WebView creando");
                    WebView wv = new WebView(context);
                    holder[0] = wv;

                    WebSettings ws = wv.getSettings();
                    ws.setJavaScriptEnabled(true);
                    ws.setDomStorageEnabled(true);
                    ws.setUserAgentString(DESKTOP_USER_AGENT);
                    ws.setMediaPlaybackRequiresUserGesture(false);
                    ws.setJavaScriptCanOpenWindowsAutomatically(true);
                    ws.setLoadWithOverviewMode(true);
                    ws.setUseWideViewPort(true);
                    ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
                    ws.setAllowFileAccess(true);
                    try { ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW); } catch (Throwable ignored) { }

                    try {
                        wv.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
                        wv.layout(0, 0, 1920, 1080);
                    } catch (Throwable ignored) { }

                    CookieManager cm = CookieManager.getInstance();
                    cm.setAcceptCookie(true);
                    try { cm.setAcceptThirdPartyCookies(wv, true); } catch (Throwable ignored) { }

                    wv.addJavascriptInterface(new Bridge(found, latch), "PelisStreamBridge");

                    wv.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageStarted(WebView view, String url, Bitmap favicon) {
                            Log.i(TAG, "[WebView] onPageStarted: " + truncate(url, 120));
                            System.out.println("[PELIS-DBG] onPageStarted " + truncate(url, 120));
                        }

                        @Override
                        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                            try {
                                Log.w(TAG, "[WebView] onReceivedError " + request.getUrl() + " -> " + error.getDescription());
                                System.out.println("[PELIS-DBG] onReceivedError " + error.getDescription());
                            } catch (Exception ignored) { }
                        }

                        @Override
                        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                            try {
                                Log.w(TAG, "[WebView] HTTP " + errorResponse.getStatusCode() + " " + request.getUrl());
                            } catch (Exception ignored) { }
                        }

                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                            try {
                                String u = request.getUrl().toString();
                                String cand = pickStream(u);
                                if (cand == null) {
                                    String acc = null;
                                    try { acc = request.getRequestHeaders().get("Accept"); } catch (Exception ignored) { }
                                    if (acc != null && (acc.contains("mpegurl") || acc.contains("dash+xml") || acc.contains("apple"))) {
                                        cand = u;
                                        Log.i(TAG, "[WebView] URL con Accept m3u8/dash: " + truncate(u, 160));
                                    }
                                }
                                if (cand != null && found.compareAndSet(null, cand)) {
                                    Log.i(TAG, "[WebView] stream interceptado en red: " + cand);
                                    System.out.println("[PELIS-DBG] interceptado " + cand);
                                    latch.countDown();
                                }
                            } catch (Exception ignored) { }
                            return null;
                        }

                        @Override
                        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                            try {
                                String u = request.getUrl().toString();
                                String cand = pickStream(u);
                                if (cand != null && found.compareAndSet(null, cand)) {
                                    Log.i(TAG, "[WebView] stream en navegacion: " + cand);
                                    latch.countDown();
                                }
                            } catch (Exception ignored) { }
                            return false;
                        }

                        @Override
                        public void onLoadResource(WebView view, String url) {
                            try {
                                String cand = pickStream(url);
                                if (cand != null && found.compareAndSet(null, cand)) {
                                    Log.i(TAG, "[WebView] stream en recurso: " + cand);
                                    latch.countDown();
                                }
                            } catch (Exception ignored) { }
                        }

                        @Override
                        public void onPageFinished(WebView view, String url) {
                            Log.i(TAG, "[WebView] onPageFinished: " + truncate(url, 120));
                            System.out.println("[PELIS-DBG] onPageFinished");
                            try { view.evaluateJavascript(buildInjectScript(), null); } catch (Exception ignored) { }
                        }
                    });

                    wv.setWebChromeClient(new WebChromeClient() {
                        @Override
                        public boolean onConsoleMessage(ConsoleMessage cm) {
                            try { Log.d(TAG, "[WebView][JS] " + cm.message()); } catch (Exception ignored) { }
                            return true;
                        }
                    });

                    Map<String, String> hdrs = new HashMap<String, String>();
                    if (referer != null && !referer.isEmpty()) hdrs.put("Referer", referer);
                    Log.i(TAG, "[WebView] loadUrl " + truncate(targetUrl, 140));
                    wv.loadUrl(targetUrl, hdrs);
                } catch (Throwable e) {
                    Log.e(TAG, "[WebView] error creando: " + e.getMessage(), e);
                    System.out.println("[PELIS-DBG] error creando WebView: " + e.getMessage());
                    latch.countDown();
                }
            }
        });

        final int[] retry = {0};
        Runnable reinject = new Runnable() {
            @Override public void run() {
                if (retry[0]++ > 14 || destroyed.get() || found.get() != null) return;
                main.post(new Runnable() {
                    @Override public void run() {
                        try {
                            if (holder[0] != null && !destroyed.get())
                                holder[0].evaluateJavascript(buildInjectScript(), null);
                        } catch (Exception ignored) { }
                    }
                });
                main.postDelayed(this, 2000);
            }
        };
        main.postDelayed(reinject, 2000);

        try {
            latch.await(WEBVIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        main.post(new Runnable() {
            @Override public void run() {
                if (destroyed.compareAndSet(false, true) && holder[0] != null) {
                    try {
                        holder[0].stopLoading();
                        holder[0].loadUrl("about:blank");
                        holder[0].removeAllViews();
                        holder[0].destroy();
                    } catch (Exception ignored) { }
                    holder[0] = null;
                }
            }
        });

        String m3u8 = found.get();
        if (m3u8 != null && !m3u8.isEmpty()) {
            Log.i(TAG, "[WebView] RESULTADO: " + m3u8);
            System.out.println("[PELIS-DBG] WebView RESULTADO=" + m3u8);
            return new StreamResult(m3u8, "", referer, getDefaultHeaders(referer));
        }
        Log.w(TAG, "[WebView] no se encontro stream en el tiempo dado");
        System.out.println("[PELIS-DBG] WebView timeout sin stream");
        return null;
    }

    private static String pickStream(String u) {
        if (u == null) return null;
        if (!looksLikeStream(u)) return null;
        return u;
    }

    private static boolean looksLikeStream(String u) {
        if (u == null || u.isEmpty()) return false;
        String low = u.toLowerCase(Locale.ROOT);
        if (low.contains(".m3u8") || low.contains(".mpd")) {
            return !FAKE_M3U8.matcher(u).find();
        }
        return false;
    }

    private static class Bridge {
        private final AtomicReference<String> found;
        private final CountDownLatch latch;
        Bridge(AtomicReference<String> found, CountDownLatch latch) {
            this.found = found;
            this.latch = latch;
        }

        @JavascriptInterface
        public void found(String url) {
            if (url == null) return;
            if (!looksLikeStream(url)) return;
            if (found.compareAndSet(null, url)) {
                Log.i(TAG, "[WebView][JS] stream (patron): " + url);
                System.out.println("[PELIS-DBG] JS found patrón " + url);
                latch.countDown();
            }
        }

        @JavascriptInterface
        public void foundStream(String url) {
            if (url == null || url.isEmpty()) return;
            if (url.startsWith("blob:")) return;
            if (found.compareAndSet(null, url)) {
                Log.i(TAG, "[WebView][JS] stream (content-type): " + url);
                System.out.println("[PELIS-DBG] JS found CT " + url);
                latch.countDown();
            }
        }

        @JavascriptInterface
        public void log(String msg) {
            Log.d(TAG, "[JS] " + msg);
        }
    }

    private static String buildInjectScript() {
        String[] lines = {
            "(function(){",
            "  try {",
            "    if (window.__pelisHook3) return;",
            "    window.__pelisHook3 = true;",
            "    var reported = {};",
            "    function notify(u){ try{ if(!u || typeof u!=='string') return; if(u.indexOf('.m3u8')===-1 && u.indexOf('.mpd')===-1) return; if(reported[u]) return; reported[u]=1; window.PelisStreamBridge.found(u); }catch(e){} }",
            "    function reportManifest(u){ try{ if(!u || typeof u!=='string') return; if(u.indexOf('blob:')===0) return; if(reported[u]) return; reported[u]=1; window.PelisStreamBridge.foundStream(u); }catch(e){} }",
            "    function log(m){ try{ window.PelisStreamBridge.log(m); }catch(e){} }",
            "    log('hook v3 instalado');",
            "    function isManifestCT(ct){ if(!ct) return false; ct=(ct+'').toLowerCase(); return ct.indexOf('mpegurl')!==-1 || ct.indexOf('apple')!==-1 || ct.indexOf('x-mpeg')!==-1 || ct.indexOf('dash+xml')!==-1; }",
            "    var _open = XMLHttpRequest.prototype.open;",
            "    XMLHttpRequest.prototype.open = function(m,u){ try{ this.__pelis_url = (typeof u==='string'?u:(u&&u.url)||''); notify(this.__pelis_url); }catch(e){} return _open.apply(this, arguments); };",
            "    var _send = XMLHttpRequest.prototype.send;",
            "    XMLHttpRequest.prototype.send = function(){ var self=this; this.addEventListener('load', function(){ try{ var ct=self.getResponseHeader('content-type')||''; if(isManifestCT(ct)){ reportManifest(self.responseURL || self.__pelis_url); } try{ var rt=self.responseText||''; if(rt.length && rt.length<4*1024*1024){ var re=/(https?:\\/\\/[^\\s\"'<>]+(?:\\.m3u8|\\.mpd)[^\\s\"'<>]*)/gi, mm; while((mm=re.exec(rt))){ notify(mm[1]); } } }catch(e){} }catch(e){} }); return _send.apply(this, arguments); };",
            "    var _fetch = window.fetch;",
            "    if(_fetch){ window.fetch = function(u,o){ try{ var uu=typeof u==='string'?u:(u&&u.url||''); notify(uu); }catch(e){} return _fetch.apply(this, arguments).then(function(r){ try{ var ct = (r.headers && r.headers.get)? (r.headers.get('content-type')||'') : ''; if(isManifestCT(ct)){ reportManifest(r.url); } if(r && r.url && (r.url.indexOf('.m3u8')!==-1 || r.url.indexOf('.mpd')!==-1)){ notify(r.url); } try{ var cl=r.clone && r.clone(); if(cl && cl.text){ cl.text().then(function(t){ try{ if(t && t.length<4*1024*1024){ var re=/(https?:\\/\\/[^\\s\"'<>]+(?:\\.m3u8|\\.mpd)[^\\s\"'<>]*)/gi, mm; while((mm=re.exec(t))){ notify(mm[1]); } } }catch(e){} }); } }catch(e){} }catch(e){} return r; }); }; }",
            "    try { var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src'); if(d && d.set){ Object.defineProperty(HTMLMediaElement.prototype,'src',{ get:d.get, set:function(v){ notify(v); return d.set.call(this,v); }, configurable:true }); } } catch(e){}",
            "    try { if(window.MediaSource){ var _o=MediaSource.prototype.addSourceBuffer; MediaSource.prototype.addSourceBuffer=function(m){ log('MSB '+m); return _o.apply(this,arguments); }; } }catch(e){}",
            "    var obs = new MutationObserver(function(muts){ muts.forEach(function(m){ m.addedNodes.forEach(function(n){ try{ if(!n) return; if(n.src){ notify(n.src); } if(n.tagName==='SOURCE' && n.src){ notify(n.src); } if(n.tagName==='VIDEO' && n.src){ notify(n.src); } if(n.tagName==='IFRAME' && n.src){ try{ n.addEventListener('load', function(){ try{ var dd=n.contentDocument; if(dd){ dd.querySelectorAll('video,source').forEach(function(el){ notify(el.src); }); } }catch(e){} }); }catch(e){} } }catch(e){} }); }); });",
            "    obs.observe(document.documentElement, {childList:true, subtree:true});",
            "    try{ var re=/(https?:\\/\\/[^\\s\"'<>]+(?:\\.m3u8|\\.mpd)[^\\s\"'<>]*)/gi, mm; var html=document.documentElement.outerHTML; while((mm=re.exec(html))){ notify(mm[1]); } }catch(e){}",
            "    function clickPlay(){ var sels=['video','.vjs-big-play-button','.jw-icon-display','button.play','[class*=\"play\"]','[class*=\"Play\"]','[aria-label*=\"play\"]','[aria-label*=\"Play\"]','.player .play']; for(var i=0;i<sels.length;i++){ try{ var el=document.querySelector(sels[i]); if(el){ el.click(); } }catch(e){} } try{ var v=document.querySelector('video'); if(v){ v.muted=true; var p=v.play(); if(p && p.catch) p.catch(function(){}); } }catch(e){} }",
            "    clickPlay(); setTimeout(clickPlay, 800); setTimeout(clickPlay, 2000); setTimeout(clickPlay, 4000); setTimeout(clickPlay, 6500); setTimeout(clickPlay, 9000);",
            "  } catch(e){ try{ window.PelisStreamBridge.log('inject error: '+e.message); }catch(_){} }",
            "})();"
        };
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    private static String extractRealUrl(String url) {
        if (url == null || !url.contains("?r=")) return url;
        String raw = url.substring(url.indexOf("?r=") + 3).trim();
        if (raw.startsWith("http")) return raw;
        if (raw.matches("^[A-Za-z0-9+/=]+$")) {
            String padded = raw;
            while (padded.length() % 4 != 0) padded += "=";
            try {
                byte[] decoded = Base64.decode(padded, Base64.DEFAULT);
                String dec = new String(decoded, "UTF-8");
                if (dec.startsWith("http")) return dec;
            } catch (Exception e) {
                Log.e(TAG, "Error decodificando Base64: " + e.getMessage());
            }
        }
        return url;
    }

    private static boolean isRealStream(String url) {
        if (url == null) return false;
        if (url.indexOf(".m3u8") < 0 && url.indexOf(".mpd") < 0) return false;
        return !FAKE_M3U8.matcher(url).find();
    }

    private static Map<String, String> getDefaultHeaders(String referer) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", DESKTOP_USER_AGENT);
        if (referer != null && !referer.isEmpty()) {
            h.put("Referer", referer);
            h.put("Origin", getBaseUrl(referer));
        }
        return h;
    }

    private static String getBaseUrl(String url) {
        if (url == null) return "";
        try {
            URL u = new URL(url);
            return u.getProtocol() + "://" + u.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
