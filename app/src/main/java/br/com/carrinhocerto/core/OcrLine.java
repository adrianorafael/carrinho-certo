package br.com.carrinhocerto.core;

import java.util.ArrayList;
import java.util.List;

/** Uma linha de texto reconhecida, com sua caixa delimitadora e as palavras que a compõem. */
public final class OcrLine {
    public final String text;
    public final int left, top, right, bottom;
    public final List<OcrLine> words = new ArrayList<>();

    public OcrLine(String text, int left, int top, int right, int bottom) {
        this.text = text == null ? "" : text;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int height() { return Math.max(1, bottom - top); }
    public int width() { return Math.max(1, right - left); }
    public int centerY() { return (top + bottom) / 2; }

    public OcrLine addWord(OcrLine w) { words.add(w); return this; }

    @Override public String toString() { return text + " [" + left + "," + top + "," + right + "," + bottom + "]"; }
}
