package dza.folbol.BLABONGO;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class PartidoAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_PARTIDO = 0;
    private static final int VIEW_TYPE_ANUNCIO = 1;

    private final List<Object> items;   // Partido o "ANUNCIO_MREC"
    private final Context context;
    private int expandedPosition = -1;

    public PartidoAdapter(List<Object> items, Context context) {
        this.items = items;
        this.context = context;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof Partido ? VIEW_TYPE_PARTIDO : VIEW_TYPE_ANUNCIO;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ANUNCIO) {
            View view = LayoutInflater.from(context).inflate(R.layout.item_ad_nativo, parent, false);
            return new AnuncioViewHolder(view);
        } else {
            View view = LayoutInflater.from(context).inflate(R.layout.item_partido, parent, false);
            return new PartidoViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof PartidoViewHolder) {
            Partido p = (Partido) items.get(position);
            PartidoViewHolder pHolder = (PartidoViewHolder) holder;
            pHolder.tvHora.setText(p.attributes.getHoraLocal());
            String desc = p.attributes.diary_description.replaceAll("^\\d{2}:\\d{2}\\s*", "");
            pHolder.tvDesc.setText(desc);

            boolean expanded = (position == expandedPosition);
            pHolder.channelsContainer.setVisibility(expanded ? View.VISIBLE : View.GONE);
            if (expanded) {
                pHolder.channelsContainer.removeAllViews();
                if (p.attributes.embeds != null && p.attributes.embeds.data != null) {
                    for (Partido.EmbedData embed : p.attributes.embeds.data) {
                        TextView channelView = new TextView(context);
                        channelView.setText(embed.attributes.embed_name);
                        channelView.setTextColor(0xFFFFFFFF);
                        channelView.setTextSize(16);
                        channelView.setPadding(0, 16, 0, 16);
                        channelView.setBackgroundResource(R.drawable.channel_item_bg);
                        channelView.setOnClickListener(v -> {
                            lanzarReproductor(embed.attributes.embed_iframe);
                        });
                        pHolder.channelsContainer.addView(channelView);
                    }
                }
            }

            pHolder.itemView.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (expandedPosition == pos) {
                    int old = expandedPosition;
                    expandedPosition = -1;
                    notifyItemChanged(old);
                } else {
                    int old = expandedPosition;
                    expandedPosition = pos;
                    if (old >= 0) notifyItemChanged(old);
                    notifyItemChanged(expandedPosition);
                }
            });

        } else if (holder instanceof AnuncioViewHolder) {
            // El MREC se carga automáticamente, no hay que hacer nada más
        }
    }

    @OptIn(markerClass = UnstableApi.class) private void lanzarReproductor(String iframeUrl) {
        String token = iframeUrl.contains("?r=") ? iframeUrl.substring(iframeUrl.indexOf("?r=") + 3) : iframeUrl;
        String urlFinal = "https://belkaperu.github.io/belkafut/repro.html?r=" + token;
        if (context instanceof android.app.Activity) {
            Intent intent = new Intent(context, PlayerActivity.class);
            intent.putExtra("url_iframe_inicial", urlFinal);
            context.startActivity(intent);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // ─── ViewHolders ───
    public static class PartidoViewHolder extends RecyclerView.ViewHolder {
        TextView tvDesc, tvHora;
        LinearLayout channelsContainer;
        public PartidoViewHolder(View itemView) {
            super(itemView);
            tvDesc = itemView.findViewById(R.id.textDescripcion);
            tvHora = itemView.findViewById(R.id.textHora);
            channelsContainer = itemView.findViewById(R.id.channelsContainer);
        }
    }

    public static class AnuncioViewHolder extends RecyclerView.ViewHolder {
        // El layout item_ad_nativo ya contiene un MREC que se carga solo
        public AnuncioViewHolder(View itemView) {
            super(itemView);
        }
    }
}