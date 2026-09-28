package com.pelislatinohd.app.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.pelislatinohd.app.R;
import com.pelislatinohd.app.model.Movie;
import java.util.ArrayList;
import java.util.List;

public class MovieAdapter extends RecyclerView.Adapter<MovieAdapter.VH> {
    public interface OnClick { void onClick(Movie m); }
    private final List<Movie> data = new ArrayList<>();
    private final OnClick click;

    public MovieAdapter(OnClick c) { this.click = c; }

    public void set(List<Movie> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_movie, p, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Movie m = data.get(pos);
        h.title.setText(m.title);
        h.year.setText(String.valueOf(m.year > 0 ? m.year : ""));
        h.rating.setText(m.rating > 0 ? String.format("★ %.1f", m.rating) : "★ --");
        Glide.with(h.poster.getContext())
                .load(m.poster)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .into(h.poster);
        h.itemView.setOnClickListener(v -> click.onClick(m));
    }

    @Override public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView poster; TextView title, year, rating;
        VH(View v) {
            super(v);
            poster = v.findViewById(R.id.poster);
            title = v.findViewById(R.id.title);
            year = v.findViewById(R.id.year);
            rating = v.findViewById(R.id.rating);
        }
    }
}
