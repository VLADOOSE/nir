package com.vladoose.nir.service.privaterequest;

import com.vladoose.nir.dto.response.PrivateRequestPricesResponse;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Cell;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Line;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Supplier;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.service.PrivateRequestService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

/**
 * Таблица цен частной заявки (спека §7). Колонки — поставщики в порядке первой отправки. Ячейка — последняя строка
 * запроса этого поставщика по позиции. Состояния по убыванию: цена → отказ → возврат письма → письмо не ушло →
 * ждём ответа (цена, вписанная после отказа, — цена); не запрашивали — NONE. Лучшая — наименьшая цена; при равенстве — более ранний поставщик.
 */
@Service
public class PrivateRequestPricesService {

    private final PrivateRequestService privateRequestService;
    private final PriceRequestItemRepository itemRepository;
    private final InboundEmailRepository inboundRepository;

    public PrivateRequestPricesService(PrivateRequestService privateRequestService, PriceRequestItemRepository itemRepository,
                                       InboundEmailRepository inboundRepository) {
        this.privateRequestService = privateRequestService;
        this.itemRepository = itemRepository;
        this.inboundRepository = inboundRepository;
    }

    @Transactional(readOnly = true)
    public PrivateRequestPricesResponse build(Long requestId) {
        Tender t = privateRequestService.findById(requestId);
        List<PriceRequestItem> items = new ArrayList<>(itemRepository.findByTenderId(requestId));
        items.sort(Comparator.comparing((PriceRequestItem i) -> i.getPriceRequest().getId()).thenComparing(PriceRequestItem::getId));

        Map<Long, Supplier> suppliers = new LinkedHashMap<>();
        Map<String, PriceRequestItem> latest = new HashMap<>();
        Set<Long> prIds = new LinkedHashSet<>();
        for (PriceRequestItem i : items) {
            PriceRequest pr = i.getPriceRequest();
            Distributor d = pr.getDistributor();
            suppliers.putIfAbsent(d.getId(), new Supplier(d.getId(), d.getName(), pr.getSentAt()));
            latest.put(key(i.getTenderLot().getId(), d.getId()), i);   // отсортировано по запросу — последний свежее
            prIds.add(pr.getId());
        }
        Set<Long> bounced = prIds.isEmpty() ? Set.of() : new HashSet<>(inboundRepository.findBouncedPriceRequestIds(prIds));

        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber()));
        List<Line> lines = new ArrayList<>();
        for (TenderLot lot : lots) {
            List<Cell> cells = new ArrayList<>();
            Cell best = null;
            for (Supplier s : suppliers.values()) {
                Cell c = cell(s.distributorId(), latest.get(key(lot.getId(), s.distributorId())), bounced);
                cells.add(c);
                if ("PRICE".equals(c.state()) && (best == null || c.price().compareTo(best.price()) < 0)) best = c;
            }
            final Cell winner = best;
            List<Cell> marked = cells.stream()
                    .map(c -> c == winner ? new Cell(c.distributorId(), c.state(), c.price(), c.note(), c.priceRequestId(),
                            c.itemId(), true) : c)
                    .toList();
            BigDecimal bestPrice = winner == null ? null : winner.price();
            String bestName = winner == null ? null : suppliers.get(winner.distributorId()).name();
            lines.add(new Line(lot.getId(), lot.getEquipName(), lot.getQuantity(), lot.getUnit(), marked, bestPrice, bestName));
        }
        return new PrivateRequestPricesResponse(new ArrayList<>(suppliers.values()), lines);
    }

    static Cell cell(Long distributorId, PriceRequestItem i, Set<Long> bounced) {
        if (i == null) return new Cell(distributorId, "NONE", null, null, null, null, false);
        PriceRequest pr = i.getPriceRequest();
        String state;
        // цена — сильнейший факт: отказ письмом, а потом цена по телефону — это цена (и лучшая, если ниже других)
        if (i.getResponsePrice() != null) state = "PRICE";
        else if ("DECLINED".equals(pr.getStatus())) state = "DECLINED";
        else if (bounced.contains(pr.getId())) state = "BOUNCED";
        else if (pr.getLetterStatus() == LetterStatus.NO_EMAIL || pr.getLetterStatus() == LetterStatus.FAILED) state = "NOT_SENT";
        else state = "WAITING";
        return new Cell(distributorId, state, i.getResponsePrice(), i.getResponseNote(), pr.getId(), i.getId(), false);
    }

    private static String key(Long lotId, Long distributorId) {
        return lotId + ":" + distributorId;
    }
}
