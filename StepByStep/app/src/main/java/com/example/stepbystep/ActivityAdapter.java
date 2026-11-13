package com.example.stepbystep;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ActivityAdapter extends RecyclerView.Adapter<ActivityAdapter.VH> {

    public interface OnItemClickListener {
        void onItemClick(SessionRecord record);
    }

    private final List<SessionRecord> items = new ArrayList<>();
    private final OnItemClickListener listener;

    // ใช้ Locale.ENGLISH เพื่อให้ได้รูปแบบเช่น "29 Oct 18:57" และปี ค.ศ. เสมอ
    private final SimpleDateFormat titleFormat =
            new SimpleDateFormat("dd MMM HH:mm", Locale.ENGLISH);
    private final SimpleDateFormat fullFormat =
            new SimpleDateFormat("dd/MM/yyyy • HH:mm", Locale.ENGLISH);

    public ActivityAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void submit(List<SessionRecord> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_activity, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SessionRecord r = items.get(position);
        Date start = new Date(r.startTime);

        h.tvTitle.setText(titleFormat.format(start));
        h.tvDateFull.setText(fullFormat.format(start));

        long totalSec = r.durationMs / 1000;
        android.content.Context context = h.itemView.getContext();
        String steps = context.getString(R.string.format_steps, String.valueOf(r.steps));
        String shakes = context.getString(R.string.format_shakes, String.valueOf(r.shakes));
        String duration = String.format(Locale.getDefault(), "%02d:%02d",
                totalSec / 60, totalSec % 60);
        String rate = context.getString(R.string.format_steps,
                String.format(Locale.getDefault(), "%.1f", r.stepsPerMin)) + "/min";
        h.tvDetails.setText(steps + " • " + shakes + " • " + duration + " • " + rate);


        h.badgeNewStep.setVisibility(r.isNewStepRecord ? View.VISIBLE : View.GONE);
        h.badgeNewShake.setVisibility(r.isNewShakeRecord ? View.VISIBLE : View.GONE);


        h.card.setOnClickListener(v -> {
            int clickedPosition = h.getAdapterPosition();
            if (listener != null && clickedPosition != RecyclerView.NO_POSITION) {
                listener.onItemClick(items.get(clickedPosition));
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final View card, badgeNewStep, badgeNewShake;
        final TextView tvTitle, tvDateFull, tvDetails;

        VH(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.cardHistoryItem);
            tvTitle = itemView.findViewById(R.id.tvHistoryTitle);
            tvDateFull = itemView.findViewById(R.id.tvHistoryDateFull);
            tvDetails = itemView.findViewById(R.id.tvHistoryDetails);
            badgeNewStep = itemView.findViewById(R.id.badgeNewStep);
            badgeNewShake = itemView.findViewById(R.id.badgeNewShake);
        }
    }
}