package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.util.DocFormat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Текст уведомления о письме в Telegram (спека §5.2). Чистая функция: собирается при записи письма, когда известно,
 * что сделал разбор (цена, отказ, повтор). Обычный текст без parse_mode — экранировать нечего.
 * Звук: ответ поставщика и «не доставлено» — со звуком; отложенная доставка, автоответ и прочее — без.
 * Все срезы — через {@link MailText#safeCut}: эмодзи пополам не режутся — одиночную половинку суррогатной пары
 * Telegram отклонит (400), и очередь уведомлений встанет на этом письме.
 */
public final class MailNotificationComposer {

    static final int MAX_TEXT = 3500;         // предел Telegram — 4096
    static final int EXCERPT = 700;
    static final int AUTO_EXCERPT = 300;
    static final Duration DELAYED = Duration.ofMinutes(15);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final Map<String, String> STATUS = Map.of(
            "CREATED", "Создан", "SENT", "Отправлен", "RESPONDED", "Ответ получен", "ACCEPTED", "Принят",
            "REJECTED", "Отклонён", "DECLINED", "Отказ", "CLOSED", "Закрыт");

    private MailNotificationComposer() {}

    public static MailNotification compose(ParsedMail m, Classification c, KpSnapshot kp, KpOutcome outcome, ComposeContext ctx) {
        return switch (c.mailClass()) {
            case SUPPLIER_RESPONSE -> supplierResponse(m, c, kp, outcome, ctx);
            case BOUNCE -> bounce(m, kp, ctx);
            case AUTO_REPLY -> autoReply(m, kp, ctx);
            default -> other(m, ctx);
        };
    }

    public static MailNotification composeBroken(BrokenMail b, ComposeContext ctx) {
        StringBuilder head = new StringBuilder();
        head.append("✉️ Письмо на ").append(ctx.mailbox()).append(" · ")
                .append(cut(blankTo(b.from(), "отправитель не прочитан"), 200)).append('\n');
        head.append("Тема: ").append(cut(blankTo(b.subject(), "(тема не прочитана)"), 300)).append('\n');
        delayed(head, b.receivedAt(), ctx);
        head.append("Письмо не удалось разобрать — откройте его в почте Mail.ru.\n");
        return new MailNotification(finish(head, "", 0, link(ctx, "/inbound")), true);
    }

    private static MailNotification supplierResponse(ParsedMail m, Classification c, KpSnapshot kp, KpOutcome outcome,
                                                     ComposeContext ctx) {
        StringBuilder head = new StringBuilder();
        head.append("📩 Ответ поставщика · ")
                .append(kp != null ? kp.supplierName() : cut(blankTo(m.from(), "отправитель не указан"), 200)).append('\n');
        if (kp != null) {
            head.append("Запрос КП №").append(kp.id()).append(" · ").append(tenderLabel(kp)).append('\n');
            lots(head, kp.lots());
        }
        String line = outcomeLine(outcome, kp, c.kpId(), ctx.market());
        if (!line.isEmpty()) head.append(line).append('\n');
        sender(head, m);
        delayed(head, m.receivedAt(), ctx);
        String link = kp != null ? kpLink(kp, ctx) : link(ctx, "/inbound");
        return new MailNotification(finish(head, MailText.replyText(m), EXCERPT, link), false);
    }

    /**
     * Возврат. Отчёт с Action: delayed — доставка задерживается: сервер получателя ещё повторяет попытки, поэтому не
     * «не доставлено», без звука и без совета исправить адрес — адрес, может быть, верный. Всё остальное (failed,
     * отчёт без Action, возврат без частей DSN, любой код статуса) — «не доставлено», со звуком.
     */
    private static MailNotification bounce(ParsedMail m, KpSnapshot kp, ComposeContext ctx) {
        ParsedMail.Bounce b = m.bounce();
        boolean retrying = stillRetrying(b);
        String recipient = b != null && b.finalRecipient() != null ? b.finalRecipient() : (kp != null ? kp.supplierEmail() : null);
        StringBuilder head = new StringBuilder(retrying ? "⏳ Доставка задерживается · " : "⚠️ Письмо не доставлено · ");
        if (kp != null) {
            head.append(kp.supplierName());
            if (recipient != null && !recipient.isBlank()) head.append(" (").append(recipient).append(')');
            head.append('\n').append("Запрос КП №").append(kp.id()).append(" · ").append(tenderLabel(kp)).append('\n');
        } else {
            head.append(blankTo(recipient, "адресат не указан")).append('\n');
            head.append("Тема возврата: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        }
        String reason = b == null ? null : (b.diagnostic() != null && !b.diagnostic().isBlank() ? b.diagnostic() : b.status());
        if (reason != null && !reason.isBlank()) head.append("Причина: ").append(cut(reason, 200)).append('\n');
        if (retrying) {
            head.append("Сервер получателя ещё повторяет доставку — если не получится, придёт отдельное уведомление.\n");
        } else if (kp != null) {
            head.append("Исправьте адрес в карточке поставщика и нажмите «Переслать» в запросах КП ")
                    .append(kp.privateRequest() ? "заявки" : "тендера").append(".\n");
        }
        delayed(head, m.receivedAt(), ctx);
        return new MailNotification(finish(head, "", 0, kp != null ? kpLink(kp, ctx) : link(ctx, "/inbound")), retrying);
    }

    /**
     * Отложенная доставка — только по полю Action отчёта (RFC 3464), не по коду статуса: итоговый отказ серверы шлют
     * и с последним временным кодом (Postfix после срока очереди — Action: failed и Status: 4.4.1, Exchange —
     * 4.4.7 QUEUE.Expired), и такой отказ должен прийти как «не доставлено», со звуком.
     */
    private static boolean stillRetrying(ParsedMail.Bounce b) {
        return b != null && "delayed".equals(b.action());
    }

    private static MailNotification autoReply(ParsedMail m, KpSnapshot kp, ComposeContext ctx) {
        StringBuilder head = new StringBuilder("🤖 Автоответ · ")
                .append(kp != null ? kp.supplierName() : cut(blankTo(m.from(), "отправитель не указан"), 200)).append('\n');
        if (kp != null) {
            head.append("На запрос КП №").append(kp.id()).append(" — статус запроса не меняли\n");
        } else {
            head.append("Тема: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        }
        delayed(head, m.receivedAt(), ctx);
        String link = kp != null ? kpLink(kp, ctx) : link(ctx, "/inbound");
        return new MailNotification(finish(head, MailText.replyText(m), AUTO_EXCERPT, link), true);
    }

    private static MailNotification other(ParsedMail m, ComposeContext ctx) {
        StringBuilder head = new StringBuilder("✉️ Письмо на ").append(ctx.mailbox()).append(" · ")
                .append(cut(blankTo(m.from(), "отправитель не указан"), 200)).append('\n');
        head.append("Тема: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        if (!m.attachmentNames().isEmpty()) head.append("Вложения: ").append(attachments(m.attachmentNames())).append('\n');
        delayed(head, m.receivedAt(), ctx);
        return new MailNotification(finish(head, MailText.replyText(m), EXCERPT, link(ctx, "/inbound")), true);
    }

    static String outcomeLine(KpOutcome o, KpSnapshot kp, Long kpId, Market market) {
        if (o == null) return "";
        return switch (o) {
            case PRICE_PARSED -> "💡 Цена распознана: " + money(kp, market) + " — проверьте";
            case PRICE_SET -> "Цена в АИС уже введена: " + money(kp, market);
            case DECLINED -> "⛔ Поставщик отказался";
            case NO_PRICE -> "Цену не распознали — введите вручную";
            case MULTI_LOT -> "Лотов несколько — цены вручную";
            case UNCHANGED -> "Повторное письмо — статус «" + STATUS.getOrDefault(kp.status(), kp.status()) + "» не меняли";
            case NOT_FOUND -> "Запрос КП №" + kpId + " в АИС не найден";
        };
    }

    private static String money(KpSnapshot kp, Market market) {
        return DocFormat.money(kp.price()) + " " + market.currencySymbol();
    }

    private static void sender(StringBuilder head, ParsedMail m) {
        head.append("От: ").append(cut(blankTo(m.from(), "не указан"), 200)).append('\n');
        head.append("Тема: ").append(cut(blankTo(m.subject(), "(без темы)"), 300)).append('\n');
        if (!m.attachmentNames().isEmpty()) head.append("Вложения: ").append(attachments(m.attachmentNames())).append('\n');
    }

    private static void lots(StringBuilder head, List<KpSnapshot.LotLine> lots) {
        if (lots.isEmpty()) return;
        if (lots.size() > 2) {
            head.append("Лотов: ").append(lots.size()).append('\n');
            return;
        }
        head.append(lots.size() == 1 ? "Лот: " : "Лоты: ");
        for (int i = 0; i < lots.size(); i++) {
            KpSnapshot.LotLine l = lots.get(i);
            if (i > 0) head.append("; ");
            head.append(cut(blankTo(l.name(), "без наименования"), 80));
            if (l.quantity() != null) head.append(" — ").append(l.quantity()).append(" шт.");
        }
        head.append('\n');
    }

    private static String tenderLabel(KpSnapshot kp) {
        return (kp.privateRequest() ? "частная заявка " : "тендер ") + blankTo(kp.tenderNumber(), "без номера");
    }

    private static String attachments(List<String> names) {
        List<String> shown = names.stream().limit(5).map(n -> cut(n, 100)).toList();
        return String.join(", ", shown) + (names.size() > 5 ? " и ещё " + (names.size() - 5) : "");
    }

    /** Пришло заметно раньше записи (догонка после простоя) — время получения по часовому поясу рынка. */
    private static void delayed(StringBuilder head, OffsetDateTime receivedAt, ComposeContext ctx) {
        if (receivedAt == null || ctx.queuedAt() == null) return;
        if (Duration.between(receivedAt, ctx.queuedAt()).compareTo(DELAYED) > 0) {
            head.append("Получено: ").append(receivedAt.atZoneSameInstant(zone(ctx.market())).format(WHEN)).append('\n');
        }
    }

    private static ZoneId zone(Market market) {
        return market == Market.KZ ? ZoneId.of("Asia/Oral") : ZoneId.of("Europe/Samara");
    }

    private static String kpLink(KpSnapshot kp, ComposeContext ctx) {
        return link(ctx, (kp.privateRequest() ? "/private-requests" : "/tenders") + "?openId=" + kp.tenderId());
    }

    /** Ссылка с рынком (?market=): браузер, открытый впервые, стартует на РФ. Пустой publicUrl — без ссылки. */
    private static String link(ComposeContext ctx, String path) {
        if (ctx.publicUrl() == null || ctx.publicUrl().isBlank()) return null;
        String base = ctx.publicUrl().trim().replaceAll("/+$", "");
        return base + path + (path.contains("?") ? "&" : "?") + "market=" + ctx.market().name();
    }

    /** Шапка + отрывок (влезает в остаток предела) + ссылка. */
    private static String finish(StringBuilder head, String body, int max, String link) {
        String tail = link == null ? "" : "\nОткрыть в АИС: " + link;
        int room = MAX_TEXT - head.length() - tail.length() - 2;
        String ex = body == null || body.isBlank() || max <= 0 || room < 20 ? "" : MailText.excerpt(body, Math.min(max, room));
        StringBuilder out = new StringBuilder(head.toString().stripTrailing());
        if (!ex.isEmpty()) out.append("\n\n").append(ex);
        if (!tail.isEmpty()) out.append('\n').append(tail);
        String s = out.toString();
        return s.length() <= MAX_TEXT ? s : MailText.safeCut(s, MAX_TEXT - 1) + "…";
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    /** Не длиннее max символов, с «…»; эмодзи пополам не режет ({@link MailText#safeCut}). */
    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : MailText.safeCut(s, max - 1) + "…";
    }
}
