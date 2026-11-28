package br.com.carrinhocerto.core;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import br.com.carrinhocerto.core.ReceiptCompare.AppItem;
import br.com.carrinhocerto.core.ReceiptCompare.Status;

/**
 * Casos do leitor de cupom e da conferência.
 * ATENÇÃO: os textos abaixo são SIMULADOS no formato do DANFE NFC-e (não são cupons reais).
 * Trocar/acrescentar exemplos reais assim que houver um cupom de verdade.
 */
public final class ReceiptCases {

    static final List<String> CUPOM_1 = Arrays.asList(
            "SUPERMERCADO EXEMPLO LTDA",
            "CNPJ: 00.000.000/0001-00 Rua Exemplo, 100",
            "Documento Auxiliar da Nota Fiscal de Consumidor Eletrônica",
            "Código Descrição Qtde UN Vl Unit Vl Total",
            "001 7891000100103 LEITE UHT INT 1L 6 UN X 1,79 10,74",
            "002 7896123456786 ARROZ BCO T1 5KG 1 UN X 22,90 22,90",
            "003 7896345678126 FEIJ CARIOCA T1 1KG 2 UN X 7,99 15,98",
            "004 7896456712344 CAFE TORR MOID 500G 1 UN X 16,98 16,98",
            "Desconto item -2,00",
            "005 7896567812346 ACUCAR REF 1KG 2 UN X 4,39 8,78",
            "006 7891234567895 DETERGENTE LIQ 500ML 1 UN X 2,59 2,59",
            "Qtd. total de itens 6",
            "Valor total R$ 77,97",
            "Descontos R$ 2,00",
            "Valor a pagar R$ 75,97",
            "FORMA PAGAMENTO Valor pago",
            "Cartão de Débito 75,97");

    static final List<String> CUPOM_2_LINHAS = Arrays.asList(
            "001 7891000100103 LEITE UHT INTEGRAL",
            "1L",
            "6 UN X 1,79 10,74",
            "002 2000123 BANANA PRATA KG",
            "1,240 KG X 6,990 8,67",
            "003 7896567812346 ACUCAR REF 1KG 2 UN X 4,39 (0,52) 8,78",
            "VALOR A PAGAR R$ 28,19");

    static final String XML = "<?xml version=\"1.0\"?><nfeProc><NFe><infNFe Id=\"NFe00\" versao=\"4.00\">"
            + "<emit><xNome>SUPERMERCADO EXEMPLO LTDA</xNome><xFant>Mercado Exemplo</xFant></emit>"
            + "<det nItem=\"1\"><prod><cProd>123</cProd><cEAN>7891000100103</cEAN><xProd>LEITE UHT INT 1L</xProd><uCom>UN</uCom>"
            + "<qCom>6.0000</qCom><vUnCom>1.7900000000</vUnCom><vProd>10.74</vProd></prod></det>"
            + "<det nItem=\"2\"><prod><cProd>456</cProd><cEAN>SEM GTIN</cEAN><xProd>BANANA PRATA KG</xProd><uCom>KG</uCom>"
            + "<qCom>1.2400</qCom><vUnCom>6.9900000000</vUnCom><vProd>8.67</vProd><vDesc>0.50</vDesc></prod></det>"
            + "<total><ICMSTot><vProd>19.41</vProd><vDesc>0.50</vDesc><vNF>18.91</vNF></ICMSTot></total>"
            + "</infNFe></NFe></nfeProc>";

    static List<AppItem> app() {
        return new ArrayList<>(Arrays.asList(
                new AppItem(1, "Leite UHT Integral Litro", null, 179, 6, false),
                new AppItem(2, "Arroz Branco Tipo 1 5kg", "7896123456786", 2290, 1, false),
                new AppItem(3, "Feijão Carioca Tipo 1 1kg", "7896345678126", 749, 2, false),
                new AppItem(4, "Café Torrado e Moído 500g", "7896456712344", 1698, 1, false),
                new AppItem(5, "Açúcar Refinado 1kg", "7896567812346", 439, 2, false)));
    }

    static void eq(List<String> f, String id, Object got, Object exp) {
        if (!String.valueOf(exp).equals(String.valueOf(got))) f.add(id + ": esperado " + exp + " veio " + got);
    }

    static Status statusOf(ReceiptCompare.Result r, long appId) {
        for (ReceiptCompare.Row row : r.rows) if (row.app != null && row.app.id == appId) return row.status;
        return null;
    }

    public static List<String> run() {
        List<String> f = new ArrayList<>();

        Receipt r1 = Receipt.parseText(CUPOM_1);
        eq(f, "c1-itens", r1.lines.size(), 6);
        if (r1.lines.size() == 6) {
            eq(f, "c1-leite", r1.lines.get(0).qty + "x" + r1.lines.get(0).unitCents + "=" + r1.lines.get(0).totalCents, "6.0x179=1074");
            eq(f, "c1-desc", r1.lines.get(3).discountCents, 200);
            eq(f, "c1-seq", r1.lines.get(5).seq, 6);
        }
        eq(f, "c1-total", r1.totalCents, 7597);

        Receipt r2 = Receipt.parseText(CUPOM_2_LINHAS);
        eq(f, "c2-itens", r2.lines.size(), 3);
        if (r2.lines.size() == 3) {
            eq(f, "c2-desc", r2.lines.get(0).desc, "LEITE UHT INTEGRAL 1L");
            eq(f, "c2-peso", r2.lines.get(1).qty + " " + r2.lines.get(1).unit + " " + r2.lines.get(1).unitCents + " " + r2.lines.get(1).totalCents, "1.24 KG 699 867");
            eq(f, "c2-trib", r2.lines.get(2).totalCents, 878);
        }
        eq(f, "c2-total", r2.totalCents, 2819);

        // colunas separadas pelo OCR na mesma altura
        List<OcrLine> ocr = Arrays.asList(
                new OcrLine("001 7891000100103 LEITE UHT INT 1L", 10, 100, 400, 120),
                new OcrLine("6 UN X 1,79", 420, 101, 560, 121),
                new OcrLine("10,74", 600, 99, 660, 119),
                new OcrLine("002 7896567812346 ACUCAR REF 1KG", 10, 130, 400, 150),
                new OcrLine("2 UN X 4,39", 420, 131, 560, 151),
                new OcrLine("8,78", 600, 130, 660, 150));
        Receipt r3 = Receipt.parseText(Receipt.rows(ocr));
        eq(f, "ocr-itens", r3.lines.size(), 2);
        if (r3.lines.size() == 2) eq(f, "ocr-total-item", r3.lines.get(1).totalCents, 878);

        // várias fotos com sobreposição (item 3 aparece nas duas)
        Receipt a = Receipt.parseText(CUPOM_1.subList(0, 7));
        Receipt b = Receipt.parseText(CUPOM_1.subList(6, CUPOM_1.size()));
        Receipt m = Receipt.merge(Arrays.asList(a, b));
        eq(f, "merge-itens", m.lines.size(), 6);
        eq(f, "merge-total", m.totalCents, 7597);
        // sem número de item: só remove a sobreposição, mantém repetição legítima
        Receipt p1 = Receipt.parseText(Arrays.asList("LEITE UHT 1L 1 UN X 1,79 1,79", "PAO FORMA 1 UN X 7,50 7,50", "LEITE UHT 1L 1 UN X 1,79 1,79"));
        Receipt p2 = Receipt.parseText(Arrays.asList("PAO FORMA 1 UN X 7,50 7,50", "LEITE UHT 1L 1 UN X 1,79 1,79", "OVOS BRANCOS 12 1 UN X 9,90 9,90"));
        eq(f, "merge-sem-seq", Receipt.merge(Arrays.asList(p1, p2)).lines.size(), 4);

        // XML
        try {
            Receipt x = ReceiptXml.parse(new ByteArrayInputStream(XML.getBytes(StandardCharsets.UTF_8)));
            eq(f, "xml-itens", x.lines.size(), 2);
            eq(f, "xml-total", x.totalCents, 1891);
            eq(f, "xml-loja", x.store, "Mercado Exemplo");
            if (x.lines.size() == 2) {
                eq(f, "xml-ean", x.lines.get(0).code, "7891000100103");
                eq(f, "xml-peso", x.lines.get(1).qty + " " + x.lines.get(1).byWeight() + " " + x.lines.get(1).discountCents, "1.24 true 50");
            }
        } catch (Exception e) {
            f.add("xml: " + e);
        }

        // cupom do teste no emulador (ci/e2e/cupom_simulado.jpg), linhas como o OCR devolve
        Receipt e2e = Receipt.parseText(Arrays.asList("SUPERMERCADO EXEMPLO LTDA", "CNPJ 00.000.000/0001-00",
                "Codigo Descricao Qtde UN Vl Unit Vl Total", "001 7891000100103 LEITE UHT INT 1L", "6 UN X 1,79  10,74",
                "002 7896123456786 ARROZ BCO T1 5KG", "1 UN X 23,90  23,90", "003 7891234567895 DETERGENTE LIQ 500ML",
                "1 UN X 2,59  2,59", "Qtd. total de itens  3", "Valor total R$  37,23", "Valor a pagar R$  37,23",
                "FORMA PAGAMENTO  VALOR PAGO", "Cartao de Debito  37,23"));
        eq(f, "e2e-itens", e2e.lines.size(), 3);
        eq(f, "e2e-total", e2e.totalCents, 3723);

        // conferência
        ReceiptCompare.Result res = ReceiptCompare.compare(app(), r1, null);
        eq(f, "cmp-leite", statusOf(res, 1), Status.OK);
        eq(f, "cmp-arroz", statusOf(res, 2), Status.OK);
        eq(f, "cmp-feijao", statusOf(res, 3), Status.OVER);
        eq(f, "cmp-cafe", statusOf(res, 4), Status.PROMO);
        eq(f, "cmp-acucar", statusOf(res, 5), Status.OK);
        int onlyReceipt = 0;
        for (ReceiptCompare.Row row : res.rows) if (row.status == Status.ONLY_RECEIPT) onlyReceipt++;
        eq(f, "cmp-so-nota", onlyReceipt, 1);
        eq(f, "cmp-app-total", res.appTotal, 7438);
        eq(f, "cmp-diff", res.diffTotal, 159);
        eq(f, "cmp-mais", res.paidMore, 100);
        eq(f, "cmp-menos", res.paidLess, 200);
        eq(f, "cmp-diverg", res.divergences, 2);    // feijão (acima) + detergente (só na nota)

        // item do app que não veio na nota + quantidade diferente
        List<String> semAcucar = new ArrayList<>(CUPOM_1);
        semAcucar.remove("005 7896567812346 ACUCAR REF 1KG 2 UN X 4,39 8,78");
        semAcucar.set(4, "001 7891000100103 LEITE UHT INT 1L 5 UN X 1,79 8,95");
        ReceiptCompare.Result res2 = ReceiptCompare.compare(app(), Receipt.parseText(semAcucar), null);
        eq(f, "cmp2-acucar", statusOf(res2, 5), Status.ONLY_APP);
        eq(f, "cmp2-leite-qtd", statusOf(res2, 1), Status.CHECK);

        // associação manual: o usuário diz que o detergente não é nada do app e troca o leite
        Map<Integer, Long> forced = new HashMap<>();
        forced.put(5, -1L);
        ReceiptCompare.Result res3 = ReceiptCompare.compare(app(), r1, forced);
        eq(f, "cmp3-forcado", res3.rows.get(5).status, Status.ONLY_RECEIPT);
        return f;
    }

    public static void main(String[] a) {
        List<String> f = run();
        for (String s : f) System.out.println("FALHA " + s);
        System.out.println(f.isEmpty() ? "CUPOM OK" : f.size() + " falha(s)");
        System.exit(f.isEmpty() ? 0 : 1);
    }
}
