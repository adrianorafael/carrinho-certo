package br.com.carrinhocerto.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.Money;
import br.com.carrinhocerto.core.OcrLine;
import br.com.carrinhocerto.core.Receipt;
import br.com.carrinhocerto.core.ReceiptCompare;
import br.com.carrinhocerto.core.ReceiptCompare.Status;
import br.com.carrinhocerto.core.ReceiptXml;
import br.com.carrinhocerto.data.Db;

/**
 * Conferência da nota fiscal de uma compra: entrada (fotos, imagens, PDF, XML) → revisão da
 * leitura → comparação com os preços da gôndola. Tudo no aparelho. O texto da nota não é registrado em log.
 */
public class ReceiptActivity extends AppCompatActivity {

    public static final String EXTRA_TRIP_ID = "trip_id";

    private static final int STAGE_INPUT = 0, STAGE_REVIEW = 1, STAGE_RESULT = 2;

    private Db db;
    private long tripId;
    private int stage;
    private final List<Receipt> parts = new ArrayList<>();
    private Receipt receipt;
    private Map<Integer, Long> forced = new HashMap<>();
    private ReceiptCompare.Result result;
    private final List<Object> rows = new ArrayList<>();
    private Adapter adapter;
    private TextRecognizer recognizer;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private boolean showRaw;

    private final ActivityResultLauncher<Intent> takePart = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    String p = r.getData().getStringExtra(ScanActivity.R_PHOTO);
                    if (p != null) readImages(Collections.singletonList(Uri.fromFile(new File(p))), true);
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> pickImages = registerForActivityResult(
            new ActivityResultContracts.PickMultipleVisualMedia(10), uris -> {
                if (uris != null && !uris.isEmpty()) readImages(uris, false);
            });

    private final ActivityResultLauncher<String[]> pickPdf = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) readPdf(uri); });

    private final ActivityResultLauncher<String[]> pickXml = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) readXml(uri); });

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_receipt);
        db = Db.get(this);
        tripId = getIntent().getLongExtra(EXTRA_TRIP_ID, 0);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> finish());
        tb.getMenu().add(0, 1, 0, "Refazer leitura da nota");
        tb.getMenu().add(0, 2, 1, "Apagar conferência");
        tb.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) { parts.clear(); showInput(); }
            else confirmDelete();
            return true;
        });

        RecyclerView rv = findViewById(R.id.list);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new Adapter();
        rv.setAdapter(adapter);

        findViewById(R.id.btnPhoto).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnNextPart).setOnClickListener(v -> takePhoto());
        findViewById(R.id.btnGallery).setOnClickListener(v -> pickImages.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));
        findViewById(R.id.btnPdf).setOnClickListener(v -> pickPdf.launch(new String[]{"application/pdf"}));
        findViewById(R.id.btnXml).setOnClickListener(v -> pickXml.launch(new String[]{"text/xml", "application/xml", "application/octet-stream", "*/*"}));
        findViewById(R.id.btnReview).setOnClickListener(v -> {
            receipt = Receipt.merge(parts);
            forced = new HashMap<>();
            showReview();
        });

        Db.SavedReceipt saved = db.loadReceipt(tripId);
        if (saved != null) {
            receipt = saved.receipt;
            forced = saved.forced;
            showResult();
        } else {
            showInput();
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        recognizer.close();
    }

    private void takePhoto() {
        takePart.launch(new Intent(this, ScanActivity.class).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_RECEIPT));
    }

    // ------------------------------------------------------------ etapa 1: entrada

    private void showInput() {
        stage = STAGE_INPUT;
        findViewById(R.id.stageInput).setVisibility(View.VISIBLE);
        findViewById(R.id.stageList).setVisibility(View.GONE);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle("Conferir nota fiscal");
        LinearLayout box = findViewById(R.id.parts);
        box.removeAllViews();
        int total = 0;
        for (int i = 0; i < parts.size(); i++) {
            Receipt p = parts.get(i);
            total += p.lines.size();
            TextView tv = new TextView(this);
            boolean empty = p.lines.isEmpty();
            tv.setText((empty ? "✕ " : "✓ ") + "Parte " + (i + 1) + ": " + (empty
                    ? "nenhum item reconhecido. Tente mais perto e com boa luz."
                    : p.lines.size() + (p.lines.size() == 1 ? " item lido" : " itens lidos") + (p.totalCents >= 0 ? " · total encontrado" : "")));
            tv.setTextColor(getColor(empty ? R.color.red : R.color.green));
            tv.setTextSize(15);
            tv.setPadding(0, Dialogs.dp(this, 6), 0, Dialogs.dp(this, 6));
            final int idx = i;
            tv.setOnLongClickListener(v -> { parts.remove(idx); showInput(); return true; });
            box.addView(tv);
        }
        findViewById(R.id.btnNextPart).setVisibility(parts.isEmpty() ? View.GONE : View.VISIBLE);
        MaterialButton review = findViewById(R.id.btnReview);
        review.setVisibility(total > 0 ? View.VISIBLE : View.GONE);
        review.setText("Revisar leitura (" + total + (total == 1 ? " item)" : " itens)"));
    }

    private void busy(String text) {
        ((TextView) findViewById(R.id.busyText)).setText(text);
        findViewById(R.id.busy).setVisibility(View.VISIBLE);
    }

    private void idle() { findViewById(R.id.busy).setVisibility(View.GONE); }

    private Receipt ocrPart(Bitmap bmp) throws Exception {
        List<OcrLine> lines = Ocr.lines(recognizer, bmp);
        return Receipt.parseText(Receipt.rows(lines));
    }

    private void readImages(List<Uri> uris, boolean deleteAfter) {
        busy("Lendo a nota…");
        worker.execute(() -> {
            List<Receipt> got = new ArrayList<>();
            String error = null;
            for (Uri u : uris) {
                File tmp = ImageUtils.tempFile(this);
                try {
                    try (InputStream in = getContentResolver().openInputStream(u);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    Bitmap bmp = ImageUtils.decode(tmp, 3000);
                    got.add(ocrPart(bmp));
                } catch (Exception e) {
                    error = "Não consegui ler uma das imagens.";
                } finally {
                    tmp.delete();
                    if (deleteAfter && "file".equals(u.getScheme()) && u.getPath() != null) new File(u.getPath()).delete();
                }
            }
            String err = error;
            runOnUiThread(() -> {
                idle();
                parts.addAll(got);
                showInput();
                if (err != null) Dialogs.error(findViewById(R.id.root), err);
            });
        });
    }

    private void readPdf(Uri uri) {
        busy("Lendo o PDF…");
        worker.execute(() -> {
            List<Receipt> got = new ArrayList<>();
            String error = null;
            try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                 PdfRenderer pdf = new PdfRenderer(pfd)) {
                int pages = Math.min(pdf.getPageCount(), 10);
                for (int i = 0; i < pages; i++) {
                    try (PdfRenderer.Page page = pdf.openPage(i)) {
                        float scale = 1700f / page.getWidth();
                        int w = Math.round(page.getWidth() * scale), h = Math.round(page.getHeight() * scale);
                        if ((long) w * h > 30_000_000L) { h = 30_000_000 / w; }
                        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.WHITE);
                        android.graphics.Matrix m = new android.graphics.Matrix();
                        m.setScale(scale, scale);
                        page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        got.add(ocrPart(bmp));
                        bmp.recycle();
                    }
                }
            } catch (Exception e) {
                error = "Não consegui abrir esse PDF.";
            }
            String err = error;
            runOnUiThread(() -> {
                idle();
                parts.addAll(got);
                showInput();
                if (err != null) Dialogs.error(findViewById(R.id.root), err);
            });
        });
    }

    private void readXml(Uri uri) {
        busy("Lendo o XML…");
        worker.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                Receipt r = ReceiptXml.parse(in);
                runOnUiThread(() -> {
                    idle();
                    if (r.lines.isEmpty()) { Dialogs.error(findViewById(R.id.root), "Esse arquivo não parece um XML de NFC-e."); return; }
                    receipt = r;
                    forced = new HashMap<>();
                    showReview();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { idle(); Dialogs.error(findViewById(R.id.root), "Esse arquivo não parece um XML de NFC-e."); });
            }
        });
    }

    // ------------------------------------------------------------ etapa 2: revisão

    private void showReview() {
        stage = STAGE_REVIEW;
        findViewById(R.id.stageInput).setVisibility(View.GONE);
        findViewById(R.id.stageList).setVisibility(View.VISIBLE);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle("Revisar leitura");
        rows.clear();
        rows.add("REVIEW_HEADER");
        rows.addAll(receipt.lines);
        rows.add("ADD");
        if (!receipt.rawRows.isEmpty()) rows.add("RAW");
        adapter.notifyDataSetChanged();
        MaterialButton alt = findViewById(R.id.footerAlt), main = findViewById(R.id.footerMain);
        alt.setText("Ler de novo");
        alt.setOnClickListener(v -> { parts.clear(); showInput(); });
        main.setText("Comparar");
        main.setIconResource(R.drawable.ic_check);
        main.setOnClickListener(v -> {
            if (receipt.lines.isEmpty()) { Dialogs.error(findViewById(R.id.root), "Nenhum item na nota."); return; }
            showResult();
        });
    }

    private void editLine(Receipt.Line l, boolean isNew) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Dialogs.dp(this, 24);
        box.setPadding(p, Dialogs.dp(this, 8), p, 0);
        EditText desc = field(box, "Descrição", l.desc, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        EditText qty = field(box, "Quantidade (ou peso em kg)", Money.formatQty(l.qty), InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText unit = field(box, "Valor unitário (R$)", l.unitCents >= 0 ? Money.formatPlain(l.unitCents) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText total = field(box, "Valor do item (R$)", l.totalCents >= 0 ? Money.formatPlain(l.totalCents) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText disc = field(box, "Desconto do item (R$)", l.discountCents > 0 ? Money.formatPlain(l.discountCents) : "", InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(this)
                .setTitle(isNew ? "Adicionar item da nota" : "Corrigir item")
                .setView(box)
                .setPositiveButton("Salvar", (d, w) -> {
                    l.desc = desc.getText().toString().trim();
                    double q = Money.parseQty(qty.getText().toString());
                    if (q > 0) l.qty = q;
                    long u = Money.parse(unit.getText().toString());
                    long t = Money.parse(total.getText().toString());
                    long dc = Money.parse(disc.getText().toString());
                    l.unitCents = u > 0 ? u : (t > 0 ? Math.round(t / l.qty) : l.unitCents);
                    l.totalCents = t > 0 ? t : Math.round(l.unitCents * l.qty);
                    l.discountCents = Math.max(0, dc);
                    if (l.qty != Math.rint(l.qty)) l.unit = "KG";
                    l.suspect = Math.abs(Math.round(l.unitCents * l.qty) - l.totalCents) > 2;
                    if (isNew && !l.desc.isEmpty() && l.unitCents > 0) receipt.lines.add(l);
                    showReview();
                })
                .setNegativeButton("Cancelar", null);
        if (!isNew) b.setNeutralButton("Excluir", (d, w) -> { receipt.lines.remove(l); showReview(); });
        b.show();
    }

    private EditText field(LinearLayout box, String hint, String value, int type) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextColor(getColor(R.color.gray));
        label.setTextSize(12);
        label.setPadding(0, Dialogs.dp(this, 8), 0, 0);
        EditText et = new EditText(this);
        et.setInputType(type);
        et.setText(value);
        box.addView(label);
        box.addView(et);
        return et;
    }

    private void editTotal() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Dialogs.dp(this, 24);
        box.setPadding(p, Dialogs.dp(this, 8), p, 0);
        EditText t = field(box, "Valor a pagar da nota (R$)", receipt.totalCents >= 0 ? Money.formatPlain(receipt.totalCents) : "",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        new MaterialAlertDialogBuilder(this).setTitle("Total da nota").setView(box)
                .setPositiveButton("Salvar", (d, w) -> { receipt.totalCents = Money.parse(t.getText().toString()); showReview(); })
                .setNegativeButton("Cancelar", null).show();
    }

    // ------------------------------------------------------------ etapa 3: resultado

    private List<ReceiptCompare.AppItem> appItems() {
        List<ReceiptCompare.AppItem> out = new ArrayList<>();
        for (Db.Item i : db.items(tripId)) {
            out.add(new ReceiptCompare.AppItem(i.id, i.name, i.barcode, i.unitCents, i.qty, i.byWeight));
        }
        return out;
    }

    private void showResult() {
        stage = STAGE_RESULT;
        findViewById(R.id.stageInput).setVisibility(View.GONE);
        findViewById(R.id.stageList).setVisibility(View.VISIBLE);
        ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle("Resultado da conferência");
        result = ReceiptCompare.compare(appItems(), receipt, forced);
        db.saveReceipt(tripId, receipt, forced, result.diffTotal);

        List<ReceiptCompare.Row> sorted = new ArrayList<>(result.rows);
        Collections.sort(sorted, (a, b) -> Integer.compare(severity(a.status), severity(b.status)));
        rows.clear();
        rows.add("SUMMARY");
        rows.addAll(sorted);
        adapter.notifyDataSetChanged();
        ((RecyclerView) findViewById(R.id.list)).scrollToPosition(0);

        MaterialButton alt = findViewById(R.id.footerAlt), main = findViewById(R.id.footerMain);
        alt.setText("Editar leitura");
        alt.setOnClickListener(v -> showReview());
        main.setText("Concluir");
        main.setIconResource(R.drawable.ic_check);
        main.setOnClickListener(v -> finish());
    }

    private static int severity(Status s) {
        switch (s) {
            case OVER: return 0;
            case CHECK: return 1;
            case ONLY_RECEIPT: return 2;
            case ONLY_APP: return 3;
            case UNDER: return 4;
            case PROMO: return 5;
            default: return 6;
        }
    }

    private int color(Status s) {
        switch (s) {
            case OK: return getColor(R.color.green);
            case OVER: return getColor(R.color.red);
            case UNDER: case PROMO: return getColor(R.color.blue);
            default: return getColor(R.color.amber);
        }
    }

    private static String label(Status s) {
        switch (s) {
            case OK: return "Preço correto";
            case OVER: return "Cobrado a mais";
            case UNDER: return "Cobrado a menos";
            case PROMO: return "Promoção no caixa";
            case CHECK: return "Conferir";
            case ONLY_APP: return "Não está na nota";
            default: return "Não registrado no app";
        }
    }

    private void associate(ReceiptCompare.Row row) {
        List<ReceiptCompare.AppItem> app = appItems();
        if (row.receipt != null) {
            int g = result.groups.indexOf(row.receipt);
            String[] labels = new String[app.size() + 2];
            for (int i = 0; i < app.size(); i++) labels[i] = app.get(i).name + " · " + Money.format(app.get(i).unitCents);
            labels[app.size()] = "Nenhum: não registrei no app";
            labels[app.size() + 1] = "Deixar o app decidir";
            new MaterialAlertDialogBuilder(this).setTitle("\"" + row.receipt.desc + "\" na nota é…")
                    .setItems(labels, (d, w) -> {
                        // um item do app só pode estar ligado a um produto da nota
                        if (w < app.size()) {
                            long id = app.get(w).id;
                            forced.values().removeIf(v -> v == id);
                            forced.put(g, id);
                        } else if (w == app.size()) forced.put(g, -1L);
                        else forced.remove(g);
                        showResult();
                    }).show();
        } else if (row.app != null) {
            List<ReceiptCompare.Group> gs = result.groups;
            String[] labels = new String[gs.size()];
            for (int i = 0; i < gs.size(); i++) labels[i] = gs.get(i).desc + " · " + Money.format(gs.get(i).netCents());
            new MaterialAlertDialogBuilder(this).setTitle("Onde está \"" + row.app.name + "\" na nota?")
                    .setItems(labels, (d, w) -> {
                        forced.values().removeIf(v -> v == row.app.id);
                        forced.put(w, row.app.id);
                        showResult();
                    }).show();
        }
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this).setTitle("Apagar conferência?")
                .setMessage("A leitura da nota desta compra será apagada. Os preços registrados não mudam.")
                .setPositiveButton("Apagar", (d, w) -> { db.deleteReceipt(tripId); parts.clear(); receipt = null; showInput(); })
                .setNegativeButton("Cancelar", null).show();
    }

    // ------------------------------------------------------------ lista (revisão e resultado)

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @Override public int getItemViewType(int p) {
            Object o = rows.get(p);
            return "SUMMARY".equals(o) ? 1 : 0;
        }

        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int type) {
            int layout = type == 1 ? R.layout.receipt_summary : R.layout.receipt_row;
            return new RecyclerView.ViewHolder(LayoutInflater.from(p.getContext()).inflate(layout, p, false)) {};
        }

        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            Object o = rows.get(pos);
            View v = h.itemView;
            if ("SUMMARY".equals(o)) { bindSummary(v); return; }
            TextView title = v.findViewById(R.id.title), right = v.findViewById(R.id.right), sub = v.findViewById(R.id.sub),
                    note = v.findViewById(R.id.note), badge = v.findViewById(R.id.badge);
            View strip = v.findViewById(R.id.strip);
            badge.setVisibility(View.GONE);
            note.setVisibility(View.GONE);
            right.setTextColor(getColor(R.color.ink));
            title.setTextColor(getColor(R.color.ink));
            v.setOnClickListener(null);
            v.setOnLongClickListener(null);

            if ("REVIEW_HEADER".equals(o)) {
                strip.setBackgroundColor(getColor(R.color.blue));
                title.setText(receipt.lines.size() + (receipt.lines.size() == 1 ? " item lido" : " itens lidos"));
                right.setText(receipt.totalCents >= 0 ? Money.format(receipt.totalCents) : "total ?");
                right.setTextColor(getColor(receipt.totalCents >= 0 ? R.color.green : R.color.amber));
                long sum = receipt.sumNet() - receipt.discountTotalCents;
                String check = receipt.totalCents >= 0 && Math.abs(sum - receipt.totalCents) > 2
                        ? "\n⚠ A soma dos itens (" + Money.format(sum) + ") não bate com o total da nota: falta ou sobra algum item."
                        : "";
                sub.setText("Confira com o cupom. Toque num item para corrigir e no total para ajustar." + check);
                sub.setTextColor(getColor(check.isEmpty() ? R.color.gray : R.color.amber));
                v.setOnClickListener(x -> editTotal());
                return;
            }
            sub.setTextColor(getColor(R.color.gray));
            if ("ADD".equals(o)) {
                strip.setBackgroundColor(getColor(R.color.gray_line));
                title.setText("+ Adicionar item que faltou");
                title.setTextColor(getColor(R.color.blue));
                right.setText("");
                sub.setText("Use se a leitura pulou alguma linha do cupom.");
                v.setOnClickListener(x -> editLine(new Receipt.Line(), true));
                return;
            }
            if ("RAW".equals(o)) {
                strip.setBackgroundColor(getColor(R.color.gray_line));
                title.setText(showRaw ? "Ocultar texto lido" : "Ver texto lido da nota");
                title.setTextColor(getColor(R.color.blue));
                right.setText("");
                sub.setText(showRaw ? String.join("\n", receipt.rawRows) : "Útil para entender uma leitura errada.");
                sub.setTextIsSelectable(showRaw);
                v.setOnClickListener(x -> { showRaw = !showRaw; notifyItemChanged(pos); });
                return;
            }
            if (o instanceof Receipt.Line) {
                Receipt.Line l = (Receipt.Line) o;
                strip.setBackgroundColor(getColor(l.suspect ? R.color.amber : R.color.gray_line));
                title.setText(l.desc.isEmpty() ? "(sem descrição)" : l.desc);
                right.setText(Money.format(l.netCents()));
                sub.setText(Money.formatQty(l.qty) + " " + l.unit + " × " + Money.format(l.unitCents)
                        + (l.discountCents > 0 ? " · desconto " + Money.format(l.discountCents) : ""));
                if (l.suspect) badge(badge, "Conferir valores", getColor(R.color.amber));
                v.setOnClickListener(x -> editLine(l, false));
                return;
            }
            ReceiptCompare.Row r = (ReceiptCompare.Row) o;
            int c = color(r.status);
            strip.setBackgroundColor(c);
            badge(badge, label(r.status) + (r.forced ? " · associado por você" : ""), c);
            title.setText(r.app != null ? r.app.name : r.receipt.desc);
            StringBuilder sb = new StringBuilder();
            if (r.app != null && r.receipt != null) {
                sb.append("Na nota: ").append(r.receipt.desc).append('\n');
                sb.append("Gôndola ").append(Money.format(r.app.unitCents)).append(" · Caixa ").append(Money.format(r.receipt.effUnit()));
                if (r.diffUnitCents != 0) sb.append(" (").append(signed(r.diffUnitCents)).append("/un)");
                sb.append("\nQuantidade: ").append(Money.formatQty(r.receipt.qty)).append(r.receipt.weight ? " kg" : "");
                if (r.qtyMismatch) sb.append(" (no app: ").append(Money.formatQty(r.app.qty)).append(")");
            } else if (r.app != null) {
                sb.append(Money.formatQty(r.app.qty)).append(" × ").append(Money.format(r.app.unitCents)).append(" = ").append(Money.format(r.app.subtotal()));
            } else {
                sb.append(Money.formatQty(r.receipt.qty)).append(" × ").append(Money.format(r.receipt.unitCents)).append(" = ").append(Money.format(r.receipt.netCents()));
            }
            sub.setText(sb.toString());
            if (r.status == Status.OK) { right.setText("✓"); right.setTextColor(c); }
            else if (r.status == Status.ONLY_APP || r.status == Status.ONLY_RECEIPT) { right.setText("?"); right.setTextColor(c); }
            else { right.setText(signed(r.diffTotalCents)); right.setTextColor(c); }
            if (!r.note.isEmpty() && r.status != Status.OK) {
                note.setVisibility(View.VISIBLE);
                note.setText(r.note + " Toque para corrigir a associação.");
                note.setTextColor(c);
            } else if (r.status == Status.OK && !r.note.isEmpty()) {
                note.setVisibility(View.VISIBLE);
                note.setText(r.note);
                note.setTextColor(c);
            }
            v.setOnClickListener(x -> associate(r));
        }

        private void badge(TextView b, String text, int color) {
            b.setVisibility(View.VISIBLE);
            b.setText(text);
            b.setTextColor(Color.WHITE);
            GradientDrawable g = new GradientDrawable();
            g.setColor(color);
            g.setCornerRadius(Dialogs.dp(ReceiptActivity.this, 10));
            b.setBackground(g);
        }

        @Override public int getItemCount() { return rows.size(); }
    }

    private static String signed(long cents) {
        return (cents > 0 ? "+" : cents < 0 ? "−" : "") + Money.format(Math.abs(cents));
    }

    private void bindSummary(View v) {
        ReceiptCompare.Result r = result;
        MaterialCardView card = v.findViewById(R.id.card);
        TextView head = v.findViewById(R.id.headline), sub = v.findViewById(R.id.subline);
        int color;
        if (r.paidMore > 0) {
            color = getColor(R.color.red);
            head.setText("Você pagou " + Money.format(r.paidMore) + " a mais");
            sub.setText("em itens cobrados acima do preço da gôndola");
        } else if (r.divergences > 0) {
            color = getColor(R.color.amber);
            head.setText(r.divergences + (r.divergences == 1 ? " ponto para conferir" : " pontos para conferir"));
            sub.setText("Nenhum item cobrado acima da gôndola");
        } else {
            color = getColor(R.color.green);
            head.setText("Tudo certo ✓");
            sub.setText("Os preços cobrados batem com a gôndola");
        }
        card.setCardBackgroundColor(color);
        ((TextView) v.findViewById(R.id.appTotal)).setText(Money.format(r.appTotal));
        ((TextView) v.findViewById(R.id.noteTotal)).setText(Money.format(r.receiptTotal) + (r.receiptTotalPrinted ? "" : "*"));
        ((TextView) v.findViewById(R.id.diffTotal)).setText(signed(r.diffTotal));
        ((TextView) v.findViewById(R.id.stats)).setText(r.checked + " conferidos · " + r.divergences
                + (r.divergences == 1 ? " divergência" : " divergências")
                + "\nPago a mais: " + Money.format(r.paidMore) + " · a menos/descontos: " + Money.format(r.paidLess));
        ((TextView) v.findViewById(R.id.hint)).setText((r.receiptTotalPrinted ? "" : "* total somado dos itens (o total impresso não foi lido). ")
                + "Os preços registrados na gôndola nunca são alterados. Toque num item para corrigir a associação.");
    }
}
