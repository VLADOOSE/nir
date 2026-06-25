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

    public MailReceiveService(PriceRequestRepository priceRequestRepository,
                              InboundEmailRepository inboundEmailRepository,
                              @Value("${mail.imap.enabled:false}") boolean enabled,
                              @Value("${mail.imap.host:localhost}") String host,
                              @Value("${mail.imap.port:3143}") int port,
                              @Value("${mail.imap.username:}") String username,
                              @Value("${mail.imap.password:}") String password,
                              @Value("${mail.imap.protocol:imap}") String protocol,
                              @Value("${mail.imap.market:KZ}") String market) {
        this.priceRequestRepository = priceRequestRepository;
        this.inboundEmailRepository = inboundEmailRepository;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.protocol = protocol;
        this.mailboxMarket = Market.fromHeader(market);
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

            Message[] messages = inbox.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            for (Message msg : messages) {
                handle(msg, result);
                msg.setFlag(Flags.Flag.SEEN, true);
                result.setFetched(result.getFetched() + 1);
            }
            result.setMessage("Обработано писем: " + result.getFetched());
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
        String from = (msg.getFrom() != null && msg.getFrom().length > 0) ? msg.getFrom()[0].toString() : "";
        String subject = msg.getSubject() == null ? "" : msg.getSubject();

        StringBuilder text = new StringBuilder();
        byte[] attachment = null;
        String attachmentName = null;

        Object content = msg.getContent();
        if (content instanceof Multipart mp) {
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart part = mp.getBodyPart(i);
                String fileName = part.getFileName();
                if (attachment == null && fileName != null && (fileName.toLowerCase().endsWith(".xlsx")
                        || fileName.toLowerCase().endsWith(".xls"))) {   // берём ПЕРВОЕ Excel-вложение
                    try (InputStream is = part.getInputStream()) {
                        attachment = is.readAllBytes();
                        attachmentName = fileName;
                    }
                } else if (part.isMimeType("text/plain")) {
                    Object pc = part.getContent();
                    if (pc != null) text.append(pc);
                }
            }
        } else if (content != null) {
            text.append(content);
        }

        Optional<Long> kp = KpToken.parse(subject);
        InboundType type;
        Long matchedId = null;
        if (kp.isPresent()) {
            type = InboundType.SUPPLIER_RESPONSE;
            matchedId = matchSupplierResponse(kp.get(), text.toString());
            result.setSupplierResponses(result.getSupplierResponses() + 1);
        } else if (attachment != null) {
            type = InboundType.CLIENT_REQUEST;
            result.setClientRequests(result.getClientRequests() + 1);
        } else {
            type = InboundType.UNMATCHED;
            result.setUnmatched(result.getUnmatched() + 1);
        }

        InboundEmail ie = InboundEmail.builder()
                .fromAddress(trunc(from, 320))
                .subject(trunc(subject, 998))
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .type(type)
                .matchedPriceRequestId(matchedId)
                .attachmentName(attachmentName)
                .attachment(attachment)
                .excerpt(trunc(text.toString(), 2000))
                .status(InboundStatus.NEW)
                .build();
        inboundEmailRepository.save(ie);  // @PrePersist стампит market из MarketContext
    }

    /** Найти PriceRequest по id из токена и пометить RESPONDED. Возвращает id, если сопоставлено. */
    private Long matchSupplierResponse(Long priceRequestId, String body) {
        Optional<PriceRequest> opt = priceRequestRepository.findById(priceRequestId);
        if (opt.isEmpty()) return null;
        PriceRequest pr = opt.get();
        pr.setStatus("RESPONDED");
        pr.setResponseDate(LocalDate.now());
        pr.setNote(trunc(body, 4000));
        priceRequestRepository.save(pr);
        return priceRequestId;
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
