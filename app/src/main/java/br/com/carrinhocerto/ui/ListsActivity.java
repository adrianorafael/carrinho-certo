package br.com.carrinhocerto.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.data.Db;

/** Todas as listas de compras. Em modo "escolher", tocar numa lista a liga à compra aberta. */
public class ListsActivity extends AppCompatActivity {

    public static final String EXTRA_PICK = "pick";

    private Db db;
    private boolean pick;
    private final List<Db.ShoppingList> lists = new ArrayList<>();
    private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_lists);
        db = Db.get(this);
        pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        if (pick) {
            tb.setTitle("Usar uma lista");
            findViewById(R.id.hint).setVisibility(View.VISIBLE);
        }
        findViewById(R.id.fab).setOnClickListener(v -> askName("Nova lista", "", "Criar", name -> {
            long id = db.createList(name);
            open(id, pick);
        }));

        RecyclerView rv = findViewById(R.id.list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
                return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext()).inflate(R.layout.list_row, p, false)) {};
            }
            @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
                Db.ShoppingList l = lists.get(pos);
                View v = h.itemView;
                ((TextView) v.findViewById(R.id.name)).setText(l.name);
                String used = l.usedAt > 0 ? " · usada em " + new SimpleDateFormat("dd/MM", Locale.forLanguageTag("pt-BR")).format(new Date(l.usedAt)) : "";
                ((TextView) v.findViewById(R.id.info)).setText(l.itemCount + (l.itemCount == 1 ? " item" : " itens") + used);
                v.setOnClickListener(x -> open(l.id, pick));
                v.findViewById(R.id.more).setOnClickListener(x -> menu(x, l));
            }
            @Override public int getItemCount() { return lists.size(); }
        };
        rv.setAdapter(adapter);
    }

    @Override protected void onResume() {
        super.onResume();
        lists.clear();
        lists.addAll(db.lists());
        adapter.notifyDataSetChanged();
        findViewById(R.id.empty).setVisibility(lists.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void open(long listId, boolean useNow) {
        if (useNow) db.setTripList(db.openTrip().id, listId);
        startActivity(new Intent(this, ListActivity.class).putExtra(ListActivity.EXTRA_LIST_ID, listId));
        if (pick) finish();
    }

    private void menu(View anchor, Db.ShoppingList l) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "Usar na compra de agora");
        pm.getMenu().add(0, 2, 1, "Duplicar");
        pm.getMenu().add(0, 3, 2, "Renomear");
        pm.getMenu().add(0, 4, 3, "Excluir");
        pm.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1: open(l.id, true); break;
                case 2: askName("Duplicar lista", l.name + " (cópia)", "Duplicar", n -> { db.duplicateList(l.id, n); onResume(); }); break;
                case 3: askName("Renomear lista", l.name, "Salvar", n -> { db.renameList(l.id, n); onResume(); }); break;
                case 4:
                    new MaterialAlertDialogBuilder(this).setTitle("Excluir \"" + l.name + "\"?")
                            .setMessage("A lista será apagada. Suas compras no histórico continuam.")
                            .setPositiveButton("Excluir", (d, w) -> { db.deleteList(l.id); onResume(); })
                            .setNegativeButton("Cancelar", null).show();
                    break;
            }
            return true;
        });
        pm.show();
    }

    interface OnName { void on(String name); }

    void askName(String title, String current, String positive, OnName cb) {
        TextInputLayout til = new TextInputLayout(this);
        til.setHint("Nome (ex.: Compra do mês)");
        EditText et = new EditText(til.getContext());
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setText(current);
        et.setSelectAllOnFocus(true);
        til.addView(et);
        FrameLayout box = new FrameLayout(this);
        int p = Dialogs.dp(this, 24);
        box.setPadding(p, Dialogs.dp(this, 8), p, 0);
        box.addView(til);
        new MaterialAlertDialogBuilder(this).setTitle(title).setView(box)
                .setPositiveButton(positive, (d, w) -> {
                    String n = et.getText().toString().trim();
                    cb.on(n.isEmpty() ? "Minha lista" : n);
                })
                .setNegativeButton("Cancelar", null).show();
        et.requestFocus();
    }
}
