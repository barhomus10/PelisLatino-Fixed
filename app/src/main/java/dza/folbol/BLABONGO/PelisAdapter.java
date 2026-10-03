package dza.folbol.BLABONGO;

import android.content.Context;
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

/** Cuadrícula de pósters del catálogo. */
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
        h.txtSubtitulo.setText(item.subtitulo());

        Glide.with(context)
                .load(item.poster.isEmpty() ? item.backdrop : item.poster)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_placeholder)
                .centerCrop()
                .into(h.imgPoster);

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

        public ItemViewHolder(@NonNull View itemView) {
            super(itemView);
            imgPoster = itemView.findViewById(R.id.imgPelisPoster);
            txtTitulo = itemView.findViewById(R.id.txtPelisTitulo);
            txtSubtitulo = itemView.findViewById(R.id.txtPelisSubtitulo);
        }
    }
}
