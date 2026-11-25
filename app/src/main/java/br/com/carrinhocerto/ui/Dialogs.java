package br.com.carrinhocerto.ui;

import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.FrameLayout;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

import br.com.carrinhocerto.R;
import br.com.carrinhocerto.data.Db;

public final class Dialogs {
    private Dialogs() {}

    public interface OnText { void onText(String text); }

    /** Pergunta o nome do estabelecimento, sugerindo os já usados. */
    public static void askStore(Context c, String title, String message, String current, String positive, OnText cb) {
        List<String> stores = Db.get(c).stores();
        TextInputLayout til = new TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle);
        til.setHint("Supermercado (opcional)");
        til.setEndIconMode(stores.isEmpty() ? TextInputLayout.END_ICON_NONE : TextInputLayout.END_ICON_DROPDOWN_MENU);
        AutoCompleteTextView et = new AutoCompleteTextView(til.getContext());
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setThreshold(1);
        et.setAdapter(new ArrayAdapter<>(c, android.R.layout.simple_dropdown_item_1line, stores));
        et.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        if (current != null) et.setText(current);
        til.addView(et);
        FrameLayout box = new FrameLayout(c);
        int p = dp(c, 24);
        box.setPadding(p, dp(c, 8), p, 0);
        box.addView(til);

        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(c)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(positive, (d, w) -> cb.onText(et.getText().toString().trim()))
                .setNegativeButton("Cancelar", null);
        if (message != null) b.setMessage(message);
        b.show();
        et.requestFocus();
    }

    public static void ok(View anchor, String msg) {
        Snackbar s = Snackbar.make(anchor, msg, Snackbar.LENGTH_SHORT);
        s.setBackgroundTint(anchor.getContext().getColor(R.color.green));
        s.setTextColor(anchor.getContext().getColor(R.color.white));
        s.show();
    }

    public static void error(View anchor, String msg) {
        Snackbar s = Snackbar.make(anchor, msg, Snackbar.LENGTH_LONG);
        s.setBackgroundTint(anchor.getContext().getColor(R.color.red));
        s.setTextColor(anchor.getContext().getColor(R.color.white));
        s.show();
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
