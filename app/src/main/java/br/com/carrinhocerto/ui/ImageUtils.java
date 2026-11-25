package br.com.carrinhocerto.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.widget.ImageView;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Fotos: lê com a rotação certa, recorta, reduz e grava em armazenamento privado do app. */
public final class ImageUtils {
    private ImageUtils() {}

    public static File photosDir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File tempFile(Context c) {
        return new File(c.getCacheDir(), "cap_" + System.currentTimeMillis() + ".jpg");
    }

    /** Decodifica aplicando a orientação EXIF e limitando o lado maior a maxSide. */
    public static Bitmap decode(File f, int maxSide) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (b == null) throw new IOException("Imagem inválida");

        int rot = 0;
        try {
            ExifInterface exif = new ExifInterface(f.getAbsolutePath());
            rot = exif.getRotationDegrees();
        } catch (Exception ignored) { }
        Matrix m = new Matrix();
        if (rot != 0) m.postRotate(rot);
        float scale = Math.min(1f, (float) maxSide / Math.max(b.getWidth(), b.getHeight()));
        if (scale < 1f) m.postScale(scale, scale);
        if (rot != 0 || scale < 1f) {
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        return b;
    }

    /** Recorta pela fração (0..1) da área visível; usado para ficar só com a moldura da etiqueta. */
    public static Bitmap cropFraction(Bitmap src, RectF frac) {
        int l = clamp(Math.round(frac.left * src.getWidth()), 0, src.getWidth() - 1);
        int t = clamp(Math.round(frac.top * src.getHeight()), 0, src.getHeight() - 1);
        int r = clamp(Math.round(frac.right * src.getWidth()), l + 1, src.getWidth());
        int b = clamp(Math.round(frac.bottom * src.getHeight()), t + 1, src.getHeight());
        return Bitmap.createBitmap(src, l, t, r - l, b - t);
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Grava JPEG compactado na pasta privada do app e retorna o caminho. */
    public static String savePrivate(Context c, Bitmap b, String prefix) throws IOException {
        File out = new File(photosDir(c), prefix + "_" + UUID.randomUUID() + ".jpg");
        try (FileOutputStream fo = new FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.JPEG, 82, fo);
        }
        return out.getAbsolutePath();
    }

    /** Carrega miniatura leve para listas. */
    public static void loadThumb(ImageView v, String path, int sizePx) {
        if (path == null || !new File(path).exists()) { v.setImageDrawable(null); return; }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while (Math.min(o.outWidth, o.outHeight) / (sample * 2) >= sizePx) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        v.setImageBitmap(BitmapFactory.decodeFile(path, o2));
    }
}
