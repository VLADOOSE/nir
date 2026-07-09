package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.util.KpToken;
import jakarta.mail.*;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.search.FlagTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;

@Service
public class MailReceiveService {

    private static final Logger log = LoggerFactory.getLogger(MailReceiveService.class);

    private final PriceRequestRepository priceRequestRepository;
    private final InboundEmailRepository inboundEmailRepository;
    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String protocol;
    private final Market mailboxMarket;
    private final long sinceMinutes;
    /** Наш адрес отправки КП (spring.mail.username) — письмо с этим From считаем «своим» эхом. */
    private final String sendFrom;

    public MailReceiveService(PriceRequestRepository priceRequestRepository,
                              InboundEmailRepository inboundEmailRepository,
                              @Value("${mail.imap.enabled:false}") boolean enabled,
                              @Value("${mail.imap.host:localhost}") String host,
                              @Value("${mail.imap.port:3143}") int port,
                              @Value("${mail.imap.username:}") String username,
                              @Value("${mail.imap.password:}") String password,
                              @Value("${mail.imap.protocol:imap}") String protocol,
                              @Value("${mail.imap.market:KZ}") String market,
                              @Value("${mail.imap.since-minutes:60}") long sinceMinutes,
                              @Value("${spring.mail.username:}") String sendFrom) {
        this.priceRequestRepository = priceRequestRepository;
        this.inboundEmailRepository = inboundEmailRepository;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
        this.mailboxMarket = Market.fromHeader(market);
        this.sinceMinutes = sinceMinutes;
        this.sendFrom = sendFrom == null ? "" : sendFrom.trim().toLowerCase();
    }

    public Market getMailboxMarket() {
        return mailboxMarket;
    }

    /** Предполагает, что MarketContext уже установлен вызывающим (планировщик/контроллер). */
    @Transactional
    public PollResultResponse poll() {
        PollResultResponse result = new PollResultResponse();
        if (!enabled) {
            result.setEnabled(false);
            result.setMessage("Приём почты выключен (MAIL_IMAP_ENABLED=false)");
            return result;
        }
        result.setEnabled(true);
        Store store = null;
        Folder inbox = null;
        try {
            Properties props = new Properties();
            Session session = Session.getInstance(props);
            store = session.getStore(protocol);
            store.connect(host, port, username, password);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);

            long cutoffMs = System.currentTimeMillis() - sinceMinutes * 60_000L;
            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            int skippedOld = 0;
            for (Message msg : messages) {
                java.util.Date received = msg.getReceivedDate();
                if (received != null && received.getTime() < cutoffMs) {
                    skippedOld++;   // старое непрочитанное письмо — не трогаем (не помечаем SEEN, не ингестим)
                    continue;
                }
                handle(msg, result);
                msg.setFlag(Flags.Flag.SEEN, true);
                result.setFetched(result.getFetched() + 1);
            }
            result.setMessage("Обработано свежих писем (за " + sinceMinutes + " мин): " + result.getFetched()
                    + (skippedOld > 0 ? "; пропущено старых: " + skippedOld : ""));
        } catch (Exception e) {
            log.warn("Ошибка приёма почты: {}", e.getMessage());
            result.setMessage("Ошибка подключения к почте: " + e.getMessage());
        } finally {
            try { if (inbox != null && inbox.isOpen()) inbox.close(false); } catch (Exception ignored) {}
            try { if (store != null) store.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    private void handle(Message msg, PollResultResponse result) throws Exception {
        String from = (msg.getFrom() != null && msg.getFrom().length > 0) ? decode(msg.getFrom()[0].toString()) : "";
        String subject = msg.getSubject() == null ? "" : msg.getSubject();

        Extracted ex = new Extracted();
        collect(msg, ex);
        StringBuilder text = ex.text;
        byte[] attachment = ex.attachment;
        String attachmentName = ex.attachmentName;

        Optional<Long> kp = KpToken.parse(subject);
        boolean ownEcho = !sendFrom.isBlank() && addressPart(from).equalsIgnoreCase(sendFrom);
        InboundType type;
        Long matchedId = null;
        if (kp.isPresent() && !ownEcho) {
            type = InboundType.SUPPLIER_RESPONSE;
            matchedId = matchSupplierResponse(kp.get(), text.toString());
            result.setSupplierResponses(result.getSupplierResponses() + 1);
        } else if (attachment != null && !ownEcho) {
            type = InboundType.CLIENT_REQUEST;
            result.setClientRequests(result.getClientRequests() + 1);
        } else {
            type = InboundType.UNMATCHED;
            result.setUnmatched(result.getUnmatched() + 1);
        }

        InboundEmail ie = InboundEmail.builder()
                .fromAddress(trunc(from, 320))
                .subject(trunc(subject, 998))
                .receivedAt(receivedDate(msg))
                .type(type)
                .matchedPriceRequestId(matchedId)
                .attachmentName(type == InboundType.CLIENT_REQUEST ? attachmentName : null)
                .attachment(type == InboundType.CLIENT_REQUEST ? attachment : null)
                .excerpt(trunc(text.toString(), 2000))
                .status(InboundStatus.NEW)
                .build();
        inboundEmailRepository.save(ie);  // @PrePersist стампит market из MarketContext
    }

    /** Время получения письма: дата с сервера (получено) → дата отправки → сейчас. */
    private static OffsetDateTime receivedDate(Message msg) {
        try {
            java.util.Date d = msg.getReceivedDate();
            if (d == null) d = msg.getSentDate();
            if (d != null) return d.toInstant().atOffset(ZoneOffset.UTC);
        } catch (Exception ignored) {
        }
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    /** Найти PriceRequest по id из токена и пометить RESPONDED. Возвращает id, если сопоставлено. */
    private Long matchSupplierResponse(Long priceRequestId, String body) {
        Optional<PriceRequest> opt = priceRequestRepository.findById(priceRequestId);
        if (opt.isEmpty()) {
            log.info("Ответ с токеном [КП-{}], но КП не найден в активном рынке (другой рынок/удалён)", priceRequestId);
            return null;
        }
        PriceRequest pr = opt.get();
        // Понижаем до RESPONDED только из открытых статусов — не затираем ACCEPTED/REJECTED/CLOSED/уже-RESPONDED
        String st = pr.getStatus();
        if ("CREATED".equals(st) || "SENT".equals(st)) {
            pr.setStatus("RESPONDED");
            pr.setResponseDate(LocalDate.now());
            pr.setNote(trunc(body, 4000));
            priceRequestRepository.save(pr);
        }
        return priceRequestId;
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Адресная часть из "Имя <a@b>" или "a@b" — нижним регистром, без угловых скобок. */
    private static String addressPart(String from) {
        if (from == null) return "";
        String s = from.trim();
        int lt = s.lastIndexOf('<'), gt = s.lastIndexOf('>');
        if (lt >= 0 && gt > lt) s = s.substring(lt + 1, gt);
        return s.trim().toLowerCase();
    }

    /** Рекурсивно обходит части письма (в т.ч. вложенные multipart): собирает text/plain
     *  и ПЕРВОЕ Excel-вложение; имя файла декодируется из MIME (RFC 2047), тип распознаётся
     *  и по расширению, и по Content-Type. */
    private void collect(Part part, Extracted ex) throws Exception {
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                collect(mp.getBodyPart(i), ex);
            }
            return;
        }
        String fileName = decode(part.getFileName());
        boolean excel = (fileName != null
                && (fileName.toLowerCase().endsWith(".xlsx") || fileName.toLowerCase().endsWith(".xls")))
                || part.isMimeType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || part.isMimeType("application/vnd.ms-excel");
        if (ex.attachment == null && excel) {
            try (InputStream is = part.getInputStream()) {
                ex.attachment = is.readAllBytes();
            }
            ex.attachmentName = fileName != null ? fileName : "attachment.xlsx";
        } else if (part.isMimeType("text/plain")) {
            Object c = part.getContent();
            if (c != null) ex.text.append(c);
        }
    }

    /** Декод MIME-encoded-words (RFC 2047) в заголовках (имя файла, From). */
    private static String decode(String s) {
        if (s == null) return null;
        try {
            return jakarta.mail.internet.MimeUtility.decodeText(s);
        } catch (Exception e) {
            return s;
        }
    }

    private static class Extracted {
        final StringBuilder text = new StringBuilder();
        byte[] attachment;
        String attachmentName;
    }
}
