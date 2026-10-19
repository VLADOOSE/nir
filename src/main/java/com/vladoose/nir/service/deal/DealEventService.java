package com.vladoose.nir.service.deal;

import com.vladoose.nir.entity.DealEvent;
import com.vladoose.nir.entity.DealEventType;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.DealEventRepository;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Запись событий сделки (спека 2026-10-18 §5). Гард рынка — у вызывающего (заявка / тендер уже проверены). */
@Service
public class DealEventService {

    public static final int MAX_TEXT = 2000;
    public static final String OFFER_STATUS = "OFFER_STATUS";
    public static final String CLOSED = "CLOSED";
    public static final String REOPENED = "REOPENED";
    public static final String APPLY_STATUS = "APPLY_STATUS";
    public static final String WINNER = "WINNER";

    private final DealEventRepository repository;

    public DealEventService(DealEventRepository repository) {
        this.repository = repository;
    }

    /** Заметка или звонок: текст обязателен, до 2000 символов, без пробелов по краям. */
    @Transactional
    public DealEvent note(Long tenderId, DealEventType type, String text) {
        if (type != DealEventType.NOTE && type != DealEventType.CALL) {
            throw new BadRequestException("Событие — заметка или звонок");
        }
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) throw new BadRequestException("Текст пустой");
        if (t.length() > MAX_TEXT) throw new BadRequestException("Текст длиннее " + MAX_TEXT + " символов");
        return repository.save(DealEvent.builder().tenderId(tenderId).type(type).text(t).author(CurrentUser.name()).build());
    }

    /** КП удалено — его события смены статуса не нужны: ссылка вела бы на «КП не найдено» (решение при планировании 10). */
    @Transactional
    public void forgetOffer(Long tenderId, Long offerId) {
        repository.deleteRefEvents(tenderId, OFFER_STATUS, offerId);
    }

    /** Смена этапа — в транзакции действия: откатилось действие — откатилось и событие. */
    @Transactional
    public DealEvent stage(Long tenderId, String code, String text, Long refId) {
        return repository.save(DealEvent.builder().tenderId(tenderId).type(DealEventType.STAGE).code(code).text(text)
                .refId(refId).author(CurrentUser.name()).build());
    }
}
