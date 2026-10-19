package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ClientMailSendRequest;
import com.vladoose.nir.dto.response.ClientMailSendResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.UserAccountRepository;
import com.vladoose.nir.service.mail.*;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.util.EmailAddresses;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Письмо клиенту из АИС (спека 2026-10-19-client-mail-reply §3): проверить весь ввод → собрать документы КП и письмо →
 * отправить по SMTP info@ → копия в «Отправленные» → записать (ClientLetterRecorder, одна транзакция). Сбой SMTP — 502 со
 * своим текстом, ничего не записано. Письмо ушло, а запись упала — 200 с предупреждением: повторная отправка задвоила бы
 * письмо клиенту, а приём «Отправленных» допишет переписку сам (§13 п. 1). Повтор того же запроса (token) — 409 (§13 п. 2).
 */
@Service
public class ClientMailSendService {

    private static final Logger log = LoggerFactory.getLogger(ClientMailSendService.class);
    static final int TEXT_MAX = 20_000;
    static final int SUBJECT_MAX = 255;
    static final int MAX_ADDRESSES = 10;
    static final int MAX_FILES = 10;
    static final long LETTER_MAX = 20L * 1024 * 1024;
    static final Duration TOKEN_TTL = Duration.ofMinutes(10);
    public static final String NOT_RECORDED =
            "Письмо ушло, но в АИС не записалось — оно появится в переписке после проверки почты (до минуты)";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** Письмо собрано и проверено: что отправить и что записать. */
    record Prepared(ClientLetter letter, ClientLetterRecorder.Sent sent) {}

    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final ClientSmtpSettings smtp;
    private final ClientMailSender sender;
    private final ClientSentCopy copy;
    private final ClientLetterRecorder recorder;
    private final ClientMailComposeService compose;
    private final ChatMessageRepository messages;
    private final LeadService leads;
    private final PrivateRequestService privateRequests;
    private final ClientOfferService offers;
    private final ReadyOfferService readyOffers;
    private final ReplyTemplateService templates;
    private final UserAccountRepository users;

    public ClientMailSendService(ClientSmtpSettings smtp, ClientMailSender sender, ClientSentCopy copy,
                                 ClientLetterRecorder recorder, ClientMailComposeService compose,
                                 ChatMessageRepository messages, LeadService leads, PrivateRequestService privateRequests,
                                 ClientOfferService offers, ReadyOfferService readyOffers, ReplyTemplateService templates,
                                 UserAccountRepository users) {
        this.smtp = smtp;
        this.sender = sender;
        this.copy = copy;
        this.recorder = recorder;
        this.compose = compose;
        this.messages = messages;
        this.leads = leads;
        this.privateRequests = privateRequests;
        this.offers = offers;
        this.readyOffers = readyOffers;
        this.templates = templates;
        this.users = users;
    }

    public ClientMailSendResponse send(ClientMailSendRequest req, List<MultipartFile> files, String author) {
        Market market = MarketContext.get();
        String problem = smtp.problem(market);
        if (problem != null) throw new BadRequestException(problem);
        Prepared p = prepare(req, files == null ? List.of() : files, market, author);
        String token = req.token() == null || req.token().isBlank() ? null : req.token().strip();
        claim(token);
        MimeMessage mime;
        try {
            mime = ClientLetterBuilder.build(sender.session(), p.letter(), market.zone());
            sender.send(mime);
        } catch (MailException | MessagingException e) {
            release(token);
            log.warn("Письмо клиенту не ушло: {}", e.getClass().getSimpleName());
            throw new UpstreamException(ClientMailSender.failureText(e));
        } catch (RuntimeException e) {
            release(token);
            throw e;
        }
        copy.store(mime);
        try {
            ClientLetterRecorder.Result r = recorder.record(p.sent());
            log.info("Письмо клиенту отправлено: переписок {}, вложений {}", r.chatIds().size(), p.letter().attachments().size());
            return new ClientMailSendResponse(true, r.chatIds(), r.messageIds(), r.offersSent(), null);
        } catch (RuntimeException e) {
            log.warn("Письмо клиенту ушло, но не записалось: {}", e.getClass().getSimpleName());
            return new ClientMailSendResponse(false, List.of(), List.of(), List.of(),
                    p.sent().offerIds().isEmpty() ? NOT_RECORDED : NOT_RECORDED + ". Отметьте КП «Отправлено» вручную");
        }
    }

    /** Весь ввод — до отправки: текст, адреса, тема, цитата, файлы, КП, предел письма. */
    Prepared prepare(ClientMailSendRequest req, List<MultipartFile> files, Market market, String author) {
        String text = req.text() == null ? "" : req.text().strip();
        if (text.isEmpty()) throw new BadRequestException("Напишите текст письма");
        if (text.length() > TEXT_MAX) throw new BadRequestException("Текст длиннее " + TEXT_MAX + " символов");
        if (files.size() > MAX_FILES) throw new BadRequestException("Файлов не больше " + MAX_FILES);
        Long leadId = req.leadId() == null ? null : leads.get(req.leadId()).getId();
        Long tenderId = req.tenderId() == null ? null : privateRequests.findById(req.tenderId()).getId();

        List<String> to;
        String subject;
        ClientLetter.Quote quote = null;
        String inReplyTo = null;
        if (req.chatId() != null) {
            Chat chat = compose.mailboxChat(req.chatId());
            to = List.of(chat.getExternalChatId());
            if (req.replyToMessageId() != null) {
                ChatMessage parent = messages.findById(req.replyToMessageId())
                        .filter(m -> m.getChat().getId().equals(chat.getId()))
                        .orElseThrow(() -> new BadRequestException("Письмо, на которое вы отвечаете, — не из этой переписки"));
                subject = ClientLetterBuilder.replySubject(parent.getSubject(), parent.getSentAt(), market.zone());
                quote = compose.quoteOf(chat, parent);
                if (ClientLetterBuilder.isMessageId(parent.getExternalId())) inReplyTo = parent.getExternalId();
            } else {
                subject = subject(req.subject());
            }
        } else {
            to = EmailAddresses.parse(req.to());
            if (to.isEmpty()) throw new BadRequestException("Укажите адрес получателя");
            if (to.size() > MAX_ADDRESSES) throw new BadRequestException("Адресов не больше " + MAX_ADDRESSES);
            subject = subject(req.subject());
        }

        List<ClientLetter.Attachment> attachments = new ArrayList<>();
        long total = 0;
        for (MultipartFile f : files) {
            ClientLetter.Attachment a = new ClientLetter.Attachment(fileName(f), bytes(f), baseType(f.getContentType()));
            attachments.add(a);
            total += a.bytes().length;
        }
        if (total > LETTER_MAX) throw new BadRequestException("Письмо больше 20 МБ — уберите часть файлов");
        List<Long> offerIds = new ArrayList<>();
        for (ClientMailSendRequest.OfferAttachment a : req.offers() == null ? List.<ClientMailSendRequest.OfferAttachment>of() : req.offers()) {
            ClientOffer o = offers.get(a.id());
            if (tenderId != null && !tenderId.equals(o.getTenderId())) {
                throw new BadRequestException(OfferLabels.of(o) + " — не из этой заявки");
            }
            ClientLetter.Attachment doc = offerDocument(o, a.format());
            attachments.add(doc);
            total += doc.bytes().length;
            if (total > LETTER_MAX) throw new BadRequestException("Письмо больше 20 МБ — уберите часть файлов");
            if (!offerIds.contains(o.getId())) offerIds.add(o.getId());
        }

        String messageId = ClientLetterBuilder.messageId(smtp.username());
        String senderName = fullName(author);
        String signature = templates.signatureFor(market, senderName);
        ClientLetter letter = new ClientLetter(smtp.fromName(), smtp.username(), to, subject, text, signature, quote,
                inReplyTo, messageId, attachments);
        List<MailFile> mailFiles = attachments.stream()
                .map(a -> new MailFile(a.fileName(), a.contentType(), (long) a.bytes().length, a.bytes())).toList();
        EmailLetter stored = new EmailLetter(messageId, subject, ClientLetterBuilder.body(text, signature, null, market.zone()),
                OffsetDateTime.now(), smtp.username(), null, mailFiles);
        return new Prepared(letter, new ClientLetterRecorder.Sent(smtp.username(), to, stored,
                senderName != null ? senderName : author, author, leadId, tenderId, offerIds));
    }

    private ClientLetter.Attachment offerDocument(ClientOffer o, String rawFormat) {
        String format = rawFormat == null ? "" : rawFormat.strip().toUpperCase(Locale.ROOT);
        boolean file = o.getOrigin() == ClientOfferOrigin.FILE;
        switch (format) {
            case "PDF", "DOCX" -> {
                if (file) throw new BadRequestException(OfferLabels.of(o) + " из файла — прикладывается его файл");
                ClientOfferService.OfferFile f = "PDF".equals(format) ? offers.pdf(o.getId()) : offers.docx(o.getId());
                return new ClientLetter.Attachment(f.fileName(), f.bytes(), "PDF".equals(format) ? "application/pdf" : DOCX);
            }
            case "FILE" -> {
                if (!file) throw new BadRequestException(OfferLabels.of(o) + " — из конструктора: PDF или Word");
                ClientOfferFile f = readyOffers.file(o.getId());
                return new ClientLetter.Attachment(f.getFileName(), f.getContent(), baseType(f.getMimeType()));
            }
            default -> throw new BadRequestException("Формат КП — PDF, DOCX или FILE");
        }
    }

    private void claim(String token) {
        if (token == null) return;
        Instant now = Instant.now();
        tokens.entrySet().removeIf(e -> e.getValue().isBefore(now.minus(TOKEN_TTL)));
        if (tokens.putIfAbsent(token, now) != null) {
            throw new ConflictException("Это письмо уже отправлено (или отправляется) — обновите переписку");
        }
    }

    private void release(String token) {
        if (token != null) tokens.remove(token);
    }

    private static String subject(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[\\r\\n\\t]+", " ").strip();
        if (s.isEmpty()) throw new BadRequestException("Укажите тему письма");
        if (s.length() > SUBJECT_MAX) throw new BadRequestException("Тема длиннее " + SUBJECT_MAX + " символов");
        return s;
    }

    static String fileName(MultipartFile f) {
        String n = f.getOriginalFilename() == null ? "" : f.getOriginalFilename();
        n = n.substring(Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\')) + 1).replaceAll("[\\r\\n\\t]+", " ").strip();
        if (n.isEmpty()) return "файл";
        return n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static byte[] bytes(MultipartFile f) {
        try {
            return f.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Файл «" + fileName(f) + "» не прочитался — приложите его снова");
        }
    }

    static String baseType(String contentType) {
        if (contentType == null || contentType.isBlank()) return "application/octet-stream";
        return contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    }

    private String fullName(String username) {
        if (username == null) return null;
        return users.findByUsername(username).map(UserAccount::getFullName).filter(n -> !n.isBlank()).orElse(null);
    }
}
