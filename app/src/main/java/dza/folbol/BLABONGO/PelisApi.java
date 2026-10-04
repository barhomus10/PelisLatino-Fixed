package dza.folbol.BLABONGO;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Cliente HTTP + parser de PelisLatinoHD.
 *
 * IMPORTANTE: pelislatinohd.pages.dev NO expone una API WordPress
 * (no existe /wp-json/pelis/v1/...). Es un SPA estatico que carga TODO su
 * catalogo desde un unico fichero:
 *
 *     https://pelislatinohd.pages.dev/data/catalog.json
 *
 * con la forma:
 *   {
 *     "meta":    { "base": "...", "total": {...} },
 *     "movies":  [ {id, type, title, slug, poster, backdrop, year, rating,
 *                   synopsis, embeddedId, ...}, ... ],
 *     "series":  [ {..., seasons, episodes:[{season, episode, title, date}]} ]
 *   }
 *
 * Por eso antes fallaba con HTTP 404: se pedia /wp-json/pelis/v1/search.
 *
 * Este cliente descarga catalog.json una vez, lo cachea en memoria y sirve
 * catalogo (con paginacion y busqueda), detalle y episodios a partir de el.
 */
public final class PelisApi {

    private static final String TAG = "PelisApi";

    /** Dominios: el primero es principal; el resto son espejos. */
    public static final String[] HOSTS = {
            "https://pelislatinohd.pages.dev",
            "https://pelislatinohd.com",
            "https://ev.pelislatinohd.com",
            "https://www.pelislatinohd.com"
    };

    public static final String SITIO = HOSTS[0];

    /** Fichero estatico que contiene todo el catalogo del SPA. */
    public static final String RUTA_CATALOGO = "/data/catalog.json";

    /** Prefijo de imagenes TMDB (por si llega una ruta relativa). */
    public static final String IMG = "https://image.tmdb.org/t/p/w500";

    /** Número de tarjetas por página, como en el grid de la web de referencia. */
    public static final int POR_PAGINA = 28;

    public static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    /**
     * Handler del hilo principal. Puede ser null si no hay Looper principal
     * (p. ej. en un test JVM sin Robolectric); en ese caso las respuestas se
     * entregan en el mismo hilo para no dejar colgado al llamante.
     */
    private static final Handler MAIN;

    static {
        Handler h = null;
        try {
            Looper looper = Looper.getMainLooper();
            if (looper != null) h = new Handler(looper);
        } catch (Throwable ignored) { }
        MAIN = h;
    }

    /** Cache en memoria del catalogo completo (se descarga una sola vez). */
    private static volatile JsonObject CATALOGO = null;

    private PelisApi() { }

    /* --------------------------------------------------------------- *
     * Callback + entrega en el hilo principal                          *
     * --------------------------------------------------------------- */

    public interface Callback<T> {
        void onOk(T data);
        void onError(String mensaje);
    }

    /** Una página de resultados con el total exacto para la cabecera de búsqueda. */
    public static final class PaginaCatalogo {
        public final List<PelisItem> items;
        public final int total;

        private PaginaCatalogo(List<PelisItem> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    private static <T> void enMain(Callback<T> cb, T data) {
        if (MAIN != null) MAIN.post(() -> cb.onOk(data));
        else cb.onOk(data);
    }

    private static <T> void error(Callback<T> cb, String mensaje) {
        if (MAIN != null) MAIN.post(() -> cb.onError(mensaje));
        else cb.onError(mensaje);
    }

    /* --------------------------------------------------------------- *
     * API publica                                                     *
     * --------------------------------------------------------------- */

    /** API compatible con llamadas que solo necesitan la lista de la página. */
    public static void catalogo(String tipo, int pagina, String busqueda,
                                Callback<List<PelisItem>> cb) {
        new Thread(() -> {
            try {
                enMain(cb, lista(tipo, pagina, busqueda).items);
            } catch (Exception e) {
                Log.e(TAG, "catalogo", e);
                error(cb, describir(e));
            }
        }, "PelisApi-catalogo").start();
    }

    /** Página más el total de coincidencias para mostrar resultados exactos. */
    public static void catalogoPagina(String tipo, int pagina, String busqueda,
                                      Callback<PaginaCatalogo> cb) {
        new Thread(() -> {
            try {
                enMain(cb, lista(tipo, pagina, busqueda));
            } catch (Exception e) {
                Log.e(TAG, "catalogo", e);
                error(cb, describir(e));
            }
        }, "PelisApi-catalogo-pagina").start();
    }

    public static void detalle(PelisItem base, Callback<PelisItem> cb) {
        new Thread(() -> {
            try {
                enMain(cb, detalle(base));
            } catch (Exception e) {
                Log.e(TAG, "detalle", e);
                error(cb, describir(e));
            }
        }, "PelisApi-detalle").start();
    }

    public static void episodios(PelisItem serie, Callback<List<PelisItem.Episodio>> cb) {
        new Thread(() -> {
            try {
                enMain(cb, episodios(serie));
            } catch (Exception e) {
                Log.e(TAG, "episodios", e);
                error(cb, describir(e));
            }
        }, "PelisApi-episodios").start();
    }

    /* --------------------------------------------------------------- *
     * HTTP                                                            *
     * --------------------------------------------------------------- */

    /** GET con cabeceras de navegador. Devuelve el cuerpo como texto. */
    public static String get(String url, String referer) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/html;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-ES,es;q=0.9,en;q=0.8");
        if (!TextUtils.isEmpty(referer)) {
            rb.header("Referer", referer);
            try {
                String host = new URL(referer).getProtocol() + "://" +
                        new URL(referer).getHost();
                rb.header("Origin", host);
            } catch (Exception ignored) { }
        }
        try (Response resp = CLIENT.newCall(rb.build()).execute()) {
            ResponseBody body = resp.body();
            String txt = body != null ? body.string() : "";
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + " en " + url);
            }
            return txt;
        }
    }

    /** GET contra el dominio principal. */
    public static String getDeSitio(String ruta) throws IOException {
        return get(SITIO + ruta, SITIO + "/");
    }

    /* --------------------------------------------------------------- *
     * Logica de negocio (sobre data/catalog.json)                     *
     * --------------------------------------------------------------- */

    /** Descarga (o reutiliza) el catalogo completo. */
    private static JsonObject catalogoJson() throws IOException {
        JsonObject cache = CATALOGO;
        if (cache != null) return cache;
        synchronized (PelisApi.class) {
            if (CATALOGO != null) return CATALOGO;
            String json = getDeSitio(RUTA_CATALOGO);
            JsonElement el = JsonParser.parseString(json);
            if (!el.isJsonObject()) {
                throw new IOException("catalog.json no es un objeto JSON");
            }
            CATALOGO = el.getAsJsonObject();
            return CATALOGO;
        }
    }

    private static PaginaCatalogo lista(String tipo, int pagina, String busqueda)
            throws IOException {
        JsonObject root = catalogoJson();
        String q = normalizarBusqueda(busqueda);
        boolean todas = PelisItem.TIPO_TODOS.equalsIgnoreCase(tipo);
        boolean serie = PelisItem.TIPO_SERIE.equalsIgnoreCase(tipo);

        List<PelisItem> filtrado = new ArrayList<>();
        if (todas) {
            agregarCoincidencias(array(root, "movies"), PelisItem.TIPO_PELICULA, root, q, filtrado);
            agregarCoincidencias(array(root, "series"), PelisItem.TIPO_SERIE, root, q, filtrado);
        } else {
            JsonArray origen = array(root, serie ? "series" : "movies");
            agregarCoincidencias(origen, tipo, root, q, filtrado);
        }

        // Mantiene el orden del catálogo fuente, igual que la búsqueda de la web.
        int total = filtrado.size();
        int page = Math.max(1, pagina);
        int desde = (page - 1) * POR_PAGINA;
        if (desde >= total) return new PaginaCatalogo(new ArrayList<>(), total);
        int hasta = Math.min(desde + POR_PAGINA, total);
        return new PaginaCatalogo(new ArrayList<>(filtrado.subList(desde, hasta)), total);
    }

    private static void agregarCoincidencias(JsonArray origen, String tipoPorDefecto,
                                              JsonObject root, String consulta,
                                              List<PelisItem> destino) {
        for (JsonElement el : origen) {
            if (!el.isJsonObject()) continue;
            JsonObject objeto = el.getAsJsonObject();
            if (!TextUtils.isEmpty(consulta) && !coincideBusqueda(objeto, consulta)) continue;
            PelisItem it = parseItem(objeto, tipoPorDefecto, root, false);
            if (it != null) destino.add(it);
        }
    }

    private static boolean coincideBusqueda(JsonObject item, String consulta) {
        String titulo = primero(item, "title", "titulo", "name");
        String original = primero(item, "originalTitle", "original_title", "originalName", "original_name");
        String slug = primero(item, "slug", "permalink", "url");
        return normalizarBusqueda(titulo).contains(consulta)
                || normalizarBusqueda(original).contains(consulta)
                || normalizarBusqueda(slug).contains(consulta);
    }

    /** Comparación sin mayúsculas ni tildes: "accion" también encuentra "Acción". */
    private static String normalizarBusqueda(String valor) {
        if (valor == null || valor.trim().isEmpty()) return "";
        String normalizado = Normalizer.normalize(valor.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return normalizado.toLowerCase(Locale.ROOT);
    }

    private static PelisItem detalle(PelisItem base) throws IOException {
        JsonObject root = catalogoJson();
        boolean serie = base != null && base.esSerie();
        JsonArray origen = array(root, serie ? "series" : "movies");

        if (base != null) {
            for (JsonElement el : origen) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (coincide(o, base)) {
                    PelisItem it = parseItem(o, base.tipo, root, true);
                    if (it != null) return it;
                }
            }
        }
        // Si no se localiza, devolvemos lo que ya teniamos.
        return base != null ? base : new PelisItem();
    }

    private static List<PelisItem.Episodio> episodios(PelisItem serie) throws IOException {
        JsonObject root = catalogoJson();
        JsonArray origen = array(root, "series");

        JsonObject encontrado = null;
        for (JsonElement el : origen) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            if (coincide(o, serie)) { encontrado = o; break; }
        }
        if (encontrado == null) return new ArrayList<>();

        List<PelisItem.Episodio> out = parseEpisodios(encontrado);
        Collections.sort(out, (a, b) -> {
            int t = numero(a.temporada) - numero(b.temporada);
            return t != 0 ? t : numero(a.episodio) - numero(b.episodio);
        });
        return out;
    }

    /* --------------------------------------------------------------- *
     * Parseo                                                          *
     * --------------------------------------------------------------- */

    /** El objeto del catalogo corresponde al item pedido? */
    private static boolean coincide(JsonObject o, PelisItem base) {
        if (base == null) return false;
        String embedded = primero(o, "embeddedId", "imdb_id", "imdb", "tmdb_id");
        String slug = primero(o, "slug");
        if (!TextUtils.isEmpty(base.playId) && base.playId.equals(embedded)) return true;
        if (!TextUtils.isEmpty(base.slug) && base.slug.equals(slug)) return true;
        int oid = (int) numero(o, "id");
        return base.id > 0 && base.id == oid;
    }

    private static PelisItem parseItem(JsonObject o, String tipoPorDefecto,
                                       JsonObject catalogo, boolean incluirEpisodios) {
        if (o == null) return null;
        PelisItem it = new PelisItem();

        String tipo = primero(o, "type", "post_type", "tipo");
        it.tipo = TextUtils.isEmpty(tipo) ? normalizarTipo(tipoPorDefecto) : normalizarTipo(tipo);

        it.titulo = primero(o, "title", "titulo", "name", "originalTitle", "original_title");
        if (TextUtils.isEmpty(it.titulo)) return null;

        it.tituloOriginal = primero(o, "originalTitle", "original_title", "originalName", "original_name");
        it.id = (int) numero(o, "id");
        it.slug = primero(o, "slug", "permalink", "url");
        it.poster = imagen(primero(o, "poster", "poster_path", "image", "thumbnail"));
        it.backdrop = imagen(primero(o, "backdrop", "backdrop_path", "cover"));
        it.sinopsis = sinopsisDe(o);
        it.anio = anio(primero(o, "year", "anio", "release_date", "first_air_date"));
        it.rating = numero(o, "rating", "tmdbRating", "vote_average", "imdb_rating");
        it.playId = primero(o, "embeddedId", "imdb_id", "imdb", "playId", "tmdb_id", "tmdb");
        it.temporadas = (int) numero(o, "seasons", "temporadas", "total_seasons");
        it.generos = parseGeneros(o, catalogo);
        if (incluirEpisodios) it.episodios = parseEpisodios(o);
        return it;
    }

    private static List<String> parseGeneros(JsonObject item, JsonObject catalogo) {
        List<String> nombres = new ArrayList<>();
        JsonArray ids = array(item, "genres", "genre_ids", "generos");
        JsonObject mapa = catalogo != null && catalogo.has("genres")
                && catalogo.get("genres").isJsonObject()
                ? catalogo.getAsJsonObject("genres") : new JsonObject();

        for (JsonElement elemento : ids) {
            if (elemento == null || elemento.isJsonNull()) continue;
            String valor;
            try {
                valor = elemento.getAsString();
            } catch (Exception ignored) {
                continue;
            }
            String nombre = "";
            if (mapa.has(valor) && !mapa.get(valor).isJsonNull()) {
                try { nombre = mapa.get(valor).getAsString(); } catch (Exception ignored) { }
            } else if (!valor.matches("\\d+")) {
                // Algunos espejos ya envían el nombre del género en lugar de su ID.
                nombre = valor;
            }
            if (!TextUtils.isEmpty(nombre) && !nombres.contains(nombre)) nombres.add(nombre);
        }
        return nombres;
    }

    private static List<PelisItem.Episodio> parseEpisodios(JsonObject o) {
        List<PelisItem.Episodio> out = new ArrayList<>();
        JsonArray arr = array(o, "episodes", "episodios");
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            out.add(parseEpisodio(el.getAsJsonObject()));
        }
        return out;
    }

    private static PelisItem.Episodio parseEpisodio(JsonObject o) {
        PelisItem.Episodio e = new PelisItem.Episodio();
        e.temporada = metaStr(o, "season", "temporada", "season_number");
        e.episodio = metaStr(o, "episode", "episodio", "episode_number");
        e.titulo = primero(o, "title", "titulo", "name");
        e.fecha = primero(o, "date", "air_date", "fecha", "release_date");
        if (e.temporada.isEmpty()) e.temporada = "1";
        if (e.episodio.isEmpty()) e.episodio = "1";
        return e;
    }

    private static String normalizarTipo(String t) {
        if (t == null) return PelisItem.TIPO_PELICULA;
        String s = t.toLowerCase(Locale.US);
        if (s.contains("serie") || s.contains("series") || s.contains("tv") || s.contains("show")) {
            return PelisItem.TIPO_SERIE;
        }
        return PelisItem.TIPO_PELICULA;
    }

    private static String sinopsisDe(JsonObject o) {
        String s = primero(o, "synopsis", "overview", "sinopsis", "description", "plot", "content");
        if (s.length() > 900) s = s.substring(0, 900) + "...";
        return s;
    }

    private static String imagen(String p) {
        if (TextUtils.isEmpty(p)) return "";
        if (p.startsWith("http://") || p.startsWith("https://")) return p;
        if (p.startsWith("/")) return IMG + p;
        return IMG + "/" + p;
    }

    private static String anio(String s) {
        if (TextUtils.isEmpty(s)) return "";
        String[] partes = s.split("[-/ ]");
        for (String p : partes) {
            if (p.length() == 4 && p.matches("\\d{4}")) return p;
        }
        return "";
    }

    private static String metaStr(JsonObject o, String... claves) {
        String v = primero(o, claves);
        return v == null ? "" : v.trim();
    }

    private static int numero(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); }
        catch (Exception e) { return 0; }
    }

    private static double numero(JsonObject o, String... claves) {
        for (String k : claves) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try { return o.get(k).getAsDouble(); } catch (Exception ignored) { }
            }
        }
        return 0.0;
    }

    private static String primero(JsonObject o, String... claves) {
        if (o == null) return "";
        for (String k : claves) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                try {
                    String v = o.get(k).getAsString();
                    if (!TextUtils.isEmpty(v)) return v;
                } catch (Exception ignored) { }
            }
        }
        return "";
    }

    private static JsonArray array(JsonObject root, String... claves) {
        if (root == null) return new JsonArray();
        for (String k : claves) {
            if (root.has(k) && root.get(k).isJsonArray()) {
                return root.getAsJsonArray(k);
            }
        }
        return new JsonArray();
    }

    private static String describir(Exception e) {
        String m = e.getMessage();
        return TextUtils.isEmpty(m) ? e.getClass().getSimpleName() : m;
    }
}
