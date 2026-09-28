package dza.folbol.BLABONGO;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modern Pelis Latino HD Resolver
 * 
 * Resolver moderno y robusto para PelisLatinoHD que:
 * 1. Prueba múltiples dominios activos
 * 2. Maneja cambios en la estructura HTML/JSON
 * 3. Extrae URLs directas de los reproductores
 * 4. Incluye debug detallado para diagnóstico
 * 
 * Funciona con: pelislatinohd.pages.dev, pelislatinohd.com, y otros mirrors
 */
public class ModernPelisResolver {

    private static final String TAG = "ModernPelisResolver";
    
    // Múltiples dominios activos para redundancia
    public static final String[] DOMINIOS = {
        "https://pelislatinohd.pages.dev",
        "https://pelislatinohd.com",
        "https://ev.pelislatinohd.com",
        "https://www.pelislatinohd.com"
    };
    
    // Patrones para extraer datos
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "(?:token|id)\\s*[:=]\\s*['\"]?([A-Za-z0-9_\\-]{20,})['\"]?",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern EMBED_URL_PATTERN = Pattern.compile(
        "(?:embedUrl|url|src)\\s*[:=]\\s*['\"](https?://[^\"']+)['\"]",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern VIDEO_URL_PATTERN = Pattern.compile(
        "(?:videoUrl|video|file)\\s*[:=]\\s*['\"](https?://[^\"']+)['\"]",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Datos del servidor de streaming
     */
    public static class StreamInfo {
        public String nombre = "";
        public String url = "";
        public String idioma = "";
        public String calidad = "";
        
        @Override
        public String toString() {
            return nombre + " [" + idioma + "] -> " + url;
        }
    }

    /**
     * Información de la película/serie
     */
    public static class MediaInfo {
        public String titulo = "";
        public String sinopsis = "";
        public String poster = "";
        public String backdrop = "";
        public String tipo = "movie"; // movie o series
        public String imdbId = "";
        public int tmdbId = 0;
        public int anio = 0;
        public double rating = 0.0;
        public List<StreamInfo> servidores = new ArrayList<>();
        
        public boolean isValid() {
            return !titulo.isEmpty() && !servidores.isEmpty();
        }
        
        public StreamInfo mejorServidor() {
            if (servidores.isEmpty()) return null;
            return servidores.get(0); // El primero es el mejor
        }
    }

    private ModernPelisResolver() { }

    /**
     * Resuelve una película/serie por ID
     * 
     * @param imdbId ID IMDb o TMDB
     * @param temporada (opcional) para series
     * @param episodio (opcional) para series
     * @return Información completa de la película
     * @throws IOException si falla la conexión o parsing
     */
    public static MediaInfo resolver(String imdbId, String temporada, String episodio) throws IOException {
        if (imdbId == null || imdbId.trim().isEmpty()) {
            throw new IOException("Falta el identificador IMDb/TMDB");
        }

        Log.d(TAG, "🔍 Buscando: " + imdbId + (temporada != null ? " T:" + temporada : ""));
        
        // Probar todos los dominios hasta encontrar uno que funcione
        IOException ultimoError = null;
        MediaInfo resultado = null;
        
        for (String dominio : DOMINIOS) {
            try {
                Log.d(TAG, "🌐 Probando dominio: " + dominio);
                resultado = resolverEnDominio(dominio, imdbId, temporada, episodio);
                if (resultado != null && resultado.isValid()) {
                    Log.d(TAG, "✅ Éxito en: " + dominio);
                    break;
                }
            } catch (IOException e) {
                Log.w(TAG, "❌ Falló en " + dominio + ": " + e.getMessage());
                ultimoError = e;
            }
        }
        
        if (resultado == null || !resultado.isValid()) {
            throw new IOException("No se pudo resolver. Último error: " + 
                (ultimoError != null ? ultimoError.getMessage() : "servidores agotados"));
        }
        
        Log.d(TAG, "🎬 Película resuelta: " + resultado.titulo + 
            " (" + resultado.servidores.size() + " servidores)");
        return resultado;
    }

    /**
     * Resuelve en un dominio específico
     */
    private static MediaInfo resolverEnDominio(String dominio, String imdbId, 
                                                String temporada, String episodio) throws IOException {
        
        // 1. Obtener página principal de la película
        String urlPrincipal = dominio + "/movie/" + slugify(imdbId);
        if ("series".equals(temporada)) {
            urlPrincipal = dominio + "/series/" + slugify(imdbId);
        }
        
        String html = PelisApi.get(urlPrincipal, dominio + "/");
        
        // 2. Extraer ID interno del sitio
        String internalId = extraerInternalId(html);
        if (internalId == null || internalId.isEmpty()) {
            throw new IOException("No se encontró ID interno en la página");
        }
        
        Log.d(TAG, "   ID interno: " + internalId);
        
        // 3. Obtener datos JSON de la página
        MediaInfo info = new MediaInfo();
        
        // Extraer datos de la página HTML
        extraerDeHtml(html, info);
        
        // 4. Extraer reproductores
        List<StreamInfo> servidores = extraerServidores(html, dominio);
        
        if (servidores.isEmpty()) {
            // Si no hay servidores directos, intentar API
            servidores = extraerServidoresApi(internalId, dominio, temporada, episodio);
        }
        
        info.servidores = servidores;
        return info;
    }

    /**
     * Extraer ID interno del sitio desde la página de la película
     */
    private static String extraerInternalId(String html) {
        // Intentar varios patrones
        Pattern[] patrones = {
            Pattern.compile("data-id=['\"]([A-Za-z0-9_\\-]+)['\"]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("wp-post-id=['\"]([A-Za-z0-9_\\-]+)['\"]", Pattern.CASE_INSENSITIVE),
            Pattern.compile("post_id:\\s*([0-9]+)"),
            Pattern.compile("\\bid=(\\d+)")
        };
        
        for (Pattern p : patrones) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                return m.group(1);
            }
        }
        
        return null;
    }

    /**
     * Extraer datos de la página HTML
     */
    private static void extraerDeHtml(String html, MediaInfo info) {
        Document doc = Jsoup.parse(html);
        
        // Título
        Element tituloEl = doc.selectFirst("h1.entry-title, h2.title, .movie-title");
        if (tituloEl != null) {
            info.titulo = tituloEl.text().trim();
        }
        
        // Sinopsis
        Element sinopsisEl = doc.selectFirst(".sinopsis, .overview, .description, p");
        if (sinopsisEl != null) {
            info.sinopsis = sinopsisEl.text().trim();
        }
        
        // Poster
        Element posterEl = doc.selectFirst("img.poster, img.movie-poster, [data-src*=\"poster\"]");
        if (posterEl != null && posterEl.hasAttr("src")) {
            info.poster = posterEl.attr("src");
        }
        
        // Backdrop
        Element backdropEl = doc.selectFirst("img.backdrop, .hero-bg, .hero-poster");
        if (backdropEl != null && backdropEl.hasAttr("src")) {
            info.backdrop = backdropEl.attr("src");
        }
        
        // Años
        Element anioEl = doc.selectFirst(".year, .release-year, .meta-year");
        if (anioEl != null) {
            String anioTxt = anioEl.text().trim();
            if (anioTxt.matches("\\d{4}")) {
                info.anio = Integer.parseInt(anioTxt);
            }
        }
        
        // Rating
        Element ratingEl = doc.selectFirst(".rating, .imdb-rating, .vote");
        if (ratingEl != null) {
            String ratingTxt = ratingEl.text().trim().replace(",", ".");
            try {
                info.rating = Double.parseDouble(ratingTxt);
            } catch (NumberFormatException e) {
                // Ignorar
            }
        }
    }

    /**
     * Extraer servidores de streaming desde la página HTML
     */
    private static List<StreamInfo> extraerServidores(String html, String dominio) {
        List<StreamInfo> servidores = new ArrayList<>();
        
        Document doc = Jsoup.parse(html);
        
        // Buscar botones o enlaces de servidores
        Element contenedor = doc.selectFirst(".server-list, .servers, .playlist, .player-options");
        
        if (contenedor != null) {
            Elements servidoresEl = contenedor.select("a.server-btn, li.server, button.server, .server-option");
            
            for (Element el : servidoresEl) {
                StreamInfo servidor = new StreamInfo();
                
                // Nombre
                servidor.nombre = el.selectFirst("span, .name, .label").text();
                
                // URL (puede estar en href, data-url, o data-link)
                if (el.hasAttr("data-url")) {
                    servidor.url = el.attr("data-url");
                } else if (el.hasAttr("data-link")) {
                    servidor.url = el.attr("data-link");
                } else if (el.hasAttr("href")) {
                    servidor.url = el.attr("href");
                }
                
                // Idioma
                if (el.text().toLowerCase().contains("latino")) {
                    servidor.idioma = "Latino";
                } else if (el.text().toLowerCase().contains("multi") || 
                          el.text().toLowerCase().contains("inglés")) {
                    servidor.idioma = "Multi";
                }
                
                if (servidor.url != null && !servidor.url.isEmpty()) {
                    servidores.add(servidor);
                }
            }
        }
        
        return servidores;
    }

    /**
     * Extraer servidores desde API JSON
     */
    private static List<StreamInfo> extraerServidoresApi(String internalId, String dominio,
                                                          String temporada, String episodio) throws IOException {
        List<StreamInfo> servidores = new ArrayList<>();
        
        // Probar diferentes rutas API
        String[] rutasApi = {
            "/wp-json/wp/v2/" + ("series".equals(temporada) ? "tvshows" : "movies") + "/" + internalId,
            "/api/details?id=" + internalId,
            "/player/embed.php?id=" + internalId + (temporada != null ? "&se=" + temporada : ""),
            "/player/api/details.php?id=" + internalId
        };
        
        for (String ruta : rutasApi) {
            try {
                String url = dominio + ruta;
                String json = PelisApi.get(url, dominio + "/");
                
                JsonElement raiz = JsonParser.parseString(json);
                if (!raiz.isJsonObject()) continue;
                
                JsonObject obj = raiz.getAsJsonObject();
                
                // Extraer embeds si existen
                if (obj.has("embeds")) {
                    JsonObject embeds = obj.getAsJsonObject("embeds");
                    for (var entrada : embeds.entrySet()) {
                        String nombre = entrada.getKey();
                        String streamUrl = entrada.getValue().getAsString();
                        
                        StreamInfo s = new StreamInfo();
                        s.nombre = nombre;
                        s.url = streamUrl;
                        s.idioma = obtenerIdiomaDelNombre(nombre);
                        servidores.add(s);
                    }
                }
                
                // Extraer directamente si tiene videoUrl
                if (obj.has("videoUrl")) {
                    StreamInfo s = new StreamInfo();
                    s.nombre = "Principal";
                    s.url = obj.get("videoUrl").getAsString();
                    s.idioma = "Latino";
                    servidores.add(s);
                }
                
                if (!servidores.isEmpty()) {
                    break;
                }
                
            } catch (Exception e) {
                Log.d(TAG, "API " + ruta + " falló: " + e.getMessage());
            }
        }
        
        return servidores;
    }

    /**
     * Inferir idioma del nombre del servidor
     */
    private static String obtenerIdiomaDelNombre(String nombre) {
        String n = nombre.toLowerCase();
        if (n.contains("latino")) return "Latino";
        if (n.contains("multi") || n.contains("espanol")) return "Multi";
        if (n.contains("inglés") || n.contains("eng")) return "Inglés";
        return "Desconocido";
    }

    /**
     * Convertir ID a slug
     */
    private static String slugify(String input) {
        return input.toLowerCase()
            .replace(" ", "-")
            .replace("/", "-")
            .replace("\\", "-");
    }

    /**
     * Versión asíncrona para uso en UI
     */
    public static void resolverAsync(final String imdbId, final String temporada, 
                                      final String episodio, final Callback cb) {
        new Thread(() -> {
            try {
                MediaInfo info = resolver(imdbId, temporada, episodio);
                Looper looper = Looper.getMainLooper();
                if (looper == null || Looper.myLooper() == looper) {
                    cb.onOk(info);
                } else {
                    new Handler(looper).post(() -> cb.onOk(info));
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "Error desconocido";
                Looper looper = Looper.getMainLooper();
                if (looper == null || Looper.myLooper() == looper) {
                    cb.onError(msg);
                } else {
                    new Handler(looper).post(() -> cb.onError(msg));
                }
            }
        }, "ModernPelisResolver").start();
    }

    /**
     * Callback para resolver asíncrono
     */
    public interface Callback {
        void onOk(MediaInfo info);
        void onError(String mensaje);
    }
}