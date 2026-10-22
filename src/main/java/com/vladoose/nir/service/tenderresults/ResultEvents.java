package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderResultEventRepository;
import com.vladoose.nir.service.tendernotify.NewTenderNotifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * События итогов (спека 2026-10-22-tender-results-market-prices §7, §12): один раз на вид у лота (у событий цепочки — на попытку),
 * тема по правилу «наш лот → тема нашей продукции, чужой ЗКО и «звонить» → тема «Тендеры», прочее — только «История»». Молчаливое
 * событие (первый проход, итог старше 3 дней) сразу отмечено отправленным. Вызывать внутри транзакции записи.
 */
@Component
public class ResultEvents {

    /** Итог старше — без сообщения: включили после перерыва — старьё не сыплется. */
    static final int STALE_DAYS = 3;

    private final TenderResultEventRepository events;
    private final TenderLotRepository lots;
    private final Set<String> regions;

    @Autowired
    public ResultEvents(TenderResultEventRepository events, TenderLotRepository lots, @Value("${tenders.notify.regions:}") String regions) {
        this.events = events;
        this.lots = lots;
        this.regions = NewTenderNotifier.parseRegions(regions);
    }

    /** Наши лоты (спека §7) — и лоты той же закупки по пункту плана (решение 4: повтор нашего лота — тоже наш). */
    public Set<Long> ours(Collection<TenderLot> list) {
        if (list.isEmpty()) return Set.of();
        Set<Long> ids = new HashSet<>();
        list.forEach(l -> ids.add(l.getId()));
        Set<Long> out = new HashSet<>(lots.findOurLotIds(ids));
        Set<Long> points = new HashSet<>();
        for (TenderLot l : list) if (!out.contains(l.getId()) && l.getPlanPointId() != null) points.add(l.getPlanPointId());
        if (points.isEmpty()) return out;
        List<TenderLot> same = lots.findByPlanPointIdIn(points);
        Set<Long> ourSame = new HashSet<>(lots.findOurLotIds(same.stream().map(TenderLot::getId).toList()));
        Set<Long> ourPoints = new HashSet<>();
        for (TenderLot s : same) if (ourSame.contains(s.getId())) ourPoints.add(s.getPlanPointId());
        for (TenderLot l : list) if (l.getPlanPointId() != null && ourPoints.contains(l.getPlanPointId())) out.add(l.getId());
        return out;
    }

    /** Наша заявка и наш лот — тема нашей продукции; чужой ЗКО — только «звонить», в тему «Тендеры»; прочее — никуда. */
    public static ResultTopic topic(ResultEventKind kind, boolean ours, boolean zko) {
        if (kind == ResultEventKind.OUR_BID || ours) return ResultTopic.PRODUCTS;
        return zko && kind.urgent() ? ResultTopic.TENDERS : ResultTopic.NONE;
    }

    public boolean zko(Tender t) {
        return NewTenderNotifier.regionMatches(regions, t.getRegion());
    }

    public static boolean stale(LocalDateTime occurredAt, LocalDateTime nowLocal) {
        return occurredAt != null && occurredAt.isBefore(nowLocal.minusDays(STALE_DAYS));
    }

    /** false — такое событие уже есть. */
    public boolean add(Tender t, TenderLot lot, ResultEventKind kind, String refKey, LocalDateTime occurredAt, String text, Long refTenderId,
                       boolean ours, boolean silentPass, LocalDateTime nowLocal, OffsetDateTime now) {
        boolean exists = refKey == null ? events.existsByTenderLotIdAndKind(lot.getId(), kind)
                : events.existsByTenderLotIdAndKindAndRefKey(lot.getId(), kind, refKey);
        if (exists) return false;
        ResultTopic topic = topic(kind, ours, zko(t));
        boolean send = topic != ResultTopic.NONE && !silentPass && !stale(occurredAt, nowLocal);
        events.save(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(lot.getId()).kind(kind).refKey(refKey)
                .occurredAt(occurredAt).text(TelegramText.safeCut(text, 500)).urgent(kind.urgent()).topic(topic).refTenderId(refTenderId)
                .createdAt(now)
                .notifiedAt(send && kind.urgent() ? null : now)
                .digestedAt(send && !kind.urgent() && topic == ResultTopic.PRODUCTS ? null : now)
                .build());
        return true;
    }
}
