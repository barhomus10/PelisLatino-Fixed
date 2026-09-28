package dza.folbol.BLABONGO;

import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TokenExtractionStrategy implements StreamResolverStrategy {
    private static final String TAG = "TokenExtraction";
    private final OkHttpClient client;
    private final long timeoutMs;
    private StreamResolverCallback callback;
    private String baseUrl;
    private Map<String, String> capturedHeaders;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public TokenExtractionStrategy() {
        this(15000);
    }

    public TokenExtractionStrategy(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        CookieJar cookieJar = new CookieJar() {
            private final HashMap<String, List<Cookie>> cookieStore = new HashMap<>();

            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
                cookieStore.put(url.host(), cookies);
            }

            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                List<Cookie> cookies = cookieStore.get(url.host());
                return cookies != null ? cookies : new ArrayList<>();
            }
        };

        this.client = new OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(true)
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public void resolve(String url, StreamResolverCallback callback) {
        this.callback = callback;
        this.baseUrl = url;
        this.capturedHeaders = new HashMap<>();

        Log.d(TAG, "🔍 Iniciando extracción dinámica para: " + url);

        Request step1 = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .build();

        client.newCall(step1).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Error en GET inicial: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("Paso 1 falló: código " + response.code());
                    return;
                }

                String html = response.body().string();
                Log.d(TAG, "✅ Paso 1 OK (Cookies guardadas)");

                String apiUrl = detectApiUrl(html, baseUrl);
                if (apiUrl == null) {
                    callback.onError("No se pudo detectar la URL de la API");
                    return;
                }

                Map<String, String> params = extractParams(html, baseUrl);

                capturedHeaders.put("User-Agent", USER_AGENT);
                capturedHeaders.put("Referer", baseUrl);
                capturedHeaders.put("Origin", getOrigin(baseUrl));
                capturedHeaders.put("Accept", "*/*");

                executeApiRequest(apiUrl, params);
            }
        });
    }

    private String detectApiUrl(String html, String baseUrl) {
        Pattern[] patterns = {
                Pattern.compile("(?:action|url|src|data-url|href)\\s*[:=]\\s*[\"']([^\"']+?token[^\"']*?|\\.php[^\"']*?)[\"']", Pattern.CASE_INSENSITIVE),
                Pattern.compile("fetch\\s*\\(\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE),
                Pattern.compile("ajax\\s*\\(\\s*\\{[^}]*url\\s*[:=]\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        };

        for (Pattern p : patterns) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                String apiPath = m.group(1).trim();
                HttpUrl base = HttpUrl.parse(baseUrl);
                if (base != null) {
                    if (apiPath.startsWith("/")) {
                        return base.scheme() + "://" + base.host() + apiPath;
                    } else if (apiPath.startsWith("http")) {
                        return apiPath;
                    } else {
                        return base.resolve(apiPath).toString();
                    }
                }
            }
        }

        HttpUrl base = HttpUrl.parse(baseUrl);
        if (base != null) {
            String[] commonPaths = {"/api/get_token.php", "/get_stream.php", "/api/stream", "/stream"};
            for (String path : commonPaths) {
                String fallback = base.scheme() + "://" + base.host() + path;
                Log.d(TAG, "⚠️ Probando fallback: " + fallback);
                return fallback;
            }
        }
        return null;
    }

    private Map<String, String> extractParams(String html, String baseUrl) {
        Map<String, String> params = new HashMap<>();

        Pattern csrfPattern = Pattern.compile("name=[\"']csrf_token[\"']\\s+value=[\"']([^\"']+)[\"']");
        Matcher m = csrfPattern.matcher(html);
        if (m.find()) {
            params.put("csrf_token", m.group(1));
            Log.d(TAG, "🔑 CSRF: " + m.group(1));
        }

        Pattern streamPattern = Pattern.compile("stream=([^&]+)");
        m = streamPattern.matcher(baseUrl);
        if (m.find()) {
            params.put("stream", m.group(1));
            Log.d(TAG, "📺 Stream ID: " + m.group(1));
        }

        Pattern[] extraPatterns = {
                Pattern.compile("player_id\\s*[:=]\\s*[\"']([^\"']+)[\"']"),
                Pattern.compile("id\\s*[:=]\\s*[\"']([^\"']+)[\"']")
        };
        for (Pattern p : extraPatterns) {
            m = p.matcher(html);
            if (m.find()) {
                params.put("id", m.group(1));
                Log.d(TAG, "🔑 ID extra: " + m.group(1));
            }
        }

        return params;
    }

    private void executeApiRequest(String apiUrl, Map<String, String> params) {
        FormBody.Builder formBuilder = new FormBody.Builder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            formBuilder.add(entry.getKey(), entry.getValue());
        }

        Request request = new Request.Builder()
                .url(apiUrl)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Referer", baseUrl)
                .addHeader("Origin", getOrigin(baseUrl))
                .addHeader("X-Requested-With", "XMLHttpRequest")
                .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                .post(formBuilder.build())
                .build();

        capturedHeaders.put("Referer", baseUrl);
        capturedHeaders.put("Origin", getOrigin(baseUrl));
        capturedHeaders.put("X-Requested-With", "XMLHttpRequest");

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Error en POST a API: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("API respondió con código " + response.code());
                    return;
                }

                String body = response.body().string();
                Log.d(TAG, "📦 Respuesta API: " + body);

                String m3u8 = extractM3u8FromResponse(body);
                if (m3u8 != null) {
                    Log.d(TAG, "🎯 ¡Stream encontrado!: " + m3u8);
                    callback.onStreamFound(new StreamResult(m3u8, "", baseUrl, capturedHeaders));
                } else {
                    callback.onError("No se encontró .m3u8 en la respuesta de la API");
                }
            }
        });
    }

    private String extractM3u8FromResponse(String response) {
        try {
            JSONObject json = new JSONObject(response);
            String[] keys = {"url", "link", "source", "file", "m3u8", "playlist", "stream", "video"};
            for (String key : keys) {
                if (json.has(key)) {
                    String value = json.getString(key);
                    if (value != null && value.contains(".m3u8")) {
                        return value;
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "No es JSON, buscando con regex");
        }

        Pattern p = Pattern.compile("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)");
        Matcher m = p.matcher(response);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private String getOrigin(String url) {
        HttpUrl httpUrl = HttpUrl.parse(url);
        if (httpUrl != null) {
            return httpUrl.scheme() + "://" + httpUrl.host();
        }
        return url;
    }
}