package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.dto.response.AiBriefResponse;
import com.vladoose.nir.dto.response.LotModelFitResponse;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.westmed.WestmedLeadMapper;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.TenderService;
import com.vladoose.nir.service.lotmodel.LotModelReader;
import com.vladoose.nir.service.lotmodel.ModelSnapshot;
import com.vladoose.nir.service.productwatch.fit.*;
import com.vladoose.nir.util.DocFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «📋 Для ИИ» (спека 2026-10-20-product-spec-fit §9): готовый текст по тендерам с нашими лотами для Claude / Gemini —
 * запрос, тендер, наши лоты (сверка, требования, текст ТЗ), наши товары с РУ и характеристиками, похожие записи реестра.
 * Живых запросов в ndda.kz нет: характеристики реестра — только уже загруженные.
 */
@Service
public class ProductAiBrief {

    public static final int MAX_TENDERS = 15;
    public static final int LIST_SPEC_CHARS = 4000;
    public static final int MAX_TEXT = 120_000;
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String PROMPT = """
            Ты помогаешь компании West-Med (Казахстан, поставка медицинского оборудования) разобрать государственные тендеры.
            Ниже — тендеры, в которых, возможно, есть наше оборудование: по каждому лоту — требования технической спецификации (ТЗ),
            наша автоматическая сверка, наши товары-кандидаты с характеристиками и регистрацией в РК и похожие изделия из реестра
            НЦЭЛС РК.

            Для каждого лота:
            1. Коротко — какое оборудование требуется и его ключевые параметры.
            2. Под какую модель и производителя похоже написано ТЗ; не уверен — «не определено».
            3. Подходит ли наш товар-кандидат: по каждому ключевому требованию — выполняется / не выполняется / нет данных; итог.
            4. Есть ли у подходящего изделия действующая регистрация в РК (реестр НЦЭЛС: https://oldregister.ndda.kz).
            Если можешь искать в интернете — проверь характеристики моделей по официальным источникам и дай ссылки.
            Ничего не выдумывай: нет данных — так и пиши.
            Если у лота указана «Модель в работе» — проверь её первой.

            Ответ — списком в формате:
            Тендер № … — Лот № … — Требуется: … — ТЗ похоже на: … — Наш товар: … (подходит / не подходит: причина / нет данных) — РУ в РК: …

            В самом конце ответа — блок «Для АИС»: отдельным блоком кода, по строке на каждый лот из текста:
            L<код лота> | идти / аналог / не наше / не ясно | P<код нашего товара> или — | коротко почему
            Код лота — в квадратных скобках у заголовка лота [L…], код товара — у наших товаров-кандидатов и похожих наших товаров этого лота [P…].
            «идти» — подходит наш товар по ТЗ, с его кодом; «аналог» — наш не подходит, но ТЗ подходит под чужую модель с РУ
            в РК: код товара — «—», модель, производитель и № РУ — в «почему»; «не наше» — ставить нечего (работы, услуги,
            лекарства, не наш профиль).
            """;

    private final TenderService tenderService;
    private final TenderLotProductMatchRepository matches;
    private final SiteProductRepository products;
    private final ProductRegistrationRepository registrations;
    private final MedRegistryRepository registry;
    private final RegistryMatchService registryMatch;
    private final LotModelReader modelReader;
    private final String siteUrl;

    public ProductAiBrief(TenderService tenderService, TenderLotProductMatchRepository matches, SiteProductRepository products,
                          ProductRegistrationRepository registrations, MedRegistryRepository registry, RegistryMatchService registryMatch,
                          LotModelReader modelReader, @Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl) {
        this.tenderService = tenderService; this.matches = matches; this.products = products; this.registrations = registrations;
        this.registry = registry; this.registryMatch = registryMatch; this.modelReader = modelReader;
        this.siteUrl = siteUrl == null ? "" : siteUrl.replaceAll("/+$", "");
    }

    @Transactional(readOnly = true)
    public String forTender(long tenderId) {
        Tender t = tenderService.findPublic(tenderId);
        return total(PROMPT + "\n" + block(t, Integer.MAX_VALUE));
    }

    @Transactional(readOnly = true)
    public AiBriefResponse forTenders(List<Long> ids) {
        StringBuilder b = new StringBuilder(PROMPT);
        int n = 0;
        for (Long id : new LinkedHashSet<>(ids == null ? List.<Long>of() : ids)) {
            if (n >= MAX_TENDERS) break;
            Tender t;
            try {
                t = tenderService.findPublic(id);
            } catch (NotFoundException e) {
                continue;                               // чужой рынок или вид — молча мимо (отбор делает экран)
            }
            b.append('\n').append(block(t, LIST_SPEC_CHARS));
            n++;
            if (b.length() >= MAX_TEXT) break;
        }
        return new AiBriefResponse(total(b.toString()), n, ids == null ? 0 : ids.size());
    }

    private String block(Tender t, int specChars) {
        StringBuilder b = new StringBuilder();
        b.append("=== Тендер № ").append(t.getTenderNumber()).append(" (").append(platform(t)).append(") — ").append(link(t)).append('\n');
        if (t.getDescription() != null) b.append("Предмет: ").append(one(t.getDescription())).append('\n');
        List<String> head = new ArrayList<>();
        if (t.getCustomerName() != null) head.add("Заказчик: " + one(t.getCustomerName()));
        if (t.getRegion() != null) head.add("регион: " + one(t.getRegion()));
        if (t.getDeadlineAt() != null) head.add("приём заявок до " + t.getDeadlineAt().format(DATE_TIME));
        else if (t.getDeadline() != null) head.add("приём заявок до " + t.getDeadline().format(DATE));
        if (t.getTotalCost() != null) head.add("сумма: " + DocFormat.money(t.getTotalCost()) + " ₸");
        if (!head.isEmpty()) b.append(String.join("; ", head)).append('\n');
        Map<Long, List<TenderLotProductMatch>> byLot = matches.findByTenderId(t.getId()).stream()
                .collect(Collectors.groupingBy(TenderLotProductMatch::getLotId, LinkedHashMap::new, Collectors.toList()));
        for (TenderLot l : t.getLots()) {
            List<TenderLotProductMatch> ms = byLot.get(l.getId());
            if (ms == null && l.getProposedEquipment() == null) continue;      // лот с моделью в работе — и без нашей отметки
            lot(b, l, ms == null ? List.of() : ms, specChars);
        }
        return b.toString();
    }

    private void lot(StringBuilder b, TenderLot l, List<TenderLotProductMatch> ms, int specChars) {
        String num = l.getSourceLotCode() != null ? l.getSourceLotCode() : Objects.toString(l.getLotNumber(), "—");   // № лота площадки
        b.append("--- Лот № ").append(one(num)).append(" [L").append(l.getId()).append("]: ").append(one(l.getEquipName()));
        if (l.getQuantity() != null) b.append(" — ").append(l.getQuantity()).append(" шт.");
        if (l.getMaxCost() != null) b.append(", до ").append(DocFormat.money(l.getMaxCost())).append(" ₸ за ед.");
        b.append('\n');
        modelLine(b, l);
        for (TenderLotProductMatch m : ms) b.append("Сверка АИС: ").append(verdict(m)).append('\n');
        boolean spec = l.getTechSpecStatus() == TechSpecStatus.OK && l.getRequiredSpec() != null;
        String body = ProductMatchService.fitText(spec ? l.getRequiredSpec() : null, spec ? null : l.getRequiredSpec());   // как в сверке
        List<SpecRequirement> reqs = SpecRequirements.extract(body);
        if (!reqs.isEmpty()) {
            b.append("Требования ТЗ (").append(reqs.size()).append("):\n");
            reqs.forEach(r -> b.append("- ").append(one(r.text())).append('\n'));
        }
        if (!body.isBlank()) b.append(spec ? "Текст ТЗ:\n" : "Описание лота с площадки:\n").append(cut(body, specChars)).append('\n');
        Set<Long> ids = new LinkedHashSet<>();
        ms.forEach(m -> m.getFitCandidates().forEach(c -> ids.add(c.productId())));
        ms.stream().map(TenderLotProductMatch::getProductId).filter(Objects::nonNull).forEach(ids::add);
        if (!ids.isEmpty()) {
            b.append("Наши товары-кандидаты:\n");
            for (SiteProduct p : products.findAllById(ids)) product(b, p);
        }
        // похожие наши товары — с кодом [P…], чтобы ИИ мог их назвать (прод 2026-10-22: L8375 — «электроды ФОТЕК, кода P нет»);
        // короткой строкой, без характеристик: иначе текст из 15 тендеров не помещается в предел
        Set<Long> similar = new LinkedHashSet<>();
        ms.stream().filter(m -> m.getSimilar() != null).forEach(m -> m.getSimilar().forEach(p -> {
            if (!ids.contains(p.productId())) similar.add(p.productId());
        }));
        if (!similar.isEmpty()) {
            Map<Long, SiteProduct> found = products.findAllById(similar).stream().filter(p -> p.getRemovedAt() == null)
                    .collect(Collectors.toMap(SiteProduct::getId, p -> p));
            if (!found.isEmpty()) {
                b.append("Похожие наши товары (без сверки АИС; характеристики — на сайте по ссылке):\n");
                similar.stream().map(found::get).filter(Objects::nonNull).forEach(p -> productLine(b, p));
            }
        }
        try {
            LotRegistryMatchResponse rm = registryMatch.matchForLotUi(l.getId(), 3);
            if (rm.getCandidates() != null && !rm.getCandidates().isEmpty()) {
                b.append("Похожие изделия в реестре НЦЭЛС РК:\n");
                for (RegistryCandidateResponse c : rm.getCandidates()) {
                    b.append("* ").append(one(c.getName())).append(" — ").append(one(Objects.toString(c.getProducer(), "")))
                            .append(c.getCountry() == null ? "" : ", " + one(c.getCountry())).append(" — РУ ").append(c.getRegNumber());
                    registry.findByRegNumber(c.getRegNumber()).map(MedRegistry::getTechChars).filter(s -> !s.isBlank())
                            .ifPresent(tc -> b.append(" — характеристики: ").append(cut(one(tc), 600)));
                    b.append('\n');
                }
            }
        } catch (RuntimeException ignored) {
            // подбор реестра — подсказка: без него текст полезен
        }
    }

    /**
     * Модель в работе (спека 2026-10-20-lot-model-in-work §7): что закреплено, кем и почему, и её сверка — ИИ проверит её
     * первой. Снимок — без своей транзакции: сбой внутри чужой пометил бы транзакцию «Для ИИ» на откат и уронил бы весь текст.
     */
    private void modelLine(StringBuilder b, TenderLot l) {
        MedEquipment model = l.getProposedEquipment();
        if (model == null) return;
        ModelSnapshot s = null;
        try {
            s = modelReader.snapshot(l);
        } catch (RuntimeException ignored) {
            // снимок — подсказка: строка модели полезна и без статуса РУ и сверки
        }
        b.append("Модель в работе: ").append(one(model.getName()));
        if (model.getManufact() != null && !"не указан".equals(model.getManufact())) b.append(" (").append(one(model.getManufact())).append(')');
        b.append(ruText(model, s == null ? null : s.model()));
        if (l.getModelSetBy() != null) {
            b.append("; закрепил ").append(one(l.getModelSetBy()));
            if (l.getModelSetAt() != null) b.append(' ').append(l.getModelSetAt().atZoneSameInstant(Market.KZ.zone()).format(DATE));
        }
        if (l.getModelNote() != null) b.append("; почему: ").append(one(l.getModelNote()));
        b.append('\n');
        if (s == null || s.card() == null || s.needsDetail()) return;      // в «Для ИИ» ndda.kz не зовём
        try {
            FitCandidate c = SpecFit.compare(SpecRequirements.extract(s.text()), s.card());
            long diff = c.rows() == null ? 0 : c.rows().stream().filter(r -> r.mark() == FitMark.DIFF).count();
            b.append("Сверка АИС модели в работе: ✓ ").append(c.met()).append(" · ✗ ").append(c.failed())
                    .append(" · ≈ ").append(diff).append(" · нет данных ").append(c.unknown()).append('\n');
        } catch (RuntimeException ignored) {
            // сверка — подсказка: без неё текст полезен
        }
    }

    /** «, РУ <№> — действует до …» / «, РУ не указано» — регистрацию модели ИИ проверит первой (спека §7). */
    private static String ruText(MedEquipment model, LotModelFitResponse.Model m) {
        String num = model.getRegistration() != null ? model.getRegistration().getRegNumber()
                : model.getSiteProduct() != null ? model.getSiteProduct().getRegistrationCert() : null;
        String status = m == null || m.regStatus() == null ? null : switch (m.regStatus()) {
            case "ACTIVE" -> "действует" + (m.regUnlimited() ? " бессрочно" : m.regExpires() == null ? "" : " до " + m.regExpires().format(DATE));
            case "EXPIRED" -> "истекло" + (m.regExpires() == null ? "" : " " + m.regExpires().format(DATE));
            case "NOT_FOUND" -> "не найдено в реестре";
            case "NOT_SPECIFIED" -> "не указано";
            default -> "не проверено";
        };
        if (num == null || num.isBlank()) return status == null ? "" : ", РУ " + status;
        return ", РУ " + one(num) + (status == null || "не указано".equals(status) ? "" : " — " + status);
    }

    private void product(StringBuilder b, SiteProduct p) {
        ProductRegistration r = productLine(b, p);
        if (p.getAttributes() != null && !p.getAttributes().isEmpty()) {
            b.append("  Характеристики (сайт): ").append(p.getAttributes().stream()
                    .map(a -> one(a.name()) + ": " + one(a.value())).collect(Collectors.joining("; "))).append('\n');
        }
        if (r != null && (r.getPurpose() != null || r.getTechChars() != null)) {
            b.append("  Из карточки РУ:");
            if (r.getPurpose() != null) b.append(" назначение — ").append(cut(one(r.getPurpose()), 400)).append(';');
            if (r.getTechChars() != null) b.append(" краткие характеристики — ").append(cut(one(r.getTechChars()), 800));
            b.append('\n');
        }
    }

    /** «* [P…] название — ссылка — РУ …»; запись РУ — для подробностей у кандидатов. */
    private ProductRegistration productLine(StringBuilder b, SiteProduct p) {
        b.append("* [P").append(p.getId()).append("] ").append(one(p.getName()));
        if (p.getSlug() != null) b.append(" — ").append(WestmedLeadMapper.productUrl(siteUrl, p.getSlug()));
        String reg = RegNumbers.normalize(p.getRegistrationCert());
        ProductRegistration r = reg == null ? null : registrations.findById(reg).orElse(null);
        b.append(" — ").append(regText(reg, r)).append('\n');
        return r;
    }

    private static String regText(String reg, ProductRegistration r) {
        if (reg == null) return "РУ не указано на сайте";
        if (r == null) return "РУ " + reg + " — не проверено";
        return "РУ " + reg + " — " + switch (r.getStatus()) {
            case ACTIVE -> "действует" + (Boolean.TRUE.equals(r.getUnlimited()) ? " бессрочно"
                    : r.getExpirationDate() == null ? "" : " до " + r.getExpirationDate().format(DATE));
            case EXPIRED -> "истекло" + (r.getExpirationDate() == null ? "" : " " + r.getExpirationDate().format(DATE));
            case NOT_FOUND -> "не найдено в реестре";
            case NOT_SPECIFIED -> "не указано";
            case UNKNOWN -> "не проверено";
        };
    }

    private static String verdict(TenderLotProductMatch m) {
        if (m.getLevel() == ProductMatchLevel.COPIED) return "★ ТЗ списано с нашего товара";
        if (m.getLevel() == ProductMatchLevel.NAMED) return "★ назван наш товар";
        FitCandidate b = m.getFitCandidates() == null || m.getFitCandidates().isEmpty() ? null : m.getFitCandidates().get(0);
        String foreign = m.getForeignBrand() == null ? "" : "; ТЗ под " + m.getForeignBrand();
        if (m.getFit() == null || b == null) return "🏷 наш вид изделия, сверки нет (требований в ТЗ не найдено)" + foreign;
        String n = b.met() + " из " + b.total() + " требований, нет данных " + b.unknown();
        return switch (m.getFit()) {
            case FITS -> "✓ подходит наш товар: " + b.name() + " (" + n + ")";
            case FITS_NO_RU -> "подходит по ТЗ " + b.name() + ", но " + RegStatus.valueOf(b.regStatus()).noRuText() + " (" + n + ")";
            case PARTIAL -> "◐ похоже — проверить: " + b.name() + " (" + n + ")";
            case NOT_FIT -> "✗ не подходит: " + Objects.toString(b.reason(), b.name());
            case NO_REQUIREMENTS -> "🏷 наш вид изделия, сверки нет";
        } + foreign;
    }

    private static String platform(Tender t) {
        return t.getPlatform() == TenderPlatform.SK_PHARMACY ? "СК-Фармация" : "goszakup";
    }

    /** Ссылка на объявление — как кнопка «Открыть» карточки (market.service.portalLink). */
    private static String link(Tender t) {
        String num = Objects.toString(t.getTenderNumber(), "");
        String id = num.matches("\\d+-\\d+") ? num.substring(0, num.indexOf('-')) : null;
        if (t.getPlatform() == TenderPlatform.SK_PHARMACY) return id == null ? "https://fms.ecc.kz/ru/searchanno" : "https://fms.ecc.kz/ru/announce/index/" + id + "?tab=lots";
        return id == null ? "https://goszakup.gov.kz" : "https://goszakup.gov.kz/ru/announce/index/" + id;
    }

    private static String one(String s) {
        return s == null ? "" : s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll(" {2,}", " ").strip();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Весь текст — не длиннее MAX_TEXT, обрезка подписана (спека §9.1). */
    private static String total(String s) {
        String tail = "\n…обрезано";
        return s.length() <= MAX_TEXT ? s : s.substring(0, MAX_TEXT - tail.length()) + tail;
    }
}
