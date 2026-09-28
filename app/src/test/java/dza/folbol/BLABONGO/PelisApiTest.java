package dza.folbol.BLABONGO;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Ejercita la misma ruta de red y de parseo que usa la app, pero en la JVM.
 * Sirve para separar un fallo de código de un fallo de red del dispositivo.
 */
public class PelisApiTest {

    private List<PelisItem> pedir(String tipo) throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<PelisItem>> ok = new AtomicReference<>();
        final AtomicReference<String> err = new AtomicReference<>();

        PelisApi.catalogo(tipo, 1, "", new PelisApi.Callback<List<PelisItem>>() {
            @Override public void onOk(List<PelisItem> data) { ok.set(data); latch.countDown(); }
            @Override public void onError(String mensaje) { err.set(mensaje); latch.countDown(); }
        });

        assertTrue("La llamada no terminó (timeout de 60s)", latch.await(60, TimeUnit.SECONDS));
        assertNull("La app devolvió error: " + err.get(), err.get());
        assertNotNull(ok.get());
        return ok.get();
    }

    private void volcar(String etiqueta, List<PelisItem> items) {
        System.out.println("### " + etiqueta + ": " + items.size() + " items");
        int n = Math.min(6, items.size());
        for (int i = 0; i < n; i++) {
            PelisItem it = items.get(i);
            System.out.println("###   " + it.titulo
                    + " | " + it.subtitulo()
                    + " | playId=" + it.playId
                    + " | poster=" + it.poster);
        }
    }

    @Test
    public void peliculas() throws Exception {
        List<PelisItem> items = pedir(PelisItem.TIPO_PELICULA);
        volcar("PELICULAS", items);
        assertFalse("La lista de películas llegó vacía", items.isEmpty());
    }

    @Test
    public void series() throws Exception {
        List<PelisItem> items = pedir(PelisItem.TIPO_SERIE);
        volcar("SERIES", items);
        assertFalse("La lista de series llegó vacía", items.isEmpty());
    }

    @Test
    public void resolverServidoresPelicula() throws Exception {
        PelisResolver.Servidores s = PelisResolver.servidores("tt0137523", null, null);
        System.out.println("### RESOLVER película: " + s.titulo + " | " + s.opciones.keySet());
        assertTrue("Sin servidores para la película", s.hayServidores());
    }

    @Test
    public void resolverServidoresEpisodio() throws Exception {
        PelisResolver.Servidores s = PelisResolver.servidores("66732", "2", "3");
        System.out.println("### RESOLVER episodio: " + s.titulo + " | tipo=" + s.tipo
                + " | " + s.opciones.keySet());
        for (java.util.Map.Entry<String, String> e : s.opciones.entrySet()) {
            System.out.println("###    " + e.getKey() + " -> " + e.getValue());
        }
        assertTrue("Sin servidores para el episodio", s.hayServidores());
    }

    @Test
    public void detalleDeSerie() throws Exception {
        PelisItem base = pedir(PelisItem.TIPO_SERIE).get(0);
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<PelisItem> ok = new AtomicReference<>();
        PelisApi.detalle(base, new PelisApi.Callback<PelisItem>() {
            @Override public void onOk(PelisItem data) { ok.set(data); latch.countDown(); }
            @Override public void onError(String mensaje) { latch.countDown(); }
        });
        assertTrue(latch.await(60, TimeUnit.SECONDS));
        assertNotNull(ok.get());
        System.out.println("### DETALLE: " + ok.get().titulo + " | playId=" + ok.get().playId
                + " | sinopsis=" + ok.get().sinopsis.length() + " chars");
        System.out.println("###   " + ok.get().sinopsis.substring(0, Math.min(180, ok.get().sinopsis.length())));
        assertFalse("Sin sinopsis", ok.get().sinopsis.isEmpty());
    }

    @Test
    public void episodiosDeSerie() throws Exception {
        PelisItem serie = new PelisItem();
        serie.tipo = PelisItem.TIPO_SERIE;
        serie.slug = "arcane";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<List<PelisItem.Episodio>> ok = new AtomicReference<>();
        final AtomicReference<String> err = new AtomicReference<>();

        PelisApi.episodios(serie, new PelisApi.Callback<List<PelisItem.Episodio>>() {
            @Override public void onOk(List<PelisItem.Episodio> data) { ok.set(data); latch.countDown(); }
            @Override public void onError(String mensaje) { err.set(mensaje); latch.countDown(); }
        });

        assertTrue(latch.await(60, TimeUnit.SECONDS));
        assertNull("Error: " + err.get(), err.get());
        assertNotNull(ok.get());
        System.out.println("### EPISODIOS arcane: " + ok.get().size());
        for (int i = 0; i < Math.min(5, ok.get().size()); i++) {
            PelisItem.Episodio e = ok.get().get(i);
            System.out.println("###   " + e.etiqueta() + " | " + e.titulo + " | " + e.fecha);
        }
        assertFalse("Sin episodios", ok.get().isEmpty());
    }
}
