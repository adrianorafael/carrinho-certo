package br.com.carrinhocerto.ui;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.Money;
import br.com.carrinhocerto.data.Db;

/** Tela principal: o carrinho da compra em andamento, com o total sempre visível. */
public class MainActivity extends AppCompatActivity {

    private Db db;
    private Db.Trip trip;
    private ItemAdapter adapter;
    private TextView total, count, storeName, listName;
    private View empty, root;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_main);
        db = Db.get(this);
        root = findViewById(R.id.root);
        total = findViewById(R.id.total);
        count = findViewById(R.id.count);
        storeName = findViewById(R.id.storeName);
        listName = findViewById(R.id.listName);
        findViewById(R.id.listBar).setOnClickListener(v -> {
            if (trip.listId > 0 && db.list(trip.listId) != null) {
                startActivity(new Intent(this, ListActivity.class).putExtra(ListActivity.EXTRA_LIST_ID, trip.listId));
            } else {
                startActivity(new Intent(this, ListsActivity.class).putExtra(ListsActivity.EXTRA_PICK, true));
            }
        });
        empty = findViewById(R.id.empty);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.inflateMenu(R.menu.main);
        tb.setOnMenuItemClickListener(this::onMenu);

        RecyclerView list = findViewById(R.id.list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ItemAdapter(true, new ItemAdapter.Listener() {
            @Override public void onClick(Db.Item item) {
                startActivity(new Intent(MainActivity.this, ItemActivity.class).putExtra(ItemActivity.EXTRA_ITEM_ID, item.id));
            }
            @Override public void onQtyChange(Db.Item item, double newQty) {
                item.qty = newQty;
                db.save(item);
                refresh();
            }
        });
        list.setAdapter(adapter);
        attachSwipeToDelete(list);

        findViewById(R.id.btnScan).setOnClickListener(v ->
                startActivity(new Intent(this, ScanActivity.class).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_LABEL)));
        findViewById(R.id.btnManual).setOnClickListener(v -> startActivity(new Intent(this, ItemActivity.class)));
        findViewById(R.id.btnFinish).setOnClickListener(v -> finishTrip());
        findViewById(R.id.storeBar).setOnClickListener(v ->
                Dialogs.askStore(this, "Onde você está comprando?", null, trip.store, "Salvar", name -> {
                    db.setStore(trip.id, name);
                    refresh();
                }));
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        trip = db.openTrip();
        List<Db.Item> items = db.items(trip.id);
        adapter.submit(items);
        empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        total.setText(Money.format(trip.totalCents));
        double units = 0;
        for (Db.Item i : items) units += i.byWeight ? 1 : i.qty;
        int u = (int) Math.round(units);
        count.setText(items.size() == 0 ? "Nenhum item" : (items.size() + (items.size() == 1 ? " produto" : " produtos") + " · " + u + (u == 1 ? " unidade" : " unidades")));
        Db.ShoppingList list = trip.listId > 0 ? db.list(trip.listId) : null;
        if (list != null) {
            int inCart = 0, n = 0;
            for (Db.ListRow r : db.listRows(list.id, trip.id)) { n++; if (r.state == Db.IN_CART) inCart++; }
            listName.setText(list.name + " · " + inCart + " de " + n + " no carrinho");
            listName.setTextColor(getColor(R.color.blue));
        } else {
            listName.setText("Usar uma lista de compras");
            listName.setTextColor(getColor(R.color.gray));
        }
        if (trip.store != null) {
            storeName.setText(trip.store);
            storeName.setTextColor(getColor(R.color.ink));
        } else {
            storeName.setText("Toque para informar o mercado");
            storeName.setTextColor(getColor(R.color.gray));
        }
    }

    private boolean onMenu(MenuItem mi) {
        int id = mi.getItemId();
        if (id == R.id.menu_lists) {
            startActivity(new Intent(this, ListsActivity.class));
            return true;
        } else if (id == R.id.menu_history) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        } else if (id == R.id.menu_clear) {
            if (db.items(trip.id).isEmpty()) return true;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Esvaziar carrinho?")
                    .setMessage("Todos os itens desta compra serão removidos.")
                    .setPositiveButton("Esvaziar", (d, w) -> {
                        for (Db.Item i : db.items(trip.id)) { db.deleteItemRow(i.id); Db.deleteFiles(i); }
                        refresh();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
            return true;
        }
        return false;
    }

    private void finishTrip() {
        if (db.items(trip.id).isEmpty()) {
            Dialogs.error(root, "Adicione ao menos um item antes de finalizar.");
            return;
        }
        Dialogs.askStore(this, "Finalizar compra", "Total: " + Money.format(trip.totalCents) + "\nA compra vai para o histórico e um carrinho novo começa.",
                trip.store, "Finalizar", name -> {
                    db.setStore(trip.id, name);
                    db.finishTrip(trip.id);
                    refresh();
                    Dialogs.ok(root, "Compra salva no histórico ✓");
                });
    }

    private void attachSwipeToDelete(RecyclerView list) {
        ColorDrawable bg = new ColorDrawable(getColor(R.color.red));
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) { return false; }

            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                Db.Item removed = adapter.get(vh.getBindingAdapterPosition());
                db.deleteItemRow(removed.id);
                refresh();
                Snackbar sb = Snackbar.make(root, "\"" + removed.name + "\" removido", Snackbar.LENGTH_LONG)
                        .setAction("Desfazer", v -> { removed.id = 0; db.save(removed); refresh(); })
                        .setActionTextColor(getColor(R.color.white))
                        .addCallback(new Snackbar.Callback() {
                            @Override public void onDismissed(Snackbar t, int event) {
                                if (event != DISMISS_EVENT_ACTION) Db.deleteFiles(removed);
                            }
                        });
                sb.setBackgroundTint(getColor(R.color.ink));
                sb.show();
            }

            @Override public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh,
                                              float dX, float dY, int state, boolean active) {
                View v = vh.itemView;
                if (dX > 0) bg.setBounds(v.getLeft(), v.getTop(), v.getLeft() + (int) dX, v.getBottom());
                else bg.setBounds(v.getRight() + (int) dX, v.getTop(), v.getRight(), v.getBottom());
                bg.draw(c);
                super.onChildDraw(c, r, vh, dX, dY, state, active);
            }
        }).attachToRecyclerView(list);
    }
}
