package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.Line;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.LineRef;
import com.vladoose.nir.dto.response.PrivateRequestSourcingResponse.Supplier;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.util.BrandMatch;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Кто подходит для запроса цен по позициям частной заявки (спека 2026-10-12 §6.1). Дистрибьюторы с брендом строки
 * отмечаются по умолчанию. Если бренда нет или его никто не возит — поставщики того же вида изделия, без отметки.
 * Уже запрошенная у поставщика позиция видна с датой и не отмечается. У бренда с производителем (с почтой) по умолчанию
 * отмечен производитель, дистрибьюторы бренда — без отметки.
 */
@Service
public class PrivateRequestSourcingService {

    private final PrivateRequestService privateRequestService;
    private final DistributorService distributorService;
    private final LotTypeClassifier typeClassifier;
    private final PriceRequestItemRepository itemRepository;

    public PrivateRequestSourcingService(PrivateRequestService privateRequestService, DistributorService distributorService,
                                         LotTypeClassifier typeClassifier, PriceRequestItemRepository itemRepository) {
        this.privateRequestService = privateRequestService;
        this.distributorService = distributorService;
        this.typeClassifier = typeClassifier;
        this.itemRepository = itemRepository;
    }

    @Transactional(readOnly = true)
    public PrivateRequestSourcingResponse build(Long requestId) {
        Tender t = privateRequestService.findById(requestId);
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber()));
        List<Distributor> distributors = distributorService.findAll();   // рынок режет аспект
        Map<String, OffsetDateTime> requestedAt = requestedAt(requestId);

        Map<Long, Acc> bySupplier = new LinkedHashMap<>();
        List<LineRef> unmatched = new ArrayList<>();
        for (TenderLot lot : lots) {
            boolean matched = false;
            // производитель с почтой забирает отметку у дилеров бренда; без почты — нет, иначе позиция осталась бы
            // вовсе без отмеченных поставщиков
            boolean makerWithMail = distributors.stream().anyMatch(d -> d.isManufacturer() && !blank(d.getEmail())
                    && BrandMatch.firstCarried(d.getBrands(), lot.getManufact()) != null);
            for (Distributor d : distributors) {
                String carried = BrandMatch.firstCarried(d.getBrands(), lot.getManufact());
                if (carried == null) continue;
                matched = true;
                if (d.isManufacturer()) acc(bySupplier, d).add(lot, "MANUFACTURER", "производитель", true, requestedAt);
                else acc(bySupplier, d).add(lot, "BRAND", "возит " + carried.trim(), !makerWithMail, requestedAt);
            }
            if (!matched) {
                LotTypeClassifier.TypeGuess guess = typeClassifier.classify(lot);
                if (guess.typeId() != null) {
                    for (Distributor d : distributors) {
                        if (d.getEquipmentTypes().stream().anyMatch(et -> guess.typeId().equals(et.getId()))) {
                            acc(bySupplier, d).add(lot, "TYPE", "по виду: " + guess.typeName(), false, requestedAt);
                            matched = true;
                        }
                    }
                }
            }
            if (!matched) unmatched.add(new LineRef(lot.getId(), lot.getEquipName(), lot.getManufact()));
        }
        List<Supplier> suppliers = bySupplier.values().stream().map(Acc::toSupplier)
                .sorted(Comparator.comparing(Supplier::preselected).reversed()
                        .thenComparing(Supplier::manufacturer, Comparator.reverseOrder())
                        .thenComparing((Supplier s) -> -s.lines().size())
                        .thenComparing(Supplier::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new PrivateRequestSourcingResponse(suppliers, unmatched);
    }

    /** «позиция:поставщик» → когда запрашивали последний раз (у старых запросов без дат — момент чтения). */
    private Map<String, OffsetDateTime> requestedAt(Long requestId) {
        Map<String, OffsetDateTime> out = new HashMap<>();
        OffsetDateTime now = OffsetDateTime.now();
        for (PriceRequestItem i : itemRepository.findByTenderId(requestId)) {
            PriceRequest pr = i.getPriceRequest();
            OffsetDateTime at = pr.getSentAt() != null ? pr.getSentAt()
                    : pr.getCreatedAt() != null ? pr.getCreatedAt() : now;
            out.merge(i.getTenderLot().getId() + ":" + pr.getDistributor().getId(), at,
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    private static Acc acc(Map<Long, Acc> bySupplier, Distributor d) {
        return bySupplier.computeIfAbsent(d.getId(), k -> new Acc(d));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static final class Acc {
        final Distributor d;
        final boolean hasEmail;
        final List<Line> lines = new ArrayList<>();
        final Set<Long> lotIds = new HashSet<>();

        Acc(Distributor d) {
            this.d = d;
            this.hasEmail = d.getEmail() != null && !d.getEmail().isBlank();
        }

        void add(TenderLot lot, String reason, String label, boolean byDefault, Map<String, OffsetDateTime> requestedAt) {
            if (!lotIds.add(lot.getId())) return;
            OffsetDateTime at = requestedAt.get(lot.getId() + ":" + d.getId());
            boolean pre = byDefault && hasEmail && at == null;
            lines.add(new Line(lot.getId(), lot.getEquipName(), lot.getQuantity(), lot.getUnit(), reason, label, pre, at));
        }

        Supplier toSupplier() {
            boolean pre = lines.stream().anyMatch(Line::preselected);
            return new Supplier(d.getId(), d.getName(), hasEmail ? d.getEmail().trim() : null, hasEmail, pre,
                    d.isManufacturer(), d.isTrusted(), List.copyOf(lines));
        }
    }
}
