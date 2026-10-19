package com.vladoose.nir.service.readyoffer;

import com.vladoose.nir.dto.response.ChatFileRow;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ClientOfferFileRepository;
import com.vladoose.nir.repository.LeadRepository;
import com.vladoose.nir.service.deal.DealTexts;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.offer.OfferLabels;
import com.vladoose.nir.service.supplierreply.SupplierReplyFiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Переписка сделки для готового КП (спека 2026-10-19 §3.2, §3.4, §5.3): эпизод обращения заявки (как лента
 * «Переписка и история»), наши файлы в нём (письма из «Отправленных» info@, сообщения с нашего номера), PDF самой АИС
 * и признаки «похоже на КП».
 */
@Component
public class ReadyOfferHints {

    public static final int FILES_LIMIT = 50;
    /** «КП» / «KP» отдельным словом, «коммерческое предложение», «ценовое предложение». */
    static final Pattern OFFER_WORDS = Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(?:кп|kp)(?!\\p{L})|коммерческ\\p{L}*\\s+предложени|ценов\\p{L}*\\s+предложени");

    /** Этапы, когда у сделки нет отправленного или принятого КП (спека §3.4). */
    private static final Set<String> HINT_STAGES = Set.of("EMPTY", "NEW", "REQUESTED", "PRICED", "KP_DRAFT", "KP_REJECTED");

    /** Переписка сделки: чат обращения заявки и границы эпизода. */
    public record Episode(Long chatId, OffsetDateTime from, OffsetDateTime to) {}

    private final LeadRepository leads;
    private final ChatAttachmentRepository attachments;
    private final ClientOfferFileRepository files;

    public ReadyOfferHints(LeadRepository leads, ChatAttachmentRepository attachments, ClientOfferFileRepository files) {
        this.leads = leads;
        this.attachments = attachments;
        this.files = files;
    }

    public Optional<Episode> episode(Tender t) {
        Lead lead = leads.findFirstByPrivateRequestId(t.getId()).orElse(null);
        if (lead == null || lead.getChat() == null) return Optional.empty();
        OffsetDateTime from = lead.getReceivedAt() != null ? lead.getReceivedAt() : lead.getCreatedAt();
        if (from == null) return Optional.empty();
        Long chatId = lead.getChat().getId();
        OffsetDateTime to = leads.findFirstByChatIdAndReceivedAtAfterOrderByReceivedAtAsc(chatId, from)
                .map(Lead::getReceivedAt).orElse(DealTimelineService.FAR);
        return Optional.of(new Episode(chatId, from, to));
    }

    /** Наши файлы переписки сделки — новые сверху, до FILES_LIMIT; переписки нет — пусто. */
    public List<ChatFileRow> ourFiles(Tender t) {
        return episode(t).map(e -> attachments.findOurFiles(e.chatId(), LeadDirection.OUT, e.from(), e.to(),
                PageRequest.of(0, FILES_LIMIT))).orElse(List.of());
    }

    /** Наш файл переписки этой сделки — по проекции; иначе 404. */
    public ChatFileRow requireOurFile(Tender t, Long attachmentId) {
        return ourFiles(t).stream().filter(f -> f.attachmentId().equals(attachmentId)).findFirst()
                .orElseThrow(() -> new NotFoundException("Файл переписки не найден"));
    }

    /** Тот же файл с байтами — для занесения. */
    public ChatAttachment ourAttachment(Tender t, Long attachmentId) {
        requireOurFile(t, attachmentId);
        return attachments.findById(attachmentId).orElseThrow(() -> new NotFoundException("Файл переписки не найден"));
    }

    /**
     * Подсказка «Дальше» (спека §3.4): самый поздний наш файл, ещё не занесённый и не отмеченный «Не КП»: PDF самой АИС с
     * черновиком этой сделки — «Отметить отправленным»; файл, похожий на КП, — «Занести КП». PDF самой АИС с КП в другом
     * статусе пропускается.
     */
    public Optional<DealProgress.Next> hint(Tender t, String stageCode, List<ClientOffer> offersNewestFirst) {
        if (!HINT_STAGES.contains(stageCode)) return Optional.empty();
        List<ChatFileRow> ours = ourFiles(t);
        if (ours.isEmpty()) return Optional.empty();
        Set<Long> used = usedBy(offersNewestFirst).keySet();
        ZoneId zone = (t.getMarket() == null ? Market.KZ : t.getMarket()).zone();
        for (ChatFileRow f : ours) {
            if (f.hintDismissed() || used.contains(f.attachmentId())) continue;
            String when = DealTexts.ddMM(f.sentAt(), zone) + channelWord(f.channel());
            Optional<ClientOffer> ais = aisOffer(nameHeader(f.fileName()), offersNewestFirst);
            if (ais.isPresent()) {
                if (ais.get().getStatus() != ClientOfferStatus.DRAFT) continue;
                return Optional.of(new DealProgress.Next(OfferLabels.of(ais.get()) + " ушло клиенту " + when
                        + " — отметить отправленным?", "MARK_OFFER_SENT", ais.get().getId(), f.attachmentId()));
            }
            if (looksLikeOffer(f)) {
                return Optional.of(new DealProgress.Next("Похоже, КП ушло клиенту " + when + " — «" + nz(f.fileName()) + "»",
                        "ATTACH_OFFER_FILE", null, f.attachmentId()));
            }
        }
        return Optional.empty();
    }

    /** PDF, Word или Excel, а в теме, тексте или имени файла — признак КП. */
    public static boolean looksLikeOffer(ChatFileRow f) {
        ReplyFileKind kind = SupplierReplyFiles.kind(nz(f.fileName()), f.mimeType(), new byte[0]);
        if (kind != ReplyFileKind.PDF && kind != ReplyFileKind.WORD && kind != ReplyFileKind.EXCEL) return false;
        return words(f.subject()) || words(f.body()) || words(f.fileName());
    }

    /** Номер и дата из имени файла («КП № 481 от 07.10.2026 – …» — так АИС называет свои PDF и Word). */
    public static OfferHeader nameHeader(String fileName) {
        return OfferHeader.find(List.of(nz(fileName)));
    }

    /** КП конструктора этой сделки с тем же номером и датой — документ самой АИС (спека §4.7). */
    public static Optional<ClientOffer> aisOffer(OfferHeader h, List<ClientOffer> offers) {
        if (h == null || h.number() == null || h.date() == null) return Optional.empty();
        return offers.stream().filter(o -> o.getOrigin() == ClientOfferOrigin.BUILDER && h.number().equals(o.getNumber())
                && h.date().equals(o.getOfferDate())).findFirst();
    }

    /** « письмом» / « в WhatsApp» — по каналу сообщения. */
    public static String channelWord(LeadChannel c) {
        return c == LeadChannel.EMAIL ? " письмом" : c == LeadChannel.WHATSAPP ? " в WhatsApp" : "";
    }

    /** Файл переписки → КП, в которое он занесён (по ссылке файла КП). */
    public Map<Long, ClientOffer> usedBy(List<ClientOffer> offers) {
        Map<Long, ClientOffer> byId = new HashMap<>();
        for (ClientOffer o : offers) if (o.getOrigin() == ClientOfferOrigin.FILE) byId.put(o.getId(), o);
        Map<Long, ClientOffer> out = new HashMap<>();
        if (byId.isEmpty()) return out;
        files.findMetaByOfferIds(byId.keySet()).forEach(m -> {
            if (m.chatAttachmentId() != null) out.put(m.chatAttachmentId(), byId.get(m.offerId()));
        });
        return out;
    }

    private static boolean words(String s) {
        return s != null && OFFER_WORDS.matcher(s).find();
    }

    static String nz(String s) {
        return s == null ? "" : s;
    }
}
