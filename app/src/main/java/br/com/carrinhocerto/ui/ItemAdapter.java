package br.com.carrinhocerto.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.Money;
import br.com.carrinhocerto.data.Db;

public final class ItemAdapter extends RecyclerView.Adapter<ItemAdapter.VH> {

    public interface Listener {
        void onClick(Db.Item item);
        void onQtyChange(Db.Item item, double newQty);
    }

    private final List<Db.Item> items = new ArrayList<>();
    private final boolean editable;
    private final Listener listener;

    public ItemAdapter(boolean editable, Listener listener) {
        this.editable = editable;
        this.listener = listener;
    }

    public void submit(List<Db.Item> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public Db.Item get(int pos) { return items.get(pos); }

    @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
        return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_row, p, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        Db.Item it = items.get(pos);
        h.name.setText(it.name);
        String unit = it.byWeight ? " kg" : (it.qty == 1 ? " un" : " un");
        h.detail.setText(Money.formatQty(it.qty) + unit + " × " + Money.format(it.unitCents) + (it.byWeight ? "/kg" : ""));
        h.subtotal.setText(Money.format(it.subtotal()));
        h.qty.setText(Money.formatQty(it.qty));

        String photo = it.photoPath != null ? it.photoPath : it.labelPhotoPath;
        if (photo != null) {
            h.thumb.setVisibility(View.VISIBLE);
            h.thumbIcon.setVisibility(View.GONE);
            ImageUtils.loadThumb(h.thumb, photo, 160);
        } else {
            h.thumb.setVisibility(View.GONE);
            h.thumbIcon.setVisibility(View.VISIBLE);
        }

        boolean showStepper = editable && !it.byWeight;
        h.stepper.setVisibility(showStepper ? View.VISIBLE : View.GONE);
        h.minus.setOnClickListener(v -> { if (it.qty > 1) listener.onQtyChange(it, it.qty - 1); });
        h.minus.setAlpha(it.qty > 1 ? 1f : 0.35f);
        h.plus.setOnClickListener(v -> listener.onQtyChange(it, it.qty + 1));
        h.itemView.setOnClickListener(v -> listener.onClick(it));
    }

    @Override public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name, detail, subtotal, qty;
        final ImageView thumb, thumbIcon;
        final ImageButton minus, plus;
        final View stepper;
        VH(View v) {
            super(v);
            name = v.findViewById(R.id.name);
            detail = v.findViewById(R.id.detail);
            subtotal = v.findViewById(R.id.subtotal);
            qty = v.findViewById(R.id.qty);
            thumb = v.findViewById(R.id.thumb);
            thumbIcon = v.findViewById(R.id.thumbIcon);
            minus = v.findViewById(R.id.minus);
            plus = v.findViewById(R.id.plus);
            stepper = v.findViewById(R.id.stepper);
        }
    }
}
