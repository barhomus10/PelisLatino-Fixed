package dza.folbol.BLABONGO;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.NumberFormat;
import java.util.Locale;

/** Catálogo de películas y series con búsqueda y paginación incremental. */
public class PelisFragment extends Fragment {

    private static final String TAG = "PelisFragment";
    private static final long BUSQUEDA_DEBOUNCE_MS = 320L;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());

    private EditText editBuscar;
    private ImageButton btnBuscar, btnLimpiar;
    private TextView btnTodos, btnPeliculas, btnSeries;
    private TextView txtResultados, txtConteo, txtVacio;
    private RecyclerView recycler;
    private ProgressBar progress, progressPie;

    private PelisAdapter adapter;
    private GridLayoutManager layoutManager;
    private TextWatcher searchWatcher;
    private Runnable pendingSearch;

    private String tipoActual = PelisItem.TIPO_TODOS;
    private String busqueda = "";
    private int pagina = 1;
    private int totalResultados = 0;
    private long requestId = 0;
    private boolean cargando = false;
    private boolean hayMas = true;
    private boolean errorActual = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_pelis, container, false);

        editBuscar = view.findViewById(R.id.editBuscarPelis);
        btnBuscar = view.findViewById(R.id.btnBuscarPelis);
        btnLimpiar = view.findViewById(R.id.btnLimpiarBusquedaPelis);
        btnTodos = view.findViewById(R.id.btnTodosPelis);
        btnPeliculas = view.findViewById(R.id.btnPeliculasPelis);
        btnSeries = view.findViewById(R.id.btnSeriesPelis);
        txtResultados = view.findViewById(R.id.txtResultadosPelis);
        txtConteo = view.findViewById(R.id.txtConteoPelis);
        recycler = view.findViewById(R.id.recyclerPelis);
        progress = view.findViewById(R.id.progressPelis);
        progressPie = view.findViewById(R.id.progressPelisPie);
        txtVacio = view.findViewById(R.id.txtVacioPelis);

        layoutManager = new GridLayoutManager(requireContext(), columnas());
        recycler.setLayoutManager(layoutManager);
        adapter = new PelisAdapter(requireContext(), this::abrirDetalle);
        recycler.setAdapter(adapter);
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0 || cargando || !hayMas || adapter.total() == 0) return;
                int ultimo = layoutManager.findLastVisibleItemPosition();
                if (ultimo >= adapter.total() - 6) {
                    pagina++;
                    cargar(false);
                }
            }
        });

        btnTodos.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_TODOS));
        btnPeliculas.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_PELICULA));
        btnSeries.setOnClickListener(v -> cambiarTipo(PelisItem.TIPO_SERIE));
        btnBuscar.setOnClickListener(v -> buscar(editBuscar.getText().toString()));
        btnLimpiar.setOnClickListener(v -> editBuscar.setText(""));

        editBuscar.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                buscar(editBuscar.getText().toString());
                return true;
            }
            return false;
        });

        searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable editable) {
                String texto = editable == null ? "" : editable.toString().trim();
                btnLimpiar.setVisibility(texto.isEmpty() ? View.GONE : View.VISIBLE);
                programarBusqueda(texto);
            }
        };
        editBuscar.addTextChangedListener(searchWatcher);

        pintarFiltros();
        actualizarCabecera();
        cargar(true);
        return view;
    }

    private int columnas() {
        Resources resources = getResources();
        float anchoDp = resources.getConfiguration().screenWidthDp;
        if (anchoDp <= 0) {
            DisplayMetrics dm = resources.getDisplayMetrics();
            anchoDp = dm.widthPixels / dm.density;
        }
        // Pósteres legibles en móvil; más columnas al crecer la ventana/tablet.
        int cols = (int) ((anchoDp + 12f) / 160f);
        return Math.max(2, Math.min(6, cols));
    }

    private void cambiarTipo(String tipo) {
        if (tipo.equals(tipoActual)) return;
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
            busqueda = editBuscar.getText().toString().trim();
        }
        tipoActual = tipo;
        pintarFiltros();
        recycler.scrollToPosition(0);
        cargar(true);
    }

    private void pintarFiltros() {
        pintarFiltro(btnTodos, PelisItem.TIPO_TODOS.equals(tipoActual));
        pintarFiltro(btnPeliculas, PelisItem.TIPO_PELICULA.equals(tipoActual));
        pintarFiltro(btnSeries, PelisItem.TIPO_SERIE.equals(tipoActual));
    }

    private void pintarFiltro(TextView button, boolean seleccionado) {
        if (button == null) return;
        button.setBackgroundResource(seleccionado
                ? R.drawable.bg_pelis_filter_selected
                : R.drawable.bg_pelis_filter);
        button.setTextColor(getResources().getColor(
                seleccionado ? R.color.pelis_text : R.color.pelis_muted, requireContext().getTheme()));
        button.setTypeface(android.graphics.Typeface.DEFAULT,
                seleccionado ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private void programarBusqueda(String texto) {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        if (texto.equals(busqueda)) return;
        pendingSearch = () -> {
            pendingSearch = null;
            if (!isAdded() || texto.equals(busqueda)) return;
            busqueda = texto;
            pagina = 1;
            if (recycler != null) recycler.scrollToPosition(0);
            cargar(true);
        };
        searchHandler.postDelayed(pendingSearch, BUSQUEDA_DEBOUNCE_MS);
    }

    private void buscar(String texto) {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        String nuevaBusqueda = texto == null ? "" : texto.trim();
        ocultarTeclado();
        if (nuevaBusqueda.equals(busqueda)) {
            if (recycler != null) recycler.scrollToPosition(0);
            return;
        }
        busqueda = nuevaBusqueda;
        pagina = 1;
        if (recycler != null) recycler.scrollToPosition(0);
        cargar(true);
    }

    private void ocultarTeclado() {
        View foco = getActivity() != null ? getActivity().getCurrentFocus() : null;
        if (foco == null || getContext() == null) return;
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(foco.getWindowToken(), 0);
    }

    private void actualizarCabecera() {
        if (txtResultados == null || txtConteo == null) return;

        if (!busqueda.isEmpty()) {
            txtResultados.setText("Resultados para “" + busqueda + "”");
        } else if (PelisItem.TIPO_PELICULA.equals(tipoActual)) {
            txtResultados.setText("Películas recientes");
        } else if (PelisItem.TIPO_SERIE.equals(tipoActual)) {
            txtResultados.setText("Series recientes");
        } else {
            txtResultados.setText("Explorar catálogo");
        }

        if (cargando && totalResultados == 0) {
            txtConteo.setText("Buscando...");
        } else {
            String cantidad = NumberFormat.getIntegerInstance(new Locale("es", "PE"))
                    .format(totalResultados);
            txtConteo.setText(cantidad + (totalResultados == 1 ? " título" : " títulos"));
        }
    }

    /** @param reiniciar true = primera página (limpia la lista). */
    private void cargar(boolean reiniciar) {
        if (!isAdded() || adapter == null) return;

        if (reiniciar) {
            pagina = 1;
            hayMas = true;
            totalResultados = 0;
            adapter.limpiar();
            txtVacio.setVisibility(View.GONE);
            progress.setVisibility(View.VISIBLE);
        } else {
            progressPie.setVisibility(View.VISIBLE);
        }

        errorActual = false;
        cargando = true;
        actualizarCabecera();

        final int paginaSolicitada = pagina;
        final long solicitud = ++requestId;
        PelisApi.catalogoPagina(tipoActual, paginaSolicitada, busqueda,
                new PelisApi.Callback<PelisApi.PaginaCatalogo>() {
                    @Override
                    public void onOk(PelisApi.PaginaCatalogo data) {
                        if (!isAdded() || recycler == null || solicitud != requestId) return;
                        cargando = false;
                        progress.setVisibility(View.GONE);
                        progressPie.setVisibility(View.GONE);
                        errorActual = false;

                        if (data == null || data.items == null) {
                            mostrarError("No se pudo leer el catálogo. Toca para reintentar.");
                            return;
                        }

                        totalResultados = data.total;
                        if (data.items.isEmpty()) {
                            hayMas = false;
                            if (adapter.total() == 0) {
                                txtVacio.setText(busqueda.isEmpty()
                                        ? "No hay contenido disponible por ahora."
                                        : "Sin resultados para “" + busqueda + "”.\nPrueba con otro título.");
                                txtVacio.setVisibility(View.VISIBLE);
                            }
                            actualizarCabecera();
                            return;
                        }

                        adapter.agregar(data.items);
                        hayMas = paginaSolicitada * PelisApi.POR_PAGINA < totalResultados;
                        actualizarCabecera();
                    }

                    @Override
                    public void onError(String mensaje) {
                        if (!isAdded() || recycler == null || solicitud != requestId) return;
                        cargando = false;
                        progress.setVisibility(View.GONE);
                        progressPie.setVisibility(View.GONE);
                        Log.e(TAG, "Error: " + mensaje);

                        if (adapter.total() == 0) {
                            mostrarError("No se pudo cargar el catálogo.\nToca aquí para reintentar.");
                        } else {
                            Toast.makeText(requireContext(), "No se pudieron cargar más títulos.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
    }

    private void mostrarError(String mensaje) {
        errorActual = true;
        txtVacio.setText(mensaje);
        txtVacio.setVisibility(View.VISIBLE);
        txtVacio.setOnClickListener(v -> {
            if (errorActual) cargar(true);
        });
        actualizarCabecera();
    }

    private void abrirDetalle(PelisItem item) {
        Intent intent = new Intent(getContext(), PelisDetailActivity.class);
        intent.putExtra(PelisDetailActivity.EXTRA_ITEM, item);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        if (pendingSearch != null) {
            searchHandler.removeCallbacks(pendingSearch);
            pendingSearch = null;
        }
        requestId++;
        if (editBuscar != null && searchWatcher != null) {
            editBuscar.removeTextChangedListener(searchWatcher);
        }
        if (recycler != null) recycler.clearOnScrollListeners();

        editBuscar = null;
        btnBuscar = null;
        btnLimpiar = null;
        btnTodos = null;
        btnPeliculas = null;
        btnSeries = null;
        txtResultados = null;
        txtConteo = null;
        txtVacio = null;
        recycler = null;
        progress = null;
        progressPie = null;
        adapter = null;
        layoutManager = null;
        searchWatcher = null;
        super.onDestroyView();
    }
}
