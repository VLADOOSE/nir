package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Запись КП в БД отдельным транзакционным бином — чтобы блокирующая отправка письма
 * происходила ПОСЛЕ коммита (сеть вне транзакции, §6): соединение БД не держится на время SMTP,
 * а запись КП не откатывается уже отправленным письмом.
 */
@Service
public class PriceRequestWriter {

    public record Line(TenderLot lot, MedEquipment equipment, int qty) {}

    private final PriceRequestService priceRequestService;
    private final PriceRequestItemRepository itemRepository;

    public PriceRequestWriter(PriceRequestService priceRequestService,
                              PriceRequestItemRepository itemRepository) {
        this.priceRequestService = priceRequestService;
        this.itemRepository = itemRepository;
    }

    /** Создаёт один PriceRequest (SENT) с позициями и коммитит. Письмо шлётся вызывающим уже после коммита. */
    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines) {
        PriceRequest pr = PriceRequest.builder()
                .tender(tender)
                .distributor(dist)
                .status("SENT")
                .sentAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();
        pr = priceRequestService.save(pr); // штампует market
        for (Line line : lines) {
            PriceRequestItem item = PriceRequestItem.builder()
                    .priceRequest(pr)
                    .tenderLot(line.lot())
                    .medEquipment(line.equipment())
                    .requestedQuantity(line.qty())
                    .build();
            pr.getItems().add(itemRepository.save(item));
        }
        return pr;
    }
}
