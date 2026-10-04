package dza.folbol.BLABONGO;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Item del catálogo de PelisLatinoHD: una película o una serie.
 * Se transfiere entre activities por eso implementa Serializable.
 */
public class PelisItem implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String TIPO_PELICULA = "movie";
    public static final String TIPO_SERIE = "series";
    public static final String TIPO_TODOS = "all";

    public int id;
    public String tipo = TIPO_PELICULA;
    public String titulo = "";
    public String tituloOriginal = "";
    public String slug = "";
    public String poster = "";
    public String backdrop = "";
    public String sinopsis = "";
    public String anio = "";
    public String fechaCatalogo = "";
    public double rating = 0;
    public String playId = "";     // imdb "tt..." en películas, id TMDB en series
    public int temporadas = 0;
    public List<String> generos = new ArrayList<>();
    public List<Episodio> episodios = new ArrayList<>();

    public boolean esSerie() {
        return TIPO_SERIE.equals(tipo);
    }

    /** Etiqueta compacta usada en la ficha: "2024 · 7.8 · 2 temps.". */
    public String subtitulo() {
        StringBuilder sb = new StringBuilder();
        if (!anio.isEmpty()) sb.append(anio);
        if (rating > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(String.format(Locale.US, "%.1f", rating));
        }
        if (esSerie() && temporadas > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(temporadas).append(temporadas == 1 ? " temp." : " temps.");
        }
        return sb.toString();
    }

    /** Muestra hasta tres géneros, como las tarjetas de la web de referencia. */
    public String generosTexto() {
        if (generos == null || generos.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int mostrados = 0;
        for (String genero : generos) {
            if (genero == null || genero.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(genero.trim());
            if (++mostrados >= 3) break;
        }
        return sb.toString();
    }

    public static class Episodio implements Serializable {
        private static final long serialVersionUID = 1L;
        public String temporada = "1";
        public String episodio = "1";
        public String titulo = "";
        public String fecha = "";

        public String etiqueta() {
            return "T" + temporada + " E" + episodio;
        }
    }
}
