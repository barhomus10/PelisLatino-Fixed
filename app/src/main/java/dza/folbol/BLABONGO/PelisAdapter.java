package dza.folbol.BLABONGO;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cuadrícula de pósters estilo catálogo PelisLatinoHD. */
public class PelisAdapter extends RecyclerView.Adapter<PelisAdapter.ItemViewHolder> {

    public interface OnItemClick {
        void onClick(PelisItem item);
    }

    private final List<PelisItem> items = new ArrayList<>();
    private final Context context;
    private final OnItemClick listener;

    public PelisAdapter(Context context, OnItemClick listener) {
        this.context = context;
        this.listener = listener;
    }

    public void agregar(List<PelisItem> nuevos) {
        if (nuevos == null || nuevos.isEmpty()) return;
        int desde = items.size();
        items.addAll(nuevos);
        notifyItemRangeInserted(desde, nuevos.size());
    }

    public void limpiar() {
        int n = items.size();
        items.clear();
        if (n > 0) notifyItemRangeRemoved(0, n);
    }

    public int total() {
        return items.size();
    }

    @NonNull
    @Override
    public ItemViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_pelis, parent, false);
        return new ItemViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ItemViewHolder h, int position) {
        PelisItem item = items.get(position);
        h.txtTitulo.setText(item.titulo);
        h.imgPoster.setContentDescription("Póster de " + item.titulo);

        String generos = item.generosTexto();
        h.txtSubtitulo.setText(generos);
        h.txtSubtitulo.setVisibility(TextUtils.isEmpty(generos) ? View.GONE : View.VISIBLE);

        if (item.rating > 0) {
            h.txtRating.setText(String.format(Locale.US, "★ %.1f", item.rating));
            h.txtRating.setVisibility(View.VISIBLE);
        } else {
            h.txtRating.setVisibility(View.GONE);
        }

        if (!TextUtils.isEmpty(item.anio)) {
            h.txtAnio.setText(item.anio);
            h.txtAnio.setVisibility(View.VISIBLE);
        } else {
            h.txtAnio.setVisibility(View.GONE);
        }

        h.txtTipo.setText(item.esSerie() ? "SERIE" : "HD");

        String poster = !TextUtils.isEmpty(item.poster) ? item.poster : item.backdrop;
        Glide.with(h.imgPoster)
                .load(poster)
                .placeholder(R.drawable.bg_pelis_poster_placeholder)
                .error(R.drawable.bg_pelis_poster_placeholder)
                .centerCrop()
                .into(h.imgPoster);

        // Mantiene la proporción 2:3 de los pósteres en cualquier ancho de columna.
        h.imgPoster.post(() -> {
            if (h.getBindingAdapterPosition() == RecyclerView.NO_POSITION) return;
            int width = h.imgPoster.getWidth();
            if (width <= 0) return;
            int height = Math.round(width * 1.5f);
            ViewGroup.LayoutParams params = h.imgPoster.getLayoutParams();
            if (params.height != height) {
                params.height = height;
                h.imgPoster.setLayoutParams(params);
            }
        });

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class ItemViewHolder extends RecyclerView.ViewHolder {
        final ImageView imgPoster;
        final TextView txtTitulo;
        final TextView txtSubtitulo;
        final TextView txtRating;
        final TextView txtTipo;
        final TextView txtAnio;

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            imgPoster = itemView.findViewById(R.id.imgPelisPoster);
            txtTitulo = itemView.findViewById(R.id.txtPelisTitulo);
            txtSubtitulo = itemView.findViewById(R.id.txtPelisSubtitulo);
            txtRating = itemView.findViewById(R.id.txtPelisRating);
            txtTipo = itemView.findViewById(R.id.txtPelisTipo);
            txtAnio = itemView.findViewById(R.id.txtPelisAnio);
        }
    }
}
