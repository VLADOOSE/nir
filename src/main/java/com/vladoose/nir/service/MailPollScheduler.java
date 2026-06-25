package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PollResultResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MailPollScheduler {

    private final MailReceiveService mailReceiveService;
    private final boolean enabled;

    public MailPollScheduler(MailReceiveService mailReceiveService,
                             @Value("${mail.imap.enabled:false}") boolean enabled) {
        this.mailReceiveService = mailReceiveService;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${mail.imap.poll-ms:300000}")
    public void tick() {
        if (enabled) {
            run();
        }
    }

    /** Ставит рынок ящика, зовёт @Transactional poll() (отдельный бин → прокси/аспект работают), чистит. */
    public PollResultResponse run() {
        MarketContext.set(mailReceiveService.getMailboxMarket());
        try {
            return mailReceiveService.poll();
        } finally {
            MarketContext.clear();
        }
    }
}
