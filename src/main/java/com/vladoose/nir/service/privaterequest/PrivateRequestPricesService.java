package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.PrivateRequestPricesResponse;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Cell;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Line;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Supplier;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.rates.CurrencyRates;
import com.vladoose.nir.service.rates.RateMath;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Таблица цен частной заявки (спека §7). Колонки — поставщики в порядке первой отправки. Ячейка — последняя строка
 * запроса этого поставщика по позиции. Состояния по убыванию: цена → отказ → возврат письма → письмо не ушло →
 * нет цены (поставщик ответил, а по позиции цены нет) → ждём ответа (цена, вписанная после отказа, — цена; возврат
 * до «↻ Переслать» забывается); не запрашивали — NONE. У цены — признак 💡 (auto): из разбора, ещё не сверена.
 * Запрос «передам сам» (письма не было) — тоже «ждём ответа», с признаком manual у ячейки и у поставщика. Лучшая — наименьшая цена; при равенстве — более ранний поставщик.
 */
@Service
public class PrivateRequestPricesService {

    private final PrivateRequestService privateRequestService;
    private final PriceRequestItemRepository itemRepository;
    private final InboundEmailRepository inboundRepository;
    private final CurrencyRates rates;

    public PrivateRequestPricesService(PrivateRequestService privateRequestService, PriceRequestItemRepository itemRepository,
                                       InboundEmailRepository inboundRepository, CurrencyRates rates) {
        this.privateRequestService = privateRequestService;
        this.itemRepository = itemRepository;
        this.inboundRepository = inboundRepository;
        this.rates = rates;
    }

    @Transactional(readOnly = true)
    public PrivateRequestPricesResponse build(Long requestId) {
        Tender t = privateRequestService.findById(requestId);
        List<PriceRequestItem> items = new ArrayList<>(itemRepository.findByTenderId(requestId));
        items.sort(Comparator.comparing((PriceRequestItem i) -> i.getPriceRequest().getId()).thenComparing(PriceRequestItem::getId));

        Map<Long, Supplier> suppliers = new LinkedHashMap<>();
        Set<Long> disclosed = new HashSet<>();
        Map<String, PriceRequestItem> latest = new HashMap<>();
        Set<Long> prIds = new LinkedHashSet<>();
        Map<Long, Long> replied = new HashMap<>();
        for (PriceRequestItem i : items) {
            PriceRequest pr = i.getPriceRequest();
            Distributor d = pr.getDistributor();
            suppliers.putIfAbsent(d.getId(), new Supplier(d.getId(), d.getName(), pr.getSentAt(), false,
                    pr.getLetterStatus() == LetterStatus.MANUAL, null));
            if (pr.getResponseDate() != null) replied.put(d.getId(), pr.getId());   // по запросу — последний свежее
            if (pr.isDiscloseClient()) disclosed.add(d.getId());
            latest.put(key(i.getTenderLot().getId(), d.getId()), i);   // отсортировано по запросу — последний свежее
            prIds.add(pr.getId());
        }
        Map<Long, Instant> bounced = lastBounces(inboundRepository, prIds);

        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber()));
        List<Line> lines = new ArrayList<>();
        for (TenderLot lot : lots) {
            List<Cell> cells = new ArrayList<>();
            Cell best = null;
            for (Supplier s : suppliers.values()) {
                Cell c = cell(s.distributorId(), latest.get(key(lot.getId(), s.distributorId())), bounced);
                if (c.currency() != null && c.original() != null) {
                    Cell plain = c;
                    c = c.withToday(rates.today(t.getMarket(), c.currency())
                            .map(td -> RateMath.convert(plain.original(), td.rate())).orElse(null));
                }
                cells.add(c);
                if ("PRICE".equals(c.state()) && (best == null || c.price().compareTo(best.price()) < 0)) best = c;
            }
            final Cell winner = best;
            List<Cell> marked = cells.stream()
                    .map(c -> c == winner ? c.withBest() : c)
                    .toList();
            BigDecimal bestPrice = winner == null ? null : winner.price();
            String bestName = winner == null ? null : suppliers.get(winner.distributorId()).name();
            lines.add(new Line(lot.getId(), lot.getEquipName(), lot.getQuantity(), lot.getUnit(), marked, bestPrice, bestName));
        }
        List<Supplier> header = suppliers.values().stream()
                .map(s -> new Supplier(s.distributorId(), s.name(), s.firstSentAt(), disclosed.contains(s.distributorId()),
                        s.manual(), replied.get(s.distributorId())))
                .toList();
        return new PrivateRequestPricesResponse(header, lines);
    }

    /** Последний возврат по запросу; время возврата неизвестно (старые строки) — считаем «сейчас»: возврат свежий. */
    static Map<Long, Instant> lastBounces(InboundEmailRepository inboundRepository, Collection<Long> prIds) {
        Map<Long, Instant> out = new HashMap<>();
        if (prIds.isEmpty()) return out;
        for (Object[] row : inboundRepository.findLastBounces(prIds)) {
            out.put(((Number) row[0]).longValue(), instant(row[1]));
        }
        return out;
    }

    private static Instant instant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof OffsetDateTime o) return o.toInstant();
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return Instant.now();
    }

    /**
     * Запас на часы: отметку отправки ставим после ответа SMTP, а дата возврата — с часов чужого сервера и с точностью
     * до секунды, поэтому мгновенный возврат бывает «раньше» отметки. Пересылают после исправления адреса — минуты спустя.
     */
    static final Duration BOUNCE_SKEW = Duration.ofMinutes(1);

    /** Возврат — только если он пришёл после последней отправки письма (с запасом на часы): «↻ Переслать» его снимает. */
    static boolean bouncedAfterSend(PriceRequest pr, Map<Long, Instant> bounced) {
        Instant at = bounced.get(pr.getId());
        return at != null && (pr.getSentAt() == null || !at.isBefore(pr.getSentAt().toInstant().minus(BOUNCE_SKEW)));
    }

    static Cell cell(Long distributorId, PriceRequestItem i, Map<Long, Instant> bounced) {
        if (i == null) return new Cell(distributorId, "NONE", null, null, null, null, false, false, false, false, null, null, null, null);
        PriceRequest pr = i.getPriceRequest();
        String state;
        // цена — сильнейший факт: отказ письмом, а потом цена по телефону — это цена (и лучшая, если ниже других)
        if (i.getResponsePrice() != null) state = "PRICE";
        else if ("DECLINED".equals(pr.getStatus())) state = "DECLINED";
        else if (bouncedAfterSend(pr, bounced)) state = "BOUNCED";
        else if (pr.getLetterStatus() == LetterStatus.NO_EMAIL || pr.getLetterStatus() == LetterStatus.FAILED) state = "NOT_SENT";
        // поставщик ответил, а цены по позиции нет (решение плана 9)
        else if (pr.getResponseDate() != null && ("RESPONDED".equals(pr.getStatus()) || "ACCEPTED".equals(pr.getStatus()))) state = "NO_PRICE";
        else state = "WAITING";
        return new Cell(distributorId, state, i.getResponsePrice(), i.getResponseNote(), pr.getId(), i.getId(), false,
                pr.isEmailOverride(), pr.getLetterStatus() == LetterStatus.MANUAL, i.isResponseAuto(),
                i.getResponseCurrency(), i.getResponseOriginal(), i.getResponseRate(), null);
    }

    private static String key(Long lotId, Long distributorId) {
        return lotId + ":" + distributorId;
    }
}
