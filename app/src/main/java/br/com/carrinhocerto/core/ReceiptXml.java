package br.com.carrinhocerto.core;

import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Lê o XML da NFC-e/NF-e (leiaute 4.00): det/prod (cProd, cEAN, xProd, uCom, qCom, vUnCom, vProd,
 * vDesc, vOutro), total/ICMSTot/vNF e emit/xFant|xNome. Dados exatos, sem OCR.
 */
public final class ReceiptXml {
    private ReceiptXml() {}

    public static Receipt parse(InputStream in) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) { }
        try { f.setExpandEntityReferences(false); } catch (Exception ignored) { }
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(in);
        Receipt r = new Receipt();

        NodeList dets = doc.getElementsByTagName("det");
        for (int i = 0; i < dets.getLength(); i++) {
            Element det = (Element) dets.item(i);
            Element prod = first(det, "prod");
            if (prod == null) continue;
            Receipt.Line l = new Receipt.Line();
            String n = det.getAttribute("nItem");
            if (n != null && n.matches("\\d+")) l.seq = Integer.parseInt(n);
            String ean = text(prod, "cEAN");
            l.code = ean != null && ean.matches("\\d{8,14}") ? ean : text(prod, "cProd");
            l.desc = nz(text(prod, "xProd"));
            l.unit = nz(text(prod, "uCom")).toUpperCase(java.util.Locale.ROOT);
            if (l.unit.isEmpty()) l.unit = "UN";
            l.qty = dbl(text(prod, "qCom"), 1);
            l.unitCents = Math.round(dbl(text(prod, "vUnCom"), 0) * 100);
            l.totalCents = Math.round(dbl(text(prod, "vProd"), 0) * 100);
            l.discountCents = Math.round(dbl(text(prod, "vDesc"), 0) * 100);
            l.surchargeCents = Math.round(dbl(text(prod, "vOutro"), 0) * 100);
            r.lines.add(l);
        }
        Element tot = firstAnywhere(doc, "ICMSTot");
        if (tot != null) {
            r.totalCents = Math.round(dbl(text(tot, "vNF"), -0.01) * 100);
            r.discountTotalCents = Math.round(dbl(text(tot, "vDesc"), 0) * 100);
        }
        Element emit = firstAnywhere(doc, "emit");
        if (emit != null) {
            String fant = text(emit, "xFant");
            r.store = fant != null && !fant.isEmpty() ? fant : text(emit, "xNome");
        }
        return r;
    }

    private static Element first(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        return l.getLength() > 0 ? (Element) l.item(0) : null;
    }

    private static Element firstAnywhere(Document d, String tag) {
        NodeList l = d.getElementsByTagName(tag);
        return l.getLength() > 0 ? (Element) l.item(0) : null;
    }

    private static String text(Element parent, String tag) {
        NodeList l = parent.getElementsByTagName(tag);
        if (l.getLength() == 0) return null;
        Node n = l.item(0);
        return n.getTextContent() == null ? null : n.getTextContent().trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static double dbl(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return def; }
    }
}
