package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.whatsapp.ChatKind;
import com.vladoose.nir.integration.whatsapp.IncomingFile;
import com.vladoose.nir.integration.whatsapp.ParsedNotification;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.util.PhoneNormalizer;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.util.LeadText.trunc;

/**
 * Запись уведомления WhatsApp — одна транзакция на сообщение (спека whatsapp-chats §5, §6). Сеть (файл, бренды
 * корзины) делает вызывающий ДО транзакции. Дубль проверяется ЯВНО: очередь читает один поток, «гонки вставок»
 * нет, поэтому DataIntegrityViolationException здесь не глотается (урок разбора 2026-09-28).
 */
@Service
public class ChatIngestWriter {

    static final String AUTO_TAKE_NOTE = "ответ клиенту в WhatsApp с телефона";
    /** Тема обращения, начатого входящим звонком (спека whatsapp-waha §8). */
    static final String CALL_SUBJECT = "Звонок в WhatsApp";
    private static final int PREVIEW_MAX = 300;

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final ChatLeadRules rules;
    private final LeadIntakeService intake;
    private final LeadService leadService;

    public ChatIngestWriter(ChatRepository chatRepository, ChatMessageRepository messageRepository,
                            ChatAttachmentRepository attachmentRepository, ChatLeadRules rules,
                            LeadIntakeService intake, LeadService leadService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.rules = rules;
        this.intake = intake;
        this.leadService = leadService;
    }

    /** duplicate — такое сообщение уже записано; createdLeadId — создано новое обращение. */
    public record Outcome(boolean duplicate, Long chatId, Long messageId, Long createdLeadId) {}

    @Transactional
    public Outcome write(ParsedNotification.Message m, IncomingFile file, List<IncomingLead.Item> cartItems) {
        Chat chat = upsertChat(m);
        if (m.isEdit()) {
            Optional<ChatMessage> original = messageRepository.findByChatIdAndExternalId(chat.getId(), m.editOf());
            if (original.isPresent()) {
                ChatMessage edited = original.get();
                // правка без текста (форма, скажем, правки подписи к файлу не проверена) прежний текст не стирает
                if (m.body() != null) edited.setBody(m.body());
                // исход звонка («— принят») дописывается к строке звонка — это не правка текста человеком
                if (m.type() != ChatMessageType.CALL) edited.setEdited(true);
                // превью — текст ПОСЛЕДНЕГО сообщения (спека §6.6): правка последнего его меняет, правка старого — нет
                if (m.body() != null && !m.body().isBlank() && isLatest(chat, edited)) {
                    chat.setLastMessagePreview(trunc(m.body().strip(), PREVIEW_MAX));
                }
                return new Outcome(false, chat.getId(), edited.getId(), null);
            }
            // исходного нет (пришло до подключения) — сохраняем правку как новое сообщение с пометкой
        }
        if (messageRepository.existsByChatIdAndExternalId(chat.getId(), m.idMessage())) {
            return new Outcome(true, chat.getId(), null, null);
        }
        ChatMessage msg = messageRepository.save(ChatMessage.builder()
                .chat(chat).externalId(m.idMessage()).direction(m.direction())
                .senderName(trunc(m.senderName(), 255)).type(m.type()).body(m.body())
                .sentAt(m.sentAt()).edited(m.isEdit() && m.type() != ChatMessageType.CALL).build());
        if (file != null) {
            attachmentRepository.save(ChatAttachment.builder().message(msg)
                    .fileName(trunc(file.fileName(), 255)).mimeType(trunc(file.mimeType(), 100))
                    .sizeBytes(file.content() == null ? null : (long) file.content().length)
                    .content(file.content()).notStoredReason(file.notStoredReason()).build());
        }
        // правила — ДО сдвига lastMessageAt: активность обращения меряется по ПРОШЛЫМ сообщениям (спека §5.1).
        // Правка без оригинала обращения не заводит, КРОМЕ исхода звонка: у событий звонка одна метка времени, и после
        // простоя WAHA досылает их в любом порядке — исход раньше звонка иначе оставлял звонок нового клиента без обращения
        Long createdLeadId = m.isEdit() && m.type() != ChatMessageType.CALL ? null : applyLeadRules(chat, m, cartItems);
        if (chat.getLastMessageAt() == null || !m.sentAt().isBefore(chat.getLastMessageAt())) {
            chat.setLastMessageAt(m.sentAt());
            chat.setLastMessagePreview(trunc(m.displayText().strip(), PREVIEW_MAX));
        }
        return new Outcome(false, chat.getId(), msg.getId(), createdLeadId);
    }

    /** Последнее в порядке ленты (sentAt, id) — тот же порядок, что у экрана «Чаты» и у записи превью. */
    private boolean isLatest(Chat chat, ChatMessage msg) {
        List<ChatMessage> latest = messageRepository.findLatest(chat.getId(), PageRequest.of(0, 1));
        return !latest.isEmpty() && latest.get(0).getId().equals(msg.getId());
    }

    /** «Удалено отправителем»: пометка, текст сохраняется. Неизвестное сообщение — пропускаем. */
    @Transactional
    public void applyDelete(ParsedNotification.Delete d) {
        chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, d.account(), d.chatId())
                .flatMap(c -> messageRepository.findByChatIdAndExternalId(c.getId(), d.deletedId()))
                .ifPresent(msg -> msg.setDeleted(true));
    }

    private Chat upsertChat(ParsedNotification.Message m) {
        Optional<Chat> found = chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, m.account(), m.chatId());
        if (found.isEmpty()) {
            return chatRepository.save(Chat.builder()
                    .market(MarketContext.get())   // пред-штамп (defense-in-depth к листенеру)
                    .channel(LeadChannel.WHATSAPP).account(m.account()).externalChatId(m.chatId())
                    .group(m.kind() == ChatKind.GROUP).phoneNorm(phoneNorm(m.phone()))
                    .title(m.chatName() != null ? trunc(m.chatName().strip(), 255) : m.phone())
                    .build());
        }
        Chat chat = found.get();
        String name = m.chatName();
        // имя — из входящих (несут имя из контактов телефона); из исходящих — только если имени ещё нет
        if (name != null && !name.isBlank() && (m.direction() == LeadDirection.IN || chat.getTitle() == null)
                && !name.strip().equals(chat.getTitle())) {
            chat.setTitle(trunc(name.strip(), 255));
        }
        return chat;
    }

    /** Спека §5.2: группы и «не клиент» — без обращений; открытое — продолжаем; нет — входящее создаёт новое. */
    private Long applyLeadRules(Chat chat, ParsedNotification.Message m, List<IncomingLead.Item> cartItems) {
        if (chat.isGroup() || chat.isNotClient()) return null;
        Optional<Lead> open = rules.findOpenLead(chat, m.sentAt());
        if (open.isPresent()) {
            Lead lead = open.get();
            if (lead.getChat() == null) lead.setChat(chat);
            // «в работу» — только ответ человека с телефона; отправленное через API (бот, автоответ интеграции) — нет
            if (m.direction() == LeadDirection.OUT && !m.viaApi()) leadService.takeAutomatically(lead, AUTO_TAKE_NOTE);
            return null;
        }
        if (m.direction() != LeadDirection.IN) return null;   // написали первыми мы — обращение не создаём
        boolean cart = cartItems != null && !cartItems.isEmpty();
        String subject = cart ? "Запрос КП" : m.type() == ChatMessageType.CALL ? CALL_SUBJECT : "WhatsApp";
        IncomingLead in = new IncomingLead(LeadSources.WHATSAPP, "wa:" + m.idMessage(), LeadChannel.WHATSAPP,
                subject, m.sentAt(), m.chatName(), m.phone(), null, null,
                m.displayText(), cart ? cartItems : List.of(), LeadStatus.NEW, null, null);
        return intake.ingest(in).map(lead -> {
            lead.setChat(chat);
            return lead.getId();
        }).orElse(null);
    }

    /** «+77011234567» → нормализованный +7; иностранный номер — как есть (он уже международный). */
    static String phoneNorm(String phone) {
        if (phone == null) return null;
        String n = PhoneNormalizer.normalize(phone);
        return n != null ? n : trunc(phone, 20);
    }
}
