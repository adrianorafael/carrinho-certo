package br.com.carrinhocerto.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cupom fiscal (NFC-e / SAT) lido do XML ou por OCR.
 * O leitor de texto entende o layout do DANFE NFC-e: "001 7891000100103 LEITE UHT 1L 6 UN X 1,79 10,74",
 * com variações (descrição numa linha e valores na seguinte, peso, linhas de desconto).
 */
public final class Receipt {

    public static final class Line {
        public int seq = -1;            // número do item no cupom (001, 002…), -1 se não lido
        public String code;             // código/EAN impresso
        public String desc = "";
        public double qty = 1;
        public String unit = "UN";
        public long unitCents = -1;
        public long totalCents = -1;    // valor bruto do item
        public long discountCents;      // desconto do próprio item (positivo)
        public long surchargeCents;     // acréscimo do item
        public boolean suspect;         // qtd × unitário não bate com o total: revisar

        public boolean byWeight() { return unit.startsWith("K") || unit.equals("G") || (qty != Math.rint(qty)); }
        public long netCents() {
            long t = totalCents >= 0 ? totalCents : Math.round(unitCents * qty);
            return t - discountCents + surchargeCents;
        }
        @Override public String toString() {
            return (seq >= 0 ? seq + " " : "") + desc + " | " + Money.formatQty(qty) + " " + unit + " x " + unitCents + " = " + totalCents
                    + (discountCents > 0 ? " desc " + discountCents : "") + (suspect ? " (?)" : "");
        }
    }

    public final List<Line> lines = new ArrayList<>();
    public long totalCents = -1;        // "Valor a pagar"/"Valor total" impresso
    public long discountTotalCents;
    public String store;
    public final List<String> rawRows = new ArrayList<>();

    public long sumNet() {
        long s = 0;
        for (Line l : lines) s += l.netCents();
        return s;
    }

    // ------------------------------------------------------------ OCR: agrupar em linhas

    /** Junta blocos do OCR que estão na mesma altura (as colunas do cupom vêm separadas). */
    public static List<String> rows(List<OcrLine> ocr) {
        List<OcrLine> ls = new ArrayList<>(ocr);
        Collections.sort(ls, (a, b) -> Integer.compare(a.centerY(), b.centerY()));
        List<List<OcrLine>> groups = new ArrayList<>();
        for (OcrLine l : ls) {
            List<OcrLine> g = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (g != null) {
                OcrLine ref = g.get(0);
                int tol = Math.max(4, (int) (Math.min(ref.height(), l.height()) * 0.55));
                if (Math.abs(l.centerY() - avgY(g)) <= tol) { g.add(l); continue; }
            }
            List<OcrLine> ng = new ArrayList<>();
            ng.add(l);
            groups.add(ng);
        }
        List<String> out = new ArrayList<>();
        for (List<OcrLine> g : groups) {
            Collections.sort(g, (a, b) -> Integer.compare(a.left, b.left));
            StringBuilder sb = new StringBuilder();
            for (OcrLine l : g) { if (sb.length() > 0) sb.append("  "); sb.append(l.text.trim()); }
            out.add(sb.toString());
        }
        return out;
    }

    private static int avgY(List<OcrLine> g) {
        int s = 0;
        for (OcrLine l : g) s += l.centerY();
        return s / g.size();
    }

    // ------------------------------------------------------------ texto -> itens

    private static final String NUM = "(\\d{1,5}(?:\\.\\d{3})*[.,]\\d{2,3})";
    private static final Pattern QTY_X_UNIT = Pattern.compile(
            "(\\d{1,4}(?:[.,]\\d{1,3})?)\\s*(UN|UND|UNID|KG|G|GR|L|LT|ML|PC|PCT|CX|FD|DZ|BD|PT|SC)?\\s*[xX×*]\\s*" + NUM,
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_VALUE = Pattern.compile(NUM + "\\s*[A-Z]?\\s*$");
    private static final Pattern ITEM_START = Pattern.compile("^(\\d{1,3})\\s+(\\d{3,14})?\\s*([A-Za-zÀ-ÿ].*)$");
    private static final Pattern CODE_START = Pattern.compile("^(\\d{4,14})\\s+([A-Za-zÀ-ÿ].*)$");
    private static final Pattern DISCOUNT = Pattern.compile("^\\s*(DESC(ONTO)?|DESC\\.)\\b.*?-?\\s*" + NUM + "\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SURCHARGE = Pattern.compile("^\\s*(ACRESC|ACR[EÉ]SCIMO).*?" + NUM + "\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_PAY = Pattern.compile("(VALOR\\s+A\\s+PAGAR|VALOR\\s+PAGO|TOTAL\\s+A\\s+PAGAR)\\D{0,12}" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL = Pattern.compile("(VALOR\\s+TOTAL|^TOTAL\\b|TOTAL\\s+R\\$)\\D{0,12}" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern DISC_TOTAL = Pattern.compile("^\\s*DESCONTOS?\\b\\D{0,12}" + NUM, Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_ITEMS = Pattern.compile("(QTD\\.?\\s*TOTAL|QUANTIDADE\\s+TOTAL|FORMA\\s+(DE\\s+)?PAGAMENTO|VALOR\\s+TOTAL|VALOR\\s+A\\s+PAGAR|TROCO|CONSULTE\\s+PELA|CHAVE\\s+DE\\s+ACESSO)", Pattern.CASE_INSENSITIVE);

    public static Receipt parseText(List<String> textRows) {
        Receipt r = new Receipt();
        Line cur = null;
        boolean itemsDone = false;
        for (String raw : textRows) {
            String row = clean(raw);
            if (row.isEmpty()) continue;
            r.rawRows.add(row);
            String up = row.toUpperCase(Locale.ROOT);

            Matcher mt = TOTAL_PAY.matcher(row);
            if (mt.find()) { r.totalCents = cents(mt.group(2)); itemsDone = true; continue; }
            Matcher mt2 = TOTAL.matcher(row);
            if (mt2.find() && !up.contains("ITENS") && !up.contains("TRIBUT")) {
                if (r.totalCents < 0) r.totalCents = cents(mt2.group(2));
                itemsDone = true;
                continue;
            }
            if (itemsDone) {
                Matcher dt = DISC_TOTAL.matcher(row);
                if (dt.find()) r.discountTotalCents = cents(dt.group(1));
                continue;
            }
            if (STOP_ITEMS.matcher(row).find()) { itemsDone = true; continue; }
            if (up.contains("CANCELAD")) { if (cur != null) { r.lines.remove(cur); cur = null; } continue; }

            Matcher dm = DISCOUNT.matcher(row);
            if (dm.find() && cur != null && !QTY_X_UNIT.matcher(row).find()) { cur.discountCents += cents(dm.group(3)); continue; }
            Matcher sm = SURCHARGE.matcher(row);
            if (sm.find() && cur != null) { cur.surchargeCents += cents(sm.group(2)); continue; }

            Matcher q = QTY_X_UNIT.matcher(row);
            boolean hasQty = q.find();
            String head = hasQty ? row.substring(0, q.start()).trim() : row;

            Matcher is = ITEM_START.matcher(head);
            Matcher cs = CODE_START.matcher(head);
            boolean startsItem = (is.matches() && letters(is.group(3)) >= 3) || (cs.matches() && letters(cs.group(2)) >= 3);

            if (startsItem) {
                cur = new Line();
                if (is.matches() && letters(is.group(3)) >= 3) {
                    cur.seq = Integer.parseInt(is.group(1));
                    cur.code = is.group(2);
                    cur.desc = is.group(3).trim();
                } else {
                    cur.code = cs.group(1);
                    cur.desc = cs.group(2).trim();
                }
                r.lines.add(cur);
            } else if (!hasQty && cur != null && cur.unitCents < 0 && letters(head) >= 1 && !head.matches(".*\\d[.,]\\d{2}.*")
                    && digits(head) <= letters(head) + 3) {
                cur.desc = (cur.desc + " " + head).trim();          // descrição continuou na linha de baixo
                continue;
            } else if (hasQty && cur != null && cur.unitCents >= 0) {
                // valores sem cabeçalho: item sem número/código; usa o que vier antes como descrição
                if (letters(head) >= 3) { cur = new Line(); cur.desc = head; r.lines.add(cur); }
            } else if (hasQty && cur == null && letters(head) >= 3) {
                cur = new Line(); cur.desc = head; r.lines.add(cur);
            }

            if (hasQty && cur != null) {
                cur.qty = qty(q.group(1));
                cur.unit = q.group(2) == null ? "UN" : q.group(2).toUpperCase(Locale.ROOT);
                cur.unitCents = cents(q.group(3));
                String tail = row.substring(q.end());
                tail = tail.replaceAll("\\([^)]*\\)", " ");          // valor aproximado de tributos "(0,54)"
                Matcher tv = TRAILING_VALUE.matcher(tail.trim());
                if (tv.find()) cur.totalCents = cents(tv.group(1));
                cur.desc = trimDesc(cur.desc);
            }
        }
        // limpa e valida
        List<Line> ok = new ArrayList<>();
        for (Line l : r.lines) {
            if (l.unitCents < 0 && l.totalCents < 0) continue;     // cabeçalho sem valores
            if (l.totalCents < 0) l.totalCents = Math.round(l.unitCents * l.qty);
            if (l.unitCents < 0 && l.qty > 0) l.unitCents = Math.round(l.totalCents / l.qty);
            long calc = Math.round(l.unitCents * l.qty);
            l.suspect = Math.abs(calc - l.totalCents) > Math.max(2, l.totalCents / 100);
            l.desc = trimDesc(l.desc);
            ok.add(l);
        }
        r.lines.clear();
        r.lines.addAll(ok);
        return r;
    }

    private static String clean(String s) {
        String t = s.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        t = t.replaceAll("(?<=\\d)\\s+,\\s*(?=\\d)", ",").replaceAll("(?<=\\d),\\s+(?=\\d)", ",");
        return t;
    }

    private static String trimDesc(String d) {
        String t = d.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("\\s+(UN|KG|PC)$", "");
        return t;
    }

    private static int letters(String s) { int n = 0; for (char c : s.toCharArray()) if (Character.isLetter(c)) n++; return n; }
    private static int digits(String s) { int n = 0; for (char c : s.toCharArray()) if (Character.isDigit(c)) n++; return n; }

    static long cents(String v) {
        String s = v.trim();
        int sep = Math.max(s.lastIndexOf(','), s.lastIndexOf('.'));
        String intPart = s.substring(0, sep).replace(".", "").replace(",", "");
        String dec = s.substring(sep + 1);
        if (dec.length() == 3) { // valor unitário com 3 casas (ex.: 6,990) -> arredonda
            return Math.round((Long.parseLong(intPart) * 1000 + Long.parseLong(dec)) / 10.0);
        }
        return Long.parseLong(intPart) * 100 + Long.parseLong(dec);
    }

    private static double qty(String v) {
        return Double.parseDouble(v.replace(',', '.'));
    }

    // ------------------------------------------------------------ várias fotos

    /**
     * Junta leituras de várias fotos do mesmo cupom. Com número de item, une pelo número.
     * Sem número, remove só a sobreposição entre o fim de uma foto e o começo da seguinte
     * (o mesmo produto pode aparecer em duas linhas legítimas do cupom).
     */
    public static Receipt merge(List<Receipt> parts) {
        Receipt out = new Receipt();
        for (Receipt p : parts) {
            boolean numbered = !p.lines.isEmpty();
            for (Line l : p.lines) if (l.seq < 0) { numbered = false; break; }
            if (numbered) {
                for (Line l : p.lines) {
                    boolean seen = false;
                    for (Line o : out.lines) if (o.seq == l.seq) { seen = true; break; }
                    if (!seen) out.lines.add(l);
                }
            } else {
                int overlap = 0;
                int maxK = Math.min(out.lines.size(), p.lines.size());
                for (int k = maxK; k >= 1; k--) {
                    boolean same = true;
                    for (int i = 0; i < k; i++) {
                        Line a = out.lines.get(out.lines.size() - k + i), b = p.lines.get(i);
                        if (a.totalCents != b.totalCents || !sameDesc(a.desc, b.desc)) { same = false; break; }
                    }
                    if (same) { overlap = k; break; }
                }
                for (int i = overlap; i < p.lines.size(); i++) out.lines.add(p.lines.get(i));
            }
            if (p.totalCents >= 0) out.totalCents = p.totalCents;
            if (p.discountTotalCents > 0) out.discountTotalCents = p.discountTotalCents;
            if (out.store == null) out.store = p.store;
            out.rawRows.addAll(p.rawRows);
        }
        boolean allNumbered = true;
        for (Line l : out.lines) if (l.seq < 0) { allNumbered = false; break; }
        if (allNumbered) Collections.sort(out.lines, (a, b) -> Integer.compare(a.seq, b.seq));
        return out;
    }

    private static boolean sameDesc(String a, String b) {
        return ProductMatcher.normalize(a).replace(" ", "").equals(ProductMatcher.normalize(b).replace(" ", ""));
    }
}
