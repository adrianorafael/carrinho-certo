package br.com.carrinhocerto.ui;

import android.os.Bundle;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

import br.com.carrinhocerto.R;

/** Mostra uma foto em tela cheia. Toque para fechar. */
public class PhotoActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "path";

    @Override protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_photo);
        ImageView img = findViewById(R.id.img);
        String p = getIntent().getStringExtra(EXTRA_PATH);
        if (p == null || !new File(p).exists()) { finish(); return; }
        ImageUtils.loadThumb(img, p, 1600);
        img.setOnClickListener(v -> finish());
    }
}
