package com.vladoose.nir.service.productwatch.fit;

import com.vladoose.nir.entity.MedEquipment;
import com.vladoose.nir.entity.MedRegistry;
import com.vladoose.nir.entity.RegStatus;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import com.vladoose.nir.service.productwatch.ProductText;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Карточка товара из товара сайта и его РУ (спека §4.3): факты — сайт, карточка РУ, описание; стемы — для признаков. */
public final class ProductCards {

    public static final String SITE = "сайт", REG = "РУ", DESCRIPTION = "описание";

    /** «Мощность, Вт», «Срок службы (лет)», «Потребляемая мощность, не более, ВА» — единица в конце названия. */
    private static final Pattern NAME_UNIT = Pattern.compile("[,(]\\s*([^,()]{1,14})\\)?\\s*$");
    private static final Pattern PLAIN = Pattern.compile("^(\\d+(?:[.,]\\d+)?)$");

    private ProductCards() {}

    public static ProductCard of(SiteProduct p, RegistrationInfo reg) {
        List<ProductFact> facts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        append(text, p.getName(), p.getShortDescription(), p.getDescription());
        for (SiteProductAttribute a : p.getAttributes() == null ? List.<SiteProductAttribute>of() : p.getAttributes()) {
            if (a == null || a.name() == null || a.value() == null) continue;
            append(text, a.name(), a.value());
            facts.addAll(attributeFacts(a));
        }
        if (reg != null) {
            append(text, reg.purpose(), reg.useArea(), reg.techChars());
            if (reg.components() != null) reg.components().forEach(c -> append(text, c));
            facts.addAll(textFacts(reg.techChars(), REG));
        }
        facts.addAll(textFacts(p.getDescription(), DESCRIPTION));
        RegStatus status = reg != null ? reg.status()
                : RegNumbers.normalize(p.getRegistrationCert()) == null ? RegStatus.NOT_SPECIFIED : RegStatus.UNKNOWN;
        return new ProductCard(p.getId(), p.getName(), p.getBrand(), p.getSlug(), RegNumbers.normalize(p.getRegistrationCert()),
                status, reg == null ? null : reg.expires(), reg != null && reg.unlimited(), List.copyOf(facts),
                Set.copyOf(FitText.stems(text.toString())));
    }

    /**
     * Модель реестра «в работе» (спека 2026-10-20-lot-model-in-work §6): факты — числа кратких характеристик карточки РУ
     * (источник «РУ»), слова — наименование, назначение, область применения, характеристики; РУ — по сроку в реестре.
     */
    public static ProductCard ofRegistry(MedRegistry r, LocalDate today) {
        StringBuilder text = new StringBuilder();
        append(text, r.getName(), r.getPurpose(), r.getUseArea(), r.getTechChars());
        boolean unlimited = Boolean.TRUE.equals(r.getUnlimited());
        RegStatus status = unlimited ? RegStatus.ACTIVE : r.getExpirationDate() == null ? RegStatus.UNKNOWN
                : r.getExpirationDate().isBefore(today) ? RegStatus.EXPIRED : RegStatus.ACTIVE;
        return new ProductCard(0, r.getName(), r.getProducer(), null, r.getRegNumber(), status, r.getExpirationDate(), unlimited,
                List.copyOf(textFacts(r.getTechChars(), REG)), Set.copyOf(FitText.stems(text.toString())));
    }

    /** Позиция каталога без товара сайта и без РУ (подбор по каталогу): факты — из её описания. */
    public static ProductCard ofEquipment(MedEquipment e) {
        StringBuilder text = new StringBuilder();
        append(text, e.getName(), e.getSpec());
        return new ProductCard(0, e.getName(), e.getManufact(), null, null, RegStatus.UNKNOWN, null, false,
                List.copyOf(textFacts(e.getSpec(), DESCRIPTION)), Set.copyOf(FitText.stems(text.toString())));
    }

    static List<ProductFact> attributeFacts(SiteProductAttribute a) {
        String name = ProductText.normalize(a.name()).strip();
        String value = ProductText.normalize(a.value()).strip();
        String text = a.name().strip() + ": " + a.value().strip();
        List<ProductFact> out = new ArrayList<>();
        List<SpecNumbers.Hit> hits = SpecNumbers.find(value);
        SpecCmp nameCmp = name.contains("не более") || name.contains("не выше") ? SpecCmp.MAX
                : name.contains("не менее") || name.contains("не ниже") ? SpecCmp.MIN : null;
        if (!hits.isEmpty()) {
            // габариты с допусками («300 × 300 × 100 мм (допуск −10 / −10 / −5 мм)»): прочие длины — допуски, не факты
            boolean dims = hits.stream().anyMatch(h -> !h.dims().isEmpty());
            for (SpecNumbers.Hit h : hits) {
                if (dims && h.dims().isEmpty() && h.unit() != null && "длина".equals(h.unit().family())) continue;
                out.add(fact(cleanName(name), h, nameCmp, SITE, text));
            }
            return out;
        }
        Matcher plain = PLAIN.matcher(value);
        if (!plain.matches()) return out;
        double v = Double.parseDouble(plain.group(1).replace(',', '.'));
        SpecUnit unit = unitInName(name);
        double base = unit == null ? v : unit.toBase(v);
        SpecCmp cmp = nameCmp == null ? SpecCmp.APPROX : nameCmp;
        double lo = cmp == SpecCmp.MAX ? 0 : base, hi = cmp == SpecCmp.MIN ? Double.POSITIVE_INFINITY : base;
        out.add(new ProductFact(cleanName(name), cmp, lo, hi, unit, List.of(), SITE, text));
        return out;
    }

    /** Числа текста (карточка РУ, описание) — через разбор требований: те же имена и условия. */
    static List<ProductFact> textFacts(String raw, String source) {
        List<ProductFact> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (SpecRequirement r : SpecRequirements.extract(SpecRequirements.reflowText(raw))) {
            if (r.kind() == SpecRequirement.Kind.FEATURE) continue;
            out.add(new ProductFact(r.name(), r.cmp(), r.lo(), r.hi(), r.unit(), r.dims(), source, r.text()));
        }
        return out;
    }

    private static ProductFact fact(String name, SpecNumbers.Hit h, SpecCmp nameCmp, String source, String text) {
        SpecCmp cmp = h.cmp() == SpecCmp.APPROX && nameCmp != null ? nameCmp : h.cmp();
        double lo = h.lo(), hi = h.hi();
        if (cmp == SpecCmp.MAX && h.cmp() == SpecCmp.APPROX) lo = 0;
        if (cmp == SpecCmp.MIN && h.cmp() == SpecCmp.APPROX) hi = Double.POSITIVE_INFINITY;
        return new ProductFact(name, cmp, lo, hi, h.unit(), h.dims(), source, text);
    }

    static SpecUnit unitInName(String normalizedName) {
        Matcher m = NAME_UNIT.matcher(normalizedName);
        if (!m.find()) return null;
        SpecUnit.Hit u = SpecUnit.parseAt(m.group(1).strip(), 0);
        return u == null ? null : u.unit();
    }

    /** Имя без хвоста единицы и «не более»: «потребляемая мощность, не более, ва» → «потребляемая мощность». */
    private static String cleanName(String name) {
        String n = name.replaceAll("[,(]\\s*(не более|не менее|не выше|не ниже)\\s*[,)]?", ",");
        n = NAME_UNIT.matcher(n).replaceFirst("");
        return n.replaceAll("[,\\s]+$", "").strip();
    }

    private static void append(StringBuilder b, String... parts) {
        for (String s : parts) if (s != null && !s.isBlank()) b.append(' ').append(s);
    }
}
