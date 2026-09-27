package com.vladoose.nir.service;

import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadEventType;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Транзакционные шаги записи статуса на сайт (§6 CLAUDE.md): прочитать ожидающие — в транзакции,
 * сходить на сайт — ВНЕ её (это делает WestmedLeadSync), записать исход — отдельной транзакцией.
 */
@Service
public class LeadStatusPushWriter {

    public record PendingPush(Long leadId, String externalId, String status) {}

    static final String GONE = "Заявки на сайте больше нет — статус не записан";

    private final LeadRepository leadRepository;

    public LeadStatusPushWriter(LeadRepository leadRepository) {
        this.leadRepository = leadRepository;
    }

    @Transactional(readOnly = true)
    public List<PendingPush> findPending(String source) {
        return leadRepository.findBySourceAndExtStatusPendingIsNotNull(source).stream()
                .map(l -> new PendingPush(l.getId(), l.getExternalId(), l.getExtStatusPending()))
                .toList();
    }

    @Transactional
    public void markPushed(Long leadId, String status) {
        leadRepository.findById(leadId).ifPresent(l -> {
            l.setExtStatus(status);
            // пока шёл PATCH, оператор мог сменить статус ещё раз — тогда новое ожидание остаётся
            if (status.equals(l.getExtStatusPending())) l.setExtStatusPending(null);
            l.setExtSyncError(null);
            l.addEvent(LeadEventType.SYNC, null, "Статус на сайте: «" + LeadSources.siteStatusLabel(status) + "»")
                    .setChannel(LeadChannel.SITE);
        });
    }

    /** Ошибку — только в поле (баннер карточки); в ленту НЕ пишем, иначе каждые 90 с новая строка. */
    @Transactional
    public void markFailed(Long leadId, String error) {
        leadRepository.findById(leadId).ifPresent(l -> l.setExtSyncError(trunc(error, 500)));
    }

    @Transactional
    public void markGone(Long leadId) {
        leadRepository.findById(leadId).ifPresent(l -> {
            l.setExtStatusPending(null);
            l.setExtSyncError(GONE);
            l.addEvent(LeadEventType.SYNC, null, GONE).setChannel(LeadChannel.SITE);
        });
    }
}
