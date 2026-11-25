package br.com.carrinhocerto.core;

/** Valores monetários sempre em centavos (long). Sem dependências do Android. */
public final class Money {
    private Money() {}

    /** Formata centavos em "R$ 1.234,56". */
    public static String format(long cents) {
        boolean neg = cents < 0;
        long abs = Math.abs(cents);
        long reais = abs / 100;
        long cent = abs % 100;
        String r = groupThousands(Long.toString(reais));
        return (neg ? "-R$ " : "R$ ") + r + "," + (cent < 10 ? "0" : "") + cent;
    }

    /** Formata sem o prefixo "R$ " (ex.: "12,90"), útil para campos de edição. */
    public static String formatPlain(long cents) {
        return format(cents).replace("R$ ", "");
    }

    private static String groupThousands(String digits) {
        StringBuilder sb = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            sb.append(digits.charAt(i));
            int rest = n - i - 1;
            if (rest > 0 && rest % 3 == 0) sb.append('.');
        }
        return sb.toString();
    }

    /**
     * Converte texto digitado pelo usuário em centavos.
     * Aceita "12,90", "12.90", "R$ 1.234,56", "1234.5", "12". Retorna -1 se inválido.
     */
    public static long parse(String input) {
        if (input == null) return -1;
        String s = input.trim().replace("R$", "").replace(" ", "").replace(" ", "");
        if (s.isEmpty()) return -1;
        int lastComma = s.lastIndexOf(',');
        int lastDot = s.lastIndexOf('.');
        int sep = Math.max(lastComma, lastDot);
        String intPart;
        String decPart = "";
        if (sep >= 0) {
            String after = s.substring(sep + 1);
            // "1.234" (3 dígitos após o ponto, sem vírgula) = milhar, não decimal
            if (after.length() == 3 && lastComma < 0 && lastDot == sep) {
                intPart = s.replace(".", "");
            } else {
                intPart = s.substring(0, sep).replace(".", "").replace(",", "");
                decPart = after;
            }
        } else {
            intPart = s;
        }
        if (intPart.isEmpty()) intPart = "0";
        if (!intPart.matches("\\d{1,9}") || !decPart.matches("\\d{0,2}")) return -1;
        if (decPart.length() == 1) decPart = decPart + "0";
        if (decPart.isEmpty()) decPart = "00";
        return Long.parseLong(intPart) * 100 + Long.parseLong(decPart);
    }

    /** Subtotal = preço unitário x quantidade, arredondado ao centavo. */
    public static long times(long unitCents, double quantity) {
        return Math.round(unitCents * quantity);
    }

    /** Formata quantidade: "2", "1,5", "0,735". */
    public static String formatQty(double q) {
        long thousandths = Math.round(q * 1000);
        long whole = thousandths / 1000;
        long frac = thousandths % 1000;
        if (frac == 0) return Long.toString(whole);
        String f = String.format(java.util.Locale.ROOT, "%03d", frac);
        while (f.endsWith("0")) f = f.substring(0, f.length() - 1);
        return whole + "," + f;
    }

    /** Lê quantidade digitada ("2", "1,5", "0.735"). Retorna -1 se inválida ou <= 0. */
    public static double parseQty(String input) {
        if (input == null) return -1;
        String s = input.trim().replace(',', '.');
        if (!s.matches("\\d{1,5}(\\.\\d{1,3})?")) return -1;
        double v = Double.parseDouble(s);
        return v > 0 ? v : -1;
    }
}
