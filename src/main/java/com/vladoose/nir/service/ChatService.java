package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «Чаты» (спека whatsapp-chats §8–9): список, переписка, файлы, «Создать обращение», «не клиент».
 * Чат грузится через findById (он ОБХОДИТ рыночный фильтр) — поэтому явный гард рынка, как в LeadService.get.
 * Сообщения и файлы отдаются только через свой чат.
 */
@Service
public class ChatService {

    public static final int LIST_LIMIT = 300;
    public static final int PAGE_MAX = 100;
    public static final int LEAD_MESSAGES = 200;
    private static final Set<LeadStatus> OPEN = EnumSet.of(LeadStatus.NEW, LeadStatus.IN_WORK, LeadStatus.CONVERTED);
    private static final Set<String> FILTERS = Set.of("ALL", "WITH_LEAD", "WITHOUT_LEAD", "GROUPS");
    private static final Set<String> INLINE_IMAGES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final OffsetDateTime EPOCH = OffsetDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final ChatRepository chatRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatAttachmentRepository attachmentRepository;
    private final LeadRepository leadRepository;
    private final ChatLeadRules rules;
    private final LeadIntakeService intake;
    private final PrivateRequestImportService importService;
    private final LeadService leadService;

    public ChatService(ChatRepository chatRepository, ChatMessageRepository messageRepository,
                       ChatAttachmentRepository attachmentRepository, LeadRepository leadRepository,
                       ChatLeadRules rules, LeadIntakeService intake, PrivateRequestImportService importService,
                       LeadService leadService) {
        this.chatRepository = chatRepository;
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.leadRepository = leadRepository;
        this.rules = rules;
        this.intake = intake;
        this.importService = importService;
        this.leadService = leadService;
    }

    /** До LIST_LIMIT свежих чатов; q — по имени, номеру (от 4 цифр) и тексту сообщений. */
    @Transactional(readOnly = true)
    public List<ChatListItemResponse> list(String filter, String q) {
        String f = filter == null || filter.isBlank() ? "ALL" : filter.trim().toUpperCase(Locale.ROOT);
        if (!FILTERS.contains(f)) throw new BadRequestException("Неизвестный фильтр: " + filter);
        List<Chat> chats = chatRepository.findRecent(PageRequest.of(0, LIST_LIMIT));
        Map<Long, Lead> current = currentLeads(chats.stream().map(Chat::getId).toList());
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String digits = needle.replaceAll("\\D", "");
        // без рыночного фильтра — пересечение с чатами рынка ниже делает его безопасным
        Set<Long> bodyHits = needle.length() >= 2 ? new HashSet<>(messageRepository.findChatIdsByBody(needle)) : Set.of();
        return chats.stream()
                .filter(c -> passes(f, c, current.get(c.getId())))
                .filter(c -> needle.isEmpty() || matches(c, needle, digits) || bodyHits.contains(c.getId()))
                .map(c -> toListItem(c, current.get(c.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatResponse get(Long id) {
        return toResponse(chat(id));
    }

    /** Порция: последние limit сообщений, либо перед сообщением beforeId; внутри порции — по времени. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(Long chatId, Long beforeId, int limit) {
        Chat c = chat(chatId);
        PageRequest page = PageRequest.of(0, Math.max(1, Math.min(limit, PAGE_MAX)));
        if (beforeId == null) return toResponses(messageRepository.findLatest(c.getId(), page));
        ChatMessage before = messageRepository.findById(beforeId)
                .filter(m -> m.getChat().getId().equals(c.getId()))
                .orElseThrow(() -> new NotFoundException("Сообщение не найдено: id=" + beforeId));
        return toResponses(messageRepository.findBefore(c.getId(), before.getSentAt(), before.getId(), page));
    }

    /** Переписка чата обращения с момента обращения (спека §9.3); у обращения без чата — пусто. */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messagesForLead(Long leadId) {
        Lead lead = leadService.get(leadId);   // гард рынка — там
        if (lead.getChat() == null) return List.of();
        return toResponses(messageRepository.findSince(lead.getChat().getId(), lead.getReceivedAt(),
                PageRequest.of(0, LEAD_MESSAGES)));
    }

    /** Файл с байтами — только через свой чат текущего рынка. */
    @Transactional(readOnly = true)
    public ChatAttachment attachment(Long chatId, Long attachmentId) {
        Chat c = chat(chatId);
        ChatAttachment a = attachmentRepository.findById(attachmentId)
                .filter(x -> x.getMessage().getChat().getId().equals(c.getId()))
                .orElseThrow(() -> new NotFoundException("Файл не найден: id=" + attachmentId));
        if (a.getContent() == null) throw new NotFoundException("Файл не сохранён в АИС — смотрите его в телефоне");
        return a;
    }

    @Transactional(readOnly = true)
    public ImportPreviewResponse previewAttachment(Long chatId, Long attachmentId) {
        ChatAttachment a = attachment(chatId, attachmentId);
        if (!isExcel(a.getFileName(), a.getMimeType())) {
            throw new BadRequestException("Разобрать в позиции можно только Excel-файл");
        }
        return importService.preview(a.getContent(), a.getFileName());
    }

    /** «Создать обращение» (спека §5.2 п.5): личный чат без открытого обращения; сразу «В работе». */
    @Transactional
    public ChatResponse createLead(Long chatId, String author) {
        Chat c = chat(chatId);
        if (c.isGroup()) throw new BadRequestException("Из группы обращение не создаётся");
        OffsetDateTime now = OffsetDateTime.now();
        if (rules.findOpenLead(c, now).isPresent()) throw new BadRequestException("У чата уже есть открытое обращение");
        // обращение начинается с первого сообщения после КОНЦА прошлого обращения чата (закрыто/последняя правка):
        // от его начала карточка нового снова показала бы старую переписку (ревью 2026-09-28)
        OffsetDateTime after = leadRepository.findByChatIdIn(List.of(c.getId())).stream()
                .map(ChatService::episodeEnd).max(Comparator.naturalOrder()).orElse(EPOCH);
        OffsetDateTime start = messageRepository.findEarliestAfter(c.getId(), after, PageRequest.of(0, 1)).stream()
                .findFirst().map(ChatMessage::getSentAt).orElse(now);
        String lastIncoming = messageRepository.findLatestByDirection(c.getId(), LeadDirection.IN, PageRequest.of(0, 1))
                .stream().findFirst().map(ChatMessage::getBody).orElse(null);
        IncomingLead in = new IncomingLead(LeadSources.WHATSAPP, null, LeadChannel.WHATSAPP, "WhatsApp", start,
                contactName(c), c.getPhoneNorm(), null, null, lastIncoming, List.of(), LeadStatus.IN_WORK, null, author);
        Lead lead = intake.ingest(in).orElseThrow();   // externalId = null → дублей не бывает
        lead.setChat(c);
        return toResponse(c);
    }

    @Transactional
    public ChatResponse setNotClient(Long chatId, boolean value) {
        Chat c = chat(chatId);
        if (c.isGroup()) throw new BadRequestException("Для группы отметка не нужна — из групп обращения не создаются");
        c.setNotClient(value);
        return toResponse(c);
    }

    /** Сообщения (новые → старые) в ответ по времени + метаданные файлов одним запросом. */
    List<ChatMessageResponse> toResponses(List<ChatMessage> newestFirst) {
        List<ChatMessage> chrono = new ArrayList<>(newestFirst);
        Collections.reverse(chrono);
        Map<Long, ChatAttachmentMeta> files = chrono.isEmpty() ? Map.of()
                : attachmentRepository.findMetaByMessageIds(chrono.stream().map(ChatMessage::getId).toList()).stream()
                        .collect(Collectors.toMap(ChatAttachmentMeta::messageId, a -> a));
        return chrono.stream().map(m -> toMessage(m, files.get(m.getId()))).toList();
    }

    Chat chat(Long id) {
        Chat c = chatRepository.findById(id).orElseThrow(() -> new NotFoundException("Чат не найден: id=" + id));
        if (c.getMarket() != null && c.getMarket() != MarketContext.get()) {
            throw new NotFoundException("Чат не найден: id=" + id);
        }
        return c;
    }

    public static boolean isSafeImage(String mime) {
        return safeImageType(mime) != null;
    }

    /** Базовый тип картинки из белого списка — без параметров отправителя (кривой параметр валил отдачу 500-й). */
    public static String safeImageType(String mime) {
        if (mime == null) return null;
        String base = mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
        return INLINE_IMAGES.contains(base) ? base : null;
    }

    private static OffsetDateTime episodeEnd(Lead l) {
        OffsetDateTime u = l.getUpdatedAt();
        return u != null && u.isAfter(l.getReceivedAt()) ? u : l.getReceivedAt();
    }

    public static boolean isExcel(String fileName, String mime) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String t = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        return n.endsWith(".xlsx") || n.endsWith(".xls")
                || t.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || t.equals("application/vnd.ms-excel");
    }

    private ChatResponse toResponse(Chat c) {
        ChatResponse r = new ChatResponse();
        r.setId(c.getId());
        r.setTitle(c.getTitle());
        r.setPhone(c.getPhoneNorm());
        r.setGroup(c.isGroup());
        r.setNotClient(c.isNotClient());
        r.setLastMessageAt(c.getLastMessageAt());
        // открытое обращение может быть найдено по НОМЕРУ (с сайта, ещё не привязано к чату) — показываем его,
        // иначе кнопка «Создать обращение» спрятана, а ссылки нет (ревью 2026-09-28)
        Optional<Lead> open = c.isGroup() ? Optional.empty() : rules.findOpenLead(c, OffsetDateTime.now());
        Lead lead = open.orElseGet(() -> currentLeads(List.of(c.getId())).get(c.getId()));
        r.setLead(lead == null ? null : new ChatLeadRef(lead.getId(), lead.getStatus().name()));
        r.setLeadOpen(open.isPresent());
        return r;
    }

    private static ChatListItemResponse toListItem(Chat c, Lead lead) {
        ChatListItemResponse r = new ChatListItemResponse();
        r.setId(c.getId());
        r.setTitle(c.getTitle());
        r.setPhone(c.getPhoneNorm());
        r.setGroup(c.isGroup());
        r.setNotClient(c.isNotClient());
        r.setLastMessageAt(c.getLastMessageAt());
        r.setLastMessagePreview(c.getLastMessagePreview());
        r.setLead(lead == null ? null : new ChatLeadRef(lead.getId(), lead.getStatus().name()));
        return r;
    }

    private static ChatMessageResponse toMessage(ChatMessage m, ChatAttachmentMeta a) {
        ChatMessageResponse r = new ChatMessageResponse();
        r.setId(m.getId());
        r.setDirection(m.getDirection().name());
        r.setSenderName(m.getSenderName());
        r.setType(m.getType().name());
        r.setBody(m.getBody());
        r.setSentAt(m.getSentAt());
        r.setEdited(m.isEdited());
        r.setDeleted(m.isDeleted());
        if (a != null) {
            ChatAttachmentResponse f = new ChatAttachmentResponse();
            f.setId(a.id());
            f.setFileName(a.fileName());
            f.setMimeType(a.mimeType());
            f.setSizeBytes(a.sizeBytes());
            f.setStored(a.stored());
            f.setNotStoredReason(a.notStoredReason() == null ? null : a.notStoredReason().name());
            f.setExcel(isExcel(a.fileName(), a.mimeType()));
            f.setImage(a.stored() && isSafeImage(a.mimeType()));
            r.setAttachment(f);
        }
        return r;
    }

    /** Текущее обращение чата: открытое (NEW/IN_WORK/CONVERTED), иначе последнее. */
    private Map<Long, Lead> currentLeads(Collection<Long> chatIds) {
        if (chatIds.isEmpty()) return Map.of();
        Comparator<Lead> newest = Comparator.comparing(Lead::getReceivedAt).thenComparing(Lead::getId);
        Map<Long, Lead> out = new HashMap<>();
        for (Lead l : leadRepository.findByChatIdIn(chatIds)) {
            Long chatId = l.getChat().getId();
            Lead cur = out.get(chatId);
            boolean open = OPEN.contains(l.getStatus());
            boolean curOpen = cur != null && OPEN.contains(cur.getStatus());
            if (cur == null || (open && !curOpen) || (open == curOpen && newest.compare(l, cur) > 0)) out.put(chatId, l);
        }
        return out;
    }

    private static boolean passes(String filter, Chat c, Lead lead) {
        return switch (filter) {
            case "WITH_LEAD" -> lead != null;
            case "WITHOUT_LEAD" -> !c.isGroup() && lead == null;
            case "GROUPS" -> c.isGroup();
            default -> true;
        };
    }

    private static boolean matches(Chat c, String needle, String digits) {
        if (c.getTitle() != null && c.getTitle().toLowerCase(Locale.ROOT).contains(needle)) return true;
        return digits.length() >= 4 && c.getPhoneNorm() != null && c.getPhoneNorm().contains(digits);
    }

    /** Имя контакта для обращения — если название чата не просто номер. */
    private static String contactName(Chat c) {
        return c.getTitle() != null && !c.getTitle().equals(c.getPhoneNorm()) ? c.getTitle() : null;
    }
}
