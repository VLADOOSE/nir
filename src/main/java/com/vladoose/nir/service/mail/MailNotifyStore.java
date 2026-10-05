package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.NotifyStatus;
import com.vladoose.nir.repository.InboundEmailRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Очередь уведомлений в строках inbound_email. Отдельный бин с транзакцией на метод: отправка в Telegram идёт ВНЕ
 * транзакции (§6), исход каждой — своей короткой транзакцией. Рыночный фильтр — по MarketContext вызывающего: выборка
 * и счётчик видят строки рынка ящика; отметки идут по id из этой выборки (findById фильтр рынка не применяет).
 */
@Service
public class MailNotifyStore {

    private final InboundEmailRepository repo;

    public MailNotifyStore(InboundEmailRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public List<PendingNotification> pending(int limit) {
        return repo.findPendingNotifications(NotifyStatus.PENDING, PageRequest.of(0, limit));
    }

    @Transactional(readOnly = true)
    public long countPending() {
        return repo.countByNotifyStatus(NotifyStatus.PENDING);
    }

    @Transactional
    public void markSent(long id, OffsetDateTime at) {
        repo.findById(id).ifPresent(e -> {
            e.setNotifyStatus(NotifyStatus.SENT);
            e.setNotifiedAt(at);
            e.setNotifyError(null);
        });
    }

    @Transactional
    public void markAttempt(long id, String error) {
        repo.findById(id).ifPresent(e -> {
            e.setNotifyAttempts(e.getNotifyAttempts() + 1);
            e.setNotifyError(cut(error));
        });
    }

    @Transactional
    public void markFailed(long id, String error) {
        repo.findById(id).ifPresent(e -> {
            e.setNotifyStatus(NotifyStatus.FAILED);
            e.setNotifyError(cut(error));
        });
    }

    /** Срез под длину колонки notify_error (300); эмодзи пополам не режет ({@link MailText#safeCut}). */
    private static String cut(String s) {
        return MailText.safeCut(s, 300);
    }
}
