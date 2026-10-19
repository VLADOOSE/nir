package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ClientMailComposeResponse;
import com.vladoose.nir.dto.response.ClientOfferFileMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.mail.ClientLetter;
import com.vladoose.nir.service.mail.ClientLetterBuilder;
import com.vladoose.nir.service.mail.ClientSmtpSettings;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.EmailAddresses;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.*;
import java.util.stream.Stream;

/**
 * Данные окна письма клиенту (спека 2026-10-19-client-mail-reply §4–§5): ровно одна точка входа — переписка, обращение,
 * заявка или КП. Есть почтовая переписка с адресом — ответ на её последнее письмо; нет — новое письмо на адрес контакта.
 * Заявка выбирает переписку с самым поздним сообщением среди переписки обращения и переписок писем сделки (§5 п. 3).
 */
@Service
public class ClientMailComposeService {

    /** Кому и откуда: переписка (ответ в неё) или адрес (новое письмо); заявка и КП — для «📎 КП» и события сделки. */
    record Target(Chat chat, String address, Long leadId, Long tenderId, ClientOffer offer) {}

    private final ClientSmtpSettings smtp;
    private final ChatService chatService;
    private final ChatRepository chatRepository;
    private final ChatMessageRepository messages;
    private final LeadService leadService;
    private final LeadRepository leadRepository;
    private final PrivateRequestService privateRequests;
    private final ClientOfferService offers;
    private final ClientOfferRepository offerRepository;
    private final ClientOfferFileRepository offerFiles;
    private final DealEventRepository dealEvents;
    private final ReplyTemplateService templates;
    private final UserAccountRepository users;

    public ClientMailComposeService(ClientSmtpSettings smtp, ChatService chatService, ChatRepository chatRepository,
                                    ChatMessageRepository messages, LeadService leadService, LeadRepository leadRepository,
                                    PrivateRequestService privateRequests, ClientOfferService offers,
                                    ClientOfferRepository offerRepository, ClientOfferFileRepository offerFiles,
                                    DealEventRepository dealEvents, ReplyTemplateService templates,
                                    UserAccountRepository users) {
        this.smtp = smtp;
        this.chatService = chatService;
        this.chatRepository = chatRepository;
        this.messages = messages;
        this.leadService = leadService;
        this.leadRepository = leadRepository;
        this.privateRequests = privateRequests;
        this.offers = offers;
        this.offerRepository = offerRepository;
        this.offerFiles = offerFiles;
        this.dealEvents = dealEvents;
        this.templates = templates;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public ClientMailComposeResponse compose(Long chatId, Long leadId, Long tenderId, Long offerId, String username) {
        if (Stream.of(chatId, leadId, tenderId, offerId).filter(Objects::nonNull).count() != 1) {
            throw new BadRequestException("Не указано, кому письмо");
        }
        Market market = MarketContext.get();
        Target t = target(chatId, leadId, tenderId, offerId);
        Chat chat = t.chat();
        ClientMailComposeResponse.Reply reply = chat == null ? null : reply(chat, market.zone());
        boolean first = chat == null
                || messages.findLatestByDirection(chat.getId(), LeadDirection.OUT, PageRequest.of(0, 1)).isEmpty();
        List<String> to = chat != null ? List.of(chat.getExternalChatId()) : t.address() == null ? List.of() : List.of(t.address());
        String problem = smtp.problem(market);
        return new ClientMailComposeResponse(problem == null, problem, smtp.fromName() + " <" + smtp.username() + ">",
                chat == null ? null : chat.getId(), chat == null ? null : chat.getTitle(), to, reply,
                t.offer() == null ? "" : offerSubject(t.offer()), first, templates.templates(market),
                templates.signatureFor(market, fullName(username)), offerRefs(t),
                t.offer() == null ? null : t.offer().getId(), t.leadId(), t.tenderId());
    }

    /** Почтовая переписка ящика клиентов текущего рынка, иначе 404 (чат WhatsApp, чужой ящик, чужой рынок). */
    Chat mailboxChat(Long id) {
        Chat c = chatService.chat(id);
        if (!isMailboxChat(c)) throw new NotFoundException("Почтовая переписка не найдена: id=" + id);
        return c;
    }

    /** Цитата письма переписки: наше письмо — от имени «От» и адреса ящика; имя, равное адресу, не повторяется. */
    ClientLetter.Quote quoteOf(Chat chat, ChatMessage m) {
        boolean in = m.getDirection() == LeadDirection.IN;
        String address = in ? chat.getExternalChatId() : smtp.username();
        String name = in ? m.getSenderName() : smtp.fromName();
        if (name != null && name.equalsIgnoreCase(address)) name = null;
        return new ClientLetter.Quote(m.getSentAt(), name, address, m.getBody());
    }

    /** Первый адрес поля почты нижним регистром; кривой адрес карточки — как пустой. */
    static String firstAddress(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            List<String> all = EmailAddresses.parse(raw);
            return all.isEmpty() ? null : all.get(0).toLowerCase(Locale.ROOT);
        } catch (BadRequestException e) {
            return null;
        }
    }

    private Target target(Long chatId, Long leadId, Long tenderId, Long offerId) {
        if (chatId != null) return new Target(mailboxChat(chatId), null, null, null, null);
        if (leadId != null) {
            Lead lead = leadService.get(leadId);
            if (isMailboxChat(lead.getChat())) return new Target(lead.getChat(), null, lead.getId(), null, null);
            String address = firstAddress(lead.getContactEmail());
            return new Target(chatByAddress(address), address, lead.getId(), null, null);
        }
        if (offerId != null) {
            ClientOffer o = offers.get(offerId);
            if (o.getTenderId() != null) return dealTarget(o.getTenderId(), o);
            String address = o.getFacility() == null ? null : firstAddress(o.getFacility().getEmail());
            return new Target(chatByAddress(address), address, null, null, o);
        }
        return dealTarget(tenderId, null);
    }

    private Target dealTarget(Long tenderId, ClientOffer offer) {
        Tender t = privateRequests.findById(tenderId);
        Lead lead = leadRepository.findFirstByPrivateRequestId(t.getId()).orElse(null);
        List<Chat> candidates = new ArrayList<>();
        if (lead != null && isMailboxChat(lead.getChat())) candidates.add(lead.getChat());
        for (DealEvent e : dealEvents.findByTenderIdAndTypeOrderByOccurredAtAscIdAsc(t.getId(), DealEventType.EMAIL)) {
            if (e.getRefId() != null) chatRepository.findById(e.getRefId()).filter(this::isMailboxChat).ifPresent(candidates::add);
        }
        Chat best = candidates.stream()
                .max(Comparator.comparing(Chat::getLastMessageAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        if (best != null) return new Target(best, null, null, t.getId(), offer);
        String address = Stream.of(firstAddress(t.getContactEmail()), lead == null ? null : firstAddress(lead.getContactEmail()),
                        t.getFacility() == null ? null : firstAddress(t.getFacility().getEmail()))
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new Target(chatByAddress(address), address, null, t.getId(), offer);
    }

    private boolean isMailboxChat(Chat c) {
        return c != null && c.getChannel() == LeadChannel.EMAIL && smtp.username().equalsIgnoreCase(c.getAccount())
                && (c.getMarket() == null || c.getMarket() == MarketContext.get());
    }

    private Chat chatByAddress(String address) {
        if (address == null) return null;
        return chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.EMAIL, smtp.username(), address)
                .filter(this::isMailboxChat).orElse(null);
    }

    private ClientMailComposeResponse.Reply reply(Chat chat, ZoneId zone) {
        List<ChatMessage> last = messages.findLatest(chat.getId(), PageRequest.of(0, 1));
        if (last.isEmpty()) return null;
        ChatMessage m = last.get(0);
        ClientLetter.Quote q = quoteOf(chat, m);
        return new ClientMailComposeResponse.Reply(m.getId(), m.getSubject(),
                ClientLetterBuilder.replySubject(m.getSubject(), m.getSentAt(), zone), m.getSentAt(), q.name(), q.address(),
                m.getDirection().name());
    }

    /** «Коммерческое предложение № 24 от 19.10.2026 – West-Med»: среднее тире (спека §2 п. 4). */
    private String offerSubject(ClientOffer o) {
        return "Коммерческое предложение " + (o.getNumber() != null ? "№ " + o.getNumber() + " " : "")
                + "от " + DocFormat.date(o.getOfferDate()) + " " + DocFormat.DASH + " " + smtp.fromName();
    }

    private List<ClientMailComposeResponse.OfferRef> offerRefs(Target t) {
        List<ClientOffer> list = t.tenderId() != null ? offerRepository.findByTenderIdOrderByIdDesc(t.tenderId())
                : t.offer() != null ? List.of(t.offer()) : List.of();
        Map<Long, String> fileNames = new HashMap<>();
        List<Long> fileIds = list.stream().filter(o -> o.getOrigin() == ClientOfferOrigin.FILE).map(ClientOffer::getId).toList();
        if (!fileIds.isEmpty()) for (ClientOfferFileMeta m : offerFiles.findMetaByOfferIds(fileIds)) fileNames.put(m.offerId(), m.name());
        return list.stream().map(o -> new ClientMailComposeResponse.OfferRef(o.getId(), OfferLabels.of(o), o.getOrigin().name(),
                o.getStatus().name(), o.getOrigin() == ClientOfferOrigin.FILE ? fileNames.get(o.getId())
                        : offers.fileBaseName(o) + ".pdf")).toList();
    }

    private String fullName(String username) {
        if (username == null) return null;
        return users.findByUsername(username).map(UserAccount::getFullName).filter(n -> !n.isBlank()).orElse(null);
    }
}
