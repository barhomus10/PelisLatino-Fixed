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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Catálogo de películas y series con búsqueda y paginación incremental. */
public class PelisFragment extends Fragment {

    private static final String TAG = "PelisFragment";
    private static final long BUSQUEDA_DEBOUNCE_MS = 320L;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());

    private EditText editBuscar;
    private ImageButton btnBuscar, btnLimpiar;
    private TextView btnTodos, btnPeliculas, btnSeries;
    private TextView btnFiltroGenero, btnFiltroPais, btnLimpiarFiltros;
    private TextView txtResultados, txtConteo, txtVacio;
    private RecyclerView recycler;
    private ProgressBar progress, progressPie;

    private PelisAdapter adapter;
    private GridLayoutManager layoutManager;
    private TextWatcher searchWatcher;
    private Runnable pendingSearch;

    private List<PelisApi.OpcionFiltro> opcionesGenero = Collections.emptyList();
    private List<PelisApi.OpcionFiltro> opcionesPais = Collections.emptyList();
    private String tipoActual = PelisItem.TIPO_TODOS;
    private String busqueda = "";
    private String nombreGenero = "";
    private String nombrePais = "";
    private int generoSeleccionado = 0;
    private int paisSeleccionado = 0;
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
        btnFiltroGenero = view.findViewById(R.id.btnFiltroGeneroPelis);
        btnFiltroPais = view.findViewById(R.id.btnFiltroPaisPelis);
        btnLimpiarFiltros = view.findViewById(R.id.btnLimpiarFiltrosPelis);
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
        btnFiltroGenero.setOnClickListener(v -> mostrarSelectorGenero());
        btnFiltroPais.setOnClickListener(v -> mostrarSelectorPais());
        btnLimpiarFiltros.setOnClickListener(v -> limpiarFiltros());
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
        cargarFiltros();
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
        pintarSelectores();
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

    private void pintarSelectores() {
        if (btnFiltroGenero == null || btnFiltroPais == null) return;
        String genero = generoSeleccionado == 0 ? "Género"
                : "Género: " + (nombreGenero.isEmpty() ? "Seleccionado" : nombreGenero);
        String pais = paisSeleccionado == 0 ? "País"
                : "País: " + (nombrePais.isEmpty() ? "Seleccionado" : nombrePais);
        btnFiltroGenero.setText(genero + "  ▾");
        btnFiltroPais.setText(pais + "  ▾");
        pintarFiltro(btnFiltroGenero, generoSeleccionado > 0);
        pintarFiltro(btnFiltroPais, paisSeleccionado > 0);
        if (btnLimpiarFiltros != null) {
            btnLimpiarFiltros.setVisibility(generoSeleccionado > 0 || paisSeleccionado > 0
                    ? View.VISIBLE : View.GONE);
        }
    }

    private void cargarFiltros() {
        PelisApi.filtrosCatalogo(new PelisApi.Callback<PelisApi.FiltrosCatalogo>() {
            @Override
            public void onOk(PelisApi.FiltrosCatalogo data) {
                if (!isAdded() || recycler == null || data == null) return;
                opcionesGenero = data.generos != null ? data.generos : Collections.emptyList();
                opcionesPais = data.paises != null ? data.paises : Collections.emptyList();
                nombreGenero = nombreOpcion(opcionesGenero, generoSeleccionado);
                nombrePais = nombreVisiblePais(nombreOpcion(opcionesPais, paisSeleccionado));
                pintarSelectores();
                actualizarCabecera();
            }

            @Override
            public void onError(String mensaje) {
                Log.w(TAG, "No se pudieron cargar géneros y países: " + mensaje);
            }
        });
    }

    private String nombreOpcion(List<PelisApi.OpcionFiltro> opciones, int id) {
        if (id <= 0 || opciones == null) return "";
        for (PelisApi.OpcionFiltro opcion : opciones) {
            if (opcion.id == id) return opcion.nombre;
        }
        return "";
    }

    private String nombreVisiblePais(String nombre) {
        return "United States of America".equals(nombre) ? "United States" : nombre;
    }

    private interface OnFiltroElegido {
        void elegir(@Nullable PelisApi.OpcionFiltro opcion);
    }

    private void mostrarSelectorGenero() {
        mostrarSelector("Género", "Todos los géneros", opcionesGenero,
                generoSeleccionado, opcion -> {
                    int nuevoId = opcion == null ? 0 : opcion.id;
                    if (nuevoId == generoSeleccionado) return;
                    generoSeleccionado = nuevoId;
                    nombreGenero = opcion == null ? "" : opcion.nombre;
                    actualizarFiltrosYRecargar();
                });
    }

    private void mostrarSelectorPais() {
        mostrarSelector("País", "Todos los países", opcionesPais,
                paisSeleccionado, opcion -> {
                    int nuevoId = opcion == null ? 0 : opcion.id;
                    if (nuevoId == paisSeleccionado) return;
                    paisSeleccionado = nuevoId;
                    nombrePais = opcion == null ? "" : nombreVisiblePais(opcion.nombre);
                    actualizarFiltrosYRecargar();
                });
    }

    private void mostrarSelector(String titulo, String todosLabel,
                                 List<PelisApi.OpcionFiltro> opciones, int seleccionado,
                                 OnFiltroElegido callback) {
        if (opciones == null || opciones.isEmpty()) {
            Toast.makeText(requireContext(), "La lista de filtros aún no está disponible.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        CharSequence[] etiquetas = new CharSequence[opciones.size() + 1];
        etiquetas[0] = todosLabel;
        int checked = seleccionado == 0 ? 0 : -1;
        for (int i = 0; i < opciones.size(); i++) {
            PelisApi.OpcionFiltro opcion = opciones.get(i);
            String nombre = "País".equals(titulo) ? nombreVisiblePais(opcion.nombre) : opcion.nombre;
            etiquetas[i + 1] = nombre;
            if (opcion.id == seleccionado) checked = i + 1;
        }

        new MaterialAlertDialogBuilder(requireContext(),
                R.style.ThemeOverlay_PelisLatino_AlertDialog)
                .setTitle("Seleccionar " + titulo.toLowerCase(Locale.ROOT))
                .setSingleChoiceItems(etiquetas, checked, (dialog, which) -> {
                    dialog.dismiss();
                    callback.elegir(which == 0 ? null : opciones.get(which - 1));
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void actualizarFiltrosYRecargar() {
        pintarSelectores();
        pagina = 1;
        if (recycler != null) recycler.scrollToPosition(0);
        cargar(true);
    }

    private void limpiarFiltros() {
        if (generoSeleccionado == 0 && paisSeleccionado == 0) return;
        generoSeleccionado = 0;
        paisSeleccionado = 0;
        nombreGenero = "";
        nombrePais = "";
        actualizarFiltrosYRecargar();
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
        } else if (generoSeleccionado > 0 || paisSeleccionado > 0) {
            StringBuilder titulo = new StringBuilder();
            if (generoSeleccionado > 0) {
                titulo.append("Género: ").append(nombreGenero.isEmpty() ? "Seleccionado" : nombreGenero);
            }
            if (paisSeleccionado > 0) {
                if (titulo.length() > 0) titulo.append(" · ");
                titulo.append("País: ").append(nombrePais.isEmpty() ? "Seleccionado" : nombrePais);
            }
            txtResultados.setText(titulo.toString());
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
                generoSeleccionado, paisSeleccionado,
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
                                if (!busqueda.isEmpty()) {
                                    txtVacio.setText("Sin resultados para “" + busqueda
                                            + "”.\nPrueba con otro título.");
                                } else if (generoSeleccionado > 0 || paisSeleccionado > 0) {
                                    txtVacio.setText("No encontramos títulos con esos filtros.\nPrueba otra combinación.");
                                } else {
                                    txtVacio.setText("No hay contenido disponible por ahora.");
                                }
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
        btnFiltroGenero = null;
        btnFiltroPais = null;
        btnLimpiarFiltros = null;
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
