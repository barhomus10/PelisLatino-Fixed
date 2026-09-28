package dza.folbol.BLABONGO;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import java.util.ArrayList;
import java.util.List;

public class BottomSheetOpcionesFragment extends BottomSheetDialogFragment {

    private static final String ARG_CANAL = "canal";
    private Canal canal;

    public static BottomSheetOpcionesFragment newInstance(Canal canal) {
        BottomSheetOpcionesFragment fragment = new BottomSheetOpcionesFragment();
        Bundle args = new Bundle();
        args.putSerializable(ARG_CANAL, canal); // Asegúrate de que Canal implemente Serializable o usa Parcelable
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.bottom_sheet_opciones_canal, container, false);

        if (getArguments() != null) {
            canal = (Canal) getArguments().getSerializable(ARG_CANAL);
        }

        if (canal == null) {
            dismiss();
            return view;
        }

        // Header
        ImageView iconoCanal = view.findViewById(R.id.iconoCanal);
        TextView tituloCanal = view.findViewById(R.id.tituloCanal);
        Glide.with(requireContext())
                .load(canal.image)
                .circleCrop()
                .into(iconoCanal);
        tituloCanal.setText(canal.title);

        // Lista de opciones
        RecyclerView recyclerOpciones = view.findViewById(R.id.recyclerOpciones);
        recyclerOpciones.setLayoutManager(new LinearLayoutManager(getContext()));

        if (canal.options != null && !canal.options.isEmpty()) {
            OpcionesAdapter adapter = new OpcionesAdapter(canal.options, opcion -> {
                if (opcion.url == null || opcion.url.isEmpty()) {
                    Toast.makeText(getContext(), "URL no disponible", Toast.LENGTH_SHORT).show();
                    return;
                }
                lanzarReproductor(opcion.url);
                dismiss(); // cerrar el bottom sheet al seleccionar
            });
            recyclerOpciones.setAdapter(adapter);
        } else {
            Toast.makeText(getContext(), "Sin opciones", Toast.LENGTH_SHORT).show();
            dismiss();
        }

        return view;
    }

    @OptIn(markerClass = UnstableApi.class)
    private void lanzarReproductor(String url) {
        String urlFinal = url.contains("https://belkaperu.github.io/") ? url :
                "https://belkaperu.github.io/belkafut/repro.html?r=" + extraerToken(url);
        Intent intent = new Intent(getActivity(), PlayerActivity.class);
        intent.putExtra("url_iframe_inicial", urlFinal);
        startActivity(intent);

        // 👇 Cerrar la actividad de canales para que el reproductor sea la única pantalla activa
        if (getActivity() != null) {

        }
    }

    private String extraerToken(String url) {
        if (url.contains("?r=")) return url.substring(url.indexOf("?r=") + 3);
        return url;
    }

    // Adaptador para las opciones
    private static class OpcionesAdapter extends RecyclerView.Adapter<OpcionesAdapter.ViewHolder> {

        private final List<Canal.OpcionCanal> opciones;
        private final OnOpcionClickListener listener;

        public interface OnOpcionClickListener {
            void onOpcionClick(Canal.OpcionCanal opcion);
        }

        public OpcionesAdapter(List<Canal.OpcionCanal> opciones, OnOpcionClickListener listener) {
            this.opciones = opciones;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_opcion_bottom_sheet, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Canal.OpcionCanal opcion = opciones.get(position);
            holder.textLabel.setText(opcion.label);
            holder.itemView.setOnClickListener(v -> listener.onOpcionClick(opcion));
        }

        @Override
        public int getItemCount() {
            return opciones.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textLabel;
            ViewHolder(View itemView) {
                super(itemView);
                textLabel = itemView.findViewById(R.id.textOpcionLabel);
            }
        }
    }
}