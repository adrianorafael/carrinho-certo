package br.com.carrinhocerto.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.com.carrinhocerto.core.ProductMatcher.Confidence;
import br.com.carrinhocerto.core.ProductMatcher.Product;

/** Casos do comparador de produtos. Roda com main() ou via JUnit. */
public final class MatcherCases {

    static Product P(String n) { return new Product(n, null, 0); }
    static Product P(String n, long c) { return new Product(n, null, c); }
    static Product P(String n, String code) { return new Product(n, code, 0); }

    static void expect(List<String> f, String id, Product a, Product b, Confidence exp) {
        ProductMatcher.Match m = ProductMatcher.compare(a, b);
        if (m.confidence != exp) f.add(id + ": esperado " + exp + " veio " + m + " [" + a.name + " x " + b.name + "]");
    }

    public static List<String> run() {
        List<String> f = new ArrayList<>();
        // cupom x etiqueta
        expect(f, "leite", P("LEITE UHT INT 1L", 179), P("Leite UHT Integral Litro", 179), Confidence.HIGH);
        expect(f, "arroz", P("ARROZ BCO T1 5KG", 2290), P("Arroz Branco Tipo 1 5kg", 2290), Confidence.HIGH);
        expect(f, "feijao", P("FEIJ CARIOCA 1KG"), P("Feijão Carioca Tipo 1 1kg"), Confidence.HIGH);
        expect(f, "cafe", P("CAFE TORR MOID 500G", 1698), P("Café Torrado e Moído 500g", 1698), Confidence.HIGH);
        expect(f, "acucar", P("ACUCAR REF 1KG"), P("Açúcar Refinado 1kg"), Confidence.HIGH);
        // gramatura diferente não é o mesmo produto
        expect(f, "gramatura", P("ARROZ BCO T1 1KG"), P("Arroz Branco Tipo 1 5kg"), Confidence.LOW);
        // produtos diferentes
        expect(f, "diferentes", P("FEIJAO PRETO 1KG"), P("Arroz Branco Tipo 1 1kg"), Confidence.LOW);
        expect(f, "dif2", P("ACUCAR REF 1KG"), P("Café Torrado e Moído 500g"), Confidence.LOW);
        // código de barras
        expect(f, "ean", P("XPTO 123", "7896006716112"), P("Arroz Qualquer", "7896006716112"), Confidence.HIGH);
        // lista digitada (curta) -> pede confirmação
        expect(f, "lista", P("arroz"), P("Arroz Branco Tipo 1 5kg"), Confidence.MEDIUM);
        expect(f, "lista2", P("leite"), P("Leite UHT Integral Litro"), Confidence.MEDIUM);
        expect(f, "lista3", P("detergente"), P("Leite UHT Integral Litro"), Confidence.LOW);

        // melhor candidato entre vários
        List<Product> cart = Arrays.asList(P("Leite UHT Integral Litro", 179), P("Arroz Branco Tipo 1 5kg", 2290),
                P("Feijão Carioca Tipo 1 1kg", 749), P("Café Torrado e Moído 500g", 1698), P("Açúcar Refinado 1kg", 439));
        ProductMatcher.Best b = ProductMatcher.best(P("FEIJ CARIOCA T1 1KG", 749), cart);
        if (b == null || b.index != 2 || b.match.confidence != Confidence.HIGH) f.add("best-feijao: " + (b == null ? "null" : b.index + " " + b.match));
        ProductMatcher.Best none = ProductMatcher.best(P("DETERGENTE LIQ 500ML", 259), cart);
        if (none != null) f.add("best-nada: esperava null veio " + none.index + " " + none.match);
        // cupom genérico e dois leites no carrinho -> não pode ter certeza
        List<Product> two = Arrays.asList(P("Leite UHT Integral 1L", 479), P("Leite UHT Desnatado 1L", 479));
        ProductMatcher.Best amb = ProductMatcher.best(P("LEITE UHT 1L"), two);
        if (amb == null || amb.match.confidence == Confidence.HIGH) f.add("ambiguo: " + (amb == null ? "null" : amb.match));

        // gramatura
        if (!ProductMatcher.size("SABAO PO 1,6KG").toString().equals("1600.0G")) f.add("size1 " + ProductMatcher.size("SABAO PO 1,6KG"));
        if (!ProductMatcher.size("Leite UHT Integral Litro").toString().equals("1000.0ML")) f.add("size2");
        return f;
    }

    public static void main(String[] a) {
        List<String> f = run();
        for (String s : f) System.out.println("FALHA " + s);
        System.out.println(f.isEmpty() ? "COMPARADOR OK" : f.size() + " falha(s)");
        System.exit(f.isEmpty() ? 0 : 1);
    }
}
