package dza.folbol.BLABONGO;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class AppIntroAdapter extends RecyclerView.Adapter<AppIntroAdapter.ViewHolder> {

    private int[] slideImages;
    private String[] slideTitles;
    private String[] slideDescriptions;

    public AppIntroAdapter(int[] slideImages, String[] slideTitles, String[] slideDescriptions) {
        this.slideImages = slideImages;
        this.slideTitles = slideTitles;
        this.slideDescriptions = slideDescriptions;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.slide_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.imageView.setImageResource(slideImages[position]);
        holder.titleText.setText(slideTitles[position]);
        holder.descriptionText.setText(slideDescriptions[position]);
    }

    @Override
    public int getItemCount() {
        return slideTitles.length;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imageView;
        TextView titleText;
        TextView descriptionText;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imageView = itemView.findViewById(R.id.slideImage);
            titleText = itemView.findViewById(R.id.slideTitle);
            descriptionText = itemView.findViewById(R.id.slideDescription);
        }
    }
}
