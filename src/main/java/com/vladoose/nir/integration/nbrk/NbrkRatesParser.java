package com.vladoose.nir.integration.nbrk;

import com.vladoose.nir.exception.UpstreamException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Разбор `rss/get_rates.cfm`: item → title (код), description (курс), quant (за сколько единиц). DOCTYPE запрещён (XXE). */
public final class NbrkRatesParser {

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private NbrkRatesParser() {}

    public static List<NbrkRate> parse(byte[] xml) {
        Document doc;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new UpstreamException("Нацбанк РК: ответ не разобран");
        }
        List<NbrkRate> out = new ArrayList<>();
        NodeList items = doc.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Element it = (Element) items.item(i);
            String code = text(it, "title");
            BigDecimal value = number(text(it, "description"));
            BigDecimal quant = number(text(it, "quant"));
            if (code == null || !CODE.matcher(code).matches() || value == null || value.signum() <= 0) continue;
            if (quant == null || quant.signum() <= 0) quant = BigDecimal.ONE;
            out.add(new NbrkRate(code, value.divide(quant, 6, RoundingMode.HALF_UP)));
        }
        if (out.isEmpty()) throw new UpstreamException("Нацбанк РК: курсов нет в ответе");
        return out;
    }

    private static String text(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }

    private static BigDecimal number(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return new BigDecimal(s.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
