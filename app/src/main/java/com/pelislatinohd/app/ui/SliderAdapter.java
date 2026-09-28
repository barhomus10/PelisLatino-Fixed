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

public class SliderAdapter extends RecyclerView.Adapter<SliderAdapter.VH> {
    public interface OnClick { void onClick(Movie m); }
    private final List<Movie> data = new ArrayList<>();
    private final OnClick click;
    public SliderAdapter(OnClick c){ this.click=c; }
    public void set(List<Movie> l){ data.clear(); if(l!=null) data.addAll(l); notifyDataSetChanged(); }
    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t){
        return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_slider, p,false));
    }
    @Override public void onBindViewHolder(@NonNull VH h,int pos){
        Movie m=data.get(pos);
        h.title.setText(m.title);
        h.desc.setText(m.synopsis != null ? m.synopsis : "");
        h.chip.setText(m.year + " • ★ " + m.rating);
        Glide.with(h.bg.getContext()).load(m.backdrop != null ? m.backdrop : m.poster).centerCrop().into(h.bg);
        h.itemView.setOnClickListener(v->click.onClick(m));
    }
    @Override public int getItemCount(){ return data.size(); }
    static class VH extends RecyclerView.ViewHolder{
        ImageView bg; TextView title,desc,chip;
        VH(View v){ super(v); bg=v.findViewById(R.id.bg); title=v.findViewById(R.id.title); desc=v.findViewById(R.id.desc); chip=v.findViewById(R.id.chip); }
    }
}
