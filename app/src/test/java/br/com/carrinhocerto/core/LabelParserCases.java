package br.com.carrinhocerto.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Casos de etiquetas reais simuladas. Roda sem JUnit (main) e também via LabelParserTest. */
public final class LabelParserCases {

    static OcrLine L(String t, int l, int top, int r, int b) { return new OcrLine(t, l, top, r, b); }

    public static List<String> run() {
        List<String> fails = new ArrayList<>();

        // 1) Etiqueta clássica: nome no topo, preço grande, preço/kg pequeno, EAN e data
        Result(fails, "classica",
                Arrays.asList(
                        L("ARROZ TIO JOAO TIPO 1 5KG", 20, 10, 600, 50),
                        L("R$ 24,90", 150, 80, 560, 220),
                        L("R$/KG 4,98", 20, 240, 200, 270),
                        L("7896006716112", 300, 240, 600, 270),
                        L("15/11/2026", 20, 280, 160, 300)),
                "Arroz Tio Joao Tipo 1 5kg", 2490, "7896006716112");

        // 2) Reais e centavos separados (centavos sobrescritos)
        OcrLine big = L("R$ 8 99", 100, 80, 420, 260)
                .addWord(L("R$", 100, 90, 160, 130))
                .addWord(L("8", 170, 80, 300, 260))
                .addWord(L("99", 305, 85, 420, 160));
        Result(fails, "centavos-separados",
                Arrays.asList(
                        L("LEITE UHT INTEGRAL", 20, 10, 500, 45),
                        L("ITALAC 1L", 20, 48, 220, 80),
                        big,
                        L("PRECO POR LITRO R$ 8,99", 20, 270, 300, 290)),
                "Leite UHT Integral Italac 1l", 899, null);

        // 3) Preço por kg aparece maior em texto, mas com indicador -> deve escolher o de venda
        Result(fails, "preco-kg-na-mesma-linha",
                Arrays.asList(
                        L("CAFE PILAO TRADICIONAL 500G", 10, 10, 560, 50),
                        L("19,49", 200, 70, 520, 200),
                        L("R$/KG 38,98", 10, 210, 220, 240)),
                "Cafe Pilao Tradicional 500g", 1949, null);

        // 4) Confusão de OCR: O no lugar de 0, ponto como separador
        Result(fails, "ocr-confuso",
                Arrays.asList(
                        L("SABAO EM PO OMO 1,6KG", 10, 10, 540, 50),
                        L("R$ 3O.9O", 150, 70, 540, 210),
                        L("COD 123456", 10, 220, 150, 240)),
                "Sabao em Po Omo 1,6kg", 3090, null);

        // 5) "De / Por": preço antigo menor ou igual em altura
        Result(fails, "de-por",
                Arrays.asList(
                        L("OFERTA", 10, 0, 200, 30),
                        L("BISCOITO RECHEADO OREO 90G", 10, 35, 520, 70),
                        L("DE R$ 4,99 POR", 10, 80, 300, 110),
                        L("R$ 3,79", 120, 120, 500, 250)),
                "Biscoito Recheado Oreo 90g", 379, null);

        // 6) Milhar
        Result(fails, "milhar",
                Arrays.asList(
                        L("TV SMART 50 POL", 10, 10, 400, 50),
                        L("R$ 2.199,00", 50, 70, 560, 200)),
                "Tv Smart 50 Pol", 219900, null);

        // 8) Gramatura com duas casas decimais no nome não pode virar preço
        Result(fails, "gramatura-decimal",
                Arrays.asList(
                        L("REFRIGERANTE COLA 2,25L", 10, 10, 520, 50),
                        L("R$ 9,49", 120, 70, 520, 200),
                        L("R$/L 4,22", 10, 210, 200, 235)),
                "Refrigerante Cola 2,25l", 949, null);
        Result(fails, "gramatura-kg",
                Arrays.asList(
                        L("SABAO EM PO 1,02KG", 10, 10, 520, 50),
                        L("15,90", 120, 70, 520, 200)),
                "Sabao em Po 1,02kg", 1590, null);

        // 9) OCR trocou Ã por Á
        Result(fails, "acento-ocr",
                Arrays.asList(L("FEIJÁO CARIOCA TIPO 1 1KG", 10, 10, 520, 50), L("R$ 7,49", 120, 70, 520, 200)),
                "Feijão Carioca Tipo 1 1kg", 749, null);

        // 7) Sem preço legível
        LabelParser.Result r7 = LabelParser.parse(Arrays.asList(L("DETERGENTE YPE NEUTRO 500ML", 0, 0, 400, 40)));
        if (r7.hasPrice()) fails.add("sem-preco: esperava nenhum preço, veio " + r7.prices);
        if (!"Detergente Ype Neutro 500ml".equals(r7.name)) fails.add("sem-preco: nome '" + r7.name + "'");

        // Money
        check(fails, "fmt1", Money.format(123456), "R$ 1.234,56");
        check(fails, "fmt2", Money.format(5), "R$ 0,05");
        check(fails, "parse1", String.valueOf(Money.parse("12,9")), "1290");
        check(fails, "parse2", String.valueOf(Money.parse("R$ 1.234,56")), "123456");
        check(fails, "parse3", String.valueOf(Money.parse("1.234")), "123400");
        check(fails, "parse4", String.valueOf(Money.parse("12.90")), "1290");
        check(fails, "parse5", String.valueOf(Money.parse("abc")), "-1");
        check(fails, "times", String.valueOf(Money.times(1999, 0.735)), "1469");
        check(fails, "qty1", Money.formatQty(1.5), "1,5");
        check(fails, "qty2", Money.formatQty(3), "3");
        check(fails, "qtyp", String.valueOf(Money.parseQty("0,735")), "0.735");
        check(fails, "gtin", String.valueOf(LabelParser.validGtin("7891000100103")), "true");
        return fails;
    }

    private static void Result(List<String> fails, String id, List<OcrLine> lines, String name, long cents, String barcode) {
        LabelParser.Result r = LabelParser.parse(lines);
        if (r.bestPriceCents() != cents) fails.add(id + ": preço esperado " + cents + " veio " + r.prices);
        if (!name.equals(r.name)) fails.add(id + ": nome esperado '" + name + "' veio '" + r.name + "'");
        if (barcode != null && !barcode.equals(r.barcode)) fails.add(id + ": ean esperado " + barcode + " veio " + r.barcode);
    }

    private static void check(List<String> fails, String id, String got, String exp) {
        if (!exp.equals(got)) fails.add(id + ": esperado '" + exp + "' veio '" + got + "'");
    }

    public static void main(String[] a) {
        List<String> f = run();
        for (String s : f) System.out.println("FALHA " + s);
        System.out.println(f.isEmpty() ? "TODOS OS CASOS PASSARAM" : f.size() + " falha(s)");
        System.exit(f.isEmpty() ? 0 : 1);
    }
}
