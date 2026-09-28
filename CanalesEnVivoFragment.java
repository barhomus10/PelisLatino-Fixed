package dza.folbol.BLABONGO;

import android.content.Context;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class CanalesEnVivoFragment extends Fragment {

    private static final String TAG = "CanalesEnVivo";
    private static final String URL_CANALES = "https://belkaperu.github.io/belkafut/data1.json";

    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private CanalAdapter adapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_canales_en_vivo, container, false);

        progressBar = view.findViewById(R.id.progressBarCanales);
        recyclerView = view.findViewById(R.id.recyclerViewCanales);

        if (recyclerView != null) {
            // Cuadrícula adaptativa (mínimo 2 columnas)
            int columnas = calcularNumeroDeColumnas(requireContext());
            GridLayoutManager gridLayoutManager = new GridLayoutManager(getContext(), columnas);

            // ⚡ NUEVO: Configurar aquí para que el anuncio ocupe toda la fila
            gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    if (adapter != null) {
                        // 1 es el VIEW_TYPE_ANUNCIO definido en tu CanalAdapter
                        if (adapter.getItemViewType(position) == 1) {
                            return columnas; // El anuncio ocupa todo el ancho disponible
                        }
                    }
                    return 1; // Los canales normales ocupan solo 1 columna
                }
            });

            recyclerView.setLayoutManager(gridLayoutManager);
        }

        cargarCanales();
        return view;
    }

    /**
     * Calcula cuántas columnas mostrar según el ancho de la pantalla.
     * Mínimo 2, máximo 4 (ajustable).
     */
    private int calcularNumeroDeColumnas(Context context) {
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        float dpAncho = displayMetrics.widthPixels / displayMetrics.density;
        int anchoMinimoColumnaDp = 150; // ancho deseado para cada columna (dp)
        int columnas = (int) (dpAncho / anchoMinimoColumnaDp);
        if (columnas < 2) columnas = 2;
        if (columnas > 4) columnas = 4; // opcional, evita demasiadas columnas en tablets
        return columnas;
    }

    private void cargarCanales() {
        mostrarCarga(true);
        OkHttpClient client = new OkHttpClient();

        Request request = new Request.Builder().url(URL_CANALES).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Error de red: " + e.getMessage());
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        mostrarCarga(false);
                        Toast.makeText(getContext(), "Error de conexión", Toast.LENGTH_SHORT).show();
                    });
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    Log.e(TAG, "Código HTTP: " + response.code());
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            mostrarCarga(false);
                            Toast.makeText(getContext(), "Error del servidor", Toast.LENGTH_SHORT).show();
                        });
                    }
                    return;
                }

                String json = response.body().string();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        mostrarCarga(false);
                        List<Canal> canales = null;
                        try {
                            Canal.RespuestaCanales respuesta = new Gson().fromJson(json, Canal.RespuestaCanales.class);
                            if (respuesta != null && respuesta.canales != null) {
                                canales = respuesta.canales;
                            }
                        } catch (Exception e) {
                            // fallback por si el JSON fuera array directo
                            try {
                                canales = new Gson().fromJson(json, new TypeToken<List<Canal>>(){}.getType());
                            } catch (Exception ignored) {}
                        }

                        if (canales != null && !canales.isEmpty()) {
                            // ⚡ Construir lista mixta: canal + anuncio cada 30 elementos
                            List<Object> items = new ArrayList<>();
                            for (int i = 0; i < canales.size(); i++) {
                                items.add(canales.get(i));
                                // Aquí se cambió a 30
                                if ((i + 1) % 30 == 0 && i != canales.size() - 1) {
                                    items.add("ANUNCIO");   // el adaptador lo reconoce y muestra un MREC
                                }
                            }

                            adapter = new CanalAdapter(items, getContext());
                            if (recyclerView != null) {
                                recyclerView.setAdapter(adapter);
                            }
                        } else {
                            Toast.makeText(getContext(), "No hay canales disponibles", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void mostrarCarga(boolean mostrar) {
        if (progressBar != null) {
            progressBar.setVisibility(mostrar ? View.VISIBLE : View.GONE);
        }
    }
}