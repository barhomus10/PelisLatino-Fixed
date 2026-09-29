package dza.folbol.BLABONGO;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Resolver del servidor <b>vimeus</b> (el que el sitio marca como <b>Latino</b>).
 *
 * <p>Por qué existe esta clase: el servidor que se venía usando por defecto
 * (vsembed / vidsrc) entrega copias YIFY-YTS <b>en inglés</b>, sin pista en
 * español y sin subtítulos, así que las pelis sonaban en inglés aunque el
 * reproductor se pidiera con {@code ds_lang=es}.</p>
 *
 * <p>Cómo funciona vimeus (verificado en vivo 2026-09-28):</p>
 * <ol>
 *   <li>La página de vimeus trae un bloque
 *       {@code <script type="text/json" id="data">} con un array
 *       {@code embeds: [{lang:"Latino", quality:"Full HD", url:"https://vimeos.net/embed-XXXX.html"}]}</code>.</li>
 *   <li>Ese embed de <b>vimeos.net</b> es un player JW con el setup ofuscado
 *       (packer {@code eval(function(p,a,c,k,e,...))}. Se descomprime con
 *       {@link JsUnpacker} y de ahí se saca {@code sources:[{file:"...master.m3u8?t=..."}]}.</li>
 *   <li>El master funciona tal cual (incluido el template {@code ,n,h,.urlset}).</li>
 * </ol>
 *
 * Todo con OkHttp, sin WebView: resuelve en ~1-2 segundos.
 */
public final class VimeusResolver {

    private static final String TAG = "VimeusResolver";
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final Pattern BLOQUE_DATOS =
            Pattern.compile("<script type=\"text/json\" id=\"data\">(.*?)</script>", Pattern.DOTALL);
    private static final Pattern FILE_EN_COMILLAS =
            Pattern.compile("file\\s*:\\s*\"([^\"]+\\.m3u8[^\"]*)\"");
    private static final Pattern M3U8_SUELTO =
            Pattern.compile("(https?://[^\\s\"'<>\\\\]+\\.m3u8[^\\s\"'<>\\\\]*)");

    private static final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            // Algunos CDNs de vídeo siguen usando TLS "clásico": sin esto habría
            // fallos de handshake en según qué móvil.
            .connectionSpecs(Arrays.asList(ConnectionSpec.MODERN_TLS,
                    ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
            .build();

    private VimeusResolver() { }

    public static boolean isVimeus(String url) {
        if (url == null) return false;
        String l = url.toLowerCase(Locale.US);
        return l.contains("vimeus.com") || l.contains("vimeos.net");
    }

    /** Etiqueta legible del idioma que anuncia el propio servidor. */
    public static String idioma(String lang) {
        if (lang == null || lang.isEmpty()) return "Sin idioma";
        String l = lang.toLowerCase(Locale.US);
        if (l.contains("latino") || l.contains("castellano") || l.contains("español") || l.contains("espanol")) {
            return "Español (" + lang + ")";
        }
        return lang;
    }

    /**
     * Devuelve el master.m3u8 del embed en español (Latino) si lo hay.
     *
     * @param vimeusUrl la URL {@code https://vimeus.com/e/movie?tmdb=...&view_key=...}
     */
    public static PelisStreamResolver.StreamResult resolve(android.content.Context ctx, String vimeusUrl)
            throws Exception {
        long t0 = System.currentTimeMillis();

        // ---- 1) página de vimeus -> JSON con los embeds ----
        String html = get(vimeusUrl, "https://playpaste.link/player/");
        Matcher m = BLOQUE_DATOS.matcher(html);
        if (!m.find()) throw new Exception("vimeus: sin bloque de datos");
        JSONObject data = new JSONObject(m.group(1).trim());
        JSONArray embeds = data.optJSONArray("embeds");
        if (embeds == null || embeds.length() == 0) throw new Exception("vimeus: sin embeds");

        JSONObject elegido = null;
        for (int i = 0; i < embeds.length(); i++) {
            JSONObject o = embeds.optJSONObject(i);
            if (o == null) continue;
            if (elegido == null || puntuacion(o) < puntuacion(elegido)) elegido = o;
        }
        if (elegido == null) throw new Exception("vimeus: embeds ilegibles");

        String lang = elegido.optString("lang", "");
        String embedUrl = elegido.optString("url", "");
        if (embedUrl.isEmpty()) throw new Exception("vimeus: embed sin url");
        Log.d(TAG, "[vimeus] idioma=" + lang + " calidad=" + elegido.optString("quality", "")
                + " -> " + embedUrl);

        // ---- 2) embed de vimeos.net -> player ofuscado -> m3u8 ----
        String embedHtml = get(embedUrl, "https://vimeus.com/");
        String m3u8 = null;
        if (JsUnpacker.isPacked(embedHtml)) {
            String packed = JsUnpacker.extractPackedBlock(embedHtml);
            String js = JsUnpacker.unpack(packed);
            Matcher fm = FILE_EN_COMILLAS.matcher(js == null ? "" : js);
            if (fm.find()) m3u8 = fm.group(1);
        }
        if (m3u8 == null) {
            Matcher fm = M3U8_SUELTO.matcher(embedHtml);
            if (fm.find()) m3u8 = fm.group(1);
        }
        if (m3u8 == null) throw new Exception("vimeos: no se encontró el m3u8");

        // ---- 3) validar el master ----
        String master = get(m3u8, "https://vimeos.net/");
        if (!master.contains("#EXTM3U")) throw new Exception("vimeos: master inválido");
        int variantes = master.split("#EXT-X-STREAM-INF", -1).length - 1;
        Log.d(TAG, "[vimeus] master OK " + master.length() + "B variantes=" + variantes
                + " en " + (System.currentTimeMillis() - t0) + "ms");

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Referer", "https://vimeos.net/");
        headers.put("Origin", "https://vimeos.net");
        headers.put("Accept", "*/*");

        return new PelisStreamResolver.StreamResult(m3u8, "", "https://vimeos.net/",
                "https://vimeos.net", headers);
    }

    /** 0 = doblado al español, 1 = otro idioma, 2 = subtitulado. */
    private static int puntuacion(JSONObject o) {
        String l = o.optString("lang", "").toLowerCase(Locale.US);
        if (l.contains("latino") || l.contains("castellano")
                || l.contains("español") || l.contains("espanol")) return 0;
        if (l.contains("sub")) return 2;
        return 1;
    }

    private static String get(String url, String referer) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .addHeader("User-Agent", UA)
                .addHeader("Referer", referer)
                .addHeader("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "es-ES,es;q=0.9")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new Exception("HTTP " + resp.code() + " en " + url);
            }
            return resp.body().string();
        }
    }
}
