package br.com.carrinhocerto.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.Money;
import br.com.carrinhocerto.data.Db;

/**
 * Uma lista de compras. Se ela estiver em uso na compra aberta, cada item mostra o estado
 * (pendente, no carrinho, não encontrado) e tocar num pendente abre a câmera já vinculada a ele.
 */
public class ListActivity extends AppCompatActivity {

    public static final String EXTRA_LIST_ID = "list_id";

    private Db db;
    private long listId;
    private Db.Trip trip;
    private boolean active;
    private final List<Db.ListRow> rows = new ArrayList<>();
    private RowAdapter adapter;
    private ItemTouchHelper dragger;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_list);
        db = Db.get(this);
        listId = getIntent().getLongExtra(EXTRA_LIST_ID, 0);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        tb.getMenu().add(0, 1, 0, "Ver carrinho");
        tb.getMenu().add(0, 2, 1, "Renomear lista");
        tb.getMenu().add(0, 3, 2, "Duplicar lista");
        tb.getMenu().add(0, 4, 3, "Parar de usar nesta compra");
        tb.getMenu().add(0, 5, 4, "Excluir lista");
        tb.setOnMenuItemClickListener(mi -> { onMenu(mi.getItemId()); return true; });

        RecyclerView rv = findViewById(R.id.list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter();
        rv.setAdapter(adapter);
        dragger = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override public boolean isLongPressDragEnabled() { return false; }
            @Override public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder a, @NonNull RecyclerView.ViewHolder b) {
                int from = a.getBindingAdapterPosition(), to = b.getBindingAdapterPosition();
                Collections.swap(rows, from, to);
                adapter.notifyItemMoved(from, to);
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int d) { }
            @Override public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder vh) {
                super.clearView(r, vh);
                List<Long> ids = new ArrayList<>();
                for (Db.ListRow row : rows) ids.add(row.item.id);
                db.reorderList(ids);
            }
        });
        dragger.attachToRecyclerView(rv);

        findViewById(R.id.btnUse).setOnClickListener(v -> { db.setTripList(db.openTrip().id, listId); refresh(); });
        TextInputEditText newName = findViewById(R.id.newName);
        newName.setOnEditorActionListener((v, a, e) -> { if (a == EditorInfo.IME_ACTION_DONE) { addItem(); return true; } return false; });
        findViewById(R.id.btnAdd).setOnClickListener(v -> addItem());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        Db.ShoppingList l = db.list(listId);
        if (l == null) { finish(); return; }
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle(l.name);
        trip = db.openTrip();
        active = trip.listId == listId;
        rows.clear();
        rows.addAll(db.listRows(listId, active ? trip.id : 0));
        if (active) {
            // durante a compra: prioritários pendentes primeiro, depois pendentes, não encontrados e, no fim, o que já está no carrinho
            List<Db.ListRow> sorted = new ArrayList<>(rows);
            Collections.sort(sorted, (a, b) -> {
                int ra = rank(a), rb = rank(b);
                return ra != rb ? Integer.compare(ra, rb) : Integer.compare(a.item.position, b.item.position);
            });
            rows.clear();
            rows.addAll(sorted);
        }
        adapter.notifyDataSetChanged();
        findViewById(R.id.empty).setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnUse).setVisibility(active ? View.GONE : View.VISIBLE);
        findViewById(R.id.summary).setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) bindSummary();
    }

    private static int rank(Db.ListRow r) {
        if (r.state == Db.PENDING) return r.item.priority ? 0 : 1;
        if (r.state == Db.MISSING) return 2;
        return 3;
    }

    private void bindSummary() {
        int inCart = 0, pending = 0, missing = 0, estimated = 0;
        long forecast = 0;
        for (Db.ListRow r : rows) {
            if (r.state == Db.IN_CART) inCart++;
            else if (r.state == Db.MISSING) missing++;
            else {
                pending++;
                if (r.last != null) { forecast += Money.times(r.last.cents, r.item.qty); estimated++; }
            }
        }
        long cart = trip.totalCents;
        forecast += cart;
        setCounter(R.id.cntTotal, rows.size(), "na lista");
        setCounter(R.id.cntCart, inCart, "no carrinho");
        setCounter(R.id.cntPending, pending, pending == 1 ? "pendente" : "pendentes");
        setCounter(R.id.cntMissing, missing, missing == 1 ? "não achado" : "não achados");
        LinearProgressIndicator pi = findViewById(R.id.progress);
        pi.setMax(Math.max(1, rows.size()));
        pi.setProgress(inCart + missing);
        ((TextView) findViewById(R.id.cartTotal)).setText(Money.format(cart));
        ((TextView) findViewById(R.id.forecast)).setText(Money.format(forecast));
        String note;
        if (pending == 0) note = "tudo resolvido";
        else if (estimated == pending) note = "estimado pelos últimos preços";
        else note = "estimado · " + (pending - estimated) + " sem preço anterior";
        ((TextView) findViewById(R.id.forecastNote)).setText(note);
    }

    private void setCounter(int id, int n, String label) {
        ((TextView) findViewById(id)).setText(n + "\n" + label);
    }

    private void addItem() {
        TextInputEditText newName = findViewById(R.id.newName);
        TextInputEditText newQty = findViewById(R.id.newQty);
        String n = String.valueOf(newName.getText()).trim();
        if (n.isEmpty()) { newName.setError("Digite o produto"); return; }
        double q = Money.parseQty(String.valueOf(newQty.getText()));
        Db.ListItem li = new Db.ListItem();
        li.listId = listId;
        li.name = Character.toUpperCase(n.charAt(0)) + n.substring(1);
        li.qty = q > 0 ? q : 1;
        db.saveListItem(li);
        newName.setText("");
        newQty.setText("1");
        refresh();
        RecyclerView rv = findViewById(R.id.list);
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).item.id == li.id) { rv.scrollToPosition(i); break; }
    }

    private void scan(Db.ListRow r) {
        if (!active) { db.setTripList(trip.id, listId); active = true; }
        startActivity(new Intent(this, ScanActivity.class)
                .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_LABEL)
                .putExtra(ScanActivity.EXTRA_LIST_ITEM_ID, r.item.id));
    }

    private void onMenu(int id) {
        Db.ShoppingList l = db.list(listId);
        switch (id) {
            case 1:
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                finish();
                break;
            case 2: askName("Renomear lista", l.name, n -> { db.renameList(listId, n); refresh(); }); break;
            case 3: askName("Duplicar lista", l.name + " (cópia)", n -> {
                long nid = db.duplicateList(listId, n);
                startActivity(new Intent(this, ListActivity.class).putExtra(EXTRA_LIST_ID, nid));
                finish();
            }); break;
            case 4: if (active) { db.setTripList(trip.id, 0); refresh(); } break;
            case 5:
                new MaterialAlertDialogBuilder(this).setTitle("Excluir \"" + l.name + "\"?")
                        .setMessage("A lista será apagada. Suas compras no histórico continuam.")
                        .setPositiveButton("Excluir", (d, w) -> { db.deleteList(listId); finish(); })
                        .setNegativeButton("Cancelar", null).show();
                break;
        }
    }

    private void askName(String title, String current, ListsActivity.OnName cb) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setText(current);
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        int p = Dialogs.dp(this, 24);
        box.setPadding(p, Dialogs.dp(this, 8), p, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
                .setPositiveButton("Salvar", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) cb.on(n);
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void editItem(Db.ListItem li) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Dialogs.dp(this, 24);
        box.setPadding(p, Dialogs.dp(this, 8), p, 0);
        EditText name = new EditText(this);
        name.setHint("Produto");
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setText(li.name);
        EditText qty = new EditText(this);
        qty.setHint("Quantidade");
        qty.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        qty.setKeyListener(android.text.method.DigitsKeyListener.getInstance("0123456789,."));
        qty.setText(Money.formatQty(li.qty));
        box.addView(name);
        box.addView(qty);
        new MaterialAlertDialogBuilder(this).setTitle("Editar item").setView(box)
                .setPositiveButton("Salvar", (d, w) -> {
                    String n = name.getText().toString().trim();
                    double q = Money.parseQty(qty.getText().toString());
                    if (!n.isEmpty()) li.name = n;
                    if (q > 0) li.qty = q;
                    db.saveListItem(li);
                    refresh();
                })
                .setNegativeButton("Cancelar", null).show();
    }

    private void rowMenu(View anchor, Db.ListRow r) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Editar");
        if (active && r.state != Db.IN_CART) {
            pm.getMenu().add(0, 2, 1, r.state == Db.MISSING ? "Desmarcar \"não encontrado\"" : "Não encontrei no mercado");
        }
        pm.getMenu().add(0, 3, 2, r.item.priority ? "Tirar prioridade" : "Marcar como prioritário");
        pm.getMenu().add(0, 4, 3, "Excluir da lista");
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: editItem(r.item); break;
                case 2: db.setMissing(trip.id, r.item.id, r.state != Db.MISSING); refresh(); break;
                case 3: r.item.priority = !r.item.priority; db.saveListItem(r.item); refresh(); break;
                case 4: db.deleteListItem(r.item.id); refresh(); break;
            }
            return true;
        });
        pm.show();
    }

    // ------------------------------------------------------------ linhas

    private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.list_item_row, p, false)) {};
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Db.ListRow r = rows.get(pos);
            View v = h.itemView;
            TextView name = v.findViewById(R.id.name);
            TextView info = v.findViewById(R.id.info);
            View dot = v.findViewById(R.id.dot);
            ImageView dotIcon = v.findViewById(R.id.dotIcon);
            ImageButton star = v.findViewById(R.id.star);
            ImageButton scan = v.findViewById(R.id.scan);
            View drag = v.findViewById(R.id.drag);

            name.setText(r.item.name);
            String want = "Quero " + Money.formatQty(r.item.qty);
            String last = r.last == null ? "" : " · último " + Money.format(r.last.cents) + (r.last.store != null ? " (" + r.last.store + ")" : "");
            name.setPaintFlags(name.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG);
            name.setTextColor(getColor(R.color.ink));
            if (!active) {
                dot.setVisibility(View.GONE);
                info.setText(want + last);
                info.setTextColor(getColor(R.color.gray));
            } else {
                dot.setVisibility(View.VISIBLE);
                if (r.state == Db.IN_CART) {
                    dot.setBackgroundResource(R.drawable.bg_dot_green);
                    dotIcon.setImageResource(R.drawable.ic_check);
                    info.setText("No carrinho: " + Money.formatQty(r.cartQty) + " · " + Money.format(r.cartCents));
                    info.setTextColor(getColor(R.color.green));
                } else if (r.state == Db.MISSING) {
                    dot.setBackgroundResource(R.drawable.bg_dot_amber);
                    dotIcon.setImageResource(R.drawable.ic_warning);
                    info.setText("Não encontrado neste mercado");
                    info.setTextColor(getColor(R.color.amber));
                    name.setPaintFlags(name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                    name.setTextColor(getColor(R.color.gray));
                } else {
                    dot.setBackgroundResource(R.drawable.bg_dot_gray);
                    dotIcon.setImageDrawable(null);
                    info.setText(want + last);
                    info.setTextColor(getColor(R.color.gray));
                }
            }
            star.setImageResource(r.item.priority ? R.drawable.ic_star : R.drawable.ic_star_border);
            star.setColorFilter(getColor(r.item.priority ? R.color.amber : R.color.gray));
            star.setOnClickListener(x -> { r.item.priority = !r.item.priority; db.saveListItem(r.item); refresh(); });
            scan.setVisibility(r.state == Db.IN_CART ? View.GONE : View.VISIBLE);
            scan.setOnClickListener(x -> scan(r));
            v.findViewById(R.id.more).setOnClickListener(x -> rowMenu(x, r));
            v.setOnClickListener(x -> {
                if (r.state == Db.PENDING || r.state == Db.MISSING) scan(r);
                else editItem(r.item);
            });
            drag.setVisibility(active ? View.GONE : View.VISIBLE);
            drag.setOnTouchListener((x, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) dragger.startDrag(h);
                return false;
            });
        }

        @Override public int getItemCount() { return rows.size(); }
    }
}
