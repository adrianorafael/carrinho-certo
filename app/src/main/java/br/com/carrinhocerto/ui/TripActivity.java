package br.com.carrinhocerto.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.Money;
import br.com.carrinhocerto.data.Db;

/** Detalhe de uma compra do histórico. */
public class TripActivity extends AppCompatActivity {

    public static final String EXTRA_TRIP_ID = "trip_id";

    private Db db;
    private long tripId;
    private ItemAdapter adapter;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_trip);
        db = Db.get(this);
        tripId = getIntent().getLongExtra(EXTRA_TRIP_ID, 0);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        tb.inflateMenu(R.menu.trip);
        tb.setOnMenuItemClickListener(mi -> {
            int id = mi.getItemId();
            Db.Trip t = db.trip(tripId);
            if (t == null) return true;
            if (id == R.id.menu_receipt) {
                startActivity(new Intent(this, ReceiptActivity.class).putExtra(ReceiptActivity.EXTRA_TRIP_ID, tripId));
            } else if (id == R.id.menu_rename) {
                Dialogs.askStore(this, "Nome do mercado", null, t.store, "Salvar", name -> { db.setStore(tripId, name); load(); });
            } else if (id == R.id.menu_reopen) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Reabrir compra?")
                        .setMessage("Ela volta a ser o carrinho atual para você continuar adicionando itens. O carrinho atual (se tiver itens) vai para o histórico.")
                        .setPositiveButton("Reabrir", (d, w) -> {
                            db.reopenTrip(tripId);
                            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                            finish();
                        })
                        .setNegativeButton("Cancelar", null).show();
            } else if (id == R.id.menu_delete) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Excluir compra?")
                        .setMessage("Esta compra e as fotos dela serão apagadas. Não dá para desfazer.")
                        .setPositiveButton("Excluir", (d, w) -> { db.deleteTrip(tripId); finish(); })
                        .setNegativeButton("Cancelar", null).show();
            }
            return true;
        });

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemAdapter(false, new ItemAdapter.Listener() {
            @Override public void onClick(Db.Item item) {
                String p = item.photoPath != null ? item.photoPath : item.labelPhotoPath;
                if (p != null) startActivity(new Intent(TripActivity.this, PhotoActivity.class).putExtra(PhotoActivity.EXTRA_PATH, p));
            }
            @Override public void onQtyChange(Db.Item item, double q) { }
        });
        list.setAdapter(adapter);
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        Db.Trip t = db.trip(tripId);
        if (t == null) { finish(); return; }
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle(t.store == null ? "Compra" : t.store);
        Locale br = Locale.forLanguageTag("pt-BR");
        String when = new SimpleDateFormat("EEEE, dd/MM/yyyy 'às' HH:mm", br).format(new Date(t.finishedAt > 0 ? t.finishedAt : t.createdAt));
        Long diff = t.checked ? db.receiptDiff(tripId) : null;
        String checked = diff == null ? "" : "\nNota conferida: " + (diff == 0 ? "sem diferença" : (diff > 0 ? "+" : "−") + Money.format(Math.abs(diff)));
        ((TextView) findViewById(R.id.info)).setText(when + "\n" + t.itemCount + (t.itemCount == 1 ? " item" : " itens") + checked);
        ((TextView) findViewById(R.id.total)).setText(Money.format(t.totalCents));
        adapter.submit(db.items(tripId));
    }
}
