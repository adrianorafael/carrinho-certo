package br.com.carrinhocerto.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.core.LabelParser;
import br.com.carrinhocerto.core.OcrLine;

/**
 * Câmera com três modos:
 *  - etiqueta: tira foto, recorta na moldura, roda OCR + leitor de código de barras (tudo offline);
 *  - foto: tira foto do produto;
 *  - código: lê código de barras ao vivo.
 */
public class ScanActivity extends AppCompatActivity {

    public static final String EXTRA_MODE = "mode";
    public static final String MODE_LABEL = "label";
    public static final String MODE_PHOTO = "photo";
    public static final String MODE_BARCODE = "barcode";

    // resultados
    public static final String R_NAME = "r_name";
    public static final String R_NAMES = "r_names";
    public static final String R_PRICES = "r_prices";
    public static final String R_BARCODE = "r_barcode";
    public static final String R_LABEL_PHOTO = "r_label_photo";
    public static final String R_PHOTO = "r_photo";
    public static final String R_RAW = "r_raw";

    private String mode;
    private PreviewView preview;
    private View frame, busy, permission, shutter;
    private ScrimView scrim;
    private TextView busyText;
    private ImageCapture imageCapture;
    private Camera camera;
    private boolean torch;
    private volatile boolean done;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextRecognizer recognizer;
    private BarcodeScanner barcodeScanner;

    private final ActivityResultLauncher<String> askPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) startCamera();
                else permission.setVisibility(View.VISIBLE);
            });

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_scan);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_LABEL;

        preview = findViewById(R.id.preview);
        frame = findViewById(R.id.frame);
        scrim = findViewById(R.id.scrim);
        busy = findViewById(R.id.busy);
        busyText = findViewById(R.id.busyText);
        permission = findViewById(R.id.permission);
        shutter = findViewById(R.id.btnShutter);
        TextView title = findViewById(R.id.title);
        TextView hint = findViewById(R.id.hint);

        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        barcodeScanner = BarcodeScanning.getClient();

        switch (mode) {
            case MODE_PHOTO:
                title.setText("Foto do produto");
                hint.setText("Enquadre o produto e toque no botão");
                break;
            case MODE_BARCODE:
                title.setText("Código de barras");
                hint.setText("Aponte para o código de barras do produto");
                shutter.setVisibility(View.GONE);
                break;
            default:
                title.setText("Escanear etiqueta");
                hint.setText("Enquadre a etiqueta inteira: nome e preço");
        }

        findViewById(R.id.btnClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnPermClose).setOnClickListener(v -> finish());
        findViewById(R.id.btnGrant).setOnClickListener(v -> {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                askPermission.launch(Manifest.permission.CAMERA);
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
            }
        });
        findViewById(R.id.btnTorch).setOnClickListener(v -> {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                torch = !torch;
                camera.getCameraControl().enableTorch(torch);
                v.setAlpha(torch ? 1f : 0.7f);
            }
        });
        shutter.setOnClickListener(v -> capture());
        View gallery = findViewById(R.id.btnGallery);
        gallery.setVisibility(MODE_LABEL.equals(mode) ? View.VISIBLE : View.GONE);
        gallery.setOnClickListener(v -> pickImage.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build()));

        // Etiqueta compartilhada de outro app (Galeria, WhatsApp...): lê direto, sem abrir a câmera
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            Uri shared = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            mode = MODE_LABEL;
            if (shared != null) { readFromUri(shared); return; }
        }

        // espera o layout para que a moldura e o "viewport" (recorte da captura) tenham tamanho real
        preview.post(() -> {
            layoutFrame();
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                askPermission.launch(Manifest.permission.CAMERA);
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        if (permission.getVisibility() == View.VISIBLE
                && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permission.setVisibility(View.GONE);
            startCamera();
        }
    }

    /** Tamanho da moldura conforme o modo (etiqueta = retângulo deitado, foto = quadrado, código = faixa). */
    private void layoutFrame() {
        int w = preview.getWidth(), h = preview.getHeight();
        int fw, fh;
        if (MODE_PHOTO.equals(mode)) { fw = (int) (w * 0.86f); fh = fw; }
        else if (MODE_BARCODE.equals(mode)) { fw = (int) (w * 0.86f); fh = (int) (fw * 0.45f); }
        else { fw = (int) (w * 0.90f); fh = (int) (fw * 0.70f); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) frame.getLayoutParams();
        lp.width = fw;
        lp.height = fh;
        frame.setLayoutParams(lp);
        float l = (w - fw) / 2f, t = (h - fh) / 2f;
        scrim.setHole(l, t, l + fw, t + fh);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                ProcessCameraProvider provider = f.get();
                provider.unbindAll();
                Preview p = new Preview.Builder().build();
                p.setSurfaceProvider(preview.getSurfaceProvider());
                UseCaseGroup.Builder group = new UseCaseGroup.Builder().addUseCase(p);

                if (MODE_BARCODE.equals(mode)) {
                    ImageAnalysis analysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                    analysis.setAnalyzer(worker, this::analyzeBarcode);
                    group.addUseCase(analysis);
                } else {
                    imageCapture = new ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build();
                    group.addUseCase(imageCapture);
                }
                ViewPort vp = preview.getViewPort();
                if (vp != null) group.setViewPort(vp);

                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group.build());
                preview.setOnTouchListener((v, e) -> {
                    if (e.getAction() == MotionEvent.ACTION_UP && camera != null) {
                        MeteringPoint pt = preview.getMeteringPointFactory().createPoint(e.getX(), e.getY());
                        camera.getCameraControl().startFocusAndMetering(
                                new FocusMeteringAction.Builder(pt).setAutoCancelDuration(3, TimeUnit.SECONDS).build());
                    }
                    return true;
                });
            } catch (Exception ex) {
                Dialogs.error(findViewById(R.id.root), "Não foi possível abrir a câmera.");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ------------------------------------------------------------ captura (etiqueta / foto)

    private void capture() {
        if (imageCapture == null || busy.getVisibility() == View.VISIBLE) return;
        busyText.setText(MODE_PHOTO.equals(mode) ? "Salvando foto…" : "Lendo etiqueta…");
        busy.setVisibility(View.VISIBLE);
        File out = ImageUtils.tempFile(this);
        imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(out).build(), worker,
                new ImageCapture.OnImageSavedCallback() {
                    @Override public void onImageSaved(@NonNull ImageCapture.OutputFileResults r) {
                        if (MODE_PHOTO.equals(mode)) processPhoto(out);
                        else processLabel(out);
                    }
                    @Override public void onError(@NonNull ImageCaptureException e) {
                        runOnUiThread(() -> {
                            busy.setVisibility(View.GONE);
                            Dialogs.error(findViewById(R.id.root), "Falha ao capturar. Tente de novo.");
                        });
                    }
                });
    }

    /** Fração da imagem ocupada pela moldura (com uma pequena margem de segurança). */
    private RectF frameFraction() {
        float w = preview.getWidth(), h = preview.getHeight();
        float margin = 0.04f;
        float l = frame.getLeft() / w - margin, t = frame.getTop() / h - margin;
        float r = frame.getRight() / w + margin, b = frame.getBottom() / h + margin;
        return new RectF(Math.max(0, l), Math.max(0, t), Math.min(1, r), Math.min(1, b));
    }

    private void processPhoto(File f) {
        try {
            Bitmap full = ImageUtils.decode(f, 1600);
            Bitmap crop = ImageUtils.cropFraction(full, frameFraction());
            Bitmap small = Bitmap.createScaledBitmap(crop, Math.min(crop.getWidth(), 900),
                    Math.round(Math.min(crop.getWidth(), 900) * (crop.getHeight() / (float) crop.getWidth())), true);
            String path = ImageUtils.savePrivate(this, small, "produto");
            f.delete();
            runOnUiThread(() -> {
                setResult(RESULT_OK, new Intent().putExtra(R_PHOTO, path));
                finish();
            });
        } catch (Exception e) {
            fail("Não foi possível salvar a foto.");
        }
    }

    /** Lê a etiqueta de uma foto já existente (galeria ou compartilhada). */
    private void readFromUri(Uri uri) {
        busyText.setText("Lendo etiqueta…");
        busy.setVisibility(View.VISIBLE);
        worker.execute(() -> {
            File tmp = ImageUtils.tempFile(this);
            try (java.io.InputStream in = getContentResolver().openInputStream(uri);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                if (in == null) throw new java.io.IOException("sem dados");
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                fail("Não consegui abrir essa imagem.");
                return;
            }
            processLabel(tmp, false);
        });
    }

    private void processLabel(File f) { processLabel(f, true); }

    private void processLabel(File f, boolean cropToFrame) {
        try {
            Bitmap full = ImageUtils.decode(f, 2200);
            Bitmap crop = cropToFrame ? ImageUtils.cropFraction(full, frameFraction()) : full;

            LabelParser.Result res = recognize(crop);
            if (!res.hasPrice()) {
                // a etiqueta pode ter ficado parcialmente fora da moldura: tenta a foto inteira
                LabelParser.Result again = recognize(full);
                if (again.hasPrice() || res.name.isEmpty()) res = again;
            }
            String barcode = readBarcode(crop);
            if (barcode == null) barcode = res.barcode;
            android.util.Log.i("CarrinhoOCR", "texto=" + res.rawText.replace('\n', '|') + " ; nome=" + res.name
                    + " ; precos=" + res.prices + " ; codigo=" + barcode);

            Bitmap labelSmall = scaleTo(crop, 1100);
            String labelPath = ImageUtils.savePrivate(this, labelSmall, "etiqueta");
            f.delete();

            long[] prices = new long[res.prices.size()];
            for (int i = 0; i < prices.length; i++) prices[i] = res.prices.get(i).cents;
            Intent data = new Intent()
                    .putExtra(R_NAME, res.name)
                    .putExtra(R_NAMES, res.nameCandidates.toArray(new String[0]))
                    .putExtra(R_PRICES, prices)
                    .putExtra(R_BARCODE, barcode)
                    .putExtra(R_LABEL_PHOTO, labelPath)
                    .putExtra(R_RAW, res.rawText);
            runOnUiThread(() -> deliver(data));
        } catch (Exception e) {
            android.util.Log.e("CarrinhoOCR", "falha ao ler etiqueta", e);
            fail("Não consegui ler a etiqueta. Tente aproximar e evitar reflexo.");
        }
    }

    private final ActivityResultLauncher<PickVisualMediaRequest> pickImage =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null) readFromUri(uri);
            });

    private void deliver(Intent data) {
        if (getCallingActivity() != null) {
            setResult(RESULT_OK, data);
        } else if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            // veio de outro app: abre o carrinho por baixo, para voltar a ele depois de salvar
            Intent main = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            Intent item = new Intent(this, ItemActivity.class).putExtras(data);
            startActivities(new Intent[]{main, item});
        } else {
            Intent i = new Intent(this, ItemActivity.class);
            i.putExtras(data);
            startActivity(i);
        }
        finish();
    }

    private LabelParser.Result recognize(Bitmap bmp) throws Exception {
        Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 45, TimeUnit.SECONDS);
        List<OcrLine> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                android.graphics.Rect r = line.getBoundingBox();
                if (r == null) continue;
                OcrLine ol = new OcrLine(line.getText(), r.left, r.top, r.right, r.bottom);
                for (Text.Element el : line.getElements()) {
                    android.graphics.Rect er = el.getBoundingBox();
                    if (er != null) ol.addWord(new OcrLine(el.getText(), er.left, er.top, er.right, er.bottom));
                }
                lines.add(ol);
            }
        }
        return LabelParser.parse(lines);
    }

    private String readBarcode(Bitmap bmp) {
        try {
            List<Barcode> codes = Tasks.await(barcodeScanner.process(InputImage.fromBitmap(bmp, 0)), 10, TimeUnit.SECONDS);
            for (Barcode b : codes) if (b.getRawValue() != null && !b.getRawValue().isEmpty()) return b.getRawValue();
        } catch (Exception ignored) { }
        return null;
    }

    private static Bitmap scaleTo(Bitmap b, int maxSide) {
        float s = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
        if (s >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.round(b.getWidth() * s), Math.round(b.getHeight() * s), true);
    }

    private void fail(String msg) {
        runOnUiThread(() -> {
            busy.setVisibility(View.GONE);
            Dialogs.error(findViewById(R.id.root), msg);
        });
    }

    // ------------------------------------------------------------ código de barras ao vivo

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeBarcode(ImageProxy proxy) {
        if (done || proxy.getImage() == null) { proxy.close(); return; }
        InputImage img = InputImage.fromMediaImage(proxy.getImage(), proxy.getImageInfo().getRotationDegrees());
        barcodeScanner.process(img)
                .addOnSuccessListener(codes -> {
                    for (Barcode b : codes) {
                        String v = b.getRawValue();
                        if (v != null && !v.isEmpty() && !done) {
                            done = true;
                            beep();
                            setResult(RESULT_OK, new Intent().putExtra(R_BARCODE, v));
                            finish();
                            break;
                        }
                    }
                })
                .addOnCompleteListener(t -> proxy.close());
    }

    private void beep() {
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120);
        } catch (Exception ignored) { }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
        recognizer.close();
        barcodeScanner.close();
    }
}
