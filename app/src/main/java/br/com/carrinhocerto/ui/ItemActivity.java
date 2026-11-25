package br.com.carrinhocerto.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.Money;
import br.com.carrinhocerto.data.Db;

/** Confirmação/edição de um item: nome, preço, quantidade e extras (foto e código de barras). */
public class ItemActivity extends AppCompatActivity {

    public static final String EXTRA_ITEM_ID = "item_id";

    private Db db;
    private Db.Item item;
    private boolean isNew;
    private String originalPhoto, originalLabel;

    private TextInputEditText name, price;
    private TextInputLayout nameLayout, priceLayout;
    private EditText qty;
    private TextView subtotal, barcodeView, qtyLabel, qtyError, bannerText, raw, rawToggle, priceChipsLabel;
    private ChipGroup nameChips, priceChips;
    private View nameChipsScroll, banner;
    private MaterialSwitch byWeight;
    private ShapeableImageView labelPhoto, productPhoto;
    private MaterialButton btnSave;
    private boolean fillingChips;

    private final ActivityResultLauncher<Intent> rescan = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    if (isNew && item.labelPhotoPath != null) new File(item.labelPhotoPath).delete();
                    applyScan(r.getData());
                }
            });

    private final ActivityResultLauncher<Intent> takePhoto = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    String p = r.getData().getStringExtra(ScanActivity.R_PHOTO);
                    if (p != null) {
                        if (item.photoPath != null && !item.photoPath.equals(originalPhoto)) new File(item.photoPath).delete();
                        item.photoPath = p;
                        showExtras();
                    }
                }
            });

    private final ActivityResultLauncher<Intent> readBarcode = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == RESULT_OK && r.getData() != null) {
                    item.barcode = r.getData().getStringExtra(ScanActivity.R_BARCODE);
                    showExtras();
                    Dialogs.ok(findViewById(R.id.root), "Código lido ✓");
                }
            });

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_item);
        db = Db.get(this);
        bind();

        long id = getIntent().getLongExtra(EXTRA_ITEM_ID, 0);
        if (id > 0) {
            item = db.item(id);
            if (item == null) { finish(); return; }
            isNew = false;
            originalPhoto = item.photoPath;
            originalLabel = item.labelPhotoPath;
            ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle("Editar item");
            btnSave.setText("Salvar");
            btnSave.setIconResource(R.drawable.ic_check);
            findViewById(R.id.btnDelete).setVisibility(View.VISIBLE);
            name.setText(item.name);
            price.setText(Money.formatPlain(item.unitCents));
            byWeight.setChecked(item.byWeight);
            qty.setText(Money.formatQty(item.qty));
            if (item.labelPhotoPath != null) {
                labelPhoto.setVisibility(View.VISIBLE);
                ImageUtils.loadThumb(labelPhoto, item.labelPhotoPath, 900);
            }
        } else {
            item = new Db.Item();
            item.tripId = db.openTrip().id;
            isNew = true;
            if (getIntent().hasExtra(ScanActivity.R_LABEL_PHOTO)) {
                applyScan(getIntent());
            } else {
                ((MaterialToolbar) findViewById(R.id.toolbar)).setTitle("Adicionar item");
                name.requestFocus();
            }
        }
        updateWeightMode();
        showExtras();
        updateSubtotal();
    }

    private void bind() {
        MaterialToolbar tb = findViewById(R.id.toolbar);
        tb.setNavigationOnClickListener(v -> cancel());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { cancel(); }
        });
        name = findViewById(R.id.name);
        price = findViewById(R.id.price);
        nameLayout = findViewById(R.id.nameLayout);
        priceLayout = findViewById(R.id.priceLayout);
        qty = findViewById(R.id.qty);
        qtyLabel = findViewById(R.id.qtyLabel);
        qtyError = findViewById(R.id.qtyError);
        subtotal = findViewById(R.id.subtotal);
        barcodeView = findViewById(R.id.barcode);
        nameChips = findViewById(R.id.nameChips);
        nameChipsScroll = findViewById(R.id.nameChipsScroll);
        priceChips = findViewById(R.id.priceChips);
        priceChipsLabel = findViewById(R.id.priceChipsLabel);
        byWeight = findViewById(R.id.byWeight);
        labelPhoto = findViewById(R.id.labelPhoto);
        productPhoto = findViewById(R.id.productPhoto);
        banner = findViewById(R.id.banner);
        bannerText = findViewById(R.id.bannerText);
        raw = findViewById(R.id.raw);
        rawToggle = findViewById(R.id.rawToggle);
        btnSave = findViewById(R.id.btnSave);

        TextWatcher w = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) {}
            @Override public void afterTextChanged(Editable e) {
                priceLayout.setError(null);
                qtyError.setVisibility(View.GONE);
                updateSubtotal();
                if (!fillingChips) syncPriceChip();
            }
        };
        price.addTextChangedListener(w);
        qty.addTextChangedListener(w);
        name.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
            @Override public void onTextChanged(CharSequence a, int b, int c, int d) { nameLayout.setError(null); }
            @Override public void afterTextChanged(Editable e) {}
        });

        findViewById(R.id.minus).setOnClickListener(v -> stepQty(-1));
        findViewById(R.id.plus).setOnClickListener(v -> stepQty(+1));
        byWeight.setOnCheckedChangeListener((b, checked) -> {
            if (checked && Money.parseQty(qty.getText().toString()) == 1) qty.setText("1");
            updateWeightMode();
            updateSubtotal();
        });
        btnSave.setOnClickListener(v -> save());
        findViewById(R.id.btnDelete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.btnRescan).setOnClickListener(v -> rescan.launch(
                new Intent(this, ScanActivity.class).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_LABEL)));
        findViewById(R.id.btnPhoto).setOnClickListener(v -> takePhoto.launch(
                new Intent(this, ScanActivity.class).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_PHOTO)));
        findViewById(R.id.btnBarcode).setOnClickListener(v -> readBarcode.launch(
                new Intent(this, ScanActivity.class).putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_BARCODE)));
        productPhoto.setOnClickListener(v -> startActivity(new Intent(this, PhotoActivity.class).putExtra(PhotoActivity.EXTRA_PATH, item.photoPath)));
        labelPhoto.setOnClickListener(v -> startActivity(new Intent(this, PhotoActivity.class).putExtra(PhotoActivity.EXTRA_PATH, item.labelPhotoPath)));
        barcodeView.setOnLongClickListener(v -> {
            item.barcode = null;
            showExtras();
            return true;
        });
        rawToggle.setOnClickListener(v -> raw.setVisibility(raw.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    /** Preenche a tela com o que o OCR encontrou. */
    private void applyScan(Intent d) {
        String n = d.getStringExtra(ScanActivity.R_NAME);
        String[] names = d.getStringArrayExtra(ScanActivity.R_NAMES);
        long[] prices = d.getLongArrayExtra(ScanActivity.R_PRICES);
        String code = d.getStringExtra(ScanActivity.R_BARCODE);
        item.labelPhotoPath = d.getStringExtra(ScanActivity.R_LABEL_PHOTO);
        String rawText = d.getStringExtra(ScanActivity.R_RAW);
        if (code != null) item.barcode = code;

        name.setText(n == null ? "" : n);
        if (n == null || n.isEmpty()) name.requestFocus();

        // chips de nome
        nameChips.removeAllViews();
        if (names != null && names.length > 1) {
            for (String c : names) {
                Chip chip = newChip(nameChips, c);
                chip.setCheckable(false);
                chip.setOnClickListener(v -> { name.setText(c); name.setSelection(c.length()); });
                nameChips.addView(chip);
            }
            nameChipsScroll.setVisibility(View.VISIBLE);
        } else {
            nameChipsScroll.setVisibility(View.GONE);
        }

        // preços
        fillingChips = true;
        priceChips.removeAllViews();
        boolean hasPrice = prices != null && prices.length > 0;
        if (hasPrice) {
            price.setText(Money.formatPlain(prices[0]));
            if (prices.length > 1) {
                for (long p : prices) {
                    Chip chip = newChip(priceChips, Money.format(p));
                    chip.setTag(p);
                    chip.setOnClickListener(v -> {
                        fillingChips = true;
                        price.setText(Money.formatPlain(p));
                        fillingChips = false;
                        syncPriceChip();
                    });
                    priceChips.addView(chip);
                }
            }
        } else {
            price.setText("");
        }
        priceChipsLabel.setVisibility(prices != null && prices.length > 1 ? View.VISIBLE : View.GONE);
        fillingChips = false;
        syncPriceChip();

        // aviso colorido
        banner.setVisibility(View.VISIBLE);
        if (hasPrice && n != null && !n.isEmpty()) {
            banner.setBackgroundResource(prices.length > 1 ? R.drawable.bg_warn : R.drawable.bg_ok);
            bannerText.setTextColor(getColor(prices.length > 1 ? R.color.amber : R.color.green));
            bannerText.setText(prices.length > 1
                    ? "Etiqueta lida. Encontrei mais de um preço: confira qual é o seu."
                    : "Etiqueta lida ✓ Confira e adicione.");
        } else if (hasPrice) {
            banner.setBackgroundResource(R.drawable.bg_warn);
            bannerText.setTextColor(getColor(R.color.amber));
            bannerText.setText("Li o preço, mas não o nome. Digite o nome do produto.");
            name.requestFocus();
        } else {
            banner.setBackgroundResource(R.drawable.bg_error);
            bannerText.setTextColor(getColor(R.color.red));
            bannerText.setText("Não consegui ler o preço. Digite ou tente ler de novo.");
            price.requestFocus();
        }

        if (item.labelPhotoPath != null) {
            labelPhoto.setVisibility(View.VISIBLE);
            ImageUtils.loadThumb(labelPhoto, item.labelPhotoPath, 900);
        }
        if (rawText != null && !rawText.isEmpty()) {
            raw.setText(rawText);
            rawToggle.setVisibility(View.VISIBLE);
        }
        showExtras();
    }

    private Chip newChip(ChipGroup parent, String text) {
        Chip chip = (Chip) getLayoutInflater().inflate(R.layout.chip_choice, parent, false);
        chip.setText(text);
        return chip;
    }

    private void syncPriceChip() {
        long cents = Money.parse(String.valueOf(price.getText()));
        for (int i = 0; i < priceChips.getChildCount(); i++) {
            Chip c = (Chip) priceChips.getChildAt(i);
            c.setChecked(c.getTag() instanceof Long && (Long) c.getTag() == cents);
        }
    }

    private void updateWeightMode() {
        boolean w = byWeight.isChecked();
        qtyLabel.setText(w ? "Peso (kg) — ex.: 0,750" : "Quantidade");
        priceLayout.setHint(w ? "Preço por kg" : "Preço unitário");
    }

    private void stepQty(int dir) {
        double step = byWeight.isChecked() ? 0.1 : 1;
        double q = Money.parseQty(qty.getText().toString());
        if (q < 0) q = byWeight.isChecked() ? 0 : 1;
        double n = Math.round((q + dir * step) * 1000) / 1000.0;
        if (n < step) n = step;
        qty.setText(Money.formatQty(n));
    }

    private void updateSubtotal() {
        long cents = Money.parse(String.valueOf(price.getText()));
        double q = Money.parseQty(qty.getText().toString());
        subtotal.setText(cents > 0 && q > 0 ? Money.format(Money.times(cents, q)) : "R$ 0,00");
    }

    private void showExtras() {
        if (item.photoPath != null && new File(item.photoPath).exists()) {
            productPhoto.setVisibility(View.VISIBLE);
            ImageUtils.loadThumb(productPhoto, item.photoPath, 200);
            ((MaterialButton) findViewById(R.id.btnPhoto)).setText("Trocar foto");
        } else {
            productPhoto.setVisibility(View.GONE);
        }
        if (item.barcode != null && !item.barcode.isEmpty()) {
            barcodeView.setVisibility(View.VISIBLE);
            barcodeView.setText("▌▌▍▌ " + item.barcode + "\n(toque e segure para apagar)");
        } else {
            barcodeView.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------ salvar

    private void save() {
        String n = String.valueOf(name.getText()).trim().replaceAll("\\s+", " ");
        long cents = Money.parse(String.valueOf(price.getText()));
        double q = Money.parseQty(qty.getText().toString());
        boolean ok = true;
        if (n.isEmpty()) { nameLayout.setError("Informe o nome do produto"); ok = false; }
        if (cents <= 0) { priceLayout.setError("Informe um preço válido, ex.: 12,90"); ok = false; }
        if (q <= 0) {
            qtyError.setText(byWeight.isChecked() ? "Informe o peso, ex.: 0,750" : "Informe a quantidade");
            qtyError.setVisibility(View.VISIBLE);
            ok = false;
        }
        if (!ok) return;

        item.name = n;
        item.unitCents = cents;
        item.qty = q;
        item.byWeight = byWeight.isChecked();

        if (isNew) {
            Db.Item dup = db.findDuplicate(item.tripId, item.barcode, item.name, item.unitCents, 0);
            if (dup != null && !dup.byWeight && !item.byWeight) {
                new MaterialAlertDialogBuilder(this)
                        .setTitle("Já está no carrinho")
                        .setMessage("\"" + dup.name + "\" já tem " + Money.formatQty(dup.qty) + " un. Somar mais " + Money.formatQty(q) + "?")
                        .setPositiveButton("Somar", (d, w) -> {
                            dup.qty += q;
                            if (dup.photoPath == null) dup.photoPath = item.photoPath;
                            else if (item.photoPath != null) new File(item.photoPath).delete();
                            if (dup.barcode == null) dup.barcode = item.barcode;
                            if (item.labelPhotoPath != null) new File(item.labelPhotoPath).delete();
                            db.save(dup);
                            finish();
                        })
                        .setNegativeButton("Item separado", (d, w) -> { db.save(item); finish(); })
                        .show();
                return;
            }
        } else {
            if (originalPhoto != null && !originalPhoto.equals(item.photoPath)) new File(originalPhoto).delete();
            if (originalLabel != null && !originalLabel.equals(item.labelPhotoPath)) new File(originalLabel).delete();
        }
        db.save(item);
        finish();
    }

    private void confirmDelete() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Remover item?")
                .setMessage("\"" + item.name + "\" sai do carrinho.")
                .setPositiveButton("Remover", (d, w) -> {
                    db.deleteItemRow(item.id);
                    Db.deleteFiles(item);
                    finish();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void cancel() {
        if (isNew) {
            Db.deleteFiles(item); // descarta fotos de um item que não foi salvo
        } else {
            if (item.photoPath != null && !item.photoPath.equals(originalPhoto)) new File(item.photoPath).delete();
            if (item.labelPhotoPath != null && !item.labelPhotoPath.equals(originalLabel)) new File(item.labelPhotoPath).delete();
        }
        finish();
    }
}
