package dza.folbol.BLABONGO;

import java.util.HashMap;
import java.util.Map;

public class StreamResult {
    public final String m3u8Url;
    public final String cookies;
    public final String referer;
    public final Map<String, String> headers;

    // Constructor con headers
    public StreamResult(String m3u8Url, String cookies, String referer, Map<String, String> headers) {
        this.m3u8Url = m3u8Url;
        this.cookies = cookies != null ? cookies : "";
        this.referer = referer != null ? referer : "";
        this.headers = headers != null ? headers : new HashMap<>();
    }

    // Constructor sin headers (para compatibilidad)
    public StreamResult(String m3u8Url, String cookies, String referer) {
        this(m3u8Url, cookies, referer, new HashMap<>());
    }

    // Constructor solo URL
    public StreamResult(String m3u8Url) {
        this(m3u8Url, "", "", new HashMap<>());
    }
}