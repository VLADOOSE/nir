package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.util.CurrentUser;
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
        return persist(tender, dist, lines, PriceRequestSendService.SendOptions.NONE);
    }

    /** Запрос помнит свои настройки — «↻ Переслать» повторяет письмо в точности (спека 2026-10-12 §6.5). */
    @Transactional
    public PriceRequest persist(Tender tender, Distributor dist, List<Line> lines, PriceRequestSendService.SendOptions options) {
        PriceRequestSendService.SendOptions o = options == null ? PriceRequestSendService.SendOptions.NONE : options;
        PriceRequest pr = PriceRequest.builder()
                .tender(tender)
                .distributor(dist)
                .status(o.awaitConfirm() ? "CREATED" : "SENT")   // «📱 Отправлю сам» — ещё не отправлен
                .sentAt(o.awaitConfirm() ? null : OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .replyBy(o.replyBy())
                .discloseClient(o.discloseClient())
                .withDocument(o.withDocument())
                .withStamp(o.withStamp())
                .emailOverride(o.emailTo() != null && !o.emailTo().isEmpty())
                .letterStatus(o.manual() ? LetterStatus.MANUAL : null)   // «передам сам» — исход известен сразу
                .createdBy(CurrentUser.label())
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
    /** «📱 Отправлю сам» у запроса, чьё письмо не ушло: тот же запрос — «передам сам», ждёт подтверждения. */
    @Transactional
    public boolean markManualPending(Long priceRequestId) {
        return priceRequestRepository.markManualPending(priceRequestId, LetterStatus.MANUAL,
                List.of(LetterStatus.FAILED, LetterStatus.NO_EMAIL)) == 1;
    }

    /** Передан сам (кнопка или зеркало WhatsApp): только запрос, ждущий подтверждения; одновременные — одно «да». */
    @Transactional
    public boolean confirmManual(Long priceRequestId, OffsetDateTime at) {
        return priceRequestId != null && priceRequestRepository.confirmManual(priceRequestId, at, LetterStatus.MANUAL) == 1;
    }

    /** «✕ Не отправлял»: запрос, ждущий подтверждения, убирается (ничего не ушло); отмеченный переданным — нет. */
    @Transactional
    public boolean deletePendingManual(Long priceRequestId) {
        return priceRequestId != null && priceRequestRepository.deletePendingManual(priceRequestId, LetterStatus.MANUAL) == 1;
    }

    @Transactional
    public void markLetter(Long priceRequestId, LetterStatus status, String sentTo) {
        priceRequestRepository.findById(priceRequestId).ifPresent(pr -> {
            pr.setLetterStatus(status);
            pr.setSentTo(sentTo);
            if (status == LetterStatus.SENT) pr.setSentAt(OffsetDateTime.now());
        });
    }
}
