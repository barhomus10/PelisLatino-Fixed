package dza.folbol.BLABONGO;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class HttpHtmlParserStrategy implements StreamResolverStrategy {
    private static final String TAG = "HttpHtmlParser";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private static final Pattern[] M3U8_PATTERNS = {
            Pattern.compile("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("src\\s*[:=]\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("data-\\w+\\s*[:=]\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:hls|player|video)\\s*[:=]\\s*\\{[^}]*?(?:src|source|file)\\s*[:=]\\s*[\"']([^\"']+\\.m3u8[^\"']*)[\"']", Pattern.CASE_INSENSITIVE)
    };

    @Override
    public void resolve(String url, StreamResolverCallback callback) {
        new Thread(() -> {
            try {
                String html = fetchHtml(url);
                if (html == null) {
                    callback.onError("No se pudo obtener HTML");
                    return;
                }

                String iframeUrl = findIframe(html);
                if (iframeUrl != null) {
                    Log.d(TAG, "🔍 Iframe encontrado: " + iframeUrl);
                    String iframeHtml = fetchHtml(iframeUrl);
                    if (iframeHtml != null) {
                        String m3u8 = findM3u8(iframeHtml);
                        if (m3u8 != null) {
                            callback.onStreamFound(new StreamResult(m3u8, "", iframeUrl));
                            return;
                        }
                    }
                }

                String m3u8 = findM3u8(html);
                if (m3u8 != null) {
                    callback.onStreamFound(new StreamResult(m3u8, "", url));
                } else {
                    callback.onError("No se encontró .m3u8");
                }
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        }).start();
    }

    private String fetchHtml(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error fetch: " + e.getMessage());
        }
        return null;
    }

    private String findIframe(String html) {
        Matcher m = Pattern.compile("<iframe[^>]*src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String findM3u8(String html) {
        for (Pattern p : M3U8_PATTERNS) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                String url = m.group(1).replaceAll("[\\'\"<>]", "");
                if (!url.isEmpty()) return url;
            }
        }
        return null;
    }
}