package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.service.mail.EmailLetter;
import com.vladoose.nir.service.mail.MailFile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.util.LeadText.*;

/**
 * Письмо ящика info@ в почтовой переписке (спека 2026-10-13-info-mail-leads §2.3–§2.4): переписка — одна на адрес
 * собеседника (channel EMAIL, account — ящик, external_chat_id — адрес нижним регистром), сообщение — письмо (вид
 * EMAIL: тема, текст без цитаты, файлы). Обращения — по правилам чатов (ChatLeadRules): входящее без открытого —
 * новое; наше письмо из «Отправленных», написанное не раньше обращения, переводит «Новое» → «В работе»; «не клиент» —
 * без обращений. Транзакцию держит вызывающий (ClientMailWriter — одна на письмо).
 */
@Service
public class EmailChatWriter {

    /** Событие ленты, когда наше письмо из почты берёт обращение в работу. */
    static final String AUTO_TAKE_NOTE = "ответ клиенту письмом из почты";
    /** Тема обращения из письма без темы. */
    static final String NO_SUBJECT = "Письмо";
    static final int LEAD_TEXT_MAX = 4000;
    private static final int PREVIEW_MAX = 300;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatLeadRules rules;
    private final LeadIntakeService intake;
    private final LeadService leadService;

    public EmailChatWriter(ChatRepository chatRepository, ChatMessageRepository messageRepository,
                           ChatAttachmentRepository attachmentRepository, ChatLeadRules rules,
                           LeadIntakeService intake, LeadService leadService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.rules = rules;
        this.intake = intake;
        this.leadService = leadService;
    }

    /** Кто и как отправил наше письмо (спека 2026-10-19-client-mail-reply §3.3): имя в переписке, текст и автор «В работу». */
    public record Outgoing(String senderName, String takeNote, String author) {}

    /** Наше письмо из «Отправленных» почты: без имени, «ответ клиенту письмом из почты», без автора. */
    static final Outgoing FROM_MAILBOX = new Outgoing(null, AUTO_TAKE_NOTE, null);

    /** duplicate — письмо уже в переписке; createdLeadId — заведено новое обращение. */
    public record Outcome(boolean duplicate, Long chatId, Long messageId, Long createdLeadId) {}

    /** Письмо собеседника из «Входящих» ящика mailbox. */
    @Transactional
    public Outcome writeIncoming(String mailbox, EmailLetter letter) {
        return write(mailbox, letter.fromAddress(), letter.fromName(), LeadDirection.IN, letter, FROM_MAILBOX);
    }

    /** Наше письмо из «Отправленных» — в переписку с адресатом to (адрес нижним регистром). */
    @Transactional
    public Outcome writeOutgoing(String mailbox, String to, EmailLetter letter) {
        return writeOutgoing(mailbox, to, letter, FROM_MAILBOX);
    }

    /** Наше письмо — из «Отправленных» или из АИС (who — отправитель и текст события «В работу»). */
    @Transactional
    public Outcome writeOutgoing(String mailbox, String to, EmailLetter letter, Outgoing who) {
        return write(mailbox, to, null, LeadDirection.OUT, letter, who);
    }

    private Outcome write(String mailbox, String address, String name, LeadDirection dir, EmailLetter letter, Outgoing who) {
        Chat chat = upsertChat(mailbox, address, name);
        if (messageRepository.existsByChatIdAndExternalId(chat.getId(), letter.key())) {
            return new Outcome(true, chat.getId(), null, null);
        }
        ChatMessage msg = messageRepository.save(ChatMessage.builder()
                .chat(chat).externalId(letter.key()).direction(dir)
                .senderName(dir == LeadDirection.IN ? trunc(name != null ? name : address, 255) : trunc(who.senderName(), 255))
                .type(ChatMessageType.EMAIL).subject(blankToNull(letter.subject())).body(blankToNull(letter.body()))
                .sentAt(letter.sentAt()).build());
        for (MailFile f : letter.files()) {
            attachmentRepository.save(ChatAttachment.builder().message(msg)
                    .fileName(trunc(f.name(), 255)).mimeType(trunc(f.mimeType(), 100))
                    .sizeBytes(f.size()).content(f.content())
                    .notStoredReason(f.content() == null ? AttachmentNotStoredReason.TOO_LARGE : null).build());
        }
        // правила — ДО сдвига lastMessageAt: активность обращения меряется по ПРОШЛЫМ письмам (как у WhatsApp)
        Long createdLeadId = applyLeadRules(chat, msg, dir, letter, who);
        if (chat.getLastMessageAt() == null || !letter.sentAt().isBefore(chat.getLastMessageAt())) {
            chat.setLastMessageAt(letter.sentAt());
            chat.setLastMessagePreview(trunc(preview(letter), PREVIEW_MAX));
        }
        return new Outcome(false, chat.getId(), msg.getId(), createdLeadId);
    }

    private Chat upsertChat(String mailbox, String address, String name) {
        Optional<Chat> found = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, mailbox, address);
        if (found.isEmpty()) {
            return chatRepository.save(Chat.builder()
                    .market(MarketContext.get())   // пред-штамп (defense-in-depth к листенеру)
                    .channel(LeadChannel.EMAIL).account(mailbox).externalChatId(address)
                    .title(name != null ? trunc(name, 255) : address)
                    .build());
        }
        Chat chat = found.get();
        // имя — из писем собеседника (наше письмо из «Отправленных» его имени не несёт)
        if (name != null && !name.equals(chat.getTitle())) chat.setTitle(trunc(name, 255));
        return chat;
    }

    private Long applyLeadRules(Chat chat, ChatMessage msg, LeadDirection dir, EmailLetter letter, Outgoing who) {
        if (chat.isNotClient()) return null;
        Optional<Lead> open = rules.findOpenLead(chat, letter.sentAt());
        if (open.isPresent()) {
            Lead lead = open.get();
            if (lead.getChat() == null) lead.setChat(chat);
            // наше письмо, написанное РАНЬШЕ обращения (первый запуск читает «Отправленные» после «Входящих»),
            // работу по нему не означает
            if (dir == LeadDirection.OUT && !letter.sentAt().isBefore(lead.getReceivedAt())) {
                leadService.takeAutomatically(lead, who.takeNote(), who.author());
            }
            return null;
        }
        if (dir != LeadDirection.IN) return null;   // написали первыми мы — обращение не заводим
        String subject = isBlank(letter.subject()) ? NO_SUBJECT : letter.subject().strip();
        IncomingLead in = new IncomingLead(LeadSources.EMAIL, "mail:" + msg.getId(), LeadChannel.EMAIL, subject,
                letter.sentAt(), letter.fromName(), null, letter.fromAddress(), null,
                trunc(letter.body(), LEAD_TEXT_MAX), List.of(), LeadStatus.NEW, null, null);
        return intake.ingest(in).map(lead -> {
            lead.setChat(chat);
            return lead.getId();
        }).orElse(null);
    }

    /** Превью в списке чатов: «Тема — текст», иначе что есть. */
    static String preview(EmailLetter l) {
        String subject = l.subject() == null ? "" : l.subject().strip();
        String body = l.body() == null ? "" : l.body().strip().replaceAll("\\s+", " ");
        if (!subject.isEmpty() && !body.isEmpty()) return subject + " — " + body;
        if (!subject.isEmpty()) return subject;
        if (!body.isEmpty()) return body;
        return l.files().isEmpty() ? NO_SUBJECT : "📎 " + l.files().get(0).name();
    }
}
