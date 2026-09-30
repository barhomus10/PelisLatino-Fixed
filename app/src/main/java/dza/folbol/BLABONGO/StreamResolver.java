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
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class StreamResolver {

    private static final String TAG = "StreamResolver";
    // 20 s en vez de 10: hay paginas (Clappr, como lunchup.net) que cargan 1 MB de
// JavaScript y decodifican una configuracion enorme antes de pedir el m3u8. Con
// 10 s se cortaban siempre y el canal no salia aunque el WebView estuviera a
// punto de encontrar el stream.
private static final long WEBVIEW_TIMEOUT_MS = 20_000;
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    // ------------------------------------------------------------------
    // Los WebView de resolución cargan el embed del servidor y su reproductor
    // puede arrancar SOLO (autoplay). Se apuntan aquí para poder cerrarlos
    // todos de golpe cuando el usuario cierra el reproductor (así no queda
    // ningún audio sonando).
    // OJO: esta lista NO se debe vaciar al crear un WebView nuevo: las tres
    // estrategias (okHttp, iframe e inyección) corren A LA VEZ, y si cada una
    // destruye los WebViews de las demás se matan entre ellas y nunca se
    // encuentra el stream (el canal se quedaba en blanco y salía el WebView
    // de respaldo).
    // ------------------------------------------------------------------
    private static final java.util.List<WebView> webViewsActivos =
            java.util.Collections.synchronizedList(new java.util.ArrayList<WebView>());

    /** Cierra YA todos los WebViews de resolución: nada sigue sonando. */
    public static void destruirWebViewsActivos() {
        final java.util.List<WebView> copia;
        synchronized (webViewsActivos) {
            copia = new java.util.ArrayList<WebView>(webViewsActivos);
            webViewsActivos.clear();
        }
        if (copia.isEmpty()) return;
        Runnable accion = new Runnable() {
            @Override public void run() {
                for (WebView w : copia) {
                    try { w.stopLoading(); } catch (Throwable ignored) { }
                    try { w.loadUrl("about:blank"); } catch (Throwable ignored) { }
                    try { w.removeAllViews(); } catch (Throwable ignored) { }
                    try { w.destroy(); } catch (Throwable ignored) { }
                }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) accion.run();
        else new Handler(Looper.getMainLooper()).post(accion);
    }

    private static final Pattern FAKE_M3U8 = Pattern.compile(
            ".*(check|ping|validate|geo|ad|ads|ima|vast|preroll|tracking|beacon|monitor|heartbeat|blank|empty).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_URL = Pattern.compile(
            "(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IFRAME_SRC = Pattern.compile(
            "<iframe[^>]*src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
    /** Recursos que nunca pueden ser un stream. */
    private static final Pattern RECURSO_ESTATICO = Pattern.compile(
            ".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|css|woff|woff2|ttf|eot|js|json|xml|ico|mp4|mp3|apk)(\\?|$).*",
            Pattern.CASE_INSENSITIVE);
    /** Embeds de deportes tipo https://..../embed2/espn.php */
    private static final Pattern EMBED_DEPORTES = Pattern.compile(
            "https?://[^/]+/embed2?/[A-Za-z0-9_-]+\\.php", Pattern.CASE_INSENSITIVE);
    /** Reproductor stream.php, por si no viene dentro de un iframe. */
    private static final Pattern STREAM_PHP = Pattern.compile(
            "https?://[^\\s\"'<>\\\\]*stream\\.php\\?[^\\s\"'<>\\\\]*", Pattern.CASE_INSENSITIVE);
    /** Playlist HLS servido como playlist.php?id=..&sig=.. (sin .m3u8). */
    private static final Pattern PLAYLIST_PHP = Pattern.compile(
            "https?://[^\\s\"'<>\\\\]*playlist\\.php\\?[^\\s\"'<>\\\\]*", Pattern.CASE_INSENSITIVE);
    /** Pinta de playlist/stream, para los CDN que no usan la extension .m3u8. */
    private static final Pattern PARECE_STREAM = Pattern.compile(
            "(playlist|stream|live|hls|index|master|mono|chunklist|/vivo/|/channels/|\\.php\\?)",
            Pattern.CASE_INSENSITIVE);

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public static class StreamResult {
        public final String m3u8Url;
        public final String cookies;
        public final String referer;
        public final String origin;          // <-- NUEVO
        public final Map<String, String> headers;

        public StreamResult(String m3u8Url, String cookies, String referer, String origin,
                            Map<String, String> headers) {
            this.m3u8Url = m3u8Url;
            this.cookies = cookies != null ? cookies : "";
            this.referer = referer != null ? referer : "";
            this.origin = origin != null ? origin : "";
            this.headers = headers != null ? headers : new HashMap<>();
        }

        // Constructor de compatibilidad para los casos donde no tenemos origin
        public StreamResult(String m3u8Url, String cookies, String referer, Map<String, String> headers) {
            this(m3u8Url, cookies, referer, getBaseUrl(referer), headers);
        }
    }

    // ------------------------------------------------------------
    // MÉTODO PRINCIPAL (usa un pool de hilos fijo para no crear excesivos WebViews)
    // ------------------------------------------------------------
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);

    public static StreamResult resolveSynchronously(Context context, String initialUrl) {
        if (initialUrl == null || initialUrl.isEmpty()) return null;
        long startTime = System.currentTimeMillis();

        String extracted = extractRealUrl(initialUrl);
        final String realUrl = (extracted != null) ? extracted : initialUrl;

        Log.d(TAG, "🔍 URL original: " + initialUrl);
        Log.d(TAG, "🎯 URL extraída del embed: " + realUrl);

        if (isRealStream(realUrl) && esPlaylistHls(realUrl, getDefaultHeaders(initialUrl))) {
            Log.d(TAG, "✅ Ya es un stream directo .m3u8: " + realUrl);
            return new StreamResult(realUrl, "", getBaseUrl(initialUrl), getDefaultHeaders(initialUrl));
        }

        // Atajo: los embeds de deportes (embed2/espn.php y compania) se resuelven
        // directos, con 3 peticiones y sin WebView. Si la URL no es de esa
        // familia devuelve null y todo sigue igual que hasta ahora.
        StreamResult deportes = resolverEmbedDeportes(realUrl);
        if (deportes != null) {
            Log.d(TAG, "Embed de deportes resuelto en " + (System.currentTimeMillis() - startTime) + "ms");
            return deportes;
        }

        Log.d(TAG, "⚡ Iniciando carrera de 4 estrategias...");
        ExecutorService raceExecutor = Executors.newFixedThreadPool(4);
        try {
            // 1º la cadena: solo HTTP, sin WebView y sin mostrar nada por pantalla
            Callable<StreamResult> cadenaTask = () -> {
                StreamResult sr = resolverEnCadena(realUrl);
                if (sr != null) {
                    Log.d(TAG, "[cadena] Stream resuelto en " + (System.currentTimeMillis() - startTime) + "ms");
                    return sr;
                }
                throw new Exception("La cadena no encontro stream");
            };

            Callable<StreamResult> okHttpTask = () -> {
                String fast = tryFastExtraction(realUrl);
                if (fast != null) {
                    Log.d(TAG, "✅ OkHttp encontró stream en " + (System.currentTimeMillis() - startTime) + "ms");
                    return new StreamResult(fast, "", getBaseUrl(initialUrl), getDefaultHeaders(initialUrl));
                }
                throw new Exception("OkHttp no encontró stream");
            };

            Callable<StreamResult> iframeTask = () -> {
                StreamResult sr = resolveWithWebViewIframe(context, realUrl, initialUrl);
                if (sr != null) {
                    Log.d(TAG, "✅ WebView-iframe encontró stream en " + (System.currentTimeMillis() - startTime) + "ms");
                    return sr;
                }
                throw new Exception("WebView-iframe falló");
            };

            Callable<StreamResult> injectionTask = () -> {
                StreamResult sr = resolveWithWebViewInjection(context, realUrl, initialUrl);
                if (sr != null) {
                    Log.d(TAG, "✅ WebView-injection encontró stream en " + (System.currentTimeMillis() - startTime) + "ms");
                    return sr;
                }
                throw new Exception("WebView-injection falló");
            };

            return raceExecutor.invokeAny(Arrays.asList(cadenaTask, okHttpTask, iframeTask, injectionTask));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "Carrera interrumpida");
            return null;
        } catch (ExecutionException e) {
            Log.e(TAG, "Todas las estrategias fallaron: " + e.getCause().getMessage());
            return null;
        } finally {
            raceExecutor.shutdownNow();
        }
    }

    // ------------------------------------------------------------
    // ESTRATEGIA IFRAME (captura origin + headers completos)
    // ------------------------------------------------------------
    private static StreamResult resolveWithWebViewIframe(Context context, String targetUrl, String wrapperUrl) {
        final CountDownLatch latch = new CountDownLatch(1);
        final StreamResult[] result = {null};
        final String wrapperBase = getBaseUrl(wrapperUrl);
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        Log.d(TAG, "🏗️ [iframe] Base URL (wrapper): " + wrapperBase);

        mainHandler.post(() -> {
            WebView webView = new WebView(context);
            webViewsActivos.add(webView);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setUserAgentString(DESKTOP_USER_AGENT);
            settings.setBlockNetworkImage(true);
            settings.setLoadsImagesAutomatically(false);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            CookieManager.getInstance().setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            }

            Handler timeout = new Handler(Looper.getMainLooper());
            Runnable timeoutAction = () -> {
                if (!destroyed.get()) {
                    Log.e(TAG, "⏰ Timeout WebView-iframe");
                    destroyWebView(webView, destroyed, mainHandler);
                    latch.countDown();
                }
            };
            timeout.postDelayed(timeoutAction, WEBVIEW_TIMEOUT_MS);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                    handler.proceed();
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String url = request.getUrl().toString();
                    if (url.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|css|woff|woff2|ttf|eot).*") ||
                            url.contains("analytics") || url.contains("adsystem") ||
                            url.contains("popads") || url.contains("doubleclick") ||
                            url.contains("googlesyndication") || url.contains("adservice")) {
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                    }

                    if (esPlaylistHls(url, request.getRequestHeaders()) && isRealStream(url)) {
                        timeout.removeCallbacks(timeoutAction);
                        synchronized (result) {
                            if (result[0] == null && !destroyed.get()) {
                                CookieManager.getInstance().flush();
                                String cookies = CookieManager.getInstance().getCookie(url);
                                if (cookies == null) cookies = "";

                                // Capturar todos los headers de la petición real
                                Map<String, String> capturedHeaders = new HashMap<>();
                                if (request.getRequestHeaders() != null) {
                                    capturedHeaders.putAll(request.getRequestHeaders());
                                }

                                // Extraer Origin de los headers o de la URL del iframe
                                String origin = capturedHeaders.get("Origin");
                                if (origin == null || origin.isEmpty()) {
                                    // Calculamos el origin del iframe real
                                    origin = getBaseUrl(targetUrl);
                                }

                                result[0] = new StreamResult(url, cookies, wrapperBase, origin, capturedHeaders);
                                Log.d(TAG, "🎯 [iframe] Stream: " + url);
                                Log.d(TAG, "   🍪 Cookies: " + (cookies.isEmpty() ? "(ninguna)" : cookies));
                                Log.d(TAG, "   🌐 Referer: " + wrapperBase + " | Origin: " + origin);
                                mainHandler.post(() -> {
                                    destroyWebView(webView, destroyed, mainHandler);
                                    latch.countDown();
                                });
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request);
                }
            });

            String html = "<html><body style='margin:0;padding:0;background:black;'>" +
                    "<iframe src='" + targetUrl + "' width='100%' height='100%' " +
                    "frameborder='0' scrolling='no' allowfullscreen allow='autoplay'></iframe>" +
                    "</body></html>";
            webView.loadDataWithBaseURL(wrapperBase, html, "text/html", "UTF-8", null);
        });

        try { latch.await(WEBVIEW_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    // ------------------------------------------------------------
    // ESTRATEGIA INYECCIÓN JS (similar, captura origin)
    // ------------------------------------------------------------
    private static StreamResult resolveWithWebViewInjection(Context context, String targetUrl, String wrapperUrl) {
        final CountDownLatch latch = new CountDownLatch(1);
        final StreamResult[] result = {null};
        final String wrapperBase = getBaseUrl(wrapperUrl);
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        mainHandler.post(() -> {
            WebView webView = new WebView(context);
            webViewsActivos.add(webView);
            WebSettings settings = webView.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setMediaPlaybackRequiresUserGesture(false);
            settings.setUserAgentString(DESKTOP_USER_AGENT);
            settings.setBlockNetworkImage(true);
            settings.setLoadsImagesAutomatically(false);
            settings.setLoadWithOverviewMode(true);
            settings.setUseWideViewPort(true);
            settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            CookieManager.getInstance().setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
            }

            class JsBridge {
                @android.webkit.JavascriptInterface
                public void onStreamFound(String url) {
                    if (url != null && esPlaylistHls(url, getDefaultHeaders(wrapperUrl)) && isRealStream(url)) {
                        synchronized (result) {
                            if (result[0] == null && !destroyed.get()) {
                                CookieManager.getInstance().flush();
                                String cookies = CookieManager.getInstance().getCookie(url);
                                if (cookies == null) cookies = "";
                                // En el bridge JS no tenemos headers, pero podemos usar el origin del target
                                result[0] = new StreamResult(url, cookies, wrapperBase,
                                        getBaseUrl(targetUrl), getDefaultHeaders(wrapperUrl));
                                Log.d(TAG, "🎯 [injection] Stream por JS: " + url);
                                mainHandler.post(() -> {
                                    destroyWebView(webView, destroyed, mainHandler);
                                    latch.countDown();
                                });
                            }
                        }
                    }
                }
            }
            webView.addJavascriptInterface(new JsBridge(), "AndroidStreamBridge");

            Handler timeout = new Handler(Looper.getMainLooper());
            Runnable timeoutAction = () -> {
                if (!destroyed.get()) {
                    Log.e(TAG, "⏰ Timeout WebView-injection");
                    destroyWebView(webView, destroyed, mainHandler);
                    latch.countDown();
                }
            };
            timeout.postDelayed(timeoutAction, WEBVIEW_TIMEOUT_MS);

            AtomicBoolean scriptInjected = new AtomicBoolean(false);
            AtomicBoolean earlyInjected = new AtomicBoolean(false);

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    String reqUrl = request.getUrl().toString();
                    if (reqUrl.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|svg|css|woff|woff2|ttf|eot).*") ||
                            reqUrl.contains("analytics") || reqUrl.contains("adsystem") ||
                            reqUrl.contains("popads") || reqUrl.contains("doubleclick") ||
                            reqUrl.contains("googlesyndication") || reqUrl.contains("adservice")) {
                        return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
                    }

                    if (esPlaylistHls(reqUrl, request.getRequestHeaders()) && isRealStream(reqUrl)) {
                        timeout.removeCallbacks(timeoutAction);
                        synchronized (result) {
                            if (result[0] == null && !destroyed.get()) {
                                CookieManager.getInstance().flush();
                                String cookies = CookieManager.getInstance().getCookie(reqUrl);
                                if (cookies == null) cookies = "";

                                Map<String, String> capturedHeaders = new HashMap<>();
                                if (request.getRequestHeaders() != null) {
                                    capturedHeaders.putAll(request.getRequestHeaders());
                                }
                                String origin = capturedHeaders.get("Origin");
                                if (origin == null || origin.isEmpty()) {
                                    origin = getBaseUrl(targetUrl);
                                }

                                result[0] = new StreamResult(reqUrl, cookies, wrapperBase, origin, capturedHeaders);
                                Log.d(TAG, "🎯 [injection] Stream interceptado: " + reqUrl);
                                mainHandler.post(() -> {
                                    destroyWebView(webView, destroyed, mainHandler);
                                    latch.countDown();
                                });
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    super.onPageFinished(view, url);
                    if (result[0] != null || destroyed.get()) return;
                    if (!scriptInjected.getAndSet(true)) {
                        injectClickScript(view);
                        scheduleRetry(view, destroyed);
                    }
                }
            });

            webView.setWebChromeClient(new android.webkit.WebChromeClient() {
                @Override
                public void onProgressChanged(WebView view, int newProgress) {
                    super.onProgressChanged(view, newProgress);
                    if (newProgress > 50 && earlyInjected.compareAndSet(false, true) && result[0] == null && !destroyed.get()) {
                        scriptInjected.set(true);
                        injectClickScript(view);
                    }
                }
            });

            webView.loadUrl(targetUrl);
        });

        try { latch.await(WEBVIEW_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    // ------------------------------------------------------------
    // EXTRACCIÓN OKHTTP (sin cambios)
    // ------------------------------------------------------------
    private static String tryFastExtraction(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", DESKTOP_USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                String html = response.body().string();

                Matcher m = M3U8_URL.matcher(html);
                if (m.find()) return m.group(1);

                Matcher iframeMatcher = IFRAME_SRC.matcher(html);
                if (iframeMatcher.find()) {
                    String iframeUrl = iframeMatcher.group(1);
                    if (iframeUrl != null && !iframeUrl.isEmpty()) {
                        Request iframeReq = new Request.Builder()
                                .url(iframeUrl)
                                .addHeader("User-Agent", DESKTOP_USER_AGENT)
                                .addHeader("Referer", url)
                                .build();
                        try (Response iframeRes = httpClient.newCall(iframeReq).execute()) {
                            if (iframeRes.isSuccessful() && iframeRes.body() != null) {
                                String iframeHtml = iframeRes.body().string();
                                Matcher m2 = M3U8_URL.matcher(iframeHtml);
                                if (m2.find()) return m2.group(1);
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "OkHttp falló (esperado): " + e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------
    // UTILIDADES (sin cambios importantes)
    // ------------------------------------------------------------
    // ------------------------------------------------------------
    // RESOLUCIÓN EN CADENA (solo HTTP, SIN WebView)
    //
    // Los canales son una cadena de páginas: el listado apunta a un envoltorio,
    // ese abre un embed, el embed mete un iframe al reproductor y el reproductor
    // pide el playlist HLS. Este método recorre esa cadena con OkHttp, sin
    // mostrar nada por pantalla, así que el WebView solo hace falta cuando la
    // página arma el stream con JavaScript.
    // ------------------------------------------------------------
    private static final int MAX_PROFUNDIDAD_CADENA = 5;
    private static final long TIEMPO_MAX_CADENA_MS = 25000;

    // Holgados: el embed de deportes es una pagina de 640 KB y el iframe del
    // reproductor va al FINAL (byte 640.862), asi que hay que descargarla
    // entera. En el movil eso puede tardar bastante mas que en casa.
    private static final OkHttpClient clienteCadena = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /** URL entre comillas dentro del HTML (los reproductores las escriben así). */
    private static final Pattern URL_SUELTA = Pattern.compile(
            "[\"'](https?://[^\"'\\s]{10,300})[\"']");
    /** Pinta de stream: por aquí se sigue la cadena. */
    private static final Pattern CANDIDATO_CADENA = Pattern.compile(
            "(playlist|stream|live|hls|index|master|mono|chunklist|/vivo/|/channels/|\\.php\\?|/embed/|/embed2/|repro)",
            Pattern.CASE_INSENSITIVE);
    /** Ni anuncios ni librerías: por ahí nunca sale el stream. */
    private static final Pattern BASURA_CADENA = Pattern.compile(
            "(google|doubleclick|googlesyndication|adsystem|popads|adservice|facebook|twitter|jsdelivr|cdnjs|cloudflare|analytics|jquery|clappr|jwplayer|videojs|player\\.js)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Sigue la cadena de páginas hasta dar con el playlist HLS.
     * Devuelve null si en 25 s o en 5 saltos no aparece nada.
     */
    static StreamResult resolverEnCadena(String url) {
        return resolverEnCadena(url, 0, new HashSet<String>(), null,
                System.currentTimeMillis() + TIEMPO_MAX_CADENA_MS);
    }

    private static StreamResult resolverEnCadena(String url, int profundidad,
                                                Set<String> visto, String referer, long caduca) {
        if (url == null || !url.startsWith("http")) return null;
        if (profundidad > MAX_PROFUNDIDAD_CADENA) return null;
        if (System.currentTimeMillis() > caduca) return null;
        if (visto.contains(url)) return null;
        visto.add(url);
        if (RECURSO_ESTATICO.matcher(url).matches()) return null;

        try {
            Request.Builder b = new Request.Builder().url(url).get()
                    .addHeader("User-Agent", DESKTOP_USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "es-ES,es;q=0.9");
            if (referer != null && !referer.isEmpty()) b.addHeader("Referer", referer);
            Response r = clienteCadena.newCall(b.build()).execute();
            try {
                if (r == null || !r.isSuccessful() || r.body() == null) return null;
                String ct = r.header("Content-Type");
                String cuerpo = r.body().string();
                if (cuerpo == null || cuerpo.isEmpty()) return null;

                boolean esHls = (ct != null && ct.toLowerCase().contains("mpegurl"))
                        || cuerpo.trim().startsWith("#EXTM3U");
                if (esHls) {
                    Log.d(TAG, "[cadena] Playlist HLS: " + url);
                    String base = referer != null ? referer : getBaseUrl(url);
                    return new StreamResult(url, "", base, getBaseUrl(url), getDefaultHeaders(base));
                }

                // Un .m3u8 escrito en la página
                Matcher m = M3U8_URL.matcher(cuerpo.replace("\\/", "/"));
                if (m.find()) {
                    String m3u8 = m.group(1);
                    Log.d(TAG, "[cadena] m3u8 dentro de la página: " + m3u8);
                    return new StreamResult(m3u8, "", url, getBaseUrl(m3u8), getDefaultHeaders(url));
                }

                // Seguir tirando del hilo
                for (String candidato : candidatosCadena(url, cuerpo)) {
                    StreamResult sr = resolverEnCadena(candidato, profundidad + 1, visto, url, caduca);
                    if (sr != null) return sr;
                }
            } finally {
                try { r.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    /** Páginas por las que merece la pena seguir: iframes, ?r= y URLs con pinta de stream. */
    private static List<String> candidatosCadena(String url, String cuerpo) {
        List<String> brutos = new ArrayList<>();
        String t = cuerpo.replace("\\/", "/").replace("&amp;", "&");

        Matcher mi = IFRAME_SRC.matcher(t);
        while (mi.find()) brutos.add(mi.group(1));

        // Los envoltorios se ANIDAN (reprón.html?r=...?r=...): se prueban TODOS
        // los niveles, no solo el primero, y también van en Base64.
        String[] partes = url.split("\\?r=");
        for (int i = 1; i < partes.length; i++) {
            String v = partes[i].trim();
            if (v.startsWith("http")) {
                brutos.add(v);
            } else {
                String dec = decodificarBase64(v);
                if (dec != null && dec.startsWith("http")) brutos.add(dec);
            }
        }

        Matcher mu = URL_SUELTA.matcher(t);
        while (mu.find()) brutos.add(mu.group(1));

        List<String> buenos = new ArrayList<>();
        List<String> iframes = new ArrayList<>();
        Matcher mf = IFRAME_SRC.matcher(t);
        while (mf.find()) iframes.add(mf.group(1));

        for (String c : brutos) {
            String u = completarUrl(c, url);
            if (u == null || !u.startsWith("http")) continue;
            if (RECURSO_ESTATICO.matcher(u).matches()) continue;
            if (BASURA_CADENA.matcher(u).find()) continue;
            // Los iframes NO se filtran por el patron de candidatos: son la
            // senal mas clara de por donde sigue la cadena, y habia enlaces
            // perfectamente validos (lunchup.net/e/xxxxx) que se descartaban
            // solo por no contener "stream" ni "playlist".
            if (!iframes.contains(c) && !CANDIDATO_CADENA.matcher(u).find()) continue;
            if (!buenos.contains(u)) buenos.add(u);
            if (buenos.size() >= 10) break;
        }
        return buenos;
    }

    private static String decodificarBase64(String txt) {
        try {
            if (txt == null || txt.length() < 24) return null;
            if (!txt.matches("^[A-Za-z0-9+/=]+$")) return null;
            String padded = txt;
            while (padded.length() % 4 != 0) padded += "=";
            return new String(Base64.decode(padded, Base64.DEFAULT), "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    /** Convierte rutas relativas ("//host/x", "/x") en URLs completas. */
    private static String completarUrl(String u, String base) {
        if (u == null) return null;
        // En el HTML los enlaces vienen con &amp; hay que dejarlos como &
        String s = u.trim().replace("\\/", "/").replace("&amp;", "&");
        if (s.startsWith("http")) return s;
        if (s.startsWith("//")) return "https:" + s;
        if (s.startsWith("/") && base != null && base.startsWith("http")) {
            try {
                URL b = new URL(base);
                return b.getProtocol() + "://" + b.getHost() + s;
            } catch (Throwable t) {
                return null;
            }
        }
        return s;
    }

    /**
     * Atajo DIRECTO para los embeds de deportes tipo
     *      https://embed.saohgdasregions.fun/embed2/espn.php
     * (y los de regionales.saohgdassregions.com).
     *
     * La cadena es siempre la misma y se recorre con 3 peticiones, sin WebView
     * y sin adivinar:
     *   1. el embed           -> un iframe a .../stream.php?canal=X&sig=...
     *   2. ese stream.php     -> dentro del JavaScript va .../playlist.php?id=N_&sig=...
     *   3. ese playlist.php   -> el HLS de verdad (sin extension .m3u8)
     *
     * Si la URL no es de esta familia devuelve null y se sigue con el resto de
     * estrategias, asi que no se toca nada de lo que ya funciona.
     */
    static StreamResult resolverEmbedDeportes(String url) {
        if (url == null || !EMBED_DEPORTES.matcher(url).find()) return null;
        try {
            String htmlEmbed = descargarTexto(url, null);
            if (htmlEmbed == null) return null;

            String reproductor = null;
            Matcher mi = IFRAME_SRC.matcher(htmlEmbed);
            while (mi.find()) {
                String c = completarUrl(mi.group(1), url);
                if (c != null && c.contains("stream.php")) { reproductor = c; break; }
            }
            if (reproductor == null) {
                // Si el iframe viene raro o la pagina cambia, se busca la URL
                // del reproductor suelta en el HTML
                Matcher ms = STREAM_PHP.matcher(htmlEmbed.replace("\\/", "/").replace("&amp;", "&"));
                if (ms.find()) reproductor = ms.group(0);
            }
            if (reproductor == null) return null;

            String htmlReproductor = descargarTexto(reproductor, url);
            if (htmlReproductor == null) return null;

            String limpio = htmlReproductor.replace("\\/", "/").replace("\\u002F", "/");
            Matcher mp = PLAYLIST_PHP.matcher(limpio);
            if (!mp.find()) return null;
            String playlist = mp.group(0).replace("&amp;", "&");

            String cuerpo = descargarTexto(playlist, reproductor);
            if (cuerpo == null || !cuerpo.trim().startsWith("#EXTM3U")) return null;

            Log.d(TAG, "[deportes] Playlist HLS: " + playlist);
            return new StreamResult(playlist, "", reproductor,
                    getBaseUrl(playlist), getDefaultHeaders(reproductor));
        } catch (Throwable t) {
            return null;
        }
    }

    /** Descarga una pagina y devuelve su texto, o null si algo va mal. */
    private static String descargarTexto(String url, String referer) {
        try {
            Request.Builder b = new Request.Builder().url(url).get()
                    .addHeader("User-Agent", DESKTOP_USER_AGENT)
                    .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .addHeader("Accept-Language", "es-ES,es;q=0.9");
            if (referer != null && !referer.isEmpty()) b.addHeader("Referer", referer);
            Response r = clienteCadena.newCall(b.build()).execute();
            try {
                if (r == null || !r.isSuccessful() || r.body() == null) return null;
                return r.body().string();
            } finally {
                try { r.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void injectClickScript(WebView webView) {
        String script = "javascript:(function(){" +
                "function notify(url){if(url&&url.includes('.m3u8'))AndroidStreamBridge.onStreamFound(url);}" +
                "document.querySelectorAll('source[src*=\".m3u8\"], video[src*=\".m3u8\"]').forEach(function(el){notify(el.src);});" +
                "var origOpen=XMLHttpRequest.prototype.open;" +
                "XMLHttpRequest.prototype.open=function(method,url){notify(url);return origOpen.apply(this,arguments);};" +
                "var origFetch=window.fetch;" +
                "window.fetch=function(url,options){notify(typeof url==='string'?url:url.url);return origFetch.call(this,url,options);};" +
                "var observer=new MutationObserver(function(mutations){mutations.forEach(function(mutation){mutation.addedNodes.forEach(function(node){" +
                "if(node.src&&node.src.includes('.m3u8'))notify(node.src);" +
                "if(node.tagName==='SOURCE'&&node.src&&node.src.includes('.m3u8'))notify(node.src);" +
                "if(node.tagName==='VIDEO'&&node.src&&node.src.includes('.m3u8'))notify(node.src);" +
                "});});});" +
                "observer.observe(document,{childList:true,subtree:true});" +
                "function clickPlay(){" +
                "var selectors=['button','a','div[role=\"button\"]','.play','.btn-play','.vjs-big-play-button','.mejs-playpause-button','.jw-controls .jw-play'];" +
                "for(var s of selectors){var els=document.querySelectorAll(s);" +
                "for(var i=0;i<els.length;i++){var el=els[i];var txt=(el.textContent||'').toLowerCase();" +
                "if(txt.includes('play')||txt.includes('reproducir')||txt.includes('▶')||txt.includes('▷')){el.click();console.log('Clic en botón play');return;}}}" +
                "var video=document.querySelector('video');if(video){video.play();video.click();console.log('Clic en video');}" +
                "}" +
                "clickPlay();" +
                "var html=document.documentElement.outerHTML;" +
                "var matches=html.match(/(https?:\\/\\/[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)/gi);" +
                "if(matches){for(var i=0;i<matches.length;i++)notify(matches[i]);}" +
                "})();";
        webView.evaluateJavascript(script, null);
    }

    private static void scheduleRetry(WebView webView, AtomicBoolean destroyed) {
        Handler retryHandler = new Handler(Looper.getMainLooper());
        final int[] count = {0};
        Runnable retryRunnable = new Runnable() {
            @Override
            public void run() {
                if (count[0]++ > 4 || destroyed.get()) return;
                Log.d(TAG, "[injection] Reintento " + count[0]);
                injectClickScript(webView);
                retryHandler.postDelayed(this, 2500);
            }
        };
        retryHandler.postDelayed(retryRunnable, 2500);
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

    private static void destroyWebView(WebView webView, AtomicBoolean destroyed, Handler mainHandler) {
        if (!destroyed.compareAndSet(false, true)) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyInternal(webView);
        } else {
            mainHandler.post(() -> destroyInternal(webView));
        }
    }

    private static void destroyInternal(WebView webView) {
        webViewsActivos.remove(webView);
        try {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearCache(true);
            webView.clearHistory();
            webView.removeAllViews();
            webView.destroy();
        } catch (Exception ignored) {}
    }

    private static Map<String, String> getDefaultHeaders(String url) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", DESKTOP_USER_AGENT);
        String base = getBaseUrl(url);
        if (base != null && !base.isEmpty()) {
            headers.put("Referer", base);
            headers.put("Origin", base);
        }
        return headers;
    }

    private static String getBaseUrl(String url) {
        try { URL u = new URL(url); return u.getProtocol() + "://" + u.getHost() + "/"; }
        catch (Exception e) { return url; }
    }

    /**
     * Hay CDN de deportes que sirven el playlist HLS SIN la extension .m3u8
     * (por ejemplo: https://.../playlist.php?id=13_&sig=...), asi que el
     * filtro de siempre ("que la URL contenga .m3u8") se los saltaba y el
     * canal se quedaba sin stream.
     *
     * Si la URL tiene pinta de stream pero no lleva .m3u8, se pide el recurso y
     * se mira de que es: si el Content-Type es mpegurl o el cuerpo empieza por
     * #EXTM3U, es el stream aunque la URL no lo diga.
     */
    private static boolean esHlsPorContenido(String url, Map<String, String> headers) {
        if (url == null || !url.startsWith("http")) return false;
        if (RECURSO_ESTATICO.matcher(url).matches()) return false;
        if (!PARECE_STREAM.matcher(url).find()) return false;
        try {
            Request.Builder b = new Request.Builder().url(url).get()
                    .header("Range", "bytes=0-1023")
                    .addHeader("User-Agent", DESKTOP_USER_AGENT);
            if (headers != null) {
                String ref = headers.get("Referer");
                if (ref != null && !ref.isEmpty()) b.addHeader("Referer", ref);
                String ck = headers.get("Cookie");
                if (ck != null && !ck.isEmpty()) b.addHeader("Cookie", ck);
            }
            Response r = httpClient.newCall(b.build()).execute();
            try {
                if (r == null || !r.isSuccessful()) return false;
                String ct = r.header("Content-Type");
                if (ct != null) {
                    String ctl = ct.toLowerCase();
                    if (ctl.contains("mpegurl") || ctl.contains("x-mpeg")) return true;
                }
                if (r.body() == null) return false;
                long len = r.body().contentLength();
                if (len > 512 * 1024) return false;   // demasiado grande para ser un playlist
                String txt = r.body().string();
                return txt != null && txt.trim().startsWith("#EXTM3U");
            } finally {
                try { r.close(); } catch (Throwable ignored) { }
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Acepta tanto el .m3u8 de siempre como los playlists sin extension. */
    private static boolean esPlaylistHls(String url, Map<String, String> headers) {
        if (url == null) return false;
        if (url.contains(".m3u8")) return true;
        return esHlsPorContenido(url, headers);
    }

    private static boolean isRealStream(String url) {
        return !FAKE_M3U8.matcher(url).matches();
    }
}
