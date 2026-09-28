package dza.folbol.BLABONGO;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * FIX para logs 2026-09-25 tt35050712 y tt29567402
 * Problemas detectados en tu log:
 * 1) OkHttp L0 candidatos iframe: 7 pero eran .css/.js (all.min.css) -> filtra mal GENERIC_SRC
 * 2) WebView nunca hizo click en #bigPlay / #fakePlayer -> nunca cargo /embed/player/... -> timeout 25s con 0 m3u8
 * 3) Timeout 25s corto para WASM + ads, y no maneja VS_EXPIRED / publicidades overlay
 * 4) embed69.org/f/ usa oxserver con fakePlayer + AES, no cloud -> necesita otro selector
 *
 * Este FIX:
 * - Corrige deepExtract para NO considerar .css/.js/.png como candidatos (solo embeds/player/vid/vs_src/details)
 * - WebView: click agresivo para ambos mundos (vsembed #bigPlay + embed69 #fakePlayer/.playButton)
 * - Timeout 35s, re-inyección cada 1.8s, manejo de cierres de ads mejorado
 * - shouldInterceptRequest: whitelist para vs_src, player, vsdec, data.vidsrc.sh y NO bloquear esos
 * - Intercepta m3u8 de TODOS los hosts (petrichor, vidhide, streamwish, voe) no solo petrichor
 */
public class StreamResolver {

    private static final String TAG = "StreamResolver";
    private static final long WEBVIEW_TIMEOUT_MS = 35_000; // 25->35 para WASM + oxserver
    private static final int MAX_IFRAME_DEPTH = 4;
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern AD_URL = Pattern.compile(
            ".*(popads|popcash|exoclick|propellerads|adsterra|doubleclick|googlesyndication|adservice|analytics|facebook\\.net/tr|googletagmanager|dtscout|dtscdn|histats|secutorlunts).*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FAKE_M3U8 = Pattern.compile(
            ".*(?:^|[/._])(check|ping|validate|geo|ad|ads|ima|vast|preroll|tracking|beacon|monitor|heartbeat|blank|empty)(?:[/._]|$).*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern M3U8_URL = Pattern.compile(
            "(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_B64 = Pattern.compile(
            "atob\\([\"']([A-Za-z0-9+/=]+)[\"']\\)", Pattern.CASE_INSENSITIVE);

    private static final Pattern IFRAME_SRC = Pattern.compile(
            "<iframe[^>]*src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    // FIX: NO incluir href genérico que agarra <link rel=stylesheet> .css
    private static final Pattern GENERIC_SRC = Pattern.compile(
            "(?:src|data-src|data-url)\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PACKER_HINT = Pattern.compile(
            "eval\\(function\\(p,a,c,k,e", Pattern.CASE_INSENSITIVE);

    // Para details/vs_src/playerUrl
    private static final Pattern DETAILS_PHP = Pattern.compile("(/player/api/details\\.php\\?id=[^\"'\\s]+)");
    private static final Pattern VS_SRC_API = Pattern.compile("(/vs_src\\.php\\?[^\"'\\s]+)");
    private static final Pattern PLAYER_URL_CFG = Pattern.compile("\"playerUrl\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern SRC_JSON = Pattern.compile("\"src\"\\s*:\\s*\"(https?://[^\"]+)\"");
    private static final Pattern EMBEDS_JSON = Pattern.compile("\"(https?://[^\"']+/embed[^\"]*)\"");

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public static class StreamResult
    // === VSEMBED FIX 2026-09-28: WASM+Token para vsembed (peliculas) ===
    private static boolean isVsEmbed(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(java.util.Locale.US);
        return l.contains("vsembed") || l.contains("ds_lang=es") || l.contains("cloudorchestranova");
    }
    private static String extractImdbVs(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = Pattern.compile("(tt\\d+)").matcher(url);
        return m.find() ? m.group(1) : null;
    }
    private static String extractDsLangVs(String url) {
        if (url == null) return "es";
        try {
            String q = new URL(url).getQuery();
            if (q != null) for (String p : q.split("&")) if (p.startsWith("ds_lang=")) return p.split("=")[1];
        } catch (Exception ignored) {}
        return "es";
    }
    private static String extractJsonStringVs(String json, String key) {
        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) return m.group(1).replace("\\u0026","&").replace("\\/","/");
        return null;
    }
    private static String resolveUrlVs(String base, String rel) {
        try {
            if (rel.startsWith("http")) return rel;
            URL b = new URL(base);
            if (rel.startsWith("/")) return b.getProtocol() + "://" + b.getHost() + rel;
            return new URL(b, rel).toString();
        } catch (Exception e) { return rel; }
    }
    private static String httpGetVs(String urlStr, String referer) throws IOException {
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
    private static String extractRegexVs(String text, String regex) {
        java.util.regex.Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static StreamResult resolveVsEmbedFast(Context context, String vsembedUrl, String referer) throws Exception {
        long t0 = System.currentTimeMillis();
        String imdb = extractImdbVs(vsembedUrl);
        String dsLang = extractDsLangVs(vsembedUrl);
        if (imdb == null) throw new IOException("vsembed sin imdb: " + vsembedUrl);
        String type = vsembedUrl.contains("/tv/") || vsembedUrl.contains("type=tv") ? "tv" : "movie";
        String vsSrcUrl = "https://vsembed.ru/vs_src.php?type=" + type + "&id=" + imdb + "&ds_lang=" + (dsLang != null ? dsLang : "es");
        Log.d(TAG, "[vsembed] 1 vs_src: " + vsSrcUrl);
        String vsSrcJson = httpGetVs(vsSrcUrl, vsembedUrl);
        String cloudUrl = extractJsonStringVs(vsSrcJson, "src");
        if (cloudUrl == null || cloudUrl.isEmpty()) throw new IOException("vs_src sin src: " + vsSrcJson);
        Log.d(TAG, "[vsembed] cloudUrl: " + cloudUrl);
        String cloudHtml = httpGetVs(cloudUrl, vsembedUrl);
        String playerRel = extractRegexVs(cloudHtml, "\"playerUrl\"\\s*:\\s*\"([^\"]+)\"");
        if (playerRel == null) throw new IOException("CFG.playerUrl no encontrado");
        playerRel = playerRel.replace("\\u0026","&").replace("\\/","/");
        String playerUrl = resolveUrlVs(cloudUrl, playerRel);
        Log.d(TAG, "[vsembed] 2 playerUrl: " + playerUrl);
        String playerHtml = httpGetVs(playerUrl, cloudUrl);
        String apiUrl = extractRegexVs(playerHtml, "\"api\"\\s*:\\s*\"([^\"]+)\"");
        if (apiUrl == null) throw new IOException("CONFIG.api no encontrado");
        apiUrl = apiUrl.replace("\\u0026","&");
        Log.d(TAG, "[vsembed] 3 api: " + apiUrl);
        String apiJson = httpGetVs(apiUrl, "https://cloudorchestranova.com/");
        String encB64 = extractJsonStringVs(apiJson, "stream_urls");
        String wasmUrl = extractJsonStringVs(apiJson, "wasm_url");
        String w = extractRegexVs(apiJson, "\"w\"\\s*:\\s*(\\d+)");
        if (encB64 == null || wasmUrl == null) throw new IOException("API sin stream_urls/wasm");
        Log.d(TAG, "[vsembed] 4 enc " + encB64.length() + " w=" + w + " wasm=" + wasmUrl);
        List<String> rawUrls = decryptViaWebViewVs(context, encB64, wasmUrl, w);
        if (rawUrls == null || rawUrls.isEmpty()) throw new IOException("WASM decrypt vacío");
        Log.d(TAG, "[vsembed] 5 rawUrls: " + rawUrls.size() + " -> " + rawUrls.get(0));
        String raw = rawUrls.get(0);
        String host = new URL(raw).getHost();
        String tokenUrl = "https://" + host + "/generate.php";
        Log.d(TAG, "[vsembed] 6 token: " + tokenUrl);
        String token = httpGetVs(tokenUrl, "https://cloudorchestranova.com/").trim().replace("\"","");
        if (token.length() < 50) throw new IOException("token inválido: " + token);
        Log.d(TAG, "[vsembed] token " + token.substring(0,20) + "...");
        String tokenized = raw.contains("__TOKEN__") ? raw.replace("__TOKEN__", token) : raw + (raw.contains("?") ? "&" : "?") + "token=" + token;
        Log.d(TAG, "[vsembed] 7 tokenized: " + tokenized);
        String master = httpGetVs(tokenized, "https://cloudorchestranova.com/");
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

    private static List<String> decryptViaWebViewVs(Context context, String encB64, String wasmUrl, String w) throws Exception {
        if (context == null) throw new IOException("Context null para WebView decrypt");
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<List<String>> out = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> err = new java.util.concurrent.atomic.AtomicReference<>();
        final Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            try {
                @SuppressLint("SetJavaScriptEnabled")
                WebView webView = new WebView(context);
                android.webkit.WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setAllowFileAccess(true);
                s.setAllowContentAccess(true);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    s.setMediaPlaybackRequiresUserGesture(false);
                }
                webView.addJavascriptInterface(new Object() {
                    @android.webkit.JavascriptInterface
                    public void onDecrypted(String jsonArray) {
                        try {
                            List<String> list = new ArrayList<>();
                            java.util.regex.Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(jsonArray);
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
                    @android.webkit.JavascriptInterface
                    public void onError(String msg) {
                        err.set(msg);
                        latch.countDown();
                        new Handler(Looper.getMainLooper()).post(() -> { try { webView.destroy(); } catch(Exception ignored){} });
                    }
                }, "AndroidBridge");
                webView.setWebChromeClient(new android.webkit.WebChromeClient() {
                    @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                        Log.d(TAG, "[vsembed][JS] " + m.message());
                        return true;
                    }
                });
                webView.setWebViewClient(new android.webkit.WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) {}
                    @Override public void onReceivedError(WebView view, android.webkit.WebResourceRequest req, android.webkit.WebResourceError error) {
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

 {
        public final String m3u8Url;
        public final String cookies;
        public final String referer;
        public final String origin;
        public final Map<String, String> headers;
        public StreamResult(String m3u8Url, String cookies, String referer, String origin, Map<String, String> headers) {
            this.m3u8Url = m3u8Url; this.cookies = cookies!=null?cookies:""; this.referer = referer!=null?referer:""; this.origin = origin!=null?origin:""; this.headers = headers!=null?headers:new HashMap<>();
        }
        public StreamResult(String m3u8Url, String cookies, String referer, Map<String, String> headers) {
            this(m3u8Url, cookies, referer, getBaseUrl(referer), headers);
        }
    }

    public static StreamResult resolveSynchronously(Context context, String initialUrl) {
        if (initialUrl == null || initialUrl.isEmpty()) return null;
        long startTime = System.currentTimeMillis();
        String extracted = extractRealUrl(initialUrl);
        final String realUrl = (extracted != null) ? extracted : initialUrl;
        Log.d(TAG, "🔍 URL original: " + initialUrl);
        Log.d(TAG, "🎯 URL extraída: " + realUrl);
        if (realUrl.contains(".m3u8") && isRealStream(realUrl)) {
            Log.d(TAG, "✅ Ya es .m3u8 directo");
            return new StreamResult(realUrl, "", getBaseUrl(initialUrl), getDefaultHeaders(initialUrl));
        }

        // FAST-PATH VSEMBED para peliculas - verificado 2026-09-28
        if (isVsEmbed(realUrl)) {
            Log.i(TAG, "[FAST] vsembed detectado WASM+Token");
            try {
                StreamResult vs = resolveVsEmbedFast(context, realUrl, getBaseUrl(initialUrl));
                if (vs != null && vs.m3u8Url != null && !vs.m3u8Url.isEmpty()) {
                    Log.i(TAG, "[FAST] vsembed OK: " + vs.m3u8Url);
                    return vs;
                }
            } catch (Exception e) {
                Log.w(TAG, "[FAST] vsembed fail, fallback: " + e.getMessage());
            }
        }
        // Si viene de pelislatinohd, resolver embed real si es necesario (opcional)
        // Si initialUrl ya es vsembed/embed69, lo dejamos tal cual

        Log.d(TAG, "⚡ Iniciando resolución profunda (OkHttp recursivo + WebView)...");
        ExecutorService race = Executors.newFixedThreadPool(2);
        try {
            Callable<StreamResult> deepOkHttp = () -> {
                String found = deepExtract(realUrl, 0, new HashSet<>(), initialUrl);
                if (found != null) {
                    Log.d(TAG, "✅ OkHttp profundo encontró m3u8 en " + (System.currentTimeMillis() - startTime) + "ms: " + found);
                    String ref = found.contains("petrichorparallax") ? "https://cloudorchestranova.com/" : getBaseUrl(initialUrl);
                    Map<String,String> h = getDefaultHeaders(ref);
                    // Para vidhide/streamwish el referer correcto es el embed original
                    if (found.contains("vidhide") || found.contains("streamwish") || found.contains("voe")) {
                        h.put("Referer", realUrl);
                    }
                    return new StreamResult(found, "", ref, h);
                }
                throw new Exception("OkHttp profundo no encontró stream (esperado para WASM/AES)");
            };
            Callable<StreamResult> deepWebView = () -> {
                StreamResult sr = resolveWithDeepWebView(context, realUrl, initialUrl);
                if (sr != null) {
                    Log.d(TAG, "✅ WebView profundo encontró stream en " + (System.currentTimeMillis() - startTime) + "ms");
                    return sr;
                }
                throw new Exception("WebView profundo falló");
            };
            return race.invokeAny(Arrays.asList(deepOkHttp, deepWebView), WEBVIEW_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.e(TAG, "⏰ Timeout global — ningún método devolvió stream a tiempo");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); return null;
        } catch (ExecutionException e) {
            Log.e(TAG, "Todas las estrategias fallaron: " + (e.getCause()!=null?e.getCause().getMessage():e.getMessage()));
            return null;
        } finally { race.shutdownNow(); }
    }

    private static String deepExtract(String url, int depth, Set<String> visited, String originalReferer) {
        if (depth > MAX_IFRAME_DEPTH) return null;
        String norm = url.trim().replace("&amp;","&").replace("\\/","/").replace("\\u0026","&");
        if (visited.contains(norm)) return null;
        visited.add(norm);
        Log.d(TAG, "  [OkHttp L" + depth + "] GET " + truncate(norm, 120));
        String html;
        try {
            Request req = new Request.Builder().url(norm)
                    .addHeader("User-Agent", DESKTOP_USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Referer", depth==0?getBaseUrl(originalReferer):getBaseUrl(norm))
                    .addHeader("Accept-Language", "es-ES,es;q=0.9,en;q=0.8").build();
            try (Response resp = httpClient.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body()==null) {
                    Log.d(TAG, "  [L"+depth+"] HTTP "+resp.code());
                    return null;
                }
                html = resp.body().string();
            }
        } catch (Exception e) { Log.d(TAG, "  [L"+depth+"] error red: "+e.getMessage()); return null; }

        Matcher m = M3U8_URL.matcher(html);
        while (m.find()){
            String cand=m.group(1);
            if (isRealStream(cand)){ Log.d(TAG,"  [L"+depth+"] 🎯 m3u8 directo: "+truncate(cand,150)); return cand; }
        }
        Matcher b64 = M3U8_B64.matcher(html);
        while (b64.find()){
            try{
                String dec=new String(Base64.decode(b64.group(1),Base64.DEFAULT),"UTF-8");
                Matcher dm=M3U8_URL.matcher(dec);
                if (dm.find() && isRealStream(dm.group(1))){ Log.d(TAG,"  [L"+depth+"] 🎯 m3u8 en base64: "+dm.group(1)); return dm.group(1); }
            }catch(Exception ignored){}
        }

        List<String> candidates=new ArrayList<>();

        // details.php
        Matcher detailsM = DETAILS_PHP.matcher(html);
        while(detailsM.find()){
            String path=detailsM.group(1).replace("&amp;","&");
            String detailUrl = path.startsWith("http")?path: getBaseUrl(norm)+path.replaceFirst("^/","");
            try{
                Request r=new Request.Builder().url(detailUrl).addHeader("User-Agent",DESKTOP_USER_AGENT).addHeader("Referer",norm).build();
                try(Response resp=httpClient.newCall(r).execute()){
                    if(resp.isSuccessful() && resp.body()!=null){
                        String j=resp.body().string();
                        Matcher emb=EMBEDS_JSON.matcher(j);
                        while(emb.find()){
                            String src=emb.group(1).replace("\\/","/"); if(src.startsWith("//"))src="https:"+src;
                            if(src.startsWith("http") && !visited.contains(src)) candidates.add(src);
                        }
                    }
                }
            }catch(Exception ignored){}
        }
        // vs_src
        Matcher vsM = VS_SRC_API.matcher(html);
        while(vsM.find()){
            String api=vsM.group(1);
            String vsUrl= api.startsWith("http")?api: getBaseUrl(norm).replaceAll("/$","")+api;
            try{
                Request r=new Request.Builder().url(vsUrl).addHeader("User-Agent",DESKTOP_USER_AGENT).addHeader("Referer",norm).build();
                try(Response resp=httpClient.newCall(r).execute()){
                    if(resp.isSuccessful() && resp.body()!=null){
                        String j=resp.body().string();
                        Matcher srcM=SRC_JSON.matcher(j);
                        if(srcM.find()){
                            String src=srcM.group(1).replace("\\/","/");
                            if(!visited.contains(src)) candidates.add(src);
                        }
                    }
                }
            }catch(Exception ignored){}
        }
        // CFG playerUrl
        Matcher cfgM=PLAYER_URL_CFG.matcher(html);
        while(cfgM.find()){
            String p=cfgM.group(1).replace("\\u0026","&").replace("\\/","/");
            String pu=p.startsWith("http")?p: p.startsWith("//")?"https:"+p: getBaseUrl(norm).replaceAll("/$","")+p;
            if(!visited.contains(pu)) candidates.add(pu);
        }

        // iframes reales - FILTRADO: excluir .css .js .png etc
        Matcher iframeM=IFRAME_SRC.matcher(html);
        while(iframeM.find()){
            String src=iframeM.group(1).trim().replace("&amp;","&");
            if(src.startsWith("//"))src="https:"+src;
            if(src.matches(".*\\.(css|js|png|jpg|jpeg|gif|webp|svg|woff2?)(\\?.*)?$")) continue;
            if(AD_URL.matcher(src).find()) continue;
            if(src.startsWith("http") && !candidates.contains(src) && !visited.contains(src)) candidates.add(src);
            else if(src.startsWith("/")){
                try{ String abs=new java.net.URL(norm).getProtocol()+"://"+new java.net.URL(norm).getHost()+src; if(!candidates.contains(abs) && !visited.contains(abs)) candidates.add(abs); }catch(Exception ignored){}
            }
        }

        // GENERIC solo src/data-src, NO href -> evita <link href="all.min.css">
        if(candidates.isEmpty()){
            Matcher genericM=GENERIC_SRC.matcher(html);
            while(genericM.find()){
                String src=genericM.group(1).replace("&amp;","&");
                if(src.startsWith("//"))src="https:"+src;
                if(!src.startsWith("http")) continue;
                if(src.matches(".*\\.(css|js|png|jpg|jpeg|gif|webp|svg|woff2?)(\\?.*)?$")) continue;
                if(src.contains(".m3u8")||src.contains("embed")||src.contains("player")||src.contains("vid")||src.contains("details.php")||src.contains("vs_src")){
                    if(!AD_URL.matcher(src).find() && !visited.contains(src) && !candidates.contains(src)) candidates.add(src);
                }
            }
        }

        Log.d(TAG,"  [L"+depth+"] candidatos iframe: "+candidates.size());
        for(String cand: candidates){
            String found=deepExtract(cand, depth+1, visited, originalReferer);
            if(found!=null) return found;
        }
        if(PACKER_HINT.matcher(html).find()) Log.d(TAG,"  [L"+depth+"] packer detectado");
        return null;
    }

    private static StreamResult resolveWithDeepWebView(Context context, String targetUrl, String wrapperUrl){
        final CountDownLatch latch=new CountDownLatch(1);
        final StreamResult[] result={null};
        final String wrapperBase=getBaseUrl(wrapperUrl);
        final AtomicBoolean destroyed=new AtomicBoolean(false);
        final Handler mainHandler=new Handler(Looper.getMainLooper());
        final Set<String> seenM3u8=new HashSet<>();
        Log.d(TAG,"🏗️ [WebView profundo] target="+truncate(targetUrl,90)+" referer="+wrapperBase);
        mainHandler.post(()->{
            WebView webView=new WebView(context);
            WebSettings s=webView.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setUserAgentString(DESKTOP_USER_AGENT);
            s.setBlockNetworkImage(true);
            s.setLoadsImagesAutomatically(false);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            s.setAllowFileAccess(false);
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptCookie(true);
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP) CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true);

            Handler timeout=new Handler(Looper.getMainLooper());
            Runnable timeoutAction=()->{
                if(!destroyed.get()){
                    Log.e(TAG,"⏰ Timeout WebView profundo ("+WEBVIEW_TIMEOUT_MS+"ms) — candidates vistos: "+seenM3u8.size());
                    destroyWebView(webView,destroyed,mainHandler);
                    latch.countDown();
                }
            };
            timeout.postDelayed(timeoutAction,WEBVIEW_TIMEOUT_MS);

            class JsBridge{
                @android.webkit.JavascriptInterface public void onStreamFound(String url){
                    if(url==null||!url.contains(".m3u8")||!isRealStream(url)) return;
                    synchronized(result){
                        if(result[0]==null && !destroyed.get()){
                            synchronized(seenM3u8){ if(seenM3u8.contains(url)) return; seenM3u8.add(url); }
                            timeout.removeCallbacks(timeoutAction);
                            CookieManager.getInstance().flush();
                            String cookies=CookieManager.getInstance().getCookie(url);
                            if(cookies==null) cookies="";
                            String ref=url.contains("petrichorparallax")?"https://cloudorchestranova.com/":wrapperBase;
                            String origin=ref.replaceAll("/$","");
                            result[0]=new StreamResult(url,cookies,ref,origin,getDefaultHeaders(ref));
                            Log.d(TAG,"🎯 [WebView JS bridge] m3u8: "+truncate(url,150));
                            mainHandler.post(()->{ destroyWebView(webView,destroyed,mainHandler); latch.countDown(); });
                        }
                    }
                }
                @android.webkit.JavascriptInterface public void log(String msg){ Log.d(TAG,"[WebView JS] "+msg); }
            }
            webView.addJavascriptInterface(new JsBridge(),"AndroidStreamBridge");
            AtomicBoolean earlyInjected=new AtomicBoolean(false);

            webView.setWebViewClient(new WebViewClient(){
                @Override public void onReceivedSslError(WebView view,SslErrorHandler h,SslError e){ h.proceed(); }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                    String url=request.getUrl().toString();
                    // WHITELIST crítica: nunca bloquear
                    if(url.contains("vs_src.php")||url.contains("details.php")||url.contains("disable-devtool")||url.contains("vsdec.js")||url.contains("/embed/player/")||url.contains("data.vidsrc.sh")||url.contains("player-oxserver")||url.contains("iframePlayer")){
                        if(url.contains("vs_src")||url.contains("m3u8")) Log.d(TAG,"  [WebView] pass critical: "+truncate(url,120));
                        return super.shouldInterceptRequest(view,request);
                    }
                    if(url.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|woff|woff2|ttf|eot)(\\?.*)?$")||AD_URL.matcher(url).find()){
                        return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                    }
                    if(url.contains(".m3u8") && isRealStream(url)){
                        synchronized(seenM3u8){ if(seenM3u8.contains(url)) return super.shouldInterceptRequest(view,request); seenM3u8.add(url); }
                        if(result[0]==null && !destroyed.get()){
                            timeout.removeCallbacks(timeoutAction);
                            CookieManager.getInstance().flush();
                            String cookies=CookieManager.getInstance().getCookie(url); if(cookies==null)cookies="";
                            Map<String,String> captured=new HashMap<>(); if(request.getRequestHeaders()!=null) captured.putAll(request.getRequestHeaders());
                            String origin=captured.get("Origin"); if(origin==null||origin.isEmpty()) origin=url.contains("petrichorparallax")?"https://cloudorchestranova.com":getBaseUrl(targetUrl);
                            String referer=captured.get("Referer"); if(referer==null||referer.isEmpty()) referer=origin+"/";
                            if(url.contains("petrichorparallax")){ origin="https://cloudorchestranova.com"; referer="https://cloudorchestranova.com/"; }
                            synchronized(result){
                                if(result[0]==null){
                                    result[0]=new StreamResult(url,cookies,referer,origin,captured);
                                    Log.d(TAG,"🎯 [intercept] m3u8: "+truncate(url,150)+" origin="+origin);
                                    mainHandler.post(()->{ destroyWebView(view,destroyed,mainHandler); latch.countDown(); });
                                }
                            }
                        }
                    }
                    if(url.contains("embed")||url.contains("player")||url.contains("vs_src")||url.contains("details.php")||url.contains("cloudorchestra")) Log.d(TAG,"  [WebView] nav: "+truncate(url,120));
                    return super.shouldInterceptRequest(view,request);
                }
                @Override public void onPageFinished(WebView view,String url){
                    super.onPageFinished(view,url);
                    if(result[0]!=null||destroyed.get()) return;
                    injectDeepScript(view);
                }
            });
            webView.setWebChromeClient(new android.webkit.WebChromeClient(){
                @Override public void onProgressChanged(WebView view,int newProgress){
                    super.onProgressChanged(view,newProgress);
                    if(newProgress>30 && earlyInjected.compareAndSet(false,true) && result[0]==null && !destroyed.get()){
                        injectDeepScript(view);
                    }
                }
            });
            // FIX embed69 anti-top: si es embed69 y se carga como top, se auto-blanquea (window.self===window.top)
            if (targetUrl.contains("embed69.org")) {
                String wrapper = "<html><body style='margin:0;padding:0;overflow:hidden;background:#000'><iframe src='" + targetUrl.replace("'","%27") + "' style='width:100%;height:100vh;border:0' allowfullscreen allow='autoplay; fullscreen; encrypted-media'></iframe></body></html>";
                webView.loadDataWithBaseURL("https://embed69.org/", wrapper, "text/html", "UTF-8", null);
                Log.d(TAG, "[WebView] embed69 wrapped in iframe to bypass anti-top");
            } else {
                webView.loadUrl(targetUrl);
            }
        });
        try{ latch.await(WEBVIEW_TIMEOUT_MS+3000,TimeUnit.MILLISECONDS); }catch(InterruptedException e){ Thread.currentThread().interrupt(); }
        return result[0];
    }

    private static void injectDeepScript(WebView webView){
        String script="(function(){"
                +"try{"
                +"function notify(u){ if(!u||typeof u!=='string')return; if(u.indexOf('.m3u8')===-1)return; try{AndroidStreamBridge.onStreamFound(u);}catch(e){} }"
                +"window.addEventListener('message',function(e){ try{ if(e.data&&e.data.type==='VS_EXPIRED'){ AndroidStreamBridge.log('VS_EXPIRED'); try{location.reload();}catch(_){} } }catch(_){} });"
                // auto click startScreen (playpaste)
                +"try{ var ss=document.getElementById('startScreen'); if(ss){ ss.click(); AndroidStreamBridge.log('click #startScreen'); } }catch(e){}"
                +"document.querySelectorAll('source[src*=\".m3u8\"],video[src*=\".m3u8\"],a[href*=\".m3u8\"]').forEach(function(el){notify(el.src||el.href);});"
                +"var _open=XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open=function(m,u){try{notify(u);}catch(e){}return _open.apply(this,arguments);};"
                +"var _send=XMLHttpRequest.prototype.send; XMLHttpRequest.prototype.send=function(){this.addEventListener('load',function(){try{var ct=this.getResponseHeader('content-type')||'';if(ct.indexOf('mpegURL')!==-1)notify(this.responseURL);}catch(e){}});return _send.apply(this,arguments);};"
                +"var _fetch=window.fetch; if(_fetch){window.fetch=function(u,opts){try{notify(typeof u==='string'?u:(u&&u.url||''));}catch(e){}return _fetch.apply(this,arguments).then(function(r){try{if(r.url&&r.url.indexOf('.m3u8')!==-1)notify(r.url);}catch(e){}return r;});}"
                +"try{var _desc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');if(_desc&&_desc.set){Object.defineProperty(HTMLMediaElement.prototype,'src',{get:_desc.get,set:function(v){notify(v);return _desc.set.call(this,v);},configurable:true});}}catch(e){}"
                +"var obs=new MutationObserver(function(muts){muts.forEach(function(m){m.addedNodes.forEach(function(n){"
                +"try{if(n.src&&n.src.indexOf('.m3u8')!==-1)notify(n.src); if(n.tagName==='IFRAME'&&n.src){try{notify(n.src);}catch(e){}} }catch(e){}"
                +"});});}); obs.observe(document,{childList:true,subtree:true});"
                +"try{var html=document.documentElement.outerHTML;var re=/(https?:\\/\\/[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)/gi,m;while(m=re.exec(html))notify(m[1]);}catch(e){}"
                +"function closeAds(){"
                +"  var sels=['[id*=\"overlay\"]','[class*=\"overlay\"]','[id*=\"popup\"]','[class*=\"popup\"]','[class*=\"modal\"]','[id*=\"ad\"]','[class*=\"ad-cover\"]','div[style*=\"z-index: 999\"]'];"
                +"  sels.forEach(function(sel){try{document.querySelectorAll(sel).forEach(function(el){ var r=el.getBoundingClientRect(); if(r.width>80&&r.height>80){ var c=el.querySelector('[class*=\"close\"],button'); if(c)try{c.click();}catch(e){} else try{el.style.display='none';}catch(e){} } });}catch(e){}});"
                +"} closeAds(); setInterval(closeAds,1200);"
                +"function clickPlay(){"
                +"  try{ var bp=document.getElementById('bigPlay'); if(bp){ bp.click(); AndroidStreamBridge.log('click #bigPlay'); return true; }}catch(e){}"
                +"  try{ var bp2=document.querySelector('.jw-bigplay'); if(bp2){ bp2.click(); AndroidStreamBridge.log('click .jw-bigplay'); return true; }}catch(e){}"
                +"  try{ var ss=document.getElementById('startScreen'); if(ss){ ss.click(); AndroidStreamBridge.log('click #startScreen'); return true; }}catch(e){}"
                // embed69 oxserver: fakePlayer es el div que tapa el player
                +"  try{ var fp=document.getElementById('fakePlayer'); if(fp && fp.style.display!=='none'){ fp.click(); AndroidStreamBridge.log('click #fakePlayer'); return true; }}catch(e){}"
                +"  try{ var fpc=document.querySelector('.fake-player-container'); if(fpc){ fpc.click(); AndroidStreamBridge.log('click .fake-player-container'); return true; }}catch(e){}"
                +"  try{ var pb=document.querySelector('.playButton'); if(pb){ pb.click(); AndroidStreamBridge.log('click .playButton'); return true; }}catch(e){}"
                +"  try{ var lb=document.getElementById('langBtn'); if(lb){ /* no click lang, solo log */ } }catch(e){}"
                +"  var sels=['#bigPlay','.jw-bigplay','#fakePlayer','.fake-player-container','.playButton','.play','button'];"
                +"  for(var i=0;i<sels.length;i++){ var els=document.querySelectorAll(sels[i]); for(var j=0;j<els.length;j++){ var el=els[j]; if(!el)continue; var txt=(el.textContent||el.getAttribute('aria-label')||'').toLowerCase(); if(el.id==='bigPlay'||el.id==='fakePlayer'||el.className.toString().toLowerCase().indexOf('play')!==-1 || txt.indexOf('play')!==-1 || txt.indexOf('reproducir')!==-1){ try{el.click(); AndroidStreamBridge.log('click play: '+sels[i]);}catch(e){} return true; } } }"
                +"  var v=document.querySelector('video'); if(v){ try{v.play(); v.click(); AndroidStreamBridge.log('video.play()');}catch(e){} return true; }"
                +"  return false;"
                +"}"
                +"clickPlay();"
                +"setTimeout(clickPlay,600); setTimeout(clickPlay,1500); setTimeout(clickPlay,2800); setTimeout(closeAds,400);"
                +"setTimeout(function(){ try{ var f=document.getElementById('player_frame'); if(f&&f.src) AndroidStreamBridge.log('player_frame src: '+f.src); var ip=document.getElementById('iframePlayer'); if(ip&&ip.src) {AndroidStreamBridge.log('iframePlayer src: '+ip.src); notify(ip.src);} }catch(e){} },1800);"
                +"}catch(e){try{AndroidStreamBridge.log('inject error: '+e.message);}catch(_){}}"
                +"})();";
        webView.evaluateJavascript(script,null);
    }

    private static String extractRealUrl(String url){
        if(url==null||!url.contains("?r=")) return url;
        String raw=url.substring(url.indexOf("?r=")+3).trim();
        if(raw.startsWith("http")) return raw;
        if(raw.matches("^[A-Za-z0-9+/=]+$")){
            String padded=raw; while(padded.length()%4!=0) padded+="=";
            try{ byte[] d=Base64.decode(padded,Base64.DEFAULT); String dec=new String(d,"UTF-8"); if(dec.startsWith("http")) return dec; }catch(Exception e){ Log.e(TAG,"Error Base64: "+e.getMessage()); }
        }
        return url;
    }
    private static void destroyWebView(WebView w,AtomicBoolean d,Handler h){
        if(!d.compareAndSet(false,true)) return;
        if(Looper.myLooper()==Looper.getMainLooper()) destroyInternal(w); else h.post(()->destroyInternal(w));
    }
    private static void destroyInternal(WebView w){
        try{ w.stopLoading(); w.loadUrl("about:blank"); w.clearCache(true); w.clearHistory(); w.removeAllViews(); w.destroy(); }catch(Exception ignored){}
    }
    private static Map<String,String> getDefaultHeaders(String url){
        Map<String,String> h=new HashMap<>(); h.put("User-Agent",DESKTOP_USER_AGENT); h.put("Accept","*/*"); h.put("Accept-Language","es-ES,es;q=0.9,en;q=0.8");
        String base=getBaseUrl(url); if(base!=null&&!base.isEmpty()){ h.put("Referer",base); h.put("Origin",base.replaceAll("/$","")); } return h;
    }
    private static String getBaseUrl(String url){ try{ return new java.net.URL(url).getProtocol()+"://"+new java.net.URL(url).getHost()+"/"; }catch(Exception e){ return url; } }
    private static boolean isRealStream(String url){
        if(url.contains("petrichorparallax")||url.contains("vidhide")||url.contains("streamwish")||url.contains("voe")||url.contains("filemoon")) return true;
        return !FAKE_M3U8.matcher(url).matches();
    }
    private static String truncate(String s,int n){ if(s==null)return ""; return s.length()<=n?s:s.substring(0,n)+"…"; }
}
