package dza.folbol.BLABONGO;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detalle de una película o serie, con acceso a la reproducción.
 *
 * Flujo de reproducción:
 *   1) M3u8PelisResolver (HTTP rápido) intenta obtener un m3u8 directo.
 *   2) Si la API solo devuelve un embed (vsembed, vimeus, embed69…), ese embed
 *      se pasa a PelisStreamResolver, que hace extracción profunda de iframes
 *      (OkHttp recursivo + WebView interna) para localizar el .m3u8 real.
 *   3) Si la extracción falla (players cross-origin con challenge JS, p.ej.
 *      vsembed), se abre el reproductor nativo en modo WebView cargando el
 *      propio embed como "url_iframe_inicial", de forma que el usuario vea
 *      el player real en pantalla completa.
 */
public class PelisDetailActivity extends AppCompatActivity {

    private static final String TAG = "PelisDetalle";

    public static final String EXTRA_ITEM = "pelis_item";

    private PelisItem item;
    private PelisEpisodioAdapter episodioAdapter;

    private ImageView imgBackdrop, imgPoster;
    private TextView txtTitulo, txtMeta, txtTipo, txtSinopsis, txtEstado;
    private Button btnReproducir;
    private ProgressBar progress;
    private LinearLayout contenedorEpisodios;
    private Spinner spinnerTemporadas;
    private RecyclerView recyclerEpisodios;

    private final Map<String, List<PelisItem.Episodio>> porTemporada = new LinkedHashMap<>();
    private final List<String> temporadas = new ArrayList<>();
    private String temporadaActual = "";

    /** Pool para resolución profunda de streams (OkHttp + WebView interna). */
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pelis_detalle);

        item = (PelisItem) getIntent().getSerializableExtra(EXTRA_ITEM);
        if (item == null) {
            Toast.makeText(this, "No se pudo abrir el título", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        imgBackdrop = findViewById(R.id.imgBackdropDetalle);
        imgPoster = findViewById(R.id.imgPosterDetalle);
        txtTitulo = findViewById(R.id.txtTituloDetalle);
        txtMeta = findViewById(R.id.txtMetaDetalle);
        txtTipo = findViewById(R.id.txtTipoDetalle);
        txtSinopsis = findViewById(R.id.txtSinopsisDetalle);
        txtEstado = findViewById(R.id.txtEstadoDetalle);
        btnReproducir = findViewById(R.id.btnReproducirDetalle);
        progress = findViewById(R.id.progressDetalle);
        contenedorEpisodios = findViewById(R.id.contenedorEpisodios);
        spinnerTemporadas = findViewById(R.id.spinnerTemporadas);
        recyclerEpisodios = findViewById(R.id.recyclerEpisodios);

        pintarCabecera();
        txtEstado.setText("Pulsa Reproducir para resolver el servidor…");

        episodioAdapter = new PelisEpisodioAdapter(this, this::reproducirEpisodio);
        recyclerEpisodios.setLayoutManager(new LinearLayoutManager(this));
        recyclerEpisodios.setAdapter(episodioAdapter);

        btnReproducir.setOnClickListener(v -> reproducirPrincipal());

        if (item.esSerie()) {
            contenedorEpisodios.setVisibility(View.VISIBLE);
            cargarEpisodios();
        }

        cargarDetalle();
    }

    private void pintarCabecera() {
        txtTitulo.setText(item.titulo);
        txtMeta.setText(item.subtitulo());
        txtTipo.setText(item.esSerie() ? "Serie" : "Película");

        String fondo = item.backdrop.isEmpty() ? item.poster : item.backdrop;
        Glide.with(this).load(fondo).centerCrop().into(imgBackdrop);
        Glide.with(this)
                .load(item.poster.isEmpty() ? item.backdrop : item.poster)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .centerCrop()
                .into(imgPoster);

        if (!item.sinopsis.isEmpty()) txtSinopsis.setText(item.sinopsis);
    }

    // ─────────────────────────── CARGA DE DATOS ───────────────────────────

    private void cargarDetalle() {
        progress.setVisibility(View.VISIBLE);

        PelisApi.detalle(item, new PelisApi.Callback<PelisItem>() {
            @Override
            public void onOk(PelisItem detalle) {
                progress.setVisibility(View.GONE);
                if (detalle == null || isFinishing()) return;
                // Refresca sinopsis/póster si el catálogo no los traía completos.
                if (!detalle.sinopsis.isEmpty()) {
                    item.sinopsis = detalle.sinopsis;
                    txtSinopsis.setText(detalle.sinopsis);
                }
                if (!detalle.backdrop.isEmpty()) {
                    item.backdrop = detalle.backdrop;
                    Glide.with(PelisDetailActivity.this).load(detalle.backdrop).centerCrop().into(imgBackdrop);
                }
            }

            @Override
            public void onError(String mensaje) {
                progress.setVisibility(View.GONE);
                Log.w(TAG, "No se pudo cargar el detalle: " + mensaje);
            }
        });
    }

    private void cargarEpisodios() {
        porTemporada.clear();
        temporadas.clear();

        for (PelisItem.Episodio ep : item.episodios) {
            String t = ep.temporada == null || ep.temporada.isEmpty() ? "1" : ep.temporada;
            List<PelisItem.Episodio> lista = porTemporada.get(t);
            if (lista == null) {
                lista = new ArrayList<>();
                porTemporada.put(t, lista);
                temporadas.add(t);
            }
            lista.add(ep);
        }

        if (temporadas.isEmpty()) {
            contenedorEpisodios.setVisibility(View.GONE);
            return;
        }

        ArrayAdapter<String> adaptador = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, temporadas);
        adaptador.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTemporadas.setAdapter(adaptador);

        spinnerTemporadas.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                temporadaActual = temporadas.get(position);
                refrescarEpisodios();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });

        temporadaActual = temporadas.get(0);
        refrescarEpisodios();
    }

    private void refrescarEpisodios() {
        List<PelisItem.Episodio> lista = porTemporada.get(temporadaActual);
        episodioAdapter.mostrar(lista != null ? lista : new ArrayList<>());
    }

    // ─────────────────────────── REPRODUCCIÓN ───────────────────────────

    private void reproducirPrincipal() {
        if (item.esSerie()) {
            List<PelisItem.Episodio> lista = porTemporada.get(temporadaActual);
            if (lista != null && !lista.isEmpty()) {
                reproducirEpisodio(lista.get(0));
                return;
            }
        }
        buscarServidoresRespaldo(null, null);
    }

    private void reproducirEpisodio(PelisItem.Episodio episodio) {
        String se = episodio.temporada == null ? "1" : episodio.temporada;
        String ep = episodio.episodio == null ? "1" : episodio.episodio;
        buscarServidoresRespaldo(se, ep);
    }

    private void buscarServidoresRespaldo(final String se, final String numeroEp) {
        PelisResolver.servidoresAsync(item.playId, se, numeroEp, new PelisResolver.Callback() {
            @Override
            public void onOk(PelisResolver.Servidores servidores) {
                if (isFinishing()) return;
                btnReproducir.setEnabled(true);
                txtEstado.setText(servidores.opciones.size() + " servidor(es) disponible(s)");
                elegirServidor(servidores);
            }

            @Override
            public void onError(String mensaje) {
                if (isFinishing()) return;
                btnReproducir.setEnabled(true);
                txtEstado.setText("Resolución directa no disponible");
                Log.w(TAG, "Servidores no disponibles: " + mensaje);
            }
        });
    }

    private void elegirServidor(final PelisResolver.Servidores servidores) {
        final List<String> nombres = new ArrayList<>(servidores.opciones.keySet());
        final List<String> urls = new ArrayList<>(servidores.opciones.values());

        // MEJORA 2026-09-28: auto-selección del servidor en español.
        // PelisResolver ya ordena primero las opciones con vsembed / ds_lang=es,
        // y es el único camino verificado (WASM + Token -> master.m3u8).
        // Los demás (vimeus, embed69) suelen caer en Cloudflare 522, así que
        // evitamos preguntar y conectamos directo. Si no hay opción en español
        // se mantiene el diálogo de siempre.
        for (int i = 0; i < urls.size(); i++) {
            String u = urls.get(i);
            if (u != null && (u.contains("vsembed") || u.contains("ds_lang=es"))) {
                Log.d(TAG, "Servidor automático (español): " + nombres.get(i));
                txtEstado.setText("Conectando con el servidor en español…");
                resolverYReproducir(u);
                return;
            }
        }

        if (urls.size() == 1) {
            resolverYReproducir(urls.get(0));
            return;
        }

        String[] etiquetas = new String[nombres.size()];
        for (int i = 0; i < nombres.size(); i++) etiquetas[i] = PelisResolver.etiqueta(nombres.get(i));

        new AlertDialog.Builder(this)
                .setTitle("Elige un servidor")
                .setItems(etiquetas, (d, which) -> resolverYReproducir(urls.get(which)))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /**
     * Toma la URL de un servidor (normalmente un iframe embed) y la resuelve
     * con PelisStreamResolver para obtener el m3u8 real antes de abrir el
     * reproductor nativo.
     *
     * Si la resolución falla (players cross-origin protegidos como vsembed,
     * cuyo stream vive dentro de un iframe de otro dominio y no se puede
     * interceptar por Same-Origin Policy), se abre el reproductor nativo en
     * modo WebView cargando el propio embed. Así el usuario ve el player
     * real a pantalla completa en vez de un error.
     */
    private void resolverYReproducir(final String url) {
        btnReproducir.setEnabled(false);
        txtEstado.setText("Resolviendo stream del servidor…");
        Log.d(TAG, "Resolviendo m3u8 desde servidor: " + url);

        executorService.execute(() -> {
            PelisStreamResolver.StreamResult sr = null;
            try {
                sr = PelisStreamResolver.resolveSynchronously(getApplicationContext(), url);
            } catch (Throwable t) {
                Log.e(TAG, "PelisStreamResolver lanzó excepción: " + t.getMessage(), t);
            }

            final PelisStreamResolver.StreamResult finalSr = sr;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                btnReproducir.setEnabled(true);
                if (finalSr != null && finalSr.m3u8Url != null && !finalSr.m3u8Url.isEmpty()) {
                    Log.d(TAG, "m3u8 resuelto: " + finalSr.m3u8Url);
                    M3u8PelisResolver.M3u8Result listo = new M3u8PelisResolver.M3u8Result();
                    listo.m3u8Url = finalSr.m3u8Url;
                    listo.cookies = finalSr.cookies;
                    listo.referer = finalSr.referer;
                    listo.origin = finalSr.origin;
                    listo.headers = finalSr.headers;
                    txtEstado.setText("✅ Stream listo");
                    abrirReproductorConStream(listo);
                } else {
                    // Fallback: no se pudo extraer el m3u8 (iframe cross-origin,
                    // challenge JS, etc.). Abrimos el embed directamente en el
                    // reproductor para que el usuario vea el player real.
                    Log.w(TAG, "No se pudo extraer el m3u8; abriendo embed en el reproductor: " + url);
                    txtEstado.setText("Abriendo player del servidor…");
                    abrirReproductorConEmbed(url);
                }
            });
        });
    }

    /** Abre el reproductor con un m3u8 ya resuelto, evitando el WebView. */
    @SuppressWarnings("UnstableApiUsage")
    private void abrirReproductorConStream(M3u8PelisResolver.M3u8Result resultado) {
        Intent intent = new Intent(this, PelisPlayerActivity.class);
        intent.putExtra("stream_url", resultado.m3u8Url);
        intent.putExtra("stream_cookies", resultado.cookies);
        intent.putExtra("stream_referer", resultado.referer);
        intent.putExtra("stream_origin", resultado.origin);
        startActivity(intent);
    }

    /**
     * Fallback cuando no se pudo extraer el m3u8: abre el reproductor nativo
     * en modo WebView cargando el embed del servidor. PlayerActivity ya
     * soporta esto vía el extra "url_iframe_inicial".
     */
    @SuppressWarnings("UnstableApiUsage")
    private void abrirReproductorConEmbed(String embedUrl) {
        Intent intent = new Intent(this, PelisPlayerActivity.class);
        intent.putExtra("url_iframe_inicial", embedUrl);
        // Referer del embed original (playpaste) para que el servidor no lo
        // rechace como hotlink.
        intent.putExtra("stream_referer", "https://playpaste.link/player/");
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}
