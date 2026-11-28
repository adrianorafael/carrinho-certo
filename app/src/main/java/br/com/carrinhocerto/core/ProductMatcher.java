package br.com.carrinhocerto.core;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compara dois produtos descritos de formas diferentes (etiqueta, cupom fiscal, lista digitada).
 * Ex.: "LEITE UHT INT 1L" x "Leite UHT Integral Litro" x "leite".
 * Código de barras igual = certeza. Senão: palavras em comum (aceitando abreviações),
 * gramatura e preço. Sem dependências do Android.
 */
public final class ProductMatcher {

    public enum Confidence { HIGH, MEDIUM, LOW }

    public static final class Product {
        public final String name;
        public final String barcode;
        public final long unitCents; // <= 0 se desconhecido
        public Product(String name, String barcode, long unitCents) {
            this.name = name == null ? "" : name;
            this.barcode = barcode;
            this.unitCents = unitCents;
        }
    }

    public static final class Match {
        public final double score;
        public final Confidence confidence;
        public final String reason;
        Match(double score, Confidence c, String reason) { this.score = score; this.confidence = c; this.reason = reason; }
        @Override public String toString() { return confidence + String.format(Locale.ROOT, " %.2f ", score) + reason; }
    }

    public static final class Best {
        public final int index;
        public final Match match;
        Best(int index, Match match) { this.index = index; this.match = match; }
    }

    /** Tamanho/gramatura em unidade base: gramas, mililitros ou unidades. */
    public static final class Size {
        public final double amount;
        public final String base; // G, ML, UN
        Size(double amount, String base) { this.amount = amount; this.base = base; }
        boolean sameAs(Size o) {
            return base.equals(o.base) && Math.abs(amount - o.amount) <= Math.max(0.5, amount * 0.02);
        }
        @Override public String toString() { return amount + base; }
    }

    private static final Map<String, String> ABBR = new HashMap<>();
    static {
        String[][] a = {
                {"INT", "INTEGRAL"}, {"INTEG", "INTEGRAL"}, {"DESN", "DESNATADO"}, {"SEMIDESN", "SEMIDESNATADO"},
                {"REF", "REFINADO"}, {"TRAD", "TRADICIONAL"}, {"BCO", "BRANCO"}, {"BRCO", "BRANCO"}, {"PTO", "PRETO"},
                {"CAR", "CARIOCA"}, {"FEIJ", "FEIJAO"}, {"ARR", "ARROZ"}, {"ACUC", "ACUCAR"}, {"ACUCA", "ACUCAR"},
                {"TORR", "TORRADO"}, {"MOID", "MOIDO"}, {"REFRIG", "REFRIGERANTE"}, {"DET", "DETERGENTE"},
                {"LIQ", "LIQUIDO"}, {"PCT", "PACOTE"}, {"PC", "PACOTE"}, {"CX", "CAIXA"}, {"LTA", "LATA"},
                {"GFA", "GARRAFA"}, {"FD", "FARDO"}, {"SC", "SACO"}, {"BDJ", "BANDEJA"}, {"TP", "TIPO"},
                {"TRADIC", "TRADICIONAL"}, {"ORIG", "ORIGINAL"}, {"CHOC", "CHOCOLATE"}, {"MORAN", "MORANGO"},
                {"BISC", "BISCOITO"}, {"RECH", "RECHEADO"}, {"MARG", "MARGARINA"}, {"QJO", "QUEIJO"},
                {"MUSS", "MUSSARELA"}, {"MUCAR", "MUSSARELA"}, {"PRES", "PRESUNTO"}, {"FGO", "FRANGO"},
                {"BOV", "BOVINA"}, {"SUIN", "SUINA"}, {"CONG", "CONGELADO"}, {"RESF", "RESFRIADO"},
                {"SAB", "SABAO"}, {"AMAC", "AMACIANTE"}, {"PAP", "PAPEL"}, {"HIG", "HIGIENICO"},
                {"FL", "FOLHA"}, {"FLS", "FOLHAS"}, {"EXT", "EXTRA"}, {"VIRG", "VIRGEM"}, {"AZ", "AZEITE"},
                {"OL", "OLEO"}, {"MAC", "MACARRAO"}, {"ESPAG", "ESPAGUETE"}, {"MOL", "MOLHO"}, {"TOM", "TOMATE"},
        };
        for (String[] p : a) ABBR.put(p[0], p[1]);
    }

    private static final Set<String> STOP = new LinkedHashSet<>(java.util.Arrays.asList(
            "DE", "DA", "DO", "DAS", "DOS", "E", "COM", "C", "S", "SEM", "EM", "P", "PARA", "A", "O",
            "UN", "UND", "UNID", "UNIDADE", "KG", "G", "GR", "ML", "L", "LT", "LTS", "LITRO", "LITROS", "QUILO",
            "PACOTE", "CAIXA", "LATA", "GARRAFA", "FARDO", "SACO", "BANDEJA", "PET", "VD"));

    private static final Pattern SIZE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s?(KG|KGS|G|GR|GRS|MG|L|LT|LTS|ML|UN|UND)\\b");

    private ProductMatcher() {}

    // ------------------------------------------------------------ normalização

    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT);
        n = n.replace("C/", "COM ").replace("S/", "SEM ").replace("P/", "PARA ");
        n = n.replaceAll("(\\d)\\s+(KG|G|GR|ML|L|LT|UN)\\b", "$1$2");      // "5 KG" -> "5KG"
        n = n.replaceAll("[^A-Z0-9,. ]", " ");
        n = n.replaceAll("(?<=[A-Z])\\.", " ");                            // "INT." -> "INT"
        return n.replaceAll("\\s+", " ").trim();
    }

    public static Size size(String s) {
        String n = normalize(s);
        Matcher m = SIZE.matcher(n);
        Size found = null;
        while (m.find()) {
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            String u = m.group(2);
            switch (u) {
                case "KG": case "KGS": found = new Size(v * 1000, "G"); break;
                case "G": case "GR": case "GRS": found = new Size(v, "G"); break;
                case "MG": found = new Size(v / 1000, "G"); break;
                case "L": case "LT": case "LTS": found = new Size(v * 1000, "ML"); break;
                case "ML": found = new Size(v, "ML"); break;
                default: found = new Size(v, "UN");
            }
            if (!"UN".equals(found.base)) return found;
        }
        if (found != null) return found;
        if (n.matches(".*\\bLITRO\\b.*")) return new Size(1000, "ML");
        if (n.matches(".*\\bQUILO\\b.*")) return new Size(1000, "G");
        return null;
    }

    /** Palavras significativas, com abreviações expandidas, sem gramatura nem conectivos. */
    public static List<String> tokens(String s) {
        String n = SIZE.matcher(normalize(s)).replaceAll(" ");
        List<String> out = new ArrayList<>();
        for (String t : n.split("[ ,.]+")) {
            if (t.isEmpty()) continue;
            if (t.matches("\\d+") && t.length() >= 6) continue;            // códigos
            String e = ABBR.getOrDefault(t, t);
            if (STOP.contains(e)) continue;
            if (!out.contains(e)) out.add(e);
        }
        return out;
    }

    private static boolean tokenEq(String a, String b) {
        if (a.equals(b)) return true;
        if (a.matches("\\d+") || b.matches("\\d+")) return false;
        String s = a.length() <= b.length() ? a : b, l = a.length() <= b.length() ? b : a;
        return s.length() >= 3 && l.startsWith(s);                         // "INTEG" ~ "INTEGRAL"
    }

    // ------------------------------------------------------------ comparação

    public static Match compare(Product a, Product b) {
        boolean ga = LabelParser.validGtin(a.barcode), gb = LabelParser.validGtin(b.barcode);
        if (ga && gb) {
            if (a.barcode.equals(b.barcode)) return new Match(1.0, Confidence.HIGH, "código de barras igual");
        }
        List<String> ta = tokens(a.name), tb = tokens(b.name);
        if (ta.isEmpty() || tb.isEmpty()) return new Match(0, Confidence.LOW, "sem nome");
        int matched = 0;
        List<String> used = new ArrayList<>();
        for (String x : ta) {
            for (String y : tb) {
                if (!used.contains(y) && tokenEq(x, y)) { matched++; used.add(y); break; }
            }
        }
        double overlapMin = (double) matched / Math.min(ta.size(), tb.size());
        double dice = 2.0 * matched / (ta.size() + tb.size());
        double score = 0.55 * overlapMin + 0.45 * dice;
        StringBuilder why = new StringBuilder(matched + " palavra(s) em comum");

        // primeira palavra (o tipo de produto) diferente pesa muito: "ARROZ" x "FEIJAO"
        if (!tokenEq(ta.get(0), tb.get(0)) && !tb.contains(ta.get(0)) && !ta.contains(tb.get(0))) score -= 0.15;

        Size sa = size(a.name), sb = size(b.name);
        boolean sizeConflict = false;
        if (sa != null && sb != null) {
            if (sa.sameAs(sb)) { score += 0.12; why.append(", mesma gramatura"); }
            else if (sa.base.equals(sb.base)) { score -= 0.35; sizeConflict = true; why.append(", gramatura diferente"); }
        }
        if (a.unitCents > 0 && b.unitCents > 0) {
            double r = (double) Math.max(a.unitCents, b.unitCents) / Math.min(a.unitCents, b.unitCents);
            if (r <= 1.15) { score += 0.08; why.append(", preço parecido"); }
            else if (r >= 1.8) { score -= 0.15; why.append(", preço muito diferente"); }
        }
        if (ga && gb) { score -= 0.25; why.append(", códigos diferentes"); }
        score = Math.max(0, Math.min(1, score));

        Confidence c;
        if (score >= 0.8 && !sizeConflict && matched >= Math.min(2, Math.min(ta.size(), tb.size()))) c = Confidence.HIGH;
        else if (score >= 0.5) c = Confidence.MEDIUM;
        else c = Confidence.LOW;
        return new Match(score, c, why.toString());
    }

    /**
     * Melhor candidato para o alvo. Se o segundo colocado estiver muito perto,
     * a confiança cai para MÉDIA (pede confirmação). Retorna null se nada passar de BAIXA.
     */
    public static Best best(Product target, List<Product> candidates) {
        int bi = -1;
        Match bm = null, second = null;
        for (int i = 0; i < candidates.size(); i++) {
            Match m = compare(target, candidates.get(i));
            if (bm == null || m.score > bm.score) { second = bm; bm = m; bi = i; }
            else if (second == null || m.score > second.score) second = m;
        }
        if (bm == null || bm.confidence == Confidence.LOW) return null;
        if (bm.confidence == Confidence.HIGH && second != null && !bm.reason.startsWith("código") && bm.score - second.score < 0.1) {
            bm = new Match(bm.score, Confidence.MEDIUM, bm.reason + ", outro parecido");
        }
        return new Best(bi, bm);
    }
}
