package com.vladoose.nir.service.tgbot;

import com.vladoose.nir.dto.response.KpPreviewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.telegram.BotUpdate;
import com.vladoose.nir.integration.telegram.TelegramBotApi;
import com.vladoose.nir.integration.telegram.TelegramBotApi.Button;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.*;
import com.vladoose.nir.service.PriceRequestSendService.SendItem;
import com.vladoose.nir.service.PriceRequestSendService.SendOptions;
import com.vladoose.nir.service.PriceRequestSendService.SendResult;
import com.vladoose.nir.util.CurrentUser;
import com.vladoose.nir.util.ErrorText;
import com.vladoose.nir.util.PhoneNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Письма из бота (спека 2026-10-21-tender-telegram-bot §7): «👁 N» — фото бланка (2+ страниц — PDF) с «⬅ Вернуться ·
 * ✅ Отправить · ✏️ Открыть в АИС»; «✅ Отправить» — тот же путь, что панель «КП» (письмо, бланк, клиент, копия в
 * «Отправленные»), после проверок: приём идёт, модель та же, из АИС тем временем не отправляли. Повтор после сбоя почты —
 * «↻ Переслать» того же запроса. «📱 Отправлю сам» (решение оператора 2026-10-21): запрос «передам сам» с номером ждёт
 * подтверждения, в чат — PDF и текст для WhatsApp; «✅ Отправил» или исходящий файл в зеркале WhatsApp — передан.
 */
@Component
public class BotLetterActions {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final Logger log = LoggerFactory.getLogger(BotLetterActions.class);

    private final TelegramBotApi api;
    private final BotLetterStore store;
    private final PriceRequestSendService sendService;
    private final RfqDocumentService documents;
    private final DistributorService distributors;
    private final TenderService tenders;
    private final TenderLotRepository lots;
    private final PriceRequestRepository priceRequests;
    private final CompanyProfileService profiles;
    private final PriceRequestWriter writer;
    private final String base;

    public BotLetterActions(TelegramBotApi api, BotLetterStore store, PriceRequestSendService sendService, RfqDocumentService documents,
                            DistributorService distributors, TenderService tenders, TenderLotRepository lots,
                            PriceRequestRepository priceRequests, CompanyProfileService profiles, PriceRequestWriter writer,
                            @Value("${ais.public-url:}") String publicUrl) {
        this.api = api;
        this.store = store;
        this.sendService = sendService;
        this.documents = documents;
        this.distributors = distributors;
        this.tenders = tenders;
        this.lots = lots;
        this.priceRequests = priceRequests;
        this.profiles = profiles;
        this.writer = writer;
        this.base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
    }

    public void view(BotUpdate.Callback c, long id) {
        BotLetter b = store.find(id).orElse(null);
        if (b == null) {
            api.answerCallback(c.id(), "Письмо не найдено", true);
            return;
        }
        if (b.getStatus() != BotLetterStatus.DRAFT && b.getStatus() != BotLetterStatus.FAILED) {
            api.answerCallback(c.id(), statusText(b), false);
            return;
        }
        api.answerCallback(c.id(), "Собираю письмо…", false);
        Distributor d = distributors.findById(b.getDistributorId());
        Tender t = tenders.findPublic(b.getTenderId());
        PriceRequest draft = sendService.draft(b.getTenderId(), d, items(b), options(t));
        KpPreviewResponse letter = sendService.preview(draft);
        RfqDocumentService.PreviewFiles files = documents.previewFiles(draft);
        String caption = BotTexts.caption(b.getLineNo(), store.list(b.getListMessageId()).size(), d.getName(), d.isManufacturer(),
                d.getEmail(), letter.getSubject(), letter.getBody());
        if (b.getPreviewMessageId() != null) api.deleteMessage(b.getPreviewMessageId());
        long mid = files.pages().size() == 1
                ? api.sendPhoto("Запрос — " + TelegramText.oneLine(d.getName(), 60) + ".png", files.pages().get(0), caption,
                        previewKeyboard(b), b.getListMessageId())
                : api.sendDocument(files.pdfName(), files.pdf(), "application/pdf", caption, previewKeyboard(b), b.getListMessageId());
        store.setPreview(id, mid);
    }

    public void back(BotUpdate.Callback c, long id) {
        api.answerCallback(c.id(), null, false);
        store.find(id).filter(b -> b.getPreviewMessageId() != null).ifPresent(b -> {
            api.deleteMessage(b.getPreviewMessageId());
            store.setPreview(id, null);
        });
    }

    public void send(BotUpdate.Callback c, long id) {
        BotLetter b = store.find(id).orElse(null);
        if (b == null) {
            api.answerCallback(c.id(), "Письмо не найдено", true);
            return;
        }
        if (!store.claim(id)) {
            api.answerCallback(c.id(), b.getStatus() == BotLetterStatus.SENDING ? "Уже отправляется…" : statusText(b), false);
            return;
        }
        api.answerCallback(c.id(), "Отправляю…", false);
        try {
            String problem = check(b);
            if (problem != null) {
                api.sendMessage(TelegramText.html(problem), true, null, b.getListMessageId());
                return;
            }
            Distributor d = distributors.findById(b.getDistributorId());
            SendResult r = b.getPriceRequestId() != null
                    ? sendService.resend(b.getPriceRequestId())
                    : sendService.send(b.getTenderId(), List.of(b.getDistributorId()), items(b), null, null,
                            options(tenders.findPublic(b.getTenderId()))).get(0);
            if (r.emailSent()) {
                store.finish(id, BotLetterStatus.SENT, r.priceRequestId(), CurrentUser.label(), null);
                if (b.getPreviewMessageId() != null) {
                    api.deleteMessage(b.getPreviewMessageId());
                    store.setPreview(id, null);
                }
                api.sendMessage(BotTexts.sent(d.getName(), CurrentUser.name(), lotNames(b)), true, null, b.getListMessageId());
            } else {
                String why = PriceRequestSendService.REASON_NO_EMAIL.equals(r.reason()) ? "у поставщика нет почты" : "почта не приняла письмо";
                store.finish(id, BotLetterStatus.FAILED, r.priceRequestId(), null, why);
                api.sendMessage(BotTexts.failed(d.getName(), why), true, null, b.getListMessageId());
            }
        } catch (RuntimeException e) {
            store.finish(id, BotLetterStatus.FAILED, null, null, ErrorText.of(e));
            throw e;
        } finally {
            if (b.getListMessageId() != null) api.editKeyboard(b.getListMessageId(), BotTexts.listKeyboard(store.list(b.getListMessageId())));
        }
    }

    /**
     * «📱 Отправлю сам»: те же проверки, что у «Отправить»; запрос цен «передам сам» с номером, ждёт подтверждения (письмо не
     * ушло раньше — тот же запрос); в чат — PDF этого запроса, текст для WhatsApp и кнопки. Сбой — запрос убирается.
     */
    public void manual(BotUpdate.Callback c, long id) {
        BotLetter b = store.find(id).orElse(null);
        if (b == null) {
            api.answerCallback(c.id(), "Письмо не найдено", true);
            return;
        }
        if (!store.claim(id)) {
            api.answerCallback(c.id(), statusText(b), false);
            return;
        }
        api.answerCallback(c.id(), "Готовлю PDF…", false);
        Long created = null;
        try {
            String problem = check(b);
            if (problem != null) {
                api.sendMessage(TelegramText.html(problem), true, null, b.getListMessageId());
                return;
            }
            Tender t = tenders.findPublic(b.getTenderId());
            Long prId = b.getPriceRequestId();
            if (prId == null) {
                prId = created = sendService.send(b.getTenderId(), List.of(b.getDistributorId()), items(b), null, null,
                        manualOptions(t)).get(0).priceRequestId();
            }
            Distributor d = distributors.findById(b.getDistributorId());
            RfqDocumentService.RfqFile pdf = sendService.document(prId, RfqDocumentService.Format.PDF);
            String text = BotTexts.whatsappText(prId, positions(b), t.getCustomerName(), manualOptions(t).replyBy());
            long mid = api.sendDocument(pdf.fileName(), pdf.bytes(), "application/pdf", BotTexts.manualCaption(d.getName(), text),
                    manualKeyboard(b, d, text), b.getListMessageId());
            if (created == null) writer.markManualPending(prId);   // письмо не ушло раньше — тот же запрос, теперь «передам сам»
            store.finish(id, BotLetterStatus.MANUAL, prId, null, null);
            if (b.getPreviewMessageId() != null) api.deleteMessage(b.getPreviewMessageId());
            store.setPreview(id, mid);
        } catch (RuntimeException e) {
            if (created != null) writer.deletePendingManual(created);
            store.finish(id, BotLetterStatus.FAILED, null, null, ErrorText.of(e));
            throw e;
        } finally {
            if (b.getListMessageId() != null) api.editKeyboard(b.getListMessageId(), BotTexts.listKeyboard(store.list(b.getListMessageId())));
        }
    }

    /** «✅ Отправил» — передали сами: запрос «передан вручную», время — сейчас; двойное нажатие — одно подтверждение. */
    public void handed(BotUpdate.Callback c, long id) {
        BotLetter b = store.find(id).orElse(null);
        if (b == null) {
            api.answerCallback(c.id(), "Письмо не найдено", true);
            return;
        }
        if (b.getStatus() != BotLetterStatus.MANUAL || !writer.confirmManual(b.getPriceRequestId(), OffsetDateTime.now())) {
            api.answerCallback(c.id(), statusText(store.find(id).orElse(b)), false);
            return;
        }
        store.finish(id, BotLetterStatus.HANDED, null, CurrentUser.label(), null);
        api.answerCallback(c.id(), "Отмечено: передано", false);
        Distributor d = distributors.findById(b.getDistributorId());
        afterHanded(b, BotTexts.handed(d.getName(), CurrentUser.name(), lotNames(b)));
    }

    /** «✕ Не отправлял» — запрос, ждущий подтверждения, убирается; письмо снова черновик (можно почтой или ещё раз сам). */
    public void undo(BotUpdate.Callback c, long id) {
        BotLetter b = store.find(id).orElse(null);
        if (b == null) {
            api.answerCallback(c.id(), "Письмо не найдено", true);
            return;
        }
        if (b.getStatus() != BotLetterStatus.MANUAL || !writer.deletePendingManual(b.getPriceRequestId())) {
            api.answerCallback(c.id(), statusText(store.find(id).orElse(b)), false);
            return;
        }
        store.undoManual(id);
        api.answerCallback(c.id(), "Запрос убран — письмо можно отправить заново", false);
        if (b.getPreviewMessageId() != null) api.deleteMessage(b.getPreviewMessageId());
        if (b.getListMessageId() != null) api.editKeyboard(b.getListMessageId(), BotTexts.listKeyboard(store.list(b.getListMessageId())));
    }

    /**
     * Исходящий файл «Запрос КП № N …» в зеркале WhatsApp (рабочий номер АИС): запрос, ждущий подтверждения, — передан, письмо
     * бота — HANDED, в Telegram — строка о передаче. Повтор того же файла и чужие запросы ничего не меняют.
     */
    public void confirmFromWhatsapp(long priceRequestId, String chat, OffsetDateTime at) {
        if (!writer.confirmManual(priceRequestId, at == null ? OffsetDateTime.now() : at)) return;
        BotLetter b = store.findByPriceRequest(priceRequestId).orElse(null);
        if (b == null || b.getStatus() != BotLetterStatus.MANUAL) return;
        store.finish(b.getId(), BotLetterStatus.HANDED, null, "WhatsApp" + (chat == null || chat.isBlank() ? "" : ": "
                + TelegramText.oneLine(chat, 60)), null);
        try {
            Distributor d = distributors.findById(b.getDistributorId());
            String time = (at == null ? OffsetDateTime.now() : at).atZoneSameInstant(Market.KZ.zone()).format(HM);
            afterHanded(b, BotTexts.handedInWhatsapp(priceRequestId, d.getName(), chat, time));
        } catch (RuntimeException e) {
            log.warn("Кнопки бота: передача запроса {} в WhatsApp отмечена, сообщение в Telegram не ушло: {}", priceRequestId,
                    e.getClass().getSimpleName());
        }
    }

    /** После «передан сам»: кнопки под PDF больше не нужны, строка о передаче, список писем — с новой кнопкой. */
    private void afterHanded(BotLetter b, String text) {
        if (b.getPreviewMessageId() != null) api.editKeyboard(b.getPreviewMessageId(), List.of());
        api.sendMessage(text, true, null, b.getListMessageId());
        if (b.getListMessageId() != null) api.editKeyboard(b.getListMessageId(), BotTexts.listKeyboard(store.list(b.getListMessageId())));
    }

    /** Кнопки под PDF «📱 Отправлю сам»: скопировать текст, чат поставщика (если в карточке есть телефон), отправил / не отправлял. */
    private List<List<Button>> manualKeyboard(BotLetter b, Distributor d, String text) {
        List<Button> top = new ArrayList<>();
        top.add(Button.copy("📋 Скопировать текст", text));
        String wa = waLink(d.getPhone(), text);
        if (wa != null) top.add(Button.link("💬 Чат WhatsApp", wa));
        return List.of(top, List.of(Button.cb("✅ Отправил", "l:" + b.getId() + ":h"), Button.cb("✕ Не отправлял", "l:" + b.getId() + ":u")));
    }

    /** wa.me с текстом — по телефону карточки поставщика (+7…); телефона нет — null. */
    static String waLink(String phone, String text) {
        String p = PhoneNormalizer.normalize(phone);
        if (p == null) return null;
        return "https://wa.me/" + p.substring(1) + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** «<модель или лот> — N шт.» по позициям письма — для текста WhatsApp. */
    private List<String> positions(BotLetter b) {
        List<String> out = new ArrayList<>();
        for (BotLetterItem it : b.getItems()) {
            TenderLot l = lots.findById(it.lotId()).orElse(null);
            if (l == null) continue;
            String name = l.getProposedEquipment() != null ? l.getProposedEquipment().getName() : l.getEquipName();
            out.add(TelegramText.oneLine(name, 120) + (it.qty() == null ? "" : " — " + it.qty() + " шт."));
        }
        return out;
    }

    private SendOptions manualOptions(Tender t) {
        SendOptions o = options(t);
        return new SendOptions(o.replyBy(), true, o.withStamp(), true, null, true, true);
    }

    /** null — можно отправлять; иначе — причина, а статус письма уже переведён. */
    private String check(BotLetter b) {
        Tender t = tenders.findPublic(b.getTenderId());
        if (!"ACTIVE".equals(t.getStatus()) || ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), LocalDateTime.now(Market.KZ.zone()))) {
            store.finish(b.getId(), BotLetterStatus.CANCELLED, null, null, "приём закрыт");
            return "Приём по тендеру закончился — письмо не отправлено";
        }
        List<BotLetterItem> fresh = new ArrayList<>();
        boolean changed = false;
        for (BotLetterItem it : b.getItems()) {
            TenderLot l = lots.findById(it.lotId()).orElseThrow();
            Long eq = l.getProposedEquipment() == null ? null : l.getProposedEquipment().getId();
            if (!Objects.equals(eq, it.equipmentId())) changed = true;
            fresh.add(new BotLetterItem(l.getId(), eq, l.getQuantity()));
        }
        if (changed) {
            store.updateItems(b.getId(), fresh);
            store.finish(b.getId(), BotLetterStatus.DRAFT, null, null, null);
            return "Модель лота изменилась — посмотрите письмо заново (👁)";
        }
        if (b.getPriceRequestId() == null) {
            OffsetDateTime last = priceRequests.lastCreatedAt(b.getTenderId(), b.getDistributorId(),
                    b.getItems().stream().map(BotLetterItem::lotId).toList());
            if (last != null && last.isAfter(b.getCreatedAt())) {
                store.finish(b.getId(), BotLetterStatus.ELSEWHERE, null, null, null);
                return "Уже отправлено из АИС в " + last.atZoneSameInstant(Market.KZ.zone()).format(HM);
            }
        }
        return null;
    }

    private SendOptions options(Tender t) {
        LocalDate deadline = t.getDeadline() != null ? t.getDeadline() : t.getDeadlineAt() == null ? null : t.getDeadlineAt().toLocalDate();
        boolean stamp = profiles.forMarket(Market.KZ).getStampPng() != null;
        return new SendOptions(replyBy(deadline, LocalDate.now(Market.KZ.zone())), true, stamp, true, null);
    }

    /** «Ответить до» (спека §7): за 2 дня до конца приёма, не раньше завтра и не позже дня окончания; срока нет — через 3 дня. */
    public static LocalDate replyBy(LocalDate deadline, LocalDate today) {
        if (deadline == null) return today.plusDays(3);
        LocalDate d = deadline.minusDays(2);
        if (d.isBefore(today.plusDays(1))) d = today.plusDays(1);
        if (d.isAfter(deadline)) d = deadline;
        return d.isBefore(today) ? today : d;
    }

    private static List<SendItem> items(BotLetter b) {
        return b.getItems().stream().map(i -> new SendItem(i.lotId(), i.equipmentId(), i.qty())).toList();
    }

    private String lotNames(BotLetter b) {
        return String.join(", ", b.getItems().stream().map(i -> lots.findById(i.lotId()).map(TenderLot::getEquipName).orElse("лот"))
                .map(n -> TelegramText.oneLine(n, 60)).toList());
    }

    private List<List<Button>> previewKeyboard(BotLetter b) {
        List<List<Button>> kb = new ArrayList<>();
        kb.add(List.of(Button.cb("⬅ Вернуться", "l:" + b.getId() + ":b"), Button.cb("✅ Отправить", "l:" + b.getId() + ":s")));
        kb.add(List.of(Button.cb("📱 Отправлю сам (WhatsApp)", "l:" + b.getId() + ":m")));
        if (base != null && !b.getItems().isEmpty()) {
            kb.add(List.of(Button.link("✏️ Открыть в АИС", base + "/tenders?openId=" + b.getTenderId() + "&lot="
                    + b.getItems().get(0).lotId() + "&kp=" + b.getDistributorId() + "&market=KZ")));
        }
        return kb;
    }

    private static String statusText(BotLetter b) {
        return switch (b.getStatus()) {
            case SENT -> "Письмо уже отправлено";
            case ELSEWHERE -> "Уже отправлено из АИС";
            case CANCELLED -> "Приём закрыт — письмо не отправлялось";
            case SENDING -> "Уже отправляется…";
            case MANUAL -> "Ждём подтверждения: «✅ Отправил» под PDF";
            case HANDED -> "Передано вручную";
            default -> "Письмо в работе";
        };
    }
}
