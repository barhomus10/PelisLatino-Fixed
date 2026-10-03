package dza.folbol.BLABONGO;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class AgendaDeportivaFragment extends Fragment {

    private static final String URL_AGENDA = "https://raw.githubusercontent.com/belkaperu/json/main/agenda_combinada.json";

    private ProgressBar progressBar;
    private PartidoAdapter adapter;
    private TextView txtTituloAgenda;

    // Lista mixta que se pasa al adaptador: contiene Partido o "ANUNCIO_MREC"
    private final List<Object> itemsParaAdapter = new ArrayList<>();
    // Respaldo con todos los partidos (para notificaciones y filtro)
    private final List<Partido> todosLosPartidos = new ArrayList<>();

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_agenda_deportiva, container, false);

        progressBar = view.findViewById(R.id.progressBar);
        txtTituloAgenda = view.findViewById(R.id.txtTituloAgenda);

        RecyclerView recyclerView = view.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new PartidoAdapter(itemsParaAdapter, getContext());
        recyclerView.setAdapter(adapter);

        configurarTituloFecha();
        cargarAgenda();

        return view;
    }

    private void configurarTituloFecha() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", new Locale("es", "ES"));
            String fecha = sdf.format(new Date());
            if (fecha != null && !fecha.isEmpty()) {
                fecha = Character.toUpperCase(fecha.charAt(0)) + fecha.substring(1);
            }
            txtTituloAgenda.setText(fecha);
        } catch (Exception e) {
            txtTituloAgenda.setText("");
        }
    }

    private void cargarAgenda() {
        progressBar.setVisibility(View.VISIBLE);

        Request request = new Request.Builder().url(URL_AGENDA).build();
        httpClient.newCall(request).enqueue(new Callback() {

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        Toast.makeText(getContext(), "Sin conexión. Verifica tu red e intenta de nuevo.", Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(getContext(), "Error al obtener la agenda", Toast.LENGTH_SHORT).show();
                        });
                    }
                    return;
                }

                String json = response.body().string();
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        progressBar.setVisibility(View.GONE);
                        try {
                            RespuestaApi api = new Gson().fromJson(json, RespuestaApi.class);
                            if (api != null && api.data != null && !api.data.isEmpty()) {
                                todosLosPartidos.clear();
                                todosLosPartidos.addAll(api.data);
                                filtrarPartidosPorHoy();
                                programarNotificacionesDePartidos();
                            } else {
                                Toast.makeText(getContext(), "No hay partidos en la agenda", Toast.LENGTH_SHORT).show();
                            }
                        } catch (Exception e) {
                            Toast.makeText(getContext(), "Error al procesar la agenda", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    private void filtrarPartidosPorHoy() {
        // 1. Obtener solo los partidos de hoy
        List<Partido> partidosHoy = new ArrayList<>();
        Calendar hoy = Calendar.getInstance();
        SimpleDateFormat sdfFecha = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        String hoyStr = sdfFecha.format(hoy.getTime());

        for (Partido p : todosLosPartidos) {
            if (p.attributes == null || p.attributes.diary_hour == null) continue;
            String rawHour = p.attributes.diary_hour.trim();

            if (!rawHour.contains("-")) {
                partidosHoy.add(p);
            } else {
                try {
                    String fechaStr = rawHour.contains("T")
                            ? rawHour.split("T")[0]
                            : rawHour.substring(0, 10);
                    if (fechaStr.equals(hoyStr)) {
                        partidosHoy.add(p);
                    }
                } catch (Exception e) {
                    partidosHoy.add(p);
                }
            }
        }

        Partido.ordenarPorHora(partidosHoy);

        // 2. Construir la lista mixta (partido + anuncio MREC cada 3)
        itemsParaAdapter.clear();
        for (int i = 0; i < partidosHoy.size(); i++) {
            itemsParaAdapter.add(partidosHoy.get(i));
            if ((i + 1) % 3 == 0 && i != partidosHoy.size() - 1) {
                itemsParaAdapter.add("ANUNCIO_MREC");
            }
        }

        adapter.notifyDataSetChanged();

        if (partidosHoy.isEmpty()) {
            Toast.makeText(getContext(), "No hay partidos programados para hoy", Toast.LENGTH_SHORT).show();
        }
    }

    private void programarNotificacionesDePartidos() {
        Context context = getContext();
        if (context == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null && !alarmManager.canScheduleExactAlarms()) {
                Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                startActivity(intent);
                Toast.makeText(context, "Concede el permiso para recibir alertas de partidos", Toast.LENGTH_LONG).show();
                return;
            }
        }

        NotificationScheduler.programarNotificaciones(getContext(), todosLosPartidos);
    }
}