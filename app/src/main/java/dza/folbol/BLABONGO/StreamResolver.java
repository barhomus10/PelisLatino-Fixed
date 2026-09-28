package dza.folbol.BLABONGO;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.net.http.SslError;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
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
    private static final long WEBVIEW_TIMEOUT_MS = 10_000; // reducimos timeout para no retener recursos
    public static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern FAKE_M3U8 = Pattern.compile(
            ".*(check|ping|validate|geo|ad|ads|ima|vast|preroll|tracking|beacon|monitor|heartbeat|blank|empty).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern M3U8_URL = Pattern.compile(
            "(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IFRAME_SRC = Pattern.compile(
            "<iframe[^>]*src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
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
            this.headers = headers != null ? headers : new HashMap<>();
        }

        // Constructor de compatibilidad para los casos donde no tenemos origin
        public StreamResult(String m3u8Url, String cookies, String referer, Map<String, String> headers) {
            this(m3u8Url, cookies, referer, getBaseUrl(referer), headers);
        }
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3);

    public static StreamResult resolveSynchronously(Context context, String initialUrl) {
        if (initialUrl == null || initialUrl.isEmpty()) return null;
        long startTime = System.currentTimeMillis();

        String extracted = extractRealUrl(initialUrl);
        final String realUrl = (extracted != null) ? extracted : initialUrl;

        Log.d(TAG, "URL original: " + initialUrl);
        Log.d(TAG, "URL extraida del embed: " + realUrl);

        if (realUrl.contains(".m3u8") && isRealStream(realUrl)) {
            Log.d(TAG, "Ya es un stream directo .m3u8: " + realUrl);
            return new StreamResult(realUrl, "", getBaseUrl(initialUrl), getDefaultHeaders(initialUrl));
        }

        Log.d(TAG, "Iniciando carrera de 3 estrategias...");
        ExecutorService raceExecutor = Executors.newFixedThreadPool(3);
        try {
            Callable<StreamResult> okHttpTask = () -> {
                String fast = tryFastExtraction(realUrl);
                if (fast != null) {
                    Log.d(TAG, "OkHttp encontro stream en " + (System.currentTimeMillis() - startTime) + "ms");
                    return new StreamResult(fast, "", getBaseUrl(initialUrl), getDefaultHeaders(initialUrl));
                }
                throw new Exception("OkHttp no encontro stream");
            };

            Callable<StreamResult> iframeTask = () -> {
                StreamResult sr = resolveWithWebViewIframe(context, realUrl, initialUrl);
                if (sr != null) {
                    Log.d(TAG, "WebView-iframe encontro stream en " + (System.currentTimeMillis() - startTime) + "ms");
                    return sr;
                }
                throw new Exception("WebView-iframe fallo");
            };

            Callable<StreamResult> injectionTask = () -> {
                StreamResult sr = resolveWithWebViewInjection(context, realUrl, initialUrl);
                if (sr != null) {
                    Log.d(TAG, "WebView-injection encontro stream en " + (System.currentTimeMillis() - startTime) + "ms");
                    return sr;
                }
                throw new Exception("WebView-injection fallo");
            };

            return raceExecutor.invokeAny(Arrays.asList(okHttpTask, iframeTask, injectionTask));
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

    private static StreamResult resolveWithWebViewIframe(Context context, String targetUrl, String wrapperUrl) {
        final CountDownLatch latch = new CountDownLatch(1);
        final StreamResult[] result = {null};
        final String wrapperBase = getBaseUrl(wrapperUrl);
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        Log.d(TAG, "[iframe] Base URL (wrapper): " + wrapperBase);

        mainHandler.post(() -> {
            WebView webView = new WebView(context);
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
                    Log.e(TAG, "Timeout WebView-iframe");
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

                    if (url.contains(".m3u8") && isRealStream(url)) {
                        timeout.removeCallbacks(timeoutAction);
                        synchronized (result) {
                            if (result[0] == null && !destroyed.get()) {
                                CookieManager.getInstance().flush();
                                String cookies = CookieManager.getInstance().getCookie(url);
                                if (cookies == null) cookies = "";

                                Map<String, String> capturedHeaders = new HashMap<>();
                                if (request.getRequestHeaders() != null) {
                                    capturedHeaders.putAll(request.getRequestHeaders());
                                }
                                String origin = capturedHeaders.get("Origin");
                                if (origin == null || origin.isEmpty()) {
                                    origin = getBaseUrl(targetUrl);
                                }

                                result[0] = new StreamResult(url, cookies, wrapperBase, origin, capturedHeaders);
                                Log.d(TAG, "[iframe] Stream interceptado: " + url);
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

            webView.loadUrl(targetUrl);
        });

        try { latch.await(WEBVIEW_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    private static StreamResult resolveWithWebViewInjection(Context context, String targetUrl, String wrapperUrl) {
        final CountDownLatch latch = new CountDownLatch(1);
        final StreamResult[] result = {null};
        final String wrapperBase = getBaseUrl(wrapperUrl);
        final AtomicBoolean destroyed = new AtomicBoolean(false);
        final Handler mainHandler = new Handler(Looper.getMainLooper());

        Log.d(TAG, "[injection] Base URL (wrapper): " + wrapperBase);

        mainHandler.post(() -> {
            WebView webView = new WebView(context);
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
                    Log.e(TAG, "Timeout WebView-injection");
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

                    if (reqUrl.contains(".m3u8") && isRealStream(reqUrl)) {
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
                                Log.d(TAG, "[injection] Stream interceptado: " + reqUrl);
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

            webView.setWebChromeClient(new WebChromeClient() {
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
            Log.d(TAG, "OkHttp fallo (esperado): " + e.getMessage());
        }
        return null;
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
                "var selectors=['button','a','div[role=\"button\"]','.play','.btn-play','.vjs-big-play-button','.mejs-playpause-button','.jw-controls .jw-play'];";
        webView.evaluateJavascript(script, null);
    }

    static void scheduleRetry(WebView webView, AtomicBoolean destroyed) {
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

    private static boolean isRealStream(String url) {
        return !FAKE_M3U8.matcher(url).matches();
    }
}
