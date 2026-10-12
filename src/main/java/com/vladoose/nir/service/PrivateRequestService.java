package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.*;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.privaterequest.PrivateRequestRegistry;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Частная заявка — tender с source = PRIVATE_REQUEST (спека 2026-10-12): черновик создаётся сразу, шапка правится по
 * полю, лоты — только через коллекцию t.getLots() (orphanRemoval, CLAUDE.md §7).
 */
@Service
public class PrivateRequestService {

    private static final Pattern NUMBER_SUFFIX = Pattern.compile("^ЧЗ-\\d{4}-(\\d+)$");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final TenderRepository tenderRepository;
    private final FacilityRepository facilityRepository;
    private final PriceRequestItemRepository priceRequestItemRepository;
    private final PriceRequestRepository priceRequestRepository;
    private final ClientOfferRepository clientOfferRepository;
    private final LeadRepository leadRepository;
    private final TenderLotRepository tenderLotRepository;
    private final PrivateRequestRegistry registry;

    public PrivateRequestService(TenderRepository tenderRepository,
                                 FacilityRepository facilityRepository,
                                 PriceRequestItemRepository priceRequestItemRepository,
                                 PriceRequestRepository priceRequestRepository,
                                 ClientOfferRepository clientOfferRepository,
                                 LeadRepository leadRepository,
                                 TenderLotRepository tenderLotRepository,
                                 PrivateRequestRegistry registry) {
        this.tenderRepository = tenderRepository;
        this.facilityRepository = facilityRepository;
        this.priceRequestItemRepository = priceRequestItemRepository;
        this.priceRequestRepository = priceRequestRepository;
        this.clientOfferRepository = clientOfferRepository;
        this.leadRepository = leadRepository;
        this.tenderLotRepository = tenderLotRepository;
        this.registry = registry;
    }

    /** «+ Новая заявка»: пустой черновик — номер, «в работе», рынок (ставит листенер), дата (@PrePersist). */
    @Transactional
    public Tender createEmpty() {
        Tender t = Tender.builder()
                .tenderNumber(nextNumber())
                .status(PrivateRequestStatus.NEW.name())
                .source(Source.PRIVATE_REQUEST)
                .build();
        return tenderRepository.save(t);
    }

    /** Заявка по id — только частная и только текущего рынка: findById = em.find, фильтр рынка его не режет (§14). */
    public Tender findById(Long id) {
        Tender t = tenderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Заявка не найдена: id=" + id));
        if (t.getSource() != Source.PRIVATE_REQUEST
                || (t.getMarket() != null && t.getMarket() != MarketContext.get())) {
            throw new NotFoundException("Заявка не найдена: id=" + id);
        }
        return t;
    }

    /** Одно поле шапки (сохранение по выходу из поля). Пустое значение очищает поле. */
    @Transactional
    public Tender updateField(Long id, PrivateRequestField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле");
        Tender t = findById(id);
        String v = blankToNull(value);
        switch (field) {
            case FACILITY -> {
                if (v == null) {
                    t.setFacility(null);
                } else {
                    t.setFacility(facility(parseId(v)));
                    t.setCustomerName(null);
                }
            }
            case CUSTOMER_NAME -> {
                t.setCustomerName(limit(v, 500, "Клиент"));
                if (v != null) t.setFacility(null);
            }
            case CONTACT_NAME -> t.setContactName(limit(v, 255, "Контактное лицо"));
            case CONTACT_PHONE -> t.setContactPhone(limit(v, 50, "Телефон"));
            case CONTACT_EMAIL -> {
                String email = limit(v, 255, "Почта");
                if (email != null && !EMAIL.matcher(email).matches()) {
                    throw new BadRequestException("Неверный адрес почты: " + email);
                }
                t.setContactEmail(email);
            }
            case DELIVERY_ADDRESS -> t.setDeliveryAddress(limit(v, 2000, "Куда поставка"));
            case NOTE -> t.setDescription(limit(v, 10000, "Примечание"));
        }
        return t;
    }

    /** «Сохранить в Учреждения»: клиент из текста становится учреждением; такое же название этого рынка — привязывается. */
    @Transactional
    public Tender saveClient(Long id) {
        Tender t = findById(id);
        String name = trunc(blankToNull(t.getCustomerName()), 255);
        if (name == null) throw new BadRequestException("Клиент не вписан текстом — сохранять нечего");
        Facility f = facilityRepository.findByName(name).orElse(null);   // HQL: учреждение чужого рынка аспект отрезает
        if (f == null) {
            if (facilityRepository.existsByNameAnyMarket(name)) {
                throw new BadRequestException("Название «" + name + "» уже занято клиентом другого рынка — уточните название");
            }
            f = facilityRepository.save(Facility.builder()
                    .name(name)
                    .phone(trunc(t.getContactPhone(), 50))
                    .email(trunc(t.getContactEmail(), 255))
                    .market(MarketContext.get())
                    .build());
        }
        t.setFacility(f);
        t.setCustomerName(null);
        return t;
    }

    @Transactional
    public Tender close(Long id, PrivateRequestStatus outcome) {
        if (outcome == null || !outcome.closed()) {
            throw new BadRequestException("Укажите исход: купили у нас, не купили или отменена");
        }
        Tender t = findById(id);
        t.setStatus(outcome.name());
        return t;
    }

    @Transactional
    public Tender reopen(Long id) {
        Tender t = findById(id);
        t.setStatus(PrivateRequestStatus.NEW.name());
        return t;
    }

    /** Только заявка без запросов цен и без КП клиенту. Обращение, из которого она создана, возвращается «В работу». */
    @Transactional
    public void delete(Long id, String author) {
        Tender t = findById(id);
        if (!priceRequestRepository.findByTenderId(id).isEmpty()) {
            throw new BadRequestException("Удалить нельзя: по заявке есть запросы цен поставщикам");
        }
        if (clientOfferRepository.existsByTenderId(id)) {
            throw new BadRequestException("Удалить нельзя: по заявке есть КП клиенту");
        }
        leadRepository.findFirstByPrivateRequestId(id).ifPresent(lead -> {
            lead.setPrivateRequest(null);
            if (lead.getStatus() == LeadStatus.CONVERTED) {
                LeadService.transition(lead, LeadStatus.IN_WORK, author, "заявка " + t.getTenderNumber() + " удалена");
            }
        });
        tenderRepository.delete(t);
    }

    public static final int MAX_LINES = 500;

    /** Заявка со строками (импорт Excel, превращение обращения из старого диалога). Клиент необязателен. */
    @Transactional
    public Tender createFromLines(PrivateRequestCreate dto) {
        if (dto.getLines() == null || dto.getLines().isEmpty()) {
            throw new BadRequestException("Нужна хотя бы одна строка");
        }
        Tender t = createEmpty();
        if (dto.getClientFacilityId() != null) t.setFacility(facility(dto.getClientFacilityId()));
        t.setDescription(dto.getNote());
        appendLines(t, dto.getLines().stream()
                .map(l -> input(l.getName(), l.getManufact(), l.getQuantity(), null, null, null)).toList());
        tenderRepository.flush();
        return t;
    }

    /** «Создать заявку» из обращения одной кнопкой (спека §4): клиент, если обращение его узнало, иначе текст компании. */
    @Transactional
    public Tender createFromLead(Lead lead) {
        Tender t = createEmpty();
        Facility f = lead.getFacility();
        if (f != null && (f.getMarket() == null || f.getMarket() == MarketContext.get())) {
            t.setFacility(f);
        } else {
            t.setCustomerName(trunc(blankToNull(lead.getCompany()), 500));
        }
        t.setContactName(trunc(blankToNull(lead.getContactName()), 255));
        t.setContactPhone(trunc(blankToNull(lead.getContactPhone()), 50));
        t.setContactEmail(trunc(blankToNull(lead.getContactEmail()), 255));
        t.setDescription(blankToNull(lead.getMessage()));
        List<PrivateRequestLineInput> inputs = lead.getItems().stream()
                .filter(i -> blankToNull(i.getName()) != null)
                .map(i -> input(i.getName(), i.getBrand(), i.getQuantity(), null, null, httpOrNull(i.getProductUrl())))
                .toList();
        if (!inputs.isEmpty()) appendLines(t, inputs);
        tenderRepository.flush();
        return t;
    }

    /** Ссылка из обращения — только http(s) и не длиннее колонки: иначе позиция без ссылки, а не отказ всей заявки. */
    private static String httpOrNull(String raw) {
        String v = blankToNull(raw);
        return v != null && v.length() <= 500 && (v.startsWith("https://") || v.startsWith("http://")) ? v : null;
    }

    /** Добавить позиции (сайт, свой текст, список, Excel). REPLACE удаляет только позиции без запросов цен. */
    @Transactional
    public Tender addLines(Long id, List<PrivateRequestLineInput> inputs, LinesMode mode) {
        Tender t = findById(id);
        if (inputs == null || inputs.isEmpty()) throw new BadRequestException("Нет позиций для добавления");
        if (mode == LinesMode.REPLACE) {
            Set<Long> requested = requestedLotIds(t.getId());
            t.getLots().removeIf(l -> !requested.contains(l.getId()));
        }
        appendLines(t, inputs);
        tenderRepository.flush();
        return t;
    }

    /**
     * Общий путь добавления: номера в конец, подсказка РУ, сплошная нумерация. Предел MAX_LINES — здесь, а не в addLines:
     * сюда же приходят импорт из «Входящих» (createFromLines) и обращение (createFromLead).
     */
    void appendLines(Tender t, List<PrivateRequestLineInput> inputs) {
        if (t.getLots().size() + inputs.size() > MAX_LINES) {
            throw new BadRequestException("В заявке не больше " + MAX_LINES + " позиций");
        }
        int no = t.getLots().stream().map(TenderLot::getLotNumber).filter(Objects::nonNull)
                .max(Integer::compare).orElse(0);
        for (PrivateRequestLineInput in : inputs) {
            TenderLot lot = TenderLot.builder().tender(t).lotNumber(++no).build();
            applyInput(lot, in);
            t.getLots().add(lot);
            registry.suggest(lot);
        }
        renumber(t);
    }

    /** Одно поле позиции. Смена наименования или бренда пересчитывает подсказку РУ (подтверждение сбрасывается). */
    @Transactional
    public TenderLot updateLineField(Long id, Long lotId, PrivateRequestLineField field, String value) {
        if (field == null) throw new BadRequestException("Не указано поле позиции");
        Tender t = findById(id);
        TenderLot lot = lotOf(t, lotId);
        String v = blankToNull(value);
        switch (field) {
            case NAME -> {
                String name = name(v);
                if (!name.equals(lot.getEquipName())) {
                    lot.setEquipName(name);
                    registry.suggest(lot);
                }
            }
            case BRAND -> {
                String brand = limit(v, 255, "Бренд");
                if (!Objects.equals(brand, lot.getManufact())) {
                    lot.setManufact(brand);
                    registry.suggest(lot);
                }
            }
            case QUANTITY -> lot.setQuantity(quantity(v));
            case UNIT -> lot.setUnit(limit(v, 30, "Ед. изм."));
            case SPEC -> lot.setRequiredSpec(limit(v, 10000, "Характеристики"));
        }
        tenderRepository.flush();
        return lot;
    }

    /** Удалить позицию (через коллекцию — orphanRemoval). С запросами цен — нельзя (внешний ключ). */
    @Transactional
    public Tender deleteLine(Long id, Long lotId) {
        Tender t = findById(id);
        TenderLot lot = lotOf(t, lotId);
        if (priceRequestItemRepository.existsByTenderLotId(lotId)) {
            throw new BadRequestException("Удалить нельзя: по позиции есть запросы цен");
        }
        t.getLots().remove(lot);
        renumber(t);
        tenderRepository.flush();
        return t;
    }

    @Transactional
    public TenderLot lineRegistry(Long id, Long lotId, LineRegistryAction action, String regNumber) {
        if (action == null) throw new BadRequestException("Не указано действие");
        Tender t = findById(id);
        TenderLot lot = lotOf(t, lotId);
        switch (action) {
            case CONFIRM -> registry.confirm(lot, regNumber);
            case NOT_REQUIRED -> registry.notRequired(lot);
            case RESET -> registry.suggest(lot);
        }
        tenderRepository.flush();
        return lot;
    }

    /** Подсказки позициям без статуса (заявки до V26, позиции из старых путей) — один вызов при открытии страницы. */
    @Transactional
    public Tender registryCheck(Long id) {
        Tender t = findById(id);
        for (TenderLot lot : t.getLots()) {
            if (lot.getRegStatus() == null) registry.suggest(lot);
        }
        tenderRepository.flush();
        return t;
    }

    @Transactional(readOnly = true)
    public List<RegistryCandidateResponse> lineCandidates(Long id, Long lotId) {
        return registry.candidates(lotOf(findById(id), lotId));
    }

    /** Новая позиция одной строкой — для импорта, обращений и тестов. */
    public static PrivateRequestLineInput input(String name, String brand, Integer quantity, String unit, String spec,
                                                String productUrl) {
        PrivateRequestLineInput in = new PrivateRequestLineInput();
        in.setName(name);
        in.setBrand(brand);
        in.setQuantity(quantity);
        in.setUnit(unit);
        in.setSpec(spec);
        in.setProductUrl(productUrl);
        return in;
    }

    private Set<Long> requestedLotIds(Long tenderId) {
        Set<Long> ids = new HashSet<>();
        for (PriceRequestItem i : priceRequestItemRepository.findByTenderId(tenderId)) ids.add(i.getTenderLot().getId());
        return ids;
    }

    private static TenderLot lotOf(Tender t, Long lotId) {
        return t.getLots().stream().filter(l -> lotId != null && lotId.equals(l.getId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Позиция не найдена: id=" + lotId));
    }

    /** Сплошная нумерация 1..n в прежнем порядке; у новых позиций id ещё нет — они в конце. */
    private static void renumber(Tender t) {
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
                .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId()));
        int no = 1;
        for (TenderLot l : lots) l.setLotNumber(no++);
    }

    private static void applyInput(TenderLot lot, PrivateRequestLineInput in) {
        lot.setEquipName(name(in.getName()));
        lot.setManufact(trunc(blankToNull(in.getBrand()), 255));
        // пути без @Valid (импорт Excel, обращение) могут прислать 0 или минус — это «1», а не ошибка всего файла
        Integer q = in.getQuantity();
        lot.setQuantity(q == null || q < 1 ? 1 : Math.min(q, 1_000_000));
        lot.setUnit(trunc(blankToNull(in.getUnit()), 30));
        lot.setRequiredSpec(blankToNull(in.getSpec()));
        lot.setProductUrl(url(in.getProductUrl()));
    }

    private static String name(String raw) {
        String v = blankToNull(raw);
        if (v == null) throw new BadRequestException("Наименование позиции пустое");
        return trunc(v.replaceAll("\\s+", " "), 255);
    }

    private static String url(String raw) {
        String v = blankToNull(raw);
        if (v == null) return null;
        if (!(v.startsWith("https://") || v.startsWith("http://")) || v.length() > 500) {
            throw new BadRequestException("Ссылка на товар должна начинаться с http:// или https://");
        }
        return v;
    }

    private static int quantity(String v) {
        if (v == null) throw new BadRequestException("Количество обязательно");
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 1 || n > 1_000_000) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new BadRequestException("Количество — целое число от 1 до 1 000 000");
        }
    }

    public static final int LIST_LIMIT = 300;

    public enum ListFilter { OPEN, CLOSED, ALL }

    /** Список рынка: фильтр статуса, новые сверху, до LIST_LIMIT; поиск — номер, клиент, контакт, телефон, позиции. */
    @Transactional(readOnly = true)
    public List<Tender> list(ListFilter filter, String q) {
        Set<String> statuses = switch (filter == null ? ListFilter.OPEN : filter) {
            case OPEN -> Set.of(PrivateRequestStatus.NEW.name());
            case CLOSED -> Set.of(PrivateRequestStatus.WON.name(), PrivateRequestStatus.LOST.name(),
                    PrivateRequestStatus.CANCELLED.name());
            case ALL -> Set.of(PrivateRequestStatus.NEW.name(), PrivateRequestStatus.WON.name(),
                    PrivateRequestStatus.LOST.name(), PrivateRequestStatus.CANCELLED.name());
        };
        List<Tender> rows = tenderRepository.findPrivate(statuses, PageRequest.of(0, LIST_LIMIT));
        if (q == null || q.isBlank()) return rows;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        String digits = q.replaceAll("\\D", "");
        Set<Long> byLines = new HashSet<>(tenderLotRepository.findPrivateTenderIdsByLineText(likePattern(needle)));
        return rows.stream().filter(t -> byLines.contains(t.getId()) || headerMatches(t, needle, digits)).toList();
    }

    private static boolean headerMatches(Tender t, String needle, String digits) {
        if (contains(t.getTenderNumber(), needle) || contains(t.getCustomerName(), needle)
                || contains(t.getContactName(), needle) || contains(t.getContactEmail(), needle)) {
            return true;
        }
        if (t.getFacility() != null && contains(t.getFacility().getName(), needle)) return true;
        return digits.length() >= 3 && t.getContactPhone() != null
                && t.getContactPhone().replaceAll("\\D", "").contains(digits);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** «%текст%» для LIKE … ESCAPE '\': знаки % и _ из запроса — буквально. */
    private static String likePattern(String needle) {
        return "%" + needle.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** «ЧЗ-<год>-NNNN»: наибольший номер этого года на рынке + 1, а не количество — после удаления дубля не будет. */
    private String nextNumber() {
        int year = LocalDate.now(MarketContext.get().zone()).getYear();
        String prefix = "ЧЗ-" + year + "-";
        int max = 0;
        for (String n : tenderRepository.findPrivateNumbers(prefix + "%")) {
            Matcher m = NUMBER_SUFFIX.matcher(n);
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return String.format("%s%04d", prefix, max + 1);
    }

    private Facility facility(Long facilityId) {
        Facility f = facilityRepository.findById(facilityId)
                .orElseThrow(() -> new NotFoundException("Клиент не найден: id=" + facilityId));
        if (f.getMarket() != null && f.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Клиент не найден: id=" + facilityId);
        }
        return f;
    }

    private static Long parseId(String v) {
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Неверный id клиента: " + v);
        }
    }

    private static String limit(String v, int max, String label) {
        if (v != null && v.length() > max) {
            throw new BadRequestException(label + ": слишком длинно — не больше " + max + " символов");
        }
        return v;
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
