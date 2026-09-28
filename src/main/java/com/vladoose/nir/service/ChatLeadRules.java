package com.vladoose.nir.service;

import com.vladoose.nir.entity.Chat;
import com.vladoose.nir.entity.Lead;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * «Открытое обращение чата» (спека whatsapp-chats §5.1): того же рынка, по чату ИЛИ по номеру — склейка через
 * каналы (заявка с сайта + WhatsApp = одно обращение); NEW/IN_WORK — всегда, CONVERTED — если активность не
 * старше N дней. Вызывать ДО сдвига chat.lastMessageAt текущим сообщением.
 */
@Service
public class ChatLeadRules {

    private static final Set<LeadStatus> CANDIDATES = EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK, LeadStatus.CONVERTED);

    private final LeadRepository leadRepository;
    private final int convertedDays;

    public ChatLeadRules(LeadRepository leadRepository,
                         @Value("${chats.whatsapp.lead-converted-days:30}") int convertedDays) {
        this.leadRepository = leadRepository;
        this.convertedDays = convertedDays;
    }

    public Optional<Lead> findOpenLead(Chat chat, OffsetDateTime at) {
        Map<Long, Lead> byId = new LinkedHashMap<>();
        if (chat.getId() != null) {
            leadRepository.findByChatIdAndStatusIn(chat.getId(), CANDIDATES).forEach(l -> byId.put(l.getId(), l));
        }
        if (chat.getPhoneNorm() != null) {
            leadRepository.findByPhoneNormAndStatusIn(chat.getPhoneNorm(), CANDIDATES).forEach(l -> byId.put(l.getId(), l));
        }
        OffsetDateTime cutoff = at.minusDays(convertedDays);
        return byId.values().stream()
                .filter(l -> l.getStatus() != LeadStatus.CONVERTED || !activity(l).isBefore(cutoff))
                .max(Comparator.comparing(Lead::getReceivedAt).thenComparing(Lead::getId));
    }

    /** Позднейшее из правки обращения и последнего сообщения его чата. */
    static OffsetDateTime activity(Lead l) {
        OffsetDateTime a = l.getUpdatedAt() != null ? l.getUpdatedAt() : l.getReceivedAt();
        OffsetDateTime chatLast = l.getChat() == null ? null : l.getChat().getLastMessageAt();
        return chatLast != null && chatLast.isAfter(a) ? chatLast : a;
    }
}
