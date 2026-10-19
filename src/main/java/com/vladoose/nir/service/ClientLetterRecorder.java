package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.mail.ClientMailRouter;
import com.vladoose.nir.service.mail.ClientMailRules;
import com.vladoose.nir.service.mail.ClientMailSettings;
import com.vladoose.nir.service.mail.EmailLetter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Запись отправленного письма клиенту (спека 2026-10-19-client-mail-reply §3.3) — одной транзакцией: переписка с каждым
 * адресатом (свой домен и служебные адреса — пропуск, как у приёма «Отправленных»), правила обращения, событие сделки,
 * событие обращения, КП «Черновик» → «Отправлено». Гарды рынка — у вызывающего (ClientMailSendService).
 */
@Service
public class ClientLetterRecorder {

    public static final String TAKE_NOTE = "ответ клиенту письмом из АИС";

    /** Что записать: письмо (ключ — наш Message-ID, текст без цитаты), адресаты, кто отправил, откуда открыли окно, КП. */
    public record Sent(String mailbox, List<String> to, EmailLetter letter, String senderName, String author,
                       Long leadId, Long tenderId, List<Long> offerIds) {}

    public record Result(List<Long> chatIds, List<Long> messageIds, List<Long> offersSent) {}

    private final EmailChatWriter chats;
    private final ClientMailSettings settings;
    private final LeadRepository leads;
    private final DealEventService dealEvents;
    private final ClientOfferService offers;

    public ClientLetterRecorder(EmailChatWriter chats, ClientMailSettings settings, LeadRepository leads,
                                DealEventService dealEvents, ClientOfferService offers) {
        this.chats = chats;
        this.settings = settings;
        this.leads = leads;
        this.dealEvents = dealEvents;
        this.offers = offers;
    }

    @Transactional
    public Result record(Sent s) {
        ClientMailRules rules = settings.rules();
        List<Long> chatIds = new ArrayList<>();
        List<Long> messageIds = new ArrayList<>();
        for (String raw : s.to()) {
            String address = raw.strip().toLowerCase(Locale.ROOT);
            if (ClientMailRouter.isOwnDomain(address, rules) || ClientMailRouter.isService(address, rules)) continue;
            EmailChatWriter.Outcome o = chats.writeOutgoing(s.mailbox(), address, s.letter(),
                    new EmailChatWriter.Outgoing(s.senderName(), TAKE_NOTE, s.author()));
            if (chatIds.contains(o.chatId())) continue;
            chatIds.add(o.chatId());
            if (o.messageId() != null) messageIds.add(o.messageId());
            if (s.tenderId() != null) {
                dealEvents.email(s.tenderId(), o.chatId(), s.letter().subject(), s.letter().sentAt(), s.author());
            }
        }
        if (s.leadId() != null) noteOnLead(s, chatIds);
        List<Long> sent = new ArrayList<>();
        for (Long id : s.offerIds()) {
            if (offers.get(id).getStatus() == ClientOfferStatus.DRAFT) {
                offers.setStatus(id, ClientOfferStatus.SENT, s.letter().sentAt());
                sent.add(id);
            }
        }
        return new Result(chatIds, messageIds, sent);
    }

    /** Письмо из карточки обращения не в его переписку (обращение из WhatsApp) — строкой в ленте обращения (§3.3 п. 4). */
    private void noteOnLead(Sent s, List<Long> chatIds) {
        Lead lead = leads.findById(s.leadId()).orElse(null);
        if (lead == null) return;
        if (lead.getChat() != null && chatIds.contains(lead.getChat().getId())) return;
        String subject = s.letter().subject() == null || s.letter().subject().isBlank() ? "без темы" : s.letter().subject().strip();
        LeadEvent e = lead.addEvent(LeadEventType.MESSAGE, s.author(),
                "Письмо клиенту «" + subject + "» на " + String.join(", ", s.to()));
        e.setDirection(LeadDirection.OUT);
        e.setChannel(LeadChannel.EMAIL);
        e.setOccurredAt(s.letter().sentAt());
    }
}
