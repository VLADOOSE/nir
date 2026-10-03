package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.dto.request.ClientOfferUpdateRequest;
import com.vladoose.nir.dto.response.PreviewPagesResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.service.document.*;
import com.vladoose.nir.service.offer.ClientOfferCalculator;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferSettingsValidator;
import com.vladoose.nir.util.DocFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/**
 * «КП клиентам» (спека client-kp-constructor §8–§12). Запись — только через этот сервис: гард рынка по id
 * (findById = em.find, фильтр рынка обходит), версия от второй вкладки, строки — через коллекцию (§7 CLAUDE.md).
 * Ввод проверяется до любых изменений КП — 400 с причиной, а не 500 из расчёта, сборщика документа или базы; суммы,
 * которые не помещаются в денежные колонки и пропись, — после расчёта, до сохранения. PDF, Word и предпросмотр
 * собираются вне транзакции: КП и реквизиты читаются короткой транзакцией только на чтение, рендер — уже после неё.
 */
@Service
public class ClientOfferService {

    private static final Logger log = LoggerFactory.getLogger(ClientOfferService.class);

    public static final int LIST_LIMIT = 300;
    public static final int PREVIEW_MAX_PAGES = 20;
    static final String NOT_REQUIRED_TEXT = "Не подлежит регистрации";
    static final String DEFAULT_TITLE = "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ";
    /** Больше не держат денежные колонки (NUMERIC(15,2)) — и пропись итога: 9 999 999 999 999,99. */
    static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999.99");
    /** Больше не держит колонка количества (NUMERIC(12,3)): 999 999 999,999. */
    static final BigDecimal MAX_QUANTITY = new BigDecimal("999999999.999");
    private static final BigDecimal MIN_MARKUP = BigDecimal.valueOf(-100), MAX_MARKUP = BigDecimal.valueOf(1000);
    /** Знаков после запятой в колонках: количество — 3; деньги, наценка и ставки НДС — 2. */
    private static final int QTY_SCALE = 3, MONEY_SCALE = 2;
    /** Наименование в тексте ошибки — не длиннее (наименование бывает в абзац). */
    private static final int NAME_IN_MESSAGE = 60;

    private final ClientOfferRepository repository;
    private final CompanyProfileService profiles;
    private final FacilityRepository facilities;
    private final ClientOfferCalculator calculator;
    private final KpDocumentBuilder builder;
    private final KpHtmlRenderer html;
    private final KpPdfRenderer pdf;
    private final KpPreviewRenderer preview;
    private final KpDocxRenderer docx;
    /** Чтение для файла КП — короткой транзакцией только на чтение, отдельно от рендера (PDFBox — секунды процессора). */
    private final TransactionTemplate readOnly;

    public ClientOfferService(ClientOfferRepository repository, CompanyProfileService profiles,
                              FacilityRepository facilities, ClientOfferCalculator calculator, KpDocumentBuilder builder,
                              KpHtmlRenderer html, KpPdfRenderer pdf, KpPreviewRenderer preview, KpDocxRenderer docx,
                              PlatformTransactionManager transactions) {
        this.repository = repository;
        this.profiles = profiles;
        this.facilities = facilities;
        this.calculator = calculator;
        this.builder = builder;
        this.html = html;
        this.pdf = pdf;
        this.preview = preview;
        this.docx = docx;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /** Файл КП для скачивания: имя («КП № 443 от 14.09.2026 — ….pdf») и байты. */
    public record OfferFile(String fileName, byte[] bytes) {}

    /** Всё, что нужно файлу КП, — из одного чтения: модель документа (суммы посчитаны) и имя файла. */
    private record Snapshot(KpDocument document, String fileBaseName) {}

    @Transactional(readOnly = true)
    public List<ClientOffer> list(Set<ClientOfferStatus> statuses, String q) {
        Pageable top = PageRequest.of(0, LIST_LIMIT);
        if (q == null || q.isBlank()) return repository.findJournal(statuses, top);
        String needle = q.trim();
        Integer number = needle.matches("\\d{1,9}") ? Integer.valueOf(needle) : null;
        return repository.searchJournal(statuses, likePattern(needle), number, top);
    }

    @Transactional(readOnly = true)
    public ClientOffer get(Long id) {
        ClientOffer o = repository.findById(id).orElseThrow(() -> notFound(id));
        if (o.getMarket() != MarketContext.get()) throw notFound(id);
        return o;
    }

    @Transactional
    public ClientOffer create(String author) {
        Market market = MarketContext.get();
        CompanyProfile p = profiles.forMarket(market);
        ClientOffer o = ClientOffer.builder()
                .market(market)
                .number(profiles.allocateNumber(market))
                .offerDate(today(market))
                .status(ClientOfferStatus.DRAFT)
                .title(DEFAULT_TITLE)
                .intro(p.getDefaultIntro())
                .vatEnabled(true)
                .defaultMarkupPct(p.getDefaultMarkupPct())
                .rounding(OfferRounding.NONE)
                .tableColumns(copyColumns(p.getDefaultColumns()))
                .detailsInName(true)
                .terms(copyTerms(p.getDefaultTerms()))
                .termsStyle(p.getDefaultTermsStyle())
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoff(OfferSignoff.DIRECTOR)
                .totalAmount(BigDecimal.ZERO.setScale(2))
                .createdBy(author)
                .build();
        return repository.saveAndFlush(o);
    }

    @Transactional
    public ClientOffer update(Long id, ClientOfferUpdateRequest r) {
        ClientOffer o = get(id);
        if (r.getVersion() != o.getVersion()) throw new ConflictException("КП изменено в другой вкладке — обновите страницу");
        // всё проверяется до первого изменения: отказ (400) не оставляет КП изменённым наполовину
        OfferSettingsValidator.columns(r.getColumns());
        OfferSettingsValidator.terms(r.getTerms());
        CompanyProfile profile = profiles.forMarket(o.getMarket());
        List<ClientOfferItem> stored = checkItems(o, r.getItems(), profile);
        Facility facility = facility(r.getFacilityId());

        o.setNumber(r.getNumber());
        o.setOfferDate(r.getOfferDate());
        o.setFacility(facility);
        o.setRecipient(trim(r.getRecipient()));
        o.setTitle(r.getTitle().trim());
        o.setSubject(trim(r.getSubject()));
        o.setIntro(trim(r.getIntro()));
        o.setVatEnabled(r.isVatEnabled());
        o.setDefaultMarkupPct(scaled(r.getDefaultMarkupPct(), MONEY_SCALE));
        o.setRounding(r.getRounding());
        o.setTableColumns(copyColumns(r.getColumns()));
        o.setDetailsInName(r.isDetailsInName());
        o.setTerms(copyTerms(r.getTerms()));
        o.setTermsStyle(r.getTermsStyle());
        o.setShowAmountInWords(r.isShowAmountInWords());
        o.setShowVatBreakdown(r.isShowVatBreakdown());
        o.setLandscape(r.isLandscape());
        o.setSignoff(r.getSignoff());
        o.setSignoffContacts(r.isSignoffContacts());
        o.setWithStamp(r.isWithStamp());
        o.setInternalNote(trim(r.getInternalNote()));
        syncItems(o, r.getItems(), stored);

        OfferCalculation calc = calculator.calculate(o);
        checkAmounts(o, calc);   // отказ откатывает транзакцию — правки выше в базу не попадают
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        o.setUpdatedAt(OffsetDateTime.now());   // версия растёт на каждом сохранении, даже если поля не поменялись
        // flush, а не saveAndFlush: КП уже в сессии, а merge заменил бы новые строки их копиями — и ключ строки клиента
        // (clientKey, не хранится) из ответа пропал бы; при сбросе новые строки сохраняются каскадом, теми же объектами
        repository.flush();
        return o;
    }

    @Transactional
    public ClientOffer duplicate(Long id, String author) {
        ClientOffer src = get(id);
        Market market = src.getMarket();
        ClientOffer o = ClientOffer.builder()
                .market(market)
                .number(profiles.allocateNumber(market))
                .offerDate(today(market))
                .status(ClientOfferStatus.DRAFT)
                .facility(src.getFacility())
                .recipient(src.getRecipient())
                .tenderId(src.getTenderId())
                .title(src.getTitle())
                .subject(src.getSubject())
                .intro(src.getIntro())
                .vatEnabled(src.isVatEnabled())
                .defaultMarkupPct(src.getDefaultMarkupPct())
                .rounding(src.getRounding())
                .tableColumns(copyColumns(src.getTableColumns()))
                .detailsInName(src.isDetailsInName())
                .terms(copyTerms(src.getTerms()))
                .termsStyle(src.getTermsStyle())
                .showAmountInWords(src.isShowAmountInWords())
                .showVatBreakdown(src.isShowVatBreakdown())
                .landscape(src.isLandscape())
                .signoff(src.getSignoff())
                .signoffContacts(src.isSignoffContacts())
                .withStamp(src.isWithStamp())
                .internalNote(src.getInternalNote())
                .totalAmount(src.getTotalAmount())
                .itemCount(src.getItemCount())
                .createdBy(author)
                .build();
        for (ClientOfferItem it : src.getItems()) o.getItems().add(copyItem(it, o));
        return repository.saveAndFlush(o);
    }

    @Transactional
    public ClientOffer setStatus(Long id, ClientOfferStatus status) {
        ClientOffer o = get(id);
        o.setStatus(status);
        if (status == ClientOfferStatus.SENT && o.getSentAt() == null) o.setSentAt(OffsetDateTime.now());
        o.setUpdatedAt(OffsetDateTime.now());
        return repository.saveAndFlush(o);
    }

    @Transactional
    public void delete(Long id) {
        ClientOffer o = get(id);
        if (o.getStatus() != ClientOfferStatus.DRAFT) {
            throw new BadRequestException("Удалить можно только черновик — отправленное КП отметьте «Отклонено»");
        }
        repository.delete(o);
    }

    public OfferCalculation calculate(ClientOffer o) {
        return calculator.calculate(o);
    }

    public OfferFile pdf(Long id) {
        Snapshot s = snapshot(id);
        return new OfferFile(s.fileBaseName() + ".pdf", rendered(id, "PDF", () -> renderPdf(s.document())));
    }

    public OfferFile docx(Long id) {
        Snapshot s = snapshot(id);
        return new OfferFile(s.fileBaseName() + ".docx", rendered(id, "Word", () -> docx.render(s.document())));
    }

    /** Страницы предпросмотра и «таблица тесная» (кегль уменьшен) — редактор подсказывает «Альбомная». */
    public PreviewPagesResponse preview(Long id) {
        Snapshot s = snapshot(id);
        List<String> pages = rendered(id, "предпросмотр",
                () -> encode(preview.pages(renderPdf(s.document()), PREVIEW_MAX_PAGES)));
        return new PreviewPagesResponse(pages, crowded(s.document()));
    }

    /** Первая страница КП-образца с текущими реквизитами, печатью и подписью — для «Реквизитов и печати». */
    public PreviewPagesResponse samplePreview() {
        Market market = MarketContext.get();
        KpDocument doc = readOnly.execute(status -> sampleDocument(market));
        return new PreviewPagesResponse(rendered(null, "образец", () -> encode(preview.pages(renderPdf(doc), 1))), false);
    }

    /** «КП № 443 от 14.09.2026 — ГКП …»: без символов, недопустимых в именах файлов. */
    public String fileBaseName(ClientOffer o) {
        String client = clientName(o);
        String base = "КП № " + o.getNumber() + " от " + DocFormat.date(o.getOfferDate()) + (client == null ? "" : " — " + client);
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return base.length() > 150 ? base.substring(0, 150).trim() : base;
    }

    static String clientName(ClientOffer o) {
        if (o.getFacility() != null) return o.getFacility().getName();
        List<String> lines = CompanyLines.split(o.getRecipient());
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** Таблица тесная — подбор колонок уменьшил кегль ниже обычного. */
    static boolean crowded(KpDocument doc) {
        return doc.tableFontPt() < KpDocumentBuilder.TABLE_FONT_PT;
    }

    private Snapshot snapshot(Long id) {
        return readOnly.execute(status -> {
            ClientOffer o = get(id);
            return new Snapshot(document(o, profiles.forMarket(o.getMarket())), fileBaseName(o));
        });
    }

    private KpDocument document(ClientOffer o, CompanyProfile profile) {
        return builder.build(o, profile, calculator.calculate(o));
    }

    private byte[] renderPdf(KpDocument doc) {
        return pdf.render(html.render(doc));
    }

    /** Выгрузка не собралась — в лог класс исключения и id КП (спека §12); ответ — обычный 500 обработчика. */
    private static <T> T rendered(Long id, String what, Supplier<T> work) {
        try {
            return work.get();
        } catch (RuntimeException e) {
            log.warn("КП id={}: {} не собран — {}", id == null ? "образец" : id, what, e.getClass().getName());
            throw e;
        }
    }

    private KpDocument sampleDocument(Market market) {
        CompanyProfile p = profiles.forMarket(market);
        ClientOffer o = ClientOffer.builder()
                .market(market)
                .number(p.getNextNumber())
                .offerDate(today(market))
                .title(DEFAULT_TITLE)
                .recipient("Главному врачу\nГКП на ПХВ «Областная больница»")
                .intro(p.getDefaultIntro())
                .vatEnabled(true)
                .defaultMarkupPct(p.getDefaultMarkupPct())
                .tableColumns(copyColumns(p.getDefaultColumns()))
                .detailsInName(true)
                .terms(copyTerms(p.getDefaultTerms()))
                .termsStyle(p.getDefaultTermsStyle())
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoffContacts(true)
                // подпись без печати — тоже видна: образец показывает каждую загруженную картинку
                .withStamp(p.getStampPng() != null || p.getSignaturePng() != null)
                .build();
        o.getItems().add(sample(o, 1, ClientOfferItemKind.ITEM, "Анализатор автоматический биохимический «ВитаЛайн 200»",
                "шт", "1", "7650000", p.getVatRegistered(), OfferRegistrationStatus.MANUAL, "№ РК-МИ (МТ)-0№000000 от 01.01.2024 г. (образец)"));
        o.getItems().add(sample(o, 2, ClientOfferItemKind.SECTION, "Расходные материалы", null, null, null, null, null, null));
        o.getItems().add(sample(o, 3, ClientOfferItemKind.ITEM, "Реакционная кювета к анализатору (упаковка 60 шт)",
                "упак", "2", "70631.23", p.getVatNotRegistrable(), OfferRegistrationStatus.NOT_REQUIRED, NOT_REQUIRED_TEXT));
        o.getItems().add(sample(o, 4, ClientOfferItemKind.INCLUDED, "Гарантийное сервисное обслуживание 12 месяцев",
                null, null, null, null, null, null));
        return document(o, p);
    }

    /**
     * Строки запроса — до любых изменений КП. Для каждой возвращается её сохранённая строка (по id) или null — новая.
     * Числа строк здесь же округляются до точности своих колонок (как округлила бы база), и проверяется уже округлённое:
     * ответ автосохранения посчитан от того же, что ляжет в базу.
     */
    private static List<ClientOfferItem> checkItems(ClientOffer o, List<ClientOfferItemDto> dtos, CompanyProfile profile) {
        Map<Long, ClientOfferItem> existing = new HashMap<>();
        for (ClientOfferItem it : o.getItems()) existing.put(it.getId(), it);
        List<ClientOfferItem> stored = new ArrayList<>(dtos.size());
        int itemNo = 0;
        for (int i = 0; i < dtos.size(); i++) {
            ClientOfferItemDto dto = dtos.get(i);
            if (dto == null) throw new BadRequestException("Пустая строка в списке строк КП (строка " + (i + 1) + ")");
            ClientOfferItem it = null;
            if (dto.getId() != null) {
                it = existing.remove(dto.getId());
                if (it == null) throw new BadRequestException("Строка не найдена или повторяется: id=" + dto.getId());
            }
            if (dto.getKind() == null) throw new BadRequestException("Не указан вид строки (строка " + (i + 1) + ")");
            if (dto.getKind() == ClientOfferItemKind.ITEM) checkItem(dto, it, profile, "№ " + (++itemNo) + quotedName(dto.getName()));
            stored.add(it);
        }
        return stored;
    }

    /** Позиция (ITEM): количество, цены, наценка, ставки, регистрация; row — «№ 2 «Пульсоксиметр»», как в колонке «№». */
    private static void checkItem(ClientOfferItemDto dto, ClientOfferItem stored, CompanyProfile profile, String row) {
        BigDecimal listedVat = dto.getVatRate();   // ставка — как её выбрали из списка рынка
        dto.setQuantity(scaled(dto.getQuantity(), QTY_SCALE));
        dto.setPurchasePrice(scaled(dto.getPurchasePrice(), MONEY_SCALE));
        dto.setPriceOverride(scaled(dto.getPriceOverride(), MONEY_SCALE));
        dto.setMarkupPct(scaled(dto.getMarkupPct(), MONEY_SCALE));
        dto.setPurchaseVatRate(scaled(dto.getPurchaseVatRate(), MONEY_SCALE));
        dto.setVatRate(scaled(listedVat, MONEY_SCALE));

        BigDecimal q = dto.getQuantity();
        if (q != null && q.compareTo(MAX_QUANTITY) > 0) {
            throw new BadRequestException("Количество больше " + DocFormat.qty(MAX_QUANTITY) + " — позиция " + row);
        }
        if (q != null && q.signum() <= 0) throw new BadRequestException("Количество должно быть больше нуля — позиция " + row);
        money(dto.getPurchasePrice(), "Цена закупки", row);
        money(dto.getPriceOverride(), "Цена клиенту", row);
        BigDecimal m = dto.getMarkupPct();
        if (m != null && (m.compareTo(MIN_MARKUP) < 0 || m.compareTo(MAX_MARKUP) > 0)) {
            throw new BadRequestException("Наценка — от −100 до 1000% — позиция " + row);
        }
        if (!OfferSettingsValidator.isVatRate(dto.getPurchaseVatRate())) {
            throw new BadRequestException("НДС закупки должен быть не меньше 0% и меньше 100% — позиция " + row);
        }
        if (!OfferSettingsValidator.isVatRate(dto.getVatRate())) {
            throw new BadRequestException("Ставка НДС должна быть не меньше 0% и меньше 100% — позиция " + row);
        }
        boolean sameAsStored = stored != null && (stored.getVatRate() == null
                ? dto.getVatRate() == null
                : dto.getVatRate() != null && stored.getVatRate().compareTo(dto.getVatRate()) == 0);
        if (!OfferSettingsValidator.containsRate(profile.getVatRates(), listedVat) && !sameAsStored) {
            throw new BadRequestException("Ставки НДС «" + DocFormat.rate(listedVat) + "» нет в настройках рынка — позиция " + row);
        }
        OfferRegistrationStatus requested = dto.getRegistrationStatus();
        if ((requested == OfferRegistrationStatus.CONFIRMED || requested == OfferRegistrationStatus.SUGGESTED)
                && (stored == null || stored.getRegistrationStatus() != requested)) {
            throw new BadRequestException("Подтвердить регистрацию можно только из реестра — позиция " + row);
        }
    }

    /**
     * Суммы, которые не помещаются в денежные колонки (NUMERIC(15,2)) и в пропись, — 400 до сохранения, а не ошибка базы
     * на total_amount и не пропись, упавшая в документе. Цены не отрицательны, поэтому итог не меньше любой строки;
     * строки проверяются отдельно, чтобы назвать позицию.
     */
    private static void checkAmounts(ClientOffer o, OfferCalculation calc) {
        int itemNo = 0;
        for (int i = 0; i < o.getItems().size(); i++) {
            ClientOfferItem it = o.getItems().get(i);
            if (it.getKind() != ClientOfferItemKind.ITEM) continue;
            itemNo++;
            BigDecimal sum = calc.items().get(i).sum();
            if (sum != null && sum.compareTo(MAX_MONEY) > 0) {
                throw new BadRequestException("Сумма позиции № " + itemNo + quotedName(it.getName()) + " больше "
                        + DocFormat.money(MAX_MONEY) + " — проверьте количество и цену");
            }
        }
        if (calc.totals().sum().compareTo(MAX_MONEY) > 0) {
            throw new BadRequestException("Итог КП больше " + DocFormat.money(MAX_MONEY) + " — проверьте количества и цены");
        }
    }

    private static void syncItems(ClientOffer o, List<ClientOfferItemDto> dtos, List<ClientOfferItem> stored) {
        List<ClientOfferItem> ordered = new ArrayList<>(dtos.size());
        for (int i = 0; i < dtos.size(); i++) {
            ClientOfferItem it = stored.get(i);
            if (it == null) {
                it = ClientOfferItem.builder().offer(o).build();
                o.getItems().add(it);
            }
            applyItem(it, dtos.get(i));
            it.setLineNo(i + 1);
            ordered.add(it);
        }
        Set<ClientOfferItem> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        keep.addAll(ordered);
        o.getItems().removeIf(it -> !keep.contains(it));
        o.getItems().sort(Comparator.comparingInt(ClientOfferItem::getLineNo));
    }

    /** Перенос строки — уже проверенной (checkItems). Связи волны 2 (поставщик, лот, строка ответа, каталог) не трогаются. */
    private static void applyItem(ClientOfferItem it, ClientOfferItemDto dto) {
        ClientOfferItemKind kind = dto.getKind();
        it.setKind(kind);
        it.setName(dto.getName() == null ? "" : dto.getName().trim());
        it.setNote(trim(dto.getNote()));
        it.setClientKey(dto.getKey());
        if (kind != ClientOfferItemKind.ITEM) {
            it.setModel(null);
            it.setManufacturer(null);
            it.setCountry(null);
            it.setUnit("шт");
            it.setQuantity(null);
            it.setPurchasePrice(null);
            it.setPurchaseVatSame(true);
            it.setPurchaseVatRate(null);
            it.setSupplierName(null);
            it.setMarkupPct(null);
            it.setPriceOverride(null);
            it.setVatRate(null);
            it.setRegistrationStatus(OfferRegistrationStatus.UNCHECKED);
            it.setRegistrationText(null);
            it.setRegNumber(null);
            it.setSuggestionScore(null);
            return;
        }
        it.setModel(trim(dto.getModel()));
        it.setManufacturer(trim(dto.getManufacturer()));
        it.setCountry(trim(dto.getCountry()));
        it.setUnit(trim(dto.getUnit()) == null ? "шт" : dto.getUnit().trim());
        it.setQuantity(dto.getQuantity());
        it.setPurchasePrice(dto.getPurchasePrice());
        it.setPurchaseVatSame(dto.getPurchaseVatSame() == null || dto.getPurchaseVatSame());
        it.setPurchaseVatRate(dto.getPurchaseVatRate());
        it.setSupplierName(trim(dto.getSupplierName()));
        it.setMarkupPct(dto.getMarkupPct());
        it.setPriceOverride(dto.getPriceOverride());
        it.setVatRate(dto.getVatRate());
        applyRegistration(it, dto);
    }

    /** UNCHECKED/MANUAL/NOT_REQUIRED задаёт оператор; CONFIRMED/SUGGESTED — только реестр на сервере (волна 2). */
    private static void applyRegistration(ClientOfferItem it, ClientOfferItemDto dto) {
        OfferRegistrationStatus requested = dto.getRegistrationStatus() == null
                ? OfferRegistrationStatus.UNCHECKED : dto.getRegistrationStatus();
        switch (requested) {
            case NOT_REQUIRED -> {
                it.setRegistrationStatus(OfferRegistrationStatus.NOT_REQUIRED);
                it.setRegistrationText(NOT_REQUIRED_TEXT);
                it.setRegNumber(null);
                it.setSuggestionScore(null);
            }
            case CONFIRMED, SUGGESTED -> {
                // тот же статус, что сохранён у строки (checkItem), — реестровое (текст, № РУ, оценка) не трогаем
            }
            case UNCHECKED, MANUAL -> {
                String text = trim(dto.getRegistrationText());
                it.setRegistrationStatus(text == null ? OfferRegistrationStatus.UNCHECKED : OfferRegistrationStatus.MANUAL);
                it.setRegistrationText(text);
                it.setRegNumber(null);
                it.setSuggestionScore(null);
            }
        }
    }

    private Facility facility(Long id) {
        if (id == null) return null;
        return facilities.findById(id)
                .filter(f -> f.getMarket() == MarketContext.get())
                .orElseThrow(() -> new BadRequestException("Клиент не найден: id=" + id));
    }

    private static ClientOfferItem copyItem(ClientOfferItem it, ClientOffer offer) {
        return ClientOfferItem.builder()
                .offer(offer).lineNo(it.getLineNo()).kind(it.getKind()).name(it.getName())
                .model(it.getModel()).manufacturer(it.getManufacturer()).country(it.getCountry()).unit(it.getUnit())
                .quantity(it.getQuantity()).purchasePrice(it.getPurchasePrice()).purchaseVatSame(it.isPurchaseVatSame())
                .purchaseVatRate(it.getPurchaseVatRate()).supplierName(it.getSupplierName())
                .distributorId(it.getDistributorId()).tenderLotId(it.getTenderLotId())
                .priceRequestItemId(it.getPriceRequestItemId()).medEquipmentId(it.getMedEquipmentId())
                .markupPct(it.getMarkupPct()).priceOverride(it.getPriceOverride()).vatRate(it.getVatRate())
                .registrationStatus(it.getRegistrationStatus()).registrationText(it.getRegistrationText())
                .regNumber(it.getRegNumber()).suggestionScore(it.getSuggestionScore()).note(it.getNote())
                .build();
    }

    private static ClientOfferItem sample(ClientOffer o, int lineNo, ClientOfferItemKind kind, String name, String unit,
                                          String qty, String price, BigDecimal vat, OfferRegistrationStatus reg, String regText) {
        return ClientOfferItem.builder()
                .offer(o).lineNo(lineNo).kind(kind).name(name)
                .unit(unit == null ? "шт" : unit)
                .quantity(qty == null ? null : new BigDecimal(qty))
                .priceOverride(price == null ? null : new BigDecimal(price))
                .vatRate(vat)
                .registrationStatus(reg == null ? OfferRegistrationStatus.UNCHECKED : reg)
                .registrationText(regText)
                .build();
    }

    private static List<OfferColumn> copyColumns(List<OfferColumn> src) {
        List<OfferColumn> out = new ArrayList<>();
        if (src != null) for (OfferColumn c : src) out.add(new OfferColumn(c.getKey(), c.getLabel()));
        return out;
    }

    private static List<OfferTerm> copyTerms(List<OfferTerm> src) {
        List<OfferTerm> out = new ArrayList<>();
        if (src != null) for (OfferTerm t : src) out.add(new OfferTerm(t.getLabel() == null ? "" : t.getLabel(), t.getValue() == null ? "" : t.getValue()));
        return out;
    }

    private static List<String> encode(List<byte[]> pages) {
        return pages.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList();
    }

    /** Дата КП — по часам рынка: West-Med в Уральске (UTC+5), Регион-Мед в Самаре (UTC+4). */
    static LocalDate today(Market market) {
        return LocalDate.now(market == Market.KZ ? ZoneId.of("Asia/Oral") : ZoneId.of("Europe/Samara"));
    }

    private static String likePattern(String q) {
        String escaped = q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** До точности колонки — так округлила бы база (HALF_UP, половина — от нуля); null — null. */
    private static BigDecimal scaled(BigDecimal v, int scale) {
        return v == null ? null : v.setScale(scale, RoundingMode.HALF_UP);
    }

    /** Цена: не отрицательна и помещается в NUMERIC(15,2). */
    private static void money(BigDecimal v, String what, String row) {
        if (v == null) return;
        if (v.signum() < 0) throw new BadRequestException(what + " не может быть отрицательной — позиция " + row);
        if (v.compareTo(MAX_MONEY) > 0) {
            throw new BadRequestException(what + " больше " + DocFormat.money(MAX_MONEY) + " — позиция " + row);
        }
    }

    /** « «Пульсоксиметр»» для текста ошибки — коротко и в одну строку; без наименования — пусто. */
    private static String quotedName(String name) {
        String n = trim(name);
        if (n == null) return "";
        n = n.replaceAll("\\s+", " ");
        return " «" + (n.length() > NAME_IN_MESSAGE ? n.substring(0, NAME_IN_MESSAGE).trim() + "…" : n) + "»";
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("КП не найдено: id=" + id);
    }
}
