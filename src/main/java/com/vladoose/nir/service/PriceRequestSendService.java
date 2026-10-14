package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.util.EmailAddresses;
import com.vladoose.nir.util.KpToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Единый канал отправки запросов КП: на каждого поставщика создаётся PriceRequest (SENT) с позициями (лот + опционально
 * модель) и уходит письмо (KpEmailComposer + EmailService), по отметке — с запросом на бланке (Word, RfqDocumentService).
 * Запись КП — в отдельном @Transactional-бине ({@link PriceRequestWriter}); письмо шлётся ПОСЛЕ коммита (сеть вне
 * транзакции, §6). Ошибка/отсутствие email НЕ валит запись — отражается флагом в SendResult. Запрос помнит свои
 * настройки, и «↻ Переслать» повторяет письмо в точности (спека 2026-10-12 §6.5).
 */
@Service
public class PriceRequestSendService {

    private static final Logger log = LoggerFactory.getLogger(PriceRequestSendService.class);

    public static final String REASON_NO_EMAIL = "NO_EMAIL";
    public static final String REASON_SEND_FAILED = "SEND_FAILED";
    /** «Передам сам»: письма не было и не будет — запрос записан, документ пересылают руками. */
    public static final String REASON_MANUAL = "MANUAL";

    public record SendItem(Long tenderLotId, Long medEquipmentId, Integer requestedQuantity) {}
    public record SendResult(Long priceRequestId, Long distributorId, String distributorName,
                             boolean emailSent, String reason) {}

    /**
     * Настройки письма запроса цен (спека 2026-10-12 §6): «Ответить до»; запрос на бланке (Word) и подпись с печатью в
     * нём (без документа печати нет); клиент указан поставщику; адрес. emailTo: null — почта из карточки поставщика;
     * пустой список — письма нет (поле почты в окне очищено); иначе — вписанные адреса (у запроса email_override).
     * manual — «передам сам»: письма нет вовсе, запрос записывается «передан вручную», а его документ (PDF / Word)
     * пересылают руками — документ здесь и есть то, что уходит поставщику, поэтому он и печать по отметке есть всегда.
     */
    public record SendOptions(LocalDate replyBy, boolean withDocument, boolean withStamp, boolean discloseClient,
                              List<String> emailTo, boolean manual) {

        public static final SendOptions NONE = new SendOptions(null, false, false, false, null);

        public SendOptions {
            withDocument = withDocument || manual;
            withStamp = withDocument && withStamp;
            emailTo = manual || emailTo == null ? null : List.copyOf(emailTo);
        }

        public SendOptions(LocalDate replyBy, boolean withDocument, boolean withStamp, boolean discloseClient,
                           List<String> emailTo) {
            this(replyBy, withDocument, withStamp, discloseClient, emailTo, false);
        }

        public static SendOptions replyBy(LocalDate replyBy) {
            return new SendOptions(replyBy, false, false, false, null);
        }
    }

    private final TenderService tenderService;
    private final TenderLotService tenderLotService;
    private final MedEquipmentService medEquipmentService;
    private final DistributorService distributorService;
    private final PriceRequestWriter writer;
    private final KpEmailComposer composer;
    private final EmailService emailService;
    private final PriceRequestRepository priceRequestRepository;
    private final CompanyProfileService profiles;
    private final RfqDocumentService documents;

    public PriceRequestSendService(TenderService tenderService,
                                   TenderLotService tenderLotService,
                                   MedEquipmentService medEquipmentService,
                                   DistributorService distributorService,
                                   PriceRequestWriter writer,
                                   KpEmailComposer composer,
                                   EmailService emailService,
                                   PriceRequestRepository priceRequestRepository,
                                   CompanyProfileService profiles,
                                   RfqDocumentService documents) {
        this.tenderService = tenderService;
        this.tenderLotService = tenderLotService;
        this.medEquipmentService = medEquipmentService;
        this.distributorService = distributorService;
        this.writer = writer;
        this.composer = composer;
        this.emailService = emailService;
        this.priceRequestRepository = priceRequestRepository;
        this.profiles = profiles;
        this.documents = documents;
    }

    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items,
                                 String subjectOverride, String bodyOverride) {
        return send(tenderId, distributorIds, items, subjectOverride, bodyOverride, SendOptions.NONE);
    }

    public List<SendResult> send(Long tenderId, List<Long> distributorIds, List<SendItem> items,
                                 String subjectOverride, String bodyOverride, SendOptions options) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        SendOptions o = options == null ? SendOptions.NONE : options;

        // findById == em.find обходит hibernate-фильтр рынка → явные гарды от чужого рынка (как в setProposedEquipment)
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);
        checkReplyBy(o.replyBy());
        if (o.withStamp()) requireStamp(market(tender));
        List<PriceRequestWriter.Line> lines = lines(tenderId, items);

        List<SendResult> results = new ArrayList<>();
        for (Long distId : new LinkedHashSet<>(distributorIds)) { // дедуп: один поставщик — одно КП/письмо
            Distributor dist = distributorService.findById(distId);
            requireCurrentMarket(dist.getMarket(), "Поставщик не найден: " + distId);
            PriceRequest pr = writer.persist(tender, dist, lines, o); // коммит записи
            if (o.manual()) {   // «передам сам»: письма нет, запрос уже записан «передан вручную»
                results.add(new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_MANUAL));
                continue;
            }
            results.add(dispatch(pr, dist, subjectOverride, bodyOverride, o.emailTo())); // письмо ПОСЛЕ коммита
        }
        return results;
    }

    /**
     * Переслать письмо существующего КП с его настройками (спека §6.5). Вписанная почта — туда же (она в sent_to); иначе —
     * на почту из карточки: исправили адрес после возврата — письмо пойдёт на новый. Новый PR не создаётся.
     */
    public SendResult resend(Long priceRequestId) {
        PriceRequest pr = priceRequestRepository.findById(priceRequestId)
                .orElseThrow(() -> new NotFoundException("КП не найден: " + priceRequestId));
        requireCurrentMarket(pr.getMarket(), "КП не найден: " + priceRequestId);
        // «передам сам»: письма не было — пересылка ушла бы на почту карточки и стёрла бы отметку «передан вручную»
        if (pr.getLetterStatus() == LetterStatus.MANUAL) {
            throw new BadRequestException("Запрос передан вручную — письмом его не пересылают: запросите цены заново");
        }
        List<String> to = pr.isEmailOverride() ? EmailAddresses.parse(pr.getSentTo()) : null;
        return dispatch(pr, pr.getDistributor(), null, null, to);
    }

    /**
     * Документ записанного запроса — Word или PDF, с его номером и настройками (печать, клиент, «Ответить до»): тот же,
     * что ушёл бы во вложении письма; у запроса «передам сам» его пересылают руками (WhatsApp). Чужой рынок — 404.
     */
    public RfqDocumentService.RfqFile document(Long priceRequestId, RfqDocumentService.Format format) {
        PriceRequest pr = priceRequestRepository.findById(priceRequestId)
                .orElseThrow(() -> new NotFoundException("КП не найден: " + priceRequestId));
        requireCurrentMarket(pr.getMarket(), "КП не найден: " + priceRequestId);
        return documents.render(pr, format);
    }

    /** «Печать» без загруженной печати — 400 до первой записи (спека §6.2: галочка недоступна, пока печати нет). */
    public void requireStamp(Market market) {
        if (profiles.forMarket(market).getStampPng() == null) {
            throw new BadRequestException("Печать не загружена — загрузите её в «Реквизитах и печати» или снимите «Печать»");
        }
    }

    private void requireCurrentMarket(Market market, String notFoundMessage) {
        if (market != null && market != MarketContext.get()) {
            throw new NotFoundException(notFoundMessage);
        }
    }

    private static Market market(Tender tender) {
        return tender.getMarket() != null ? tender.getMarket() : MarketContext.get();
    }

    private static void checkReplyBy(LocalDate replyBy) {
        if (replyBy != null && replyBy.isBefore(LocalDate.now(MarketContext.get().zone()))) {
            throw new BadRequestException("Дата «Ответить до» уже прошла");
        }
    }

    /** Позиции запроса: лот этого тендера, модель своего рынка, количество — пусто или не меньше 1. */
    private List<PriceRequestWriter.Line> lines(Long tenderId, List<SendItem> items) {
        if (items == null || items.isEmpty()) throw new BadRequestException("Не выбраны позиции");
        List<PriceRequestWriter.Line> lines = new ArrayList<>();
        for (SendItem si : items) {
            if (si.tenderLotId() == null) throw new BadRequestException("Не указан лот в позиции");
            // пусто — у лота нет количества (СК-Фармация, «приказ ЕД»): поставщику «кол-во уточняется», а не «1 шт.» (B4)
            if (si.requestedQuantity() != null && si.requestedQuantity() < 1) {
                throw new BadRequestException("Количество в позиции должно быть не меньше 1");
            }
            TenderLot lot = tenderLotService.findById(si.tenderLotId());
            if (!lot.getTender().getId().equals(tenderId)) {
                throw new BadRequestException("Лот " + si.tenderLotId() + " не принадлежит тендеру " + tenderId);
            }
            MedEquipment eq = null;
            if (si.medEquipmentId() != null) {
                eq = medEquipmentService.findById(si.medEquipmentId());
                requireCurrentMarket(eq.getMarket(), "Оборудование не найдено: " + si.medEquipmentId());
            }
            lines.add(new PriceRequestWriter.Line(lot, eq, si.requestedQuantity()));
        }
        return lines;
    }

    private SendResult dispatch(PriceRequest pr, Distributor dist, String subjectOverride, String bodyOverride,
                                List<String> emailTo) {
        List<String> to;
        try {
            to = emailTo != null ? emailTo : EmailAddresses.parse(dist.getEmail());
        } catch (BadRequestException e) {
            // в карточке адрес, который не разобрать (старые данные): письма нет, запрос сохранён — «письмо не ушло»
            log.warn("КП id={}: почту в карточке поставщика «{}» не разобрать — письмо не отправлено", pr.getId(), dist.getName());
            letter(pr, LetterStatus.FAILED, null);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
        if (to.isEmpty()) {
            log.warn("КП id={} создан, но у поставщика «{}» нет почты — письмо не отправлено", pr.getId(), dist.getName());
            letter(pr, LetterStatus.NO_EMAIL, null);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_NO_EMAIL);
        }
        String address = String.join(", ", to);
        try {
            KpEmailComposer.Composed def = composer.compose(pr);
            // токен [КП-id] ВСЕГДА серверный: при override человеческой части клеим его отдельно
            String subject = (subjectOverride != null && !subjectOverride.isBlank())
                    ? KpToken.subjectToken(pr.getId()) + " " + subjectOverride.trim()
                    : def.subject();
            String body = (bodyOverride != null && !bodyOverride.isBlank()) ? bodyOverride : def.body();
            List<EmailService.Attachment> files = new ArrayList<>();
            if (pr.isWithDocument()) {
                RfqDocumentService.RfqFile doc = documents.render(pr);
                files.add(new EmailService.Attachment(doc.fileName(), doc.bytes(), RfqDocumentService.DOCX));
            }
            emailService.sendEmail(to, subject, body, files);
            letter(pr, LetterStatus.SENT, address);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), true, null);
        } catch (Exception ex) {
            // адрес — персональные данные: в лог только домены (спека §10); текст исключения SMTP может нести адрес
            log.warn("Не удалось отправить КП id={} ({}): {}. Запрос сохранён в БД.", pr.getId(), EmailAddresses.domains(to),
                    ex.getClass().getSimpleName());
            // адрес попытки остаётся в sent_to: «↻ Переслать» вписанной почты после сбоя знает, куда слать
            letter(pr, LetterStatus.FAILED, address);
            return new SendResult(pr.getId(), dist.getId(), dist.getName(), false, REASON_SEND_FAILED);
        }
    }

    public KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items) {
        return preview(tenderId, distributorIds, items, SendOptions.NONE);
    }

    /** Черновой предпросмотр письма (образец по первому поставщику, PriceRequest НЕ сохраняется, тема без токена). */
    public KpPreviewResponse preview(Long tenderId, List<Long> distributorIds, List<SendItem> items, SendOptions options) {
        if (distributorIds == null || distributorIds.isEmpty()) throw new BadRequestException("Не выбраны поставщики");
        Distributor sample = distributorService.findById(distributorIds.get(0));
        requireCurrentMarket(sample.getMarket(), "Поставщик не найден");
        return preview(draft(tenderId, sample, items, options));
    }

    public KpPreviewResponse preview(PriceRequest draft) {
        KpEmailComposer.Composed c = composer.composeForPreview(draft);
        return new KpPreviewResponse(c.subject(), c.body());
    }

    /**
     * Запрос без сохранения: id нет — тема письма без метки, в документе «№ —». Для предпросмотра письма и документа;
     * поставщик — проверенный вызывающим или ещё не заведённый (новый из окна заявки).
     */
    public PriceRequest draft(Long tenderId, Distributor supplier, List<SendItem> items, SendOptions options) {
        if (tenderId == null) throw new BadRequestException("Не указан тендер");
        SendOptions o = options == null ? SendOptions.NONE : options;
        Tender tender = tenderService.findById(tenderId);
        requireCurrentMarket(tender.getMarket(), "Тендер не найден: " + tenderId);
        List<PriceRequestItem> prItems = new ArrayList<>();
        for (PriceRequestWriter.Line l : lines(tenderId, items)) {
            prItems.add(PriceRequestItem.builder().tenderLot(l.lot()).medEquipment(l.equipment())
                    .requestedQuantity(l.qty()).build());
        }
        return PriceRequest.builder().tender(tender).distributor(supplier).market(tender.getMarket()).items(prItems)
                .replyBy(o.replyBy()).discloseClient(o.discloseClient()).withDocument(o.withDocument())
                .withStamp(o.withStamp()).emailOverride(o.emailTo() != null && !o.emailTo().isEmpty()).build();
    }

    /** Исход письма в запрос; сбой записи не прячет результат отправки — только предупреждение в лог. */
    private void letter(PriceRequest pr, LetterStatus status, String sentTo) {
        try {
            writer.markLetter(pr.getId(), status, sentTo);
        } catch (RuntimeException e) {
            log.warn("КП id={}: исход письма {} не записан: {}", pr.getId(), status, e.getClass().getSimpleName());
        }
    }
}
