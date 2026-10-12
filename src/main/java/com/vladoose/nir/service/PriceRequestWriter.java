package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Запись КП в БД отдельным транзакционным бином — чтобы блокирующая отправка письма
 * происходила ПОСЛЕ коммита (сеть вне транзакции, §6): соединение БД не держится на время SMTP,
 * а запись КП не откатывается уже отправленным письмом.
 */
@Service
public class PriceRequestWriter {

    /** qty пусто — у лота нет количества: «кол-во уточняется» (B4). */
    public record Line(TenderLot lot, MedEquipment equipment, Integer qty) {}

    private final PriceRequestService priceRequestService;
    private final PriceRequestItemRepository itemRepository;
    private final PriceRequestRepository priceRequestRepository;

    public PriceRequestWriter(PriceRequestService priceRequestService,
                              PriceRequestItemRepository itemRepository,
                              PriceRequestRepository priceRequestRepository) {
        this.priceRequestService = priceRequestService;
        this.itemRepository = itemRepository;
        this.priceRequestRepository = priceRequestRepository;
    }

    /** Создаёт один PriceRequest (SENT) с позициями и коммитит. Письмо шлётся вызывающим уже после коммита. */
    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines) {
        return persist(tender, dist, lines, null);
    }

    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines, LocalDate replyBy) {
        PriceRequest pr = PriceRequest.builder()
                .tender(tender)
                .distributor(dist)
                .status("SENT")
                .sentAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .replyBy(replyBy)
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

    /**
     * Исход письма — своей короткой транзакцией после отправки (письмо идёт вне транзакции, §6). Ушедшее письмо
     * сдвигает sentAt на время этой отправки: после «↻ Переслать» таблица цен забывает возврат, пришедший раньше.
     */
    @Transactional
    public void markLetter(Long priceRequestId, LetterStatus status, String sentTo) {
        priceRequestRepository.findById(priceRequestId).ifPresent(pr -> {
            pr.setLetterStatus(status);
            pr.setSentTo(sentTo);
            if (status == LetterStatus.SENT) pr.setSentAt(OffsetDateTime.now());
        });
    }
}
