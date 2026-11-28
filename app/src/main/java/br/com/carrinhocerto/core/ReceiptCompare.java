package br.com.carrinhocerto.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Confere o cupom contra o que foi registrado na gôndola.
 * Agrupa as linhas do cupom por produto, casa cada grupo com um item do app (código de barras
 * primeiro, depois o comparador de nomes) e classifica a diferença. Nunca altera os preços do app.
 */
public final class ReceiptCompare {

    public enum Status { OK, OVER, UNDER, PROMO, CHECK, ONLY_APP, ONLY_RECEIPT }

    /** Item registrado no app. */
    public static final class AppItem {
        public final long id;
        public final String name;
        public final String barcode;
        public final long unitCents;
        public final double qty;
        public final boolean byWeight;
        public AppItem(long id, String name, String barcode, long unitCents, double qty, boolean byWeight) {
            this.id = id; this.name = name; this.barcode = barcode; this.unitCents = unitCents; this.qty = qty; this.byWeight = byWeight;
        }
        public long subtotal() { return Math.round(unitCents * qty); }
    }

    /** Produto do cupom (uma ou mais linhas somadas). */
    public static final class Group {
        public final List<Integer> lineIdx = new ArrayList<>();
        public String desc, code;
        public double qty;
        public long grossCents, discountCents, surchargeCents;
        public long unitCents;      // preço unitário impresso
        public boolean weight;
        public long netCents() { return grossCents - discountCents + surchargeCents; }
        public long effUnit() { return qty > 0 ? Math.round(netCents() / qty) : netCents(); }
    }

    public static final class Row {
        public Status status;
        public Group receipt;        // null se só no app
        public AppItem app;          // null se só no cupom
        public ProductMatcher.Confidence confidence;
        public boolean forced;       // associação feita pelo usuário
        public boolean qtyMismatch;
        public long diffUnitCents;   // cobrado - registrado (por unidade, já com desconto)
        public long diffTotalCents;  // cobrado - esperado para este produto
        public String note = "";
    }

    public static final class Result {
        public final List<Row> rows = new ArrayList<>();
        public final List<Group> groups = new ArrayList<>();
        public long appTotal, receiptTotal, diffTotal;
        public int checked, divergences;
        public long paidMore, paidLess;
        public boolean receiptTotalPrinted;
    }

    private ReceiptCompare() {}

    /** Agrupa linhas iguais do cupom (mesmo código, ou mesma descrição e preço). */
    public static List<Group> group(Receipt r) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (int i = 0; i < r.lines.size(); i++) {
            Receipt.Line l = r.lines.get(i);
            String key = LabelParser.validGtin(l.code) ? "C" + l.code
                    : "D" + ProductMatcher.normalize(l.desc).replace(" ", "") + "|" + l.unitCents;
            Group g = map.get(key);
            if (g == null) {
                g = new Group();
                g.desc = l.desc; g.code = l.code; g.unitCents = l.unitCents; g.weight = l.byWeight();
                map.put(key, g);
            }
            g.lineIdx.add(i);
            g.qty += l.qty;
            g.grossCents += l.totalCents >= 0 ? l.totalCents : Math.round(l.unitCents * l.qty);
            g.discountCents += l.discountCents;
            g.surchargeCents += l.surchargeCents;
        }
        return new ArrayList<>(map.values());
    }

    /**
     * @param forced associações escolhidas pelo usuário: índice do grupo do cupom -> id do item do app
     *               (valor -1 = "não é nenhum item do app").
     */
    public static Result compare(List<AppItem> app, Receipt receipt, Map<Integer, Long> forced) {
        Result res = new Result();
        List<Group> groups = group(receipt);
        res.groups.addAll(groups);
        if (forced == null) forced = new HashMap<>();

        Map<Integer, Integer> pairG2A = new HashMap<>();
        Map<Integer, ProductMatcher.Confidence> conf = new HashMap<>();
        boolean[] usedApp = new boolean[app.size()];

        // 1) escolhas do usuário
        for (Map.Entry<Integer, Long> e : forced.entrySet()) {
            if (e.getKey() >= groups.size()) continue;
            if (e.getValue() < 0) { pairG2A.put(e.getKey(), -1); continue; }
            for (int a = 0; a < app.size(); a++) {
                if (app.get(a).id == e.getValue() && !usedApp[a]) {
                    pairG2A.put(e.getKey(), a); usedApp[a] = true; conf.put(e.getKey(), ProductMatcher.Confidence.HIGH); break;
                }
            }
        }
        // 2) melhores pares por pontuação, sem repetir item
        List<double[]> cands = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            if (pairG2A.containsKey(g)) continue;
            Group gr = groups.get(g);
            ProductMatcher.Product pg = new ProductMatcher.Product(gr.desc, gr.code, gr.unitCents);
            for (int a = 0; a < app.size(); a++) {
                if (usedApp[a]) continue;
                AppItem ai = app.get(a);
                ProductMatcher.Match m = ProductMatcher.compare(pg, new ProductMatcher.Product(ai.name, ai.barcode, ai.unitCents));
                if (m.confidence == ProductMatcher.Confidence.LOW) continue;
                cands.add(new double[]{m.score, g, a, m.confidence.ordinal()});
            }
        }
        Collections.sort(cands, (x, y) -> Double.compare(y[0], x[0]));
        for (double[] c : cands) {
            int g = (int) c[1], a = (int) c[2];
            if (pairG2A.containsKey(g) || usedApp[a]) continue;
            // empate próximo com outro item: rebaixa para "conferir"
            ProductMatcher.Confidence cf = ProductMatcher.Confidence.values()[(int) c[3]];
            for (double[] o : cands) {
                if (o == c || (int) o[1] != g || usedApp[(int) o[2]] || (int) o[2] == a) continue;
                if (c[0] - o[0] < 0.08 && cf == ProductMatcher.Confidence.HIGH && c[0] < 1.0) cf = ProductMatcher.Confidence.MEDIUM;
            }
            pairG2A.put(g, a);
            usedApp[a] = true;
            conf.put(g, cf);
        }

        // 3) linhas do resultado
        for (int g = 0; g < groups.size(); g++) {
            Group gr = groups.get(g);
            Integer a = pairG2A.get(g);
            Row row = new Row();
            row.receipt = gr;
            row.forced = forced.containsKey(g);
            if (a == null || a < 0) {
                row.status = Status.ONLY_RECEIPT;
                row.diffTotalCents = gr.netCents();
                row.note = "Está na nota, mas não foi registrado no app";
                res.rows.add(row);
                continue;
            }
            AppItem ai = app.get(a);
            row.app = ai;
            row.confidence = conf.get(g);
            classify(row, ai, gr);
            res.rows.add(row);
        }
        for (int a = 0; a < app.size(); a++) {
            if (usedApp[a]) continue;
            Row row = new Row();
            row.app = app.get(a);
            row.status = Status.ONLY_APP;
            row.diffTotalCents = -app.get(a).subtotal();
            row.note = "Registrado no app, mas não está na nota";
            res.rows.add(row);
        }

        // 4) resumo
        for (AppItem ai : app) res.appTotal += ai.subtotal();
        long sumNet = 0;
        for (Group gr : groups) sumNet += gr.netCents();
        res.receiptTotalPrinted = receipt.totalCents >= 0;
        res.receiptTotal = res.receiptTotalPrinted ? receipt.totalCents : sumNet - receipt.discountTotalCents;
        res.diffTotal = res.receiptTotal - res.appTotal;
        for (Row r : res.rows) {
            if (r.receipt != null && r.app != null) res.checked++;
            if (r.status != Status.OK && r.status != Status.PROMO) res.divergences++;
            if (r.status == Status.OVER) res.paidMore += Math.max(0, r.diffTotalCents);
            if (r.status == Status.UNDER || r.status == Status.PROMO) res.paidLess += Math.max(0, -r.diffTotalCents);
        }
        return res;
    }

    static void classify(Row row, AppItem ai, Group gr) {
        long eff = gr.effUnit();
        long gross = gr.unitCents >= 0 ? gr.unitCents : (gr.qty > 0 ? Math.round(gr.grossCents / gr.qty) : gr.grossCents);
        row.diffUnitCents = eff - ai.unitCents;
        long expected = Math.round(ai.unitCents * gr.qty);       // mesmo preço da gôndola, na quantidade cobrada
        row.diffTotalCents = gr.netCents() - expected;
        boolean weight = ai.byWeight || gr.weight;
        row.qtyMismatch = !weight && Math.abs(ai.qty - gr.qty) > 0.001;
        long tol = weight ? Math.max(1, Math.round(gr.qty)) : 1;  // arredondamento de peso

        if (Math.abs(row.diffUnitCents) <= tol) {
            row.status = Status.OK;
            row.diffTotalCents = 0;
            if (gr.discountCents > 0) row.note = "Preço igual (já com desconto do caixa)";
        } else if (row.diffUnitCents > 0) {
            row.status = Status.OVER;
            row.note = "Cobrado acima do preço da gôndola";
        } else if (gr.discountCents > 0 && Math.abs(gross - ai.unitCents) <= tol) {
            row.status = Status.PROMO;
            row.note = "Desconto/promoção aplicado no caixa";
        } else {
            row.status = Status.UNDER;
            row.note = "Cobrado abaixo do preço da gôndola";
        }
        if (row.confidence == ProductMatcher.Confidence.MEDIUM && !row.forced) {
            row.note = "Confirme se é o mesmo produto. " + row.note;
            if (row.status == Status.OK) row.status = Status.CHECK;
        }
        if (row.qtyMismatch) {
            row.note = "Quantidade diferente: app " + Money.formatQty(ai.qty) + ", nota " + Money.formatQty(gr.qty) + ". " + row.note;
            if (row.status == Status.OK) row.status = Status.CHECK;
        }
    }
}
