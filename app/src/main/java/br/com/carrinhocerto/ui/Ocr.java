package br.com.carrinhocerto.ui;

import android.graphics.Bitmap;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import br.com.carrinhocerto.core.OcrLine;

/** OCR embarcado (ML Kit) convertido para as linhas com posição usadas pelos leitores. Chamar fora da thread principal. */
public final class Ocr {
    private Ocr() {}

    public static List<OcrLine> lines(TextRecognizer recognizer, Bitmap bmp) throws Exception {
        Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(bmp, 0)), 150, TimeUnit.SECONDS);
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
        return lines;
    }
}
