package br.com.carrinhocerto.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpreta o texto de uma etiqueta de gôndola de supermercado brasileiro.
 *
 * Usa a geometria do OCR (altura das linhas) além do texto: em etiquetas o preço de venda
 * costuma ser o número mais alto, o nome do produto fica no topo e o preço por kg/litro,
 * datas, códigos internos e o EAN aparecem em letras menores.
 */
public final class LabelParser {

    public static final class PriceCandidate {
        public final long cents;
        public final double score;
        public final String source;
        PriceCandidate(long cents, double score, String source) {
            this.cents = cents; this.score = score; this.source = source;
        }
        @Override public String toString() { return Money.format(cents) + " (" + String.format(Locale.ROOT, "%.2f", score) + ")"; }
    }

    public static PriceCandidate candidate(long cents, String source) { return new PriceCandidate(cents, 1.0, source); }

    public static final class Result {
        public String name = "";
        public final List<String> nameCandidates = new ArrayList<>();
        public final List<PriceCandidate> prices = new ArrayList<>();
        public String barcode = null;
        public String rawText = "";

        public long bestPriceCents() { return prices.isEmpty() ? -1 : prices.get(0).cents; }
        public boolean hasPrice() { return !prices.isEmpty(); }
    }

    // Preço com decimais: "24,90", "R$24.90", "1.234,56". Não pode estar colado a outros dígitos.
    private static final Pattern PRICE = Pattern.compile("(?<![\\d/.,])(\\d{1,3}(?:\\.\\d{3})+|\\d{1,4})\\s?[,.]\\s?(\\d{2})(?![\\d%/])(?!\\s?(?i:KG|G|GR|ML|L|LT|UN|M)\\b)");
    private static final Pattern DATE = Pattern.compile("\\b\\d{1,2}[/-]\\d{1,2}([/-]\\d{2,4})?\\b");
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d{8,14}");
    private static final Pattern INT_ONLY = Pattern.compile("^(?:R\\$)?\\s?(\\d{1,4})[,.]?$");
    private static final Pattern CENTS_ONLY = Pattern.compile("^[,.]?(\\d{2})$");

    // Indicadores de preço por unidade de medida (não é o preço a pagar)
    private static final Pattern UNIT_PRICE_HINT = Pattern.compile(
            "(R\\$\\s?/\\s?(KG|K6|L|LT|ML|G|M|UN|100)|/\\s?(KG|K6|LT|L|ML|100\\s?G|100\\s?ML|UN|M)\\b|POR\\s+(KG|QUILO|LITRO|UN)|PRE[CÇ]O\\s+(DO|POR)\\s+(KG|QUILO|LITRO|L)|\\bKG\\s*[:=]|\\bLITRO\\b|UNIDADE\\s+DE\\s+MEDIDA|VALOR\\s+(DO|POR)\\s+(KG|L))",
            Pattern.CASE_INSENSITIVE);

    private static final String[] NAME_STOPWORDS = {
            "PRECO", "OFERTA", "PROMOCAO", "PROMO", "COD", "CODIGO", "VALIDADE", "VALIDO", "VAL ", "LEVE", "PAGUE",
            "CLUBE", "CARTAO", "A VISTA", "AVISTA", "DATA", "EAN", "R$", "UNIDADE", "ATACADO", "VAREJO",
            "A PARTIR", "APARTIR", "CADA", "SETOR", "FORNECEDOR", "ESTOQUE", "EMBALAGEM", "PLU", "SKU",
            "POR KG", "POR LITRO", "IMPOSTO", "TRIBUTOS", "LEI ", "WWW", ".COM", "APP", "MENOR PRECO",
            "SUPER", "MERCADO", "HIPER", "ECONOMIZE", "DESCONTO", "IMPRESSO", "GONDOLA", "ETIQUETA", "UND", "PRECO NORMAL"
    };

    private LabelParser() {}

    public static Result parse(List<OcrLine> lines) {
        Result r = new Result();
        if (lines == null || lines.isEmpty()) return r;
        List<OcrLine> sorted = new ArrayList<>(lines);
        Collections.sort(sorted, (a, b) -> Integer.compare(a.top, b.top));
        StringBuilder raw = new StringBuilder();
        for (OcrLine l : sorted) raw.append(l.text).append('\n');
        r.rawText = raw.toString().trim();

        int maxH = 1;
        for (OcrLine l : sorted) maxH = Math.max(maxH, l.height());

        r.barcode = findBarcode(sorted);
        findPrices(sorted, maxH, r);
        findName(sorted, maxH, r);
        return r;
    }

    // ---------------------------------------------------------------- preço

    private static void findPrices(List<OcrLine> lines, int maxH, Result r) {
        List<PriceCandidate> cands = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            OcrLine line = lines.get(i);
            String text = fixDigits(line.text);
            String noDates = DATE.matcher(text).replaceAll(" ");
            noDates = DIGIT_RUN.matcher(noDates).replaceAll(" ");

            boolean unitLine = UNIT_PRICE_HINT.matcher(line.text).find();
            // Linha imediatamente acima pode ser o rótulo "R$/kg" pequeno
            boolean prevUnitLabel = i > 0 && isSmallUnitLabel(lines.get(i - 1), line);

            Matcher m = PRICE.matcher(noDates);
            while (m.find()) {
                long cents = toCents(m.group(1), m.group(2));
                if (cents <= 0 || cents > 10_000_00) continue;
                int h = heightOfToken(line, m.group());
                double score = (double) h / maxH;        // tamanho da fonte domina
                String before = noDates.substring(Math.max(0, m.start() - 4), m.start()).toUpperCase(Locale.ROOT);
                if (before.contains("R$") || before.contains("RS")) score += 0.15;
                if (unitLine && unitHintNear(line.text, m.start(), m.end())) score -= 1.0; // preço por kg/L
                if (prevUnitLabel) score -= 0.5;
                String upper = normalize(line.text);
                if (upper.contains("DE ") && upper.contains("POR ")) score -= 0.05; // "de 9,99 por 7,99": o "de" é o antigo
                if (isOldPrice(upper, m.start())) score -= 0.4;
                if (score < -0.2) continue;
                cands.add(new PriceCandidate(cents, score, line.text));
            }
        }

        // Reais e centavos separados: "24" grande + ",90"/"90" pequeno à direita (superscrito)
        for (OcrLine line : lines) {
            List<OcrLine> parts = line.words.isEmpty() ? Collections.singletonList(line) : line.words;
            for (int i = 0; i < parts.size(); i++) {
                OcrLine a = parts.get(i);
                Matcher mi = INT_ONLY.matcher(fixDigits(a.text).trim());
                if (!mi.matches()) continue;
                OcrLine b = (i + 1 < parts.size()) ? parts.get(i + 1) : findCentsNear(a, lines);
                if (b == null) continue;
                Matcher mc = CENTS_ONLY.matcher(fixDigits(b.text).trim());
                if (!mc.matches()) continue;
                boolean rightOf = b.left >= a.right - a.width() * 0.2 && b.left - a.right < a.height() * 1.2;
                boolean topAligned = b.top <= a.centerY() + a.height() * 0.1;
                if (!rightOf || !topAligned) continue;
                long cents = toCents(mi.group(1), mc.group(1));
                if (cents <= 0) continue;
                double score = (double) a.height() / maxH + 0.1;
                if (UNIT_PRICE_HINT.matcher(line.text).find()) score -= 1.0;
                if (score < -0.2) continue;
                cands.add(new PriceCandidate(cents, score, a.text + " " + b.text));
            }
        }

        Collections.sort(cands, (x, y) -> Double.compare(y.score, x.score));
        Set<Long> seen = new LinkedHashSet<>();
        for (PriceCandidate c : cands) {
            if (seen.add(c.cents)) r.prices.add(c);
            if (r.prices.size() >= 4) break;
        }
    }

    private static OcrLine findCentsNear(OcrLine a, List<OcrLine> lines) {
        OcrLine best = null;
        for (OcrLine l : lines) {
            if (l == a) continue;
            List<OcrLine> parts = l.words.isEmpty() ? Collections.singletonList(l) : l.words;
            for (OcrLine p : parts) {
                if (p == a) continue;
                if (!CENTS_ONLY.matcher(fixDigits(p.text).trim()).matches()) continue;
                if (p.height() >= a.height()) continue;
                if (p.left < a.right - a.width() * 0.2 || p.left - a.right > a.height() * 1.2) continue;
                if (p.top < a.top - a.height() * 0.3 || p.top > a.centerY() + a.height() * 0.1) continue;
                if (best == null || p.left < best.left) best = p;
            }
        }
        return best;
    }

    private static boolean isSmallUnitLabel(OcrLine prev, OcrLine line) {
        String t = prev.text.trim().toUpperCase(Locale.ROOT);
        return t.length() <= 12 && UNIT_PRICE_HINT.matcher(t).find()
                && !PRICE.matcher(fixDigits(t)).find()
                && line.top - prev.bottom < line.height();
    }

    private static boolean unitHintNear(String text, int start, int end) {
        // Se a linha tem dois preços (ex.: "R$ 24,90  R$/KG 4,98"), só penaliza o que está perto do indicador.
        Matcher h = UNIT_PRICE_HINT.matcher(text);
        Matcher p = PRICE.matcher(fixDigits(text));
        int prices = 0;
        while (p.find()) prices++;
        if (prices <= 1) return true;
        while (h.find()) {
            if (h.end() <= start && start - h.end() <= 6) return true;   // "R$/KG 4,98"
            if (h.start() >= end && h.start() - end <= 3) return true;   // "4,98/KG"
        }
        return false;
    }

    private static boolean isOldPrice(String upperNormalized, int pos) {
        // "DE R$ 9,99 POR R$ 7,99" ou "PRECO NORMAL 9,99"
        String before = upperNormalized.substring(0, Math.min(pos, upperNormalized.length()));
        int de = before.lastIndexOf("DE ");
        int por = before.lastIndexOf("POR ");
        if (before.contains("NORMAL") || before.contains("ANTES")) return true;
        return de >= 0 && de > por && before.length() - de <= 8 && upperNormalized.contains("POR ");
    }

    private static int heightOfToken(OcrLine line, String token) {
        if (line.words.isEmpty()) return line.height();
        String t = token.replace(" ", "");
        int best = 0;
        for (OcrLine w : line.words) {
            String wt = fixDigits(w.text).replace(" ", "");
            if (!wt.isEmpty() && (t.contains(wt) || wt.contains(t))) best = Math.max(best, w.height());
        }
        return best > 0 ? best : line.height();
    }

    private static long toCents(String intPart, String dec) {
        try {
            return Long.parseLong(intPart.replace(".", "")) * 100 + Long.parseLong(dec);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Corrige confusões típicas do OCR em tokens que são quase todos numéricos. */
    static String fixDigits(String s) {
        s = s.replaceAll("\\bR[S5]\\s?(?=[\\d,.OoIl])", "R\\$ ");   // "RS 4,39" / "R5,49" = "R$"
        StringBuilder out = new StringBuilder();
        Matcher m = Pattern.compile("[0-9OoIlSBZ,.]+").matcher(s);
        int last = 0;
        while (m.find()) {
            String tok = m.group();
            int digits = 0;
            for (char c : tok.toCharArray()) if (Character.isDigit(c)) digits++;
            out.append(s, last, m.start());
            if (digits >= 1 && digits * 2 >= countAlnum(tok)) {
                out.append(tok.replace('O', '0').replace('o', '0').replace('I', '1').replace('l', '1')
                        .replace('S', '5').replace('B', '8').replace('Z', '2'));
            } else {
                out.append(tok);
            }
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    private static int countAlnum(String s) {
        int n = 0;
        for (char c : s.toCharArray()) if (Character.isLetterOrDigit(c)) n++;
        return Math.max(1, n);
    }

    /**
     * Leitura de um recorte ampliado que contém só o preço: junta o número inteiro mais alto
     * com os centavos (",49"), mesmo que o OCR tenha embaralhado a ordem ou grudado o "R$".
     * Retorna centavos ou -1.
     */
    public static long parseZoomedPrice(List<OcrLine> lines) {
        List<OcrLine> toks = new ArrayList<>();
        for (OcrLine l : lines) {
            if (l.words.isEmpty()) toks.add(l); else toks.addAll(l.words);
        }
        long direct = -1; int directH = 0;
        OcrLine bigInt = null; String bigIntVal = null;
        String cents = null; int centsH = 0;
        for (OcrLine t : toks) {
            String v = fixDigits(t.text).replace("R$", "").replace("$", "").replace(" ", "");
            Matcher full = Pattern.compile("^(\\d{1,4})[,.](\\d{2})$").matcher(v);
            if (full.matches() && t.height() > directH) { direct = toCents(full.group(1), full.group(2)); directH = t.height(); continue; }
            Matcher in = Pattern.compile("^(\\d{1,4})[,.]?$").matcher(v);
            if (in.matches() && (bigInt == null || t.height() > bigInt.height())) { bigInt = t; bigIntVal = in.group(1); continue; }
            Matcher c = Pattern.compile("^[,.](\\d{2})$|^(\\d{2})$").matcher(v);
            if (c.matches()) { String cv = c.group(1) != null ? c.group(1) : c.group(2); if (cents == null || v.startsWith(",") || v.startsWith(".")) { cents = cv; centsH = t.height(); } }
        }
        if (bigInt != null && cents != null) {
            long joined = toCents(bigIntVal, cents);
            if (direct < 0 || bigInt.height() > directH) return joined;
        }
        return direct;
    }

    // ---------------------------------------------------------------- código de barras

    private static String findBarcode(List<OcrLine> lines) {
        for (OcrLine l : lines) {
            Matcher m = DIGIT_RUN.matcher(l.text.replace(" ", ""));
            while (m.find()) {
                String d = m.group();
                if ((d.length() == 13 || d.length() == 8 || d.length() == 12 || d.length() == 14) && validGtin(d)) return d;
            }
        }
        return null;
    }

    public static boolean validGtin(String d) {
        if (d == null || !d.matches("\\d{8}|\\d{12,14}")) return false;
        int sum = 0;
        int n = d.length();
        for (int i = 0; i < n - 1; i++) {
            int digit = d.charAt(n - 2 - i) - '0';
            sum += (i % 2 == 0) ? digit * 3 : digit;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == d.charAt(n - 1) - '0';
    }

    // ---------------------------------------------------------------- nome

    private static void findName(List<OcrLine> lines, int maxH, Result r) {
        int priceTop = Integer.MAX_VALUE;
        if (!r.prices.isEmpty()) {
            String src = r.prices.get(0).source;
            for (OcrLine l : lines) if (l.text.equals(src) || src.startsWith(l.text)) { priceTop = Math.min(priceTop, l.top); }
        }

        List<OcrLine> textual = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (OcrLine l : lines) {
            String t = l.text.trim();
            if (!looksLikeName(t)) continue;
            double s = 0;
            s += Math.min(1.0, (double) l.height() / maxH) * 0.6;
            s += Math.min(1.0, t.length() / 25.0) * 0.4;
            if (l.top < priceTop) s += 0.3;
            if (t.matches(".*\\d+\\s?(KG|G|ML|L|LT|UN|UND|GR|M)\\b.*") || t.toUpperCase(Locale.ROOT).matches(".*\\d+\\s?(KG|G|ML|L|LT|UN|UND|GR|M)\\b.*")) s += 0.2; // gramatura
            textual.add(l);
            scores.add(s);
        }
        if (textual.isEmpty()) return;

        // melhor linha
        int bi = 0;
        for (int i = 1; i < scores.size(); i++) if (scores.get(i) > scores.get(bi)) bi = i;
        OcrLine best = textual.get(bi);
        StringBuilder name = new StringBuilder(best.text.trim());

        // nomes longos quebram em duas linhas: junta a linha vizinha (acima ou abaixo) de altura parecida
        int idx = lines.indexOf(best);
        if (idx > 0) {
            OcrLine prev = lines.get(idx - 1);
            if (isContinuation(prev, best) && looksLikeName(prev.text.trim())) {
                name.insert(0, prev.text.trim() + " ");
            }
        }
        if (idx + 1 < lines.size() && name.length() == best.text.trim().length()) {
            OcrLine next = lines.get(idx + 1);
            if (isContinuation(best, next) && looksLikeName(next.text.trim()) && next.top < priceTop) {
                name.append(' ').append(next.text.trim());
            }
        }

        r.name = prettify(name.toString());
        // candidatos ordenados por score
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < textual.size(); i++) order.add(i);
        Collections.sort(order, (a, b) -> Double.compare(scores.get(b), scores.get(a)));
        Set<String> uniq = new LinkedHashSet<>();
        uniq.add(r.name);
        for (int i : order) {
            uniq.add(prettify(textual.get(i).text.trim()));
            if (uniq.size() >= 4) break;
        }
        r.nameCandidates.addAll(uniq);
    }

    private static boolean isContinuation(OcrLine upper, OcrLine lower) {
        int h = Math.max(upper.height(), lower.height());
        boolean similar = Math.abs(upper.height() - lower.height()) <= h * 0.35;
        boolean close = lower.top - upper.bottom <= h * 0.9 && lower.top >= upper.top;
        boolean aligned = Math.abs(upper.left - lower.left) <= h * 1.5;
        return similar && close && aligned;
    }

    static boolean looksLikeName(String t) {
        if (t.length() < 3) return false;
        int letters = 0, digits = 0;
        for (char c : t.toCharArray()) {
            if (Character.isLetter(c)) letters++;
            else if (Character.isDigit(c)) digits++;
        }
        if (letters < 3) return false;
        if (letters < (t.replace(" ", "").length()) * 0.55) return false;
        if (PRICE.matcher(t).find()) return false;
        String n = normalize(t);
        for (String sw : NAME_STOPWORDS) {
            if (sw.endsWith(" ") ? (n.startsWith(sw) || n.contains(" " + sw)) : n.contains(sw)) {
                // permite nomes que só contenham a palavra como parte (ex.: "SUPER BOM" seria descartado; aceitável)
                return false;
            }
        }
        return !DATE.matcher(t).find();
    }

    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toUpperCase(Locale.ROOT);
    }

    /** "ARROZ TIO JOAO TIPO 1 5KG" -> "Arroz Tio Joao Tipo 1 5kg" */
    public static String prettify(String s) {
        s = s.replaceAll("[ÁÀÂ](O|OS|ES)\\b", "Ã$1").replaceAll("[áàâ](o|os|es)\\b", "ã$1");
        String[] parts = s.trim().replaceAll("\\s+", " ").split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            String lower = p.toLowerCase(Locale.forLanguageTag("pt-BR"));
            if (lower.matches("\\d.*")) { sb.append(lower); continue; }               // 5kg, 500ml, 2l
            if (lower.matches("(de|da|do|das|dos|e|com|sem|c/|s/|p/|em)") && sb.length() > 0) { sb.append(lower); continue; }
            if (p.length() <= 3 && p.equals(p.toUpperCase(Locale.ROOT)) && p.matches("[A-Z]{2,3}") && isAcronymLike(p)) { sb.append(p); continue; }
            sb.append(Character.toUpperCase(lower.charAt(0))).append(lower.substring(1));
        }
        return sb.toString();
    }

    private static boolean isAcronymLike(String p) {
        // siglas comuns em etiquetas: UHT, PET, LV, PCT, CX
        return p.matches("UHT|PET|LV|PCT|CX|SC|FD|PT|TP|KG|ML|LT|UN|UND|GR");
    }
}
