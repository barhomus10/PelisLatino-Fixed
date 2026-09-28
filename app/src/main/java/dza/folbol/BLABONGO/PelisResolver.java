package dza.folbol.BLABONGO;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolución de URLs de PelisLatinoHD.
 *
 * El sitio expone un envoltorio por título:
 * {@code https://playpaste.link/player/embed.php?id=<imdb|tmdb>[&se=&ep=]}.
 * Esa página es un envoltorio con una pantalla de "Play" y un selector de
 * servidores. El flujo es:
 *
 *   1. GET embed.php?id=...           -> el HTML trae un token de sesión
 *   2. GET api/details.php?id=<token> -> JSON con los reproductores reales
 *
 * Con eso se obtiene la URL directa del reproductor (embed69, vsembed, vimeus…)
 * y los metadatos (título, backdrop, tipo). El WebView solo se usa al final,
 * una única vez, sobre la URL del reproductor elegido.
 *
 * Se prioriza SIEMPRE el reproductor en español (vsembed / ds_lang=es).
 */
public class PelisResolver {

    private static final String TAG = "PelisResolver";

    private static final String PLAYPASTE = "https://playpaste.link/player/";

    private static final Pattern TOKEN = Pattern.compile(
            "details\\.php\\?id=([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    /** Orden de preferencia del propio sitio. */
    public static final String[] ORDEN = {"playpaste", "alt", "alt1", "alt2", "alt3"};

    public static class Servidores {
        public String titulo = "";
        public String backdrop = "";
        public String tipo = "movie";
        public String imdb = "";
        public int tmdb;
        /** nombre del servidor -> URL del reproductor, en orden de preferencia. */
        public final LinkedHashMap<String, String> opciones = new LinkedHashMap<>();
        /** URL original (embed.php) por si hay que volver al camino del sitio. */
        public String urlEmbed = "";

        public boolean hayServidores() {
            return !opciones.isEmpty();
        }

        /** Primera opción disponible, o la URL del sitio si no hubo suerte. */
        public String mejorUrl() {
            for (Map.Entry<String, String> e : opciones.entrySet()) return e.getValue();
            return urlEmbed;
        }

        public List<String> urlsOrdenadas() {
            List<String> urls = new ArrayList<>(opciones.values());
            if (urls.isEmpty() && !urlEmbed.isEmpty()) urls.add(urlEmbed);
            return urls;
        }
    }

    private PelisResolver() { }

    /** URL del envoltorio del sitio, con temporada/episodio cuando corresponde. */
    public static String urlEmbed(String id, String se, String ep) {
        StringBuilder sb = new StringBuilder(PLAYPASTE).append("embed.php?id=").append(id);
        if (se != null && !se.isEmpty() && ep != null && !ep.isEmpty()) {
            sb.append("&se=").append(se).append("&ep=").append(ep);
        }
        return sb.toString();
    }

    public static String urlEmbed(PelisItem item) {
        return urlEmbed(item.playId, null, null);
    }

    public static String urlEmbed(PelisItem item, PelisItem.Episodio ep) {
        return ep == null ? urlEmbed(item) : urlEmbed(item.playId, ep.temporada, ep.episodio);
    }

    public static String etiqueta(String nombre) {
        switch (nombre) {
            case "playpaste": return "Opción 1";
            case "alt":       return "Latino";
            case "alt1":      return "Multi";
            case "alt2":      return "Stream";
            case "alt3":      return "Inglés (CC)";
            default:          return nombre;
        }
    }

    /**
     * Resuelve los reproductores disponibles. Bloquea: llamar desde un hilo
     * de fondo.
     */
    public static Servidores servidores(String id, String se, String ep) throws IOException {
        if (id == null || id.trim().isEmpty()) throw new IOException("falta el identificador");

        Servidores salida = new Servidores();
        salida.urlEmbed = urlEmbed(id, se, ep);

        long t0 = System.currentTimeMillis();

        // Paso 1: el envoltorio, del que solo interesa el token de sesión.
        String html;
        try {
            html = PelisApi.get(salida.urlEmbed, PLAYPASTE);
        } catch (IOException e) {
            throw new IOException("no se pudo abrir el reproductor (" + e.getMessage() + ")");
        }

        Matcher m = TOKEN.matcher(html);
        if (!m.find()) throw new IOException("el reproductor no devolvió token");
        String token = m.group(1);

        // Paso 2: la API de detalles devuelve los reproductores reales.
        StringBuilder apiUrl = new StringBuilder(PLAYPASTE)
                .append("api/details.php?id=").append(token);
        if (se != null && !se.isEmpty() && ep != null && !ep.isEmpty()) {
            apiUrl.append("&se=").append(se).append("&ep=").append(ep);
        }

        String json;
        try {
            json = PelisApi.get(apiUrl.toString(), salida.urlEmbed);
        } catch (IOException e) {
            throw new IOException("el reproductor no respondió (" + e.getMessage() + ")");
        }

        JsonObject o;
        try {
            JsonElement raiz = JsonParser.parseString(json);
            if (raiz == null || !raiz.isJsonObject()) throw new IOException("respuesta inesperada");
            o = raiz.getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("respuesta ilegible del reproductor");
        }

        salida.titulo = texto(o, "title");
        salida.backdrop = texto(o, "backdrop");
        salida.imdb = texto(o, "imdb");
        salida.tipo = texto(o, "type");
        if ("series".equals(salida.tipo) && (se == null || se.isEmpty())) {
            salida.tipo = "movie";
        }
        try {
            if (o.has("tmdb") && !o.get("tmdb").isJsonNull()) salida.tmdb = o.get("tmdb").getAsInt();
        } catch (Exception ignored) { }

        JsonObject embeds = o.has("embeds") && o.get("embeds").isJsonObject()
                ? o.getAsJsonObject("embeds") : null;

        if (embeds != null) {
            // 1) Preferir SIEMPRE el reproductor en español (vsembed / ds_lang=es).
            for (Map.Entry<String, JsonElement> e : embeds.entrySet()) {
                if (e.getValue() == null || e.getValue().isJsonNull()) continue;
                String url = e.getValue().getAsString();
                if (url != null && !url.isEmpty()
                        && (url.contains("ds_lang=es") || url.contains("vsembed"))) {
                    salida.opciones.put(e.getKey(), url);
                }
            }
            // 2) Resto de servidores, respetando el orden de preferencia del sitio.
            for (String nombre : ORDEN) {
                if (embeds.has(nombre) && !embeds.get(nombre).isJsonNull()
                        && !salida.opciones.containsKey(nombre)) {
                    String url = embeds.get(nombre).getAsString();
                    if (url != null && !url.isEmpty()) salida.opciones.put(nombre, url);
                }
            }
            // 3) Servidores desconocidos que el sitio agregue en el futuro.
            for (Map.Entry<String, JsonElement> e : embeds.entrySet()) {
                if (salida.opciones.containsKey(e.getKey())) continue;
                if (e.getValue() == null || e.getValue().isJsonNull()) continue;
                String url = e.getValue().getAsString();
                if (url != null && !url.isEmpty()) salida.opciones.put(e.getKey(), url);
            }
        }

        if (!salida.hayServidores()) {
            Log.w(TAG, "Sin servidores para " + id + " (se=" + se + ", ep=" + ep + ")");
            throw new IOException("no hay servidores disponibles");
        }

        Log.d(TAG, "✅ " + salida.opciones.size() + " servidor(es) para " + id
                + " en " + (System.currentTimeMillis() - t0) + " ms -> " + salida.opciones.keySet());
        return salida;
    }

    public interface Callback {
        void onOk(Servidores servidores);
        void onError(String mensaje);
    }

    /** Variante asíncrona, que entrega siempre en el hilo principal. */
    public static void servidoresAsync(final String id, final String se, final String ep,
                                       final Callback cb) {
        new Thread(() -> {
            try {
                final Servidores s = servidores(id, se, ep);
                main(() -> cb.onOk(s));
            } catch (final Exception e) {
                final String msg = e.getMessage() != null ? e.getMessage() : "error al resolver servidores";
                main(() -> cb.onError(msg));
            }
        }, "pelis-resolver").start();
    }

    private static String texto(JsonObject o, String clave) {
        if (o == null || !o.has(clave) || o.get(clave).isJsonNull()) return "";
        return o.get(clave).getAsString();
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
