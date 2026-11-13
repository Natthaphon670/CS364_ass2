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
        h.tvDetails.setText(String.format(Locale.getDefault(),
                "%d steps • %d shakes • %02d:%02d • %.1f steps/min",
                r.steps, r.shakes, totalSec / 60, totalSec % 60, r.stepsPerMin));

        // layout ตั้งค่าเริ่มต้นเป็น visible จึงต้องกำหนดทุกครั้ง
        h.badgeNewStep.setVisibility(r.isNewStepRecord ? View.VISIBLE : View.GONE);
        h.badgeNewShake.setVisibility(r.isNewShakeRecord ? View.VISIBLE : View.GONE);

        h.card.setOnClickListener(v -> listener.onItemClick(r));
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