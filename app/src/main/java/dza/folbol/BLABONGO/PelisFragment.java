package dza.folbol.BLABONGO;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * Catálogo de películas y series.
 *
 * Carga por páginas contra la API de PelisLatinoHD y va agregando resultados al
 * hacer scroll, así no se descarga nunca el catálogo completo.
 */
public class PelisFragment extends Fragment {

    private static final String TAG = "PelisFragment";

    private EditText editBuscar;
    private Button btnPeliculas, btnSeries;
    private RecyclerView recycler;
    private ProgressBar progress, progressPie;
    private TextView txtVacio;

    private PelisAdapter adapter;
    private GridLayoutManager layoutManager;

    private String tipoActual = PelisItem.TIPO_PELICULA;
    private String busqueda = "";
    private int pagina = 1;
    private boolean cargando = false;
    private boolean hayMas = true;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_pelis, container, false);

        editBuscar = view.findViewById(R.id.editBuscarPelis);
        btnPeliculas = view.findViewById(R.id.btnPeliculasPelis);
        btnSeries = view.findViewById(R.id.btnSeriesPelis);
        recycler = view.findViewById(R.id.recyclerPelis);
        progress = view.findViewById(R.id.progressPelis);
        progressPie = view.findViewById(R.id.progressPelisPie);
        txtVacio = view.findViewById(R.id.txtVacioPelis);

        layoutManager = new GridLayoutManager(getContext(), columnas());
        recycler.setLayoutManager(layoutManager);
        adapter = new PelisAdapter(getContext(), this::abrirDetalle);
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || cargando || !hayMas || adapter.total() == 0) return;
                int ultimo = layoutManager.findLastVisibleItemPosition();
                if (ultimo >= adapter.total() - 5) {
                    pagina++;
                    cargar(false);
                }
            }
        });

        btnPeliculas.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_PELICULA));
        btnSeries.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_SERIE));

        editBuscar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                buscar(editBuscar.getText().toString());
                return true;
            }
            return false;
        });

        pintarBotones();
        cargar(true);
        return view;
    }

    private int columnas() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float anchoDp = dm.widthPixels / dm.density;
        int cols = (int) (anchoDp / 120f);
        if (cols < 2) cols = 2;
        if (cols > 5) cols = 5;
        return cols;
    }

    private void cambiarTipo(String tipo) {
        if (tipo.equals(tipoActual)) return;
        tipoActual = tipo;
        pintarBotones();
        cargar(true);
    }

    private void pintarBotones() {
        boolean esPeli = PelisItem.TIPO_PELICULA.equals(tipoActual);
        btnPeliculas.setAlpha(esPeli ? 1f : 0.5f);
        btnSeries.setAlpha(esPeli ? 0.5f : 1f);
    }

    private void buscar(String texto) {
        busqueda = texto != null ? texto.trim() : "";
        ocultarTeclado();
        cargar(true);
    }

    private void ocultarTeclado() {
        View foco = getActivity() != null ? getActivity().getCurrentFocus() : null;
        if (foco == null) return;
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(foco.getWindowToken(), 0);
    }

    /** @param reiniciar true = primera página (limpia la lista) */
    private void cargar(boolean reiniciar) {
        if (cargando) return;
        cargando = true;

        if (reiniciar) {
            pagina = 1;
            hayMas = true;
            adapter.limpiar();
            txtVacio.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        } else {
            progressPie.setVisibility(View.VISIBLE);
        }

        PelisApi.catalogo(tipoActual, pagina, busqueda, new PelisApi.Callback<List<PelisItem>>() {
            @Override
            public void onOk(List<PelisItem> data) {
                if (!isAdded()) return;
                cargando = false;
                progress.setVisibility(View.GONE);
                progressPie.setVisibility(View.GONE);

                if (data == null || data.isEmpty()) {
                    hayMas = false;
                    if (adapter.total() == 0) {
                        txtVacio.setText(busqueda.isEmpty()
                                ? "No hay contenido disponible"
                                : "Sin resultados para \"" + busqueda + "\"");
                        txtVacio.setVisibility(View.VISIBLE);
                    }
                    return;
                }

                if (data.size() < PelisApi.POR_PAGINA) hayMas = false;
                adapter.agregar(data);
            }

            @Override
            public void onError(String mensaje) {
                if (!isAdded()) return;
                cargando = false;
                progress.setVisibility(View.GONE);
                progressPie.setVisibility(View.GONE);
                Log.e(TAG, "Error: " + mensaje);
                if (adapter.total() == 0) {
                    txtVacio.setText("No se pudo cargar el catálogo.\n" + mensaje);
                    txtVacio.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void abrirDetalle(PelisItem item) {
        Intent intent = new Intent(getContext(), PelisDetailActivity.class);
        intent.putExtra(PelisDetailActivity.EXTRA_ITEM, item);
        startActivity(intent);
    }
}
