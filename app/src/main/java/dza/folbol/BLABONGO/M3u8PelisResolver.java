package dza.folbol.BLABONGO;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolución especializada de URLs m3u8 para películas.
 *
 * Flujo:
 *   1. GET embed.php?id=... -> extrae token de sesión del HTML
 *   2. GET api/details.php?id=<token> -> JSON con el stream o los reproductores
 *
 * Nota: la API dejó de publicar un m3u8 directo. Cuando no hay m3u8, el JSON
 * trae un objeto "embeds" con los iframes de los reproductores; en ese caso
 * se devuelve la mejor opción en embedUrl para que el reproductor la abra.
 * Se prioriza SIEMPRE el reproductor en español (vsembed / ds_lang=es).
 */
public class M3u8PelisResolver {

    private static final String TAG = "M3u8PelisResolver";

    private static final String PLAYPASTE = "https://playpaste.link/player/";

    private static final Pattern TOKEN = Pattern.compile(
            "details\\.php\\?id=([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private M3u8PelisResolver() { }

    public static class M3u8Result {
        public String m3u8Url = "";
        /** URL del iframe del reproductor cuando la API no publica un m3u8 directo. */
        public String embedUrl = "";
        public String titulo = "";
        public String tipo = "movie";
        public String imdb = "";
        public int tmdb = 0;
        public String cookies = "";
        public String referer = PLAYPASTE;
        public String origin = "";
        public Map<String, String> headers = null;

        public boolean esValido() {
            return m3u8Url != null && !m3u8Url.isEmpty() && m3u8Url.endsWith(".m3u8");
        }
    }

    public static M3u8Result resolveM3u8(String id, String se, String ep) throws IOException {
        if (id == null || id.trim().isEmpty()) {
            throw new IOException("falta el identificador de la película");
        }

        M3u8Result resultado = new M3u8Result();

        long t0 = System.currentTimeMillis();
        Log.d(TAG, " Resolviendo m3u8 para: " + id + " se=" + se + " ep=" + ep);

        // Paso 1: obtener el token de sesión desde embed.php
        StringBuilder urlEmbedBuilder = new StringBuilder(PLAYPASTE).append("embed.php?id=").append(id);
        if (se != null && !se.isEmpty() && ep != null && !ep.isEmpty()) {
            urlEmbedBuilder.append("&se=").append(se).append("&ep=").append(ep);
        }
        String urlEmbed = urlEmbedBuilder.toString();
        String html = PelisApi.get(urlEmbed, PLAYPASTE);

        Matcher m = TOKEN.matcher(html);
        if (!m.find()) {
            throw new IOException("el reproductor no devolvió token de sesión");
        }
        String token = m.group(1);
        Log.d(TAG, "✅ Token obtenido en " + (System.currentTimeMillis() - t0) + " ms");

        // Paso 2: obtener el JSON de detalles con el stream m3u8
        StringBuilder urlApiBuilder = new StringBuilder(PLAYPASTE).append("api/details.php?id=").append(token);
        if (se != null && !se.isEmpty() && ep != null && !ep.isEmpty()) {
            urlApiBuilder.append("&se=").append(se).append("&ep=").append(ep);
        }
        String urlApi = urlApiBuilder.toString();
        String json = PelisApi.get(urlApi, urlEmbed);

        // Extraer el m3u8 del JSON
        String m3u8Url = extraerM3u8DelJson(json);

        // La API dejó de publicar m3u8 directo: ahora entrega los iframes en "embeds".
        // En ese caso devolvemos el mejor embed para que el reproductor lo resuelva.
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            resultado.embedUrl = extraerEmbedDelJson(json);
            if (resultado.embedUrl.isEmpty()) {
                throw new IOException("el reproductor no devolvió ni m3u8 ni servidores");
            }
            resultado.titulo = extraerTextoDelJson(json, "title");
            resultado.tipo = extraerTextoDelJson(json, "type");
            resultado.imdb = extraerTextoDelJson(json, "imdb");
            resultado.tmdb = extraerIntDelJson(json, "tmdb");

            Log.d(TAG, "ℹ️  Sin m3u8 directo; usando embed: " + resultado.embedUrl);
            Log.d(TAG, "⏱️  Tiempo total: " + (System.currentTimeMillis() - t0) + " ms");
            return resultado;
        }

        // Extraer metadata del JSON
        resultado.m3u8Url = m3u8Url;
        resultado.titulo = extraerTextoDelJson(json, "title");
        resultado.tipo = extraerTextoDelJson(json, "type");
        resultado.imdb = extraerTextoDelJson(json, "imdb");
        resultado.tmdb = extraerIntDelJson(json, "tmdb");

        Log.d(TAG, "✅ Stream m3u8 obtenido: " + resultado.m3u8Url);
        Log.d(TAG, "⏱️  Tiempo total: " + (System.currentTimeMillis() - t0) + " ms");

        return resultado;
    }

    private static String extraerM3u8DelJson(String json) {
        // 1. Buscar cualquier clave seguida de .m3u8 en el valor
        Pattern pattern1 = Pattern.compile("\"([a-zA-Z_][a-zA-Z0-9_]*?)\"\\s*:\\s*\"((?:http:|https:)?/[^\"]*\\.m3u8)(?:\"?\\s*,?)?",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern1.matcher(json);
        while (matcher.find()) {
            String key = matcher.group(1).toLowerCase();
            String url = matcher.group(2);
            // Filtrar claves que no sean m3u8 reales
            if (key.matches("^(m3u8|stream|url|video|source|link|embed)$") ||
                    url.contains(".m3u8")) {
                if (url.contains("pelislatinohd") || url.contains("playpaste") || url.startsWith("http")) {
                    return url;
                }
            }
        }

        // 2. Patrón específico para "url" clave
        pattern1 = Pattern.compile("\"url\"\\s*:\\s*\"((?:http:|https:)?/[^\"]*\\.m3u8)\"", Pattern.CASE_INSENSITIVE);
        matcher = pattern1.matcher(json);
        if (matcher.find()) {
            String url = matcher.group(1);
            if (url.contains("pelislatinohd") || url.contains("playpaste") || url.startsWith("http")) {
                return url;
            }
        }

        // 3. Buscar m3u8 directamente en cualquier par clave-valor
        pattern1 = Pattern.compile("\\{([^}]*?\\.m3u8[^}]*?)\\}",
                Pattern.CASE_INSENSITIVE);
        matcher = pattern1.matcher(json);
        if (matcher.find()) {
            String segment = matcher.group(1);
            Pattern p = Pattern.compile("(https?://[^\\s\"'<>]+\\.m3u8[^\\s\"'<>]*)",
                    Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(segment);
            if (m.find()) {
                String url = m.group(1);
                if (url.contains("pelislatinohd") || url.contains("playpaste") || url.startsWith("http")) {
                    return url;
                }
            }
        }

        return null;
    }

    /**
     * Devuelve la URL del mejor embed disponible dentro del objeto "embeds"
     * del JSON.
     *
     * Prioridad:
     *   1) Reproductor en ESPAÑOL: cualquier URL con "ds_lang=es" o que
     *      apunte a vsembed (es el player real del sitio en español).
     *   2) Si no hay español, respetar el orden de preferencia del sitio.
     *   3) Cualquier otro servidor que el sitio agregue en el futuro.
     *
     * Se parsea a mano para no depender de regex con comillas.
     */
    private static String extraerEmbedDelJson(String json) {
        int i = json.indexOf("\"embeds\"");
        if (i < 0) return "";

        int llave = json.indexOf('{', i);
        if (llave < 0) return "";

        int fin = json.indexOf('}', llave);
        if (fin < 0) fin = json.length();

        String cuerpo = json.substring(llave + 1, fin);

        // Nombre -> URL, para poder reordenar según el orden de preferencia.
        Map<String, String> embeds = new HashMap<>();
        int pos = 0;
        while (pos < cuerpo.length()) {
            int comillaClave = cuerpo.indexOf('"', pos);
            if (comillaClave < 0) break;
            int finClave = cuerpo.indexOf('"', comillaClave + 1);
            if (finClave < 0) break;
            String nombre = cuerpo.substring(comillaClave + 1, finClave);

            int dosPuntos = cuerpo.indexOf(':', finClave);
            if (dosPuntos < 0) break;
            int comillaValor = cuerpo.indexOf('"', dosPuntos);
            if (comillaValor < 0) break;
            int finValor = cuerpo.indexOf('"', comillaValor + 1);
            if (finValor < 0) break;
            String url = cuerpo.substring(comillaValor + 1, finValor);

            if (!nombre.isEmpty() && !url.isEmpty()) embeds.put(nombre, url);
            pos = finValor + 1;
        }

        // 1) Preferir SIEMPRE el reproductor en español.
        //    "ds_lang=es" es la marca explícita de idioma español (vsembed.ru).
        for (String url : embeds.values()) {
            if (url != null && url.contains("ds_lang=es")) return url;
        }
        // Fallback por dominio: vsembed es el player español real del sitio.
        for (String url : embeds.values()) {
            if (url != null && url.contains("vsembed")) return url;
        }

        // 2) Si no hay opción en español, respetar el orden de preferencia del sitio.
        String[] orden = {"playpaste", "alt", "alt1", "alt2", "alt3"};
        for (String nombre : orden) {
            String url = embeds.get(nombre);
            if (url != null && !url.isEmpty()) return url;
        }
        // 3) Servidores nuevos que el sitio agregue en el futuro.
        for (String url : embeds.values()) {
            if (url != null && !url.isEmpty()) return url;
        }
        return "";
    }

    private static String extraerTextoDelJson(String json, String clave) {
        Pattern pattern = Pattern.compile("\"" + clave + "\"\\s*:\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "";
    }

    private static int extraerIntDelJson(String json, String clave) {
        Pattern pattern = Pattern.compile("\"" + clave + "\"\\s*:\\s*(\\d+)");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception e) {
                return 0;
            }
        }
        return 0;
    }

    public interface Callback {
        void onOk(M3u8Result resultado);
        void onError(String mensaje);
    }

    /** Variante asíncrona, que siempre entrega en el hilo principal. */
    public static void resolveM3u8Async(final String id, final String se, final String ep,
                                        final Callback cb) {
        new Thread(() -> {
            try {
                final M3u8Result r = resolveM3u8(id, se, ep);
                main(() -> cb.onOk(r));
            } catch (final Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "error al resolver m3u8";
                main(() -> cb.onError(msg));
            }
        }, "m3u8-resolver").start();
    }

    private static void main(Runnable r) {
        Looper looper = Looper.getMainLooper();
        if (looper == null || Looper.myLooper() == looper) {
            r.run();
        } else {
            new Handler(looper).post(r);
        }
    }
}
