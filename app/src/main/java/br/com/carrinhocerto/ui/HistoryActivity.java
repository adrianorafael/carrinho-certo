package br.com.carrinhocerto.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.Money;
import br.com.carrinhocerto.data.Db;

/** Histórico por mês, com total do mês, resumo por mercado e filtro por estabelecimento. */
public class HistoryActivity extends AppCompatActivity {

    private static final Locale BR = Locale.forLanguageTag("pt-BR");
    private static final String NO_STORE = "Sem nome";

    private Db db;
    private final Calendar month = Calendar.getInstance();
    private String storeFilter; // null = todos
    private final List<Db.Trip> trips = new ArrayList<>();
    private Adapter adapter;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_history);
        db = Db.get(this);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        list.setAdapter(adapter);
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        trips.clear();
        trips.addAll(db.finishedTrips(month.get(Calendar.YEAR), month.get(Calendar.MONTH), null));
        adapter.notifyDataSetChanged();
    }

    private List<Db.Trip> visible() {
        if (storeFilter == null) return trips;
        List<Db.Trip> out = new ArrayList<>();
        for (Db.Trip t : trips) if (storeFilter.equals(label(t))) out.add(t);
        return out;
    }

    private static String label(Db.Trip t) { return t.store == null ? NO_STORE : t.store; }

    private void bindHeader(View h) {
        String m = new SimpleDateFormat("MMMM 'de' yyyy", BR).format(month.getTime());
        ((TextView) h.findViewById(R.id.month)).setText(Character.toUpperCase(m.charAt(0)) + m.substring(1));
        h.findViewById(R.id.prev).setOnClickListener(v -> { month.add(Calendar.MONTH, -1); storeFilter = null; load(); });
        h.findViewById(R.id.next).setOnClickListener(v -> { month.add(Calendar.MONTH, 1); storeFilter = null; load(); });

        List<Db.Trip> vis = visible();
        long total = 0;
        for (Db.Trip t : vis) total += t.totalCents;
        ((TextView) h.findViewById(R.id.summaryLabel)).setText(storeFilter == null ? "TOTAL GASTO NO MÊS" : "TOTAL EM " + storeFilter.toUpperCase(BR));
        ((TextView) h.findViewById(R.id.monthTotal)).setText(Money.format(total));
        int n = vis.size();
        ((TextView) h.findViewById(R.id.monthStats)).setText(n == 0 ? "Nenhuma compra"
                : n + (n == 1 ? " compra" : " compras") + " · média de " + Money.format(total / n) + " por compra");

        // resumo por mercado (apenas quando "Todos")
        LinearLayout byStore = h.findViewById(R.id.byStore);
        byStore.removeAllViews();
        Map<String, Long> sums = new LinkedHashMap<>();
        for (Db.Trip t : trips) sums.merge(label(t), t.totalCents, Long::sum);
        if (storeFilter == null && sums.size() > 1) {
            List<Map.Entry<String, Long>> e = new ArrayList<>(sums.entrySet());
            e.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> en : e) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                TextView a = new TextView(this);
                a.setText(en.getKey());
                a.setTextColor(0xE6FFFFFF);
                a.setTextSize(14);
                a.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                TextView b = new TextView(this);
                b.setText(Money.format(en.getValue()));
                b.setTextColor(0xFFFFFFFF);
                b.setTextSize(14);
                b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
                row.addView(a);
                row.addView(b);
                row.setPadding(0, Dialogs.dp(this, 2), 0, Dialogs.dp(this, 2));
                byStore.addView(row);
            }
        }

        // filtro por mercado
        ChipGroup chips = h.findViewById(R.id.storeChips);
        chips.setOnCheckedStateChangeListener(null);
        chips.removeAllViews();
        h.findViewById(R.id.storeScroll).setVisibility(sums.size() > 1 || storeFilter != null ? View.VISIBLE : View.GONE);
        addChip(chips, "Todos", null);
        for (String st : sums.keySet()) addChip(chips, st, st);
        h.findViewById(R.id.emptyMonth).setVisibility(trips.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void addChip(ChipGroup g, String text, String value) {
        Chip c = (Chip) getLayoutInflater().inflate(R.layout.chip_choice, g, false);
        c.setText(text);
        c.setChecked(value == null ? storeFilter == null : value.equals(storeFilter));
        c.setOnClickListener(v -> {
            storeFilter = value;
            adapter.notifyDataSetChanged();
        });
        g.addView(c);
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int p) { return p == 0 ? 0 : 1; }
        @Override public int getItemCount() { return 1 + visible().size(); }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
            View v = LayoutInflater.from(p.getContext()).inflate(type == 0 ? R.layout.history_header : R.layout.trip_row, p, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            if (pos == 0) { bindHeader(h.itemView); return; }
            Db.Trip t = visible().get(pos - 1);
            View v = h.itemView;
            Date d = new Date(t.finishedAt);
            ((TextView) v.findViewById(R.id.day)).setText(new SimpleDateFormat("dd", BR).format(d));
            ((TextView) v.findViewById(R.id.weekday)).setText(new SimpleDateFormat("EEE", BR).format(d).replace(".", ""));
            ((TextView) v.findViewById(R.id.store)).setText(label(t));
            ((TextView) v.findViewById(R.id.info)).setText(new SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", BR).format(d)
                    + " · " + t.itemCount + (t.itemCount == 1 ? " item" : " itens"));
            ((TextView) v.findViewById(R.id.total)).setText(Money.format(t.totalCents));
            v.setOnClickListener(x -> startActivity(new Intent(HistoryActivity.this, TripActivity.class).putExtra(TripActivity.EXTRA_TRIP_ID, t.id)));
        }
    }
}
