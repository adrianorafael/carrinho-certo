package br.com.carrinhocerto.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Escurece tudo fora da moldura de leitura, destacando onde a etiqueta deve ficar. */
public class ScrimView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hole = new RectF();
    private final Path path = new Path();
    private float radius;

    public ScrimView(Context c, AttributeSet a) {
        super(c, a);
        paint.setColor(0x8C000000);
        radius = 20 * c.getResources().getDisplayMetrics().density;
    }

    public void setHole(float l, float t, float r, float b) {
        hole.set(l, t, r, b);
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
        if (!hole.isEmpty()) path.addRoundRect(hole, radius, radius, Path.Direction.CW);
        c.drawPath(path, paint);
    }
}
