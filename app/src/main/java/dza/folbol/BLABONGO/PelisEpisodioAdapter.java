package dza.folbol.BLABONGO;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Lista de episodios de una temporada. */
public class PelisEpisodioAdapter extends RecyclerView.Adapter<PelisEpisodioAdapter.EpisodioViewHolder> {

    public interface OnEpisodioClick {
        void onClick(PelisItem.Episodio episodio);
    }

    private final List<PelisItem.Episodio> items = new ArrayList<>();
    private final Context context;
    private final OnEpisodioClick listener;

    public PelisEpisodioAdapter(Context context, OnEpisodioClick listener) {
        this.context = context;
        this.listener = listener;
    }

    public void mostrar(List<PelisItem.Episodio> episodios) {
        items.clear();
        if (episodios != null) items.addAll(episodios);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public EpisodioViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_pelis_episodio, parent, false);
        return new EpisodioViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull EpisodioViewHolder h, int position) {
        PelisItem.Episodio ep = items.get(position);
        h.txtNumero.setText("E" + ep.episodio);
        h.txtTitulo.setText(ep.titulo.isEmpty() ? ("Episodio " + ep.episodio) : ep.titulo);
        h.txtFecha.setText(ep.fecha);
        h.txtFecha.setVisibility(ep.fecha.isEmpty() ? View.GONE : View.VISIBLE);

        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(ep);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    public static class EpisodioViewHolder extends RecyclerView.ViewHolder {
        final TextView txtNumero;
        final TextView txtTitulo;
        final TextView txtFecha;

        public EpisodioViewHolder(@NonNull View itemView) {
            super(itemView);
            txtNumero = itemView.findViewById(R.id.txtEpisodioNumero);
            txtTitulo = itemView.findViewById(R.id.txtEpisodioTitulo);
            txtFecha = itemView.findViewById(R.id.txtEpisodioFecha);
        }
    }
}
