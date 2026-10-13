package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.EmailChatWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Запись письма ящика info@ (спека 2026-10-13-info-mail-leads §2.2–§2.3) — одна транзакция на письмо вместе со сдвигом
 * курсора его папки. «Входящие»: уведомление сайта — пропуск; письмо собеседника — в переписку (EmailChatWriter);
 * возврат, автоответ, рассылка, служебное и своё — строкой «Входящих» БЕЗ уведомления в Telegram (письма info@ шлёт
 * medsite, §4). «Отправленные»: наше письмо — в переписку с каждым адресатом «Кому» и «Копия», кроме своего домена и
 * служебных адресов. Рынок ставит вызывающий (MarketContext).
 */
@Service
public class ClientMailWriter {

    /** Что стало с письмом. */
    public enum Kind { SKIPPED, INBOUND, CONVERSATION, OUTGOING, DUPLICATE }

    /** Итог записи; createdLeadId — заведено новое обращение. */
    public record Result(Kind kind, Long createdLeadId) {}

    private final InboundEmailRepository inboundRepo;
    private final MailCursorRepository cursorRepo;
    private final EmailChatWriter chats;
    private final ClientMailSettings settings;

    public ClientMailWriter(InboundEmailRepository inboundRepo, MailCursorRepository cursorRepo, EmailChatWriter chats,
                            ClientMailSettings settings) {
        this.inboundRepo = inboundRepo;
        this.cursorRepo = cursorRepo;
        this.chats = chats;
        this.settings = settings;
    }

    @Transactional
    public Result write(ParsedMail m, MailFolder folder, long uidValidity) {
        Result r = folder == MailFolder.SENT ? sent(m, uidValidity) : inbox(m, uidValidity);
        cursorRepo.advance(key(folder), uidValidity, m.uid());
        return r;
    }

    /** Письмо, которое не разобралось или не записалось: короткая строка, курсор папки дальше. */
    @Transactional
    public void writeBroken(BrokenMail b, MailFolder folder, long uidValidity) {
        inboundRepo.save(InboundEmail.builder()
                .mailbox(settings.username()).imapUid(b.uid()).messageId(MailIngestWriter.messageIdOf(b.messageId()))
                .fromAddress(MailText.safeCut(b.from(), 320)).subject(MailText.safeCut(b.subject(), 998))
                .receivedAt(b.receivedAt()).type(InboundType.UNMATCHED)
                .excerpt((folder == MailFolder.SENT ? "Письмо из «Отправленных» не удалось разобрать ("
                        : "Письмо не удалось разобрать (") + b.errorClass() + ") — откройте его в почте")
                .status(InboundStatus.NEW).build());
        cursorRepo.advance(key(folder), uidValidity, b.uid());
    }

    /** Первый запуск папки: курсор — на последнее письмо, снятое до окна. */
    @Transactional
    public void moveCursorTo(MailFolder folder, long uidValidity, long uid) {
        cursorRepo.advance(key(folder), uidValidity, uid);
    }

    private Result inbox(ParsedMail m, long uidValidity) {
        return switch (ClientMailRouter.route(m, settings.rules())) {
            case SITE_NOTIFICATION -> new Result(Kind.SKIPPED, null);   // заявка уже пришла обращением через API сайта
            case CONVERSATION -> {
                EmailChatWriter.Outcome o = chats.writeIncoming(settings.username(), EmailLetter.of(m, MailFolder.INBOX, uidValidity));
                yield new Result(o.duplicate() ? Kind.DUPLICATE : Kind.CONVERSATION, o.createdLeadId());
            }
            case BOUNCE -> inbound(m, InboundType.BOUNCE);
            case DELAYED -> inbound(m, InboundType.DELAYED);
            case AUTO_REPLY -> inbound(m, InboundType.AUTO_REPLY);
            case NEWSLETTER, SERVICE, OWN_DOMAIN -> inbound(m, InboundType.UNMATCHED);
        };
    }

    private Result inbound(ParsedMail m, InboundType type) {
        String messageId = MailIngestWriter.messageIdOf(m.messageId());
        if (messageId != null && inboundRepo.existsByMailboxAndMessageId(settings.username(), messageId)) {
            return new Result(Kind.DUPLICATE, null);
        }
        inboundRepo.save(InboundEmail.builder()
                .mailbox(settings.username()).imapUid(m.uid()).messageId(messageId)
                .fromAddress(MailText.safeCut(m.from(), 320)).subject(MailText.safeCut(m.subject(), 998))
                .receivedAt(m.receivedAt()).type(type).excerpt(MailText.safeCut(m.body(), 2000))
                .status(InboundStatus.NEW).build());   // @PrePersist стампит рынок ящика из MarketContext
        return new Result(Kind.INBOUND, null);
    }

    private Result sent(ParsedMail m, long uidValidity) {
        ClientMailRules rules = settings.rules();
        List<String> to = m.recipients().stream()
                .filter(a -> !ClientMailRouter.isOwnDomain(a, rules) && !ClientMailRouter.isService(a, rules))
                .toList();
        if (to.isEmpty()) return new Result(Kind.SKIPPED, null);   // внутреннее письмо, ответ в поддержку службы
        EmailLetter letter = EmailLetter.of(m, MailFolder.SENT, uidValidity);
        boolean fresh = false;
        for (String address : to) {
            fresh |= !chats.writeOutgoing(settings.username(), address, letter).duplicate();
        }
        return new Result(fresh ? Kind.OUTGOING : Kind.DUPLICATE, null);
    }

    private MailCursorKey key(MailFolder folder) {
        return new MailCursorKey(settings.username(), folder.cursorKey());
    }
}
