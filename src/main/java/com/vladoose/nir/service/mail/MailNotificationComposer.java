package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.util.DocFormat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Текст уведомления о письме в Telegram (спека §5.2). Чистая функция: собирается при записи письма, когда известно,
 * что сделал разбор (цена, отказ, повтор). Обычный текст без parse_mode — экранировать нечего.
 * Звук: ответ поставщика и «не доставлено» — со звуком; отложенная доставка, автоответ и прочее — без.
 * Все срезы — через {@link MailText#safeCut}: эмодзи пополам не режутся — одиночную половинку суррогатной пары
 * Telegram отклонит (400), и очередь уведомлений встанет на этом письме.
 * Чужой отправитель не должен подделать системную строку («Открыть в АИС: …» со своей ссылкой, «💡 Цена распознана»):
 * каждое поле идёт одной строкой ({@link #line}), а текст письма — с отбивкой «│» на каждой строке ({@link #quote}).
 */
public final class MailNotificationComposer {

    static final int MAX_TEXT = 3500;         // предел Telegram — 4096
    static final int EXCERPT = 700;
    static final int AUTO_EXCERPT = 300;
    static final Duration DELAYED = Duration.ofMinutes(15);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM HH:mm");
    private static final Pattern SPACES = Pattern.compile(" {2,}");
    /** Всё, что клиенты показывают новой строкой: \r\n, \n, \r, VT, FF, NEL, LS, PS. */
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|[\n\r\u000B\u000C\u0085\u2028\u2029]");
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
        head.append("✉️ Письмо на ").append(line(ctx.mailbox(), 200)).append(" · ")
                .append(blankTo(line(b.from(), 200), "отправитель не прочитан")).append('\n');
        head.append("Тема: ").append(blankTo(line(b.subject(), 300), "(тема не прочитана)")).append('\n');
        delayed(head, b.receivedAt(), ctx);
        head.append("Письмо не удалось разобрать — откройте его в почте Mail.ru.\n");
        return new MailNotification(finish(head, "", 0, link(ctx, "/inbound")), true);
    }

    private static MailNotification supplierResponse(ParsedMail m, Classification c, KpSnapshot kp, KpOutcome outcome,
                                                     ComposeContext ctx) {
        StringBuilder head = new StringBuilder();
        head.append("📩 Ответ поставщика · ")
                .append(kp != null ? line(kp.supplierName(), 200) : blankTo(line(m.from(), 200), "отправитель не указан"))
                .append('\n');
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
        String recipient = line(b != null && b.finalRecipient() != null ? b.finalRecipient()
                : (kp != null ? kp.supplierEmail() : null), 200);
        StringBuilder head = new StringBuilder(retrying ? "⏳ Доставка задерживается · " : "⚠️ Письмо не доставлено · ");
        if (kp != null) {
            head.append(line(kp.supplierName(), 200));
            if (!recipient.isEmpty()) head.append(" (").append(recipient).append(')');
            head.append('\n').append("Запрос КП №").append(kp.id()).append(" · ").append(tenderLabel(kp)).append('\n');
        } else {
            head.append(blankTo(recipient, "адресат не указан")).append('\n');
            head.append("Тема возврата: ").append(blankTo(line(m.subject(), 300), "(без темы)")).append('\n');
        }
        String reason = b == null ? "" : blankTo(line(b.diagnostic(), 200), line(b.status(), 200));
        if (!reason.isEmpty()) head.append("Причина: ").append(reason).append('\n');
        if (retrying) {
            head.append("Сервер получателя ещё повторяет доставку — если не получится, придёт отдельное уведомление.\n");
        } else if (kp != null) {
            // «↻ Переслать» есть только в карточке тендера; у частной заявки КП запрашивают заново
            head.append(kp.privateRequest()
                    ? "Исправьте адрес в карточке поставщика и запросите КП заново в карточке заявки.\n"
                    : "Исправьте адрес в карточке поставщика и нажмите «Переслать» в запросах КП тендера.\n");
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
                .append(kp != null ? line(kp.supplierName(), 200) : blankTo(line(m.from(), 200), "отправитель не указан"))
                .append('\n');
        if (kp != null) {
            head.append("На запрос КП №").append(kp.id()).append(" — статус запроса не меняли\n");
        } else {
            head.append("Тема: ").append(blankTo(line(m.subject(), 300), "(без темы)")).append('\n');
        }
        delayed(head, m.receivedAt(), ctx);
        String link = kp != null ? kpLink(kp, ctx) : link(ctx, "/inbound");
        return new MailNotification(finish(head, MailText.replyText(m), AUTO_EXCERPT, link), true);
    }

    private static MailNotification other(ParsedMail m, ComposeContext ctx) {
        StringBuilder head = new StringBuilder("✉️ Письмо на ").append(line(ctx.mailbox(), 200)).append(" · ")
                .append(blankTo(line(m.from(), 200), "отправитель не указан")).append('\n');
        head.append("Тема: ").append(blankTo(line(m.subject(), 300), "(без темы)")).append('\n');
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
        head.append("От: ").append(blankTo(line(m.from(), 200), "не указан")).append('\n');
        head.append("Тема: ").append(blankTo(line(m.subject(), 300), "(без темы)")).append('\n');
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
            head.append(blankTo(line(l.name(), 80), "без наименования"));
            if (l.quantity() != null) head.append(" — ").append(l.quantity()).append(" шт.");
        }
        head.append('\n');
    }

    private static String tenderLabel(KpSnapshot kp) {
        return (kp.privateRequest() ? "частная заявка " : "тендер ") + blankTo(line(kp.tenderNumber(), 200), "без номера");
    }

    private static String attachments(List<String> names) {
        List<String> shown = names.stream().limit(5).map(n -> line(n, 100)).toList();
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

    /**
     * Шапка + отрывок с отбивкой (влезает в остаток предела) + ссылка. Предел отрывка (700 / 300) — до отбивки, а сама
     * отбивка удлиняет его: «│ » на непустую строку, «│» на пустую. В каждой непустой строке есть хотя бы один
     * символ, поэтому отбитый отрывок из L символов не длиннее 2·L + 1 — отрывку достаётся половина остатка,
     * и ссылка общим пределом не отрезается.
     */
    private static String finish(StringBuilder head, String body, int max, String link) {
        String tail = link == null ? "" : "\nОткрыть в АИС: " + link;
        int room = MAX_TEXT - head.length() - tail.length() - 2;
        int cap = Math.min(max, (room - 1) / 2);
        String ex = body == null || body.isBlank() || max <= 0 || cap < 20 ? "" : MailText.excerpt(body, cap);
        StringBuilder out = new StringBuilder(head.toString().stripTrailing());
        if (!ex.isEmpty()) out.append("\n\n").append(quote(ex));
        if (!tail.isEmpty()) out.append('\n').append(tail);
        String s = out.toString();
        return s.length() <= MAX_TEXT ? s : MailText.safeCut(s, MAX_TEXT - 1) + "…";
    }

    /**
     * Отбивка «│ » у каждой строки текста письма, у пустой — «│» без пробела. Строкой считается всё, что клиент
     * покажет с новой строки ({@link #LINE_BREAK}): «Открыть в АИС: …» из тела письма видна только как «│ Открыть в
     * АИС: …» и за системную строку не сойдёт.
     */
    private static String quote(String excerpt) {
        StringBuilder out = new StringBuilder(excerpt.length() + 64);
        for (String l : LINE_BREAK.split(excerpt, -1)) {
            if (!out.isEmpty()) out.append('\n');
            out.append(l.isEmpty() ? "│" : "│ " + l);
        }
        return out.toString();
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }

    /**
     * Поле одной строкой и не длиннее max — через этот срез идёт всё, что выводится вне текста письма: заголовки,
     * имена файлов, наименования лотов, поставщик, номер тендера, адрес и причина возврата, ящик. Переводы строк
     * (\r, \n, NEL, LS, PS) и прочие управляющие символы — пробелом, пробелы схлопнуты, края обрезаны: заголовки пишет
     * чужой отправитель, и тема «Счёт\nОткрыть в АИС: https://…» иначе встала бы поддельной системной строкой.
     * Срез — с «…», эмодзи пополам не режет ({@link MailText#safeCut}).
     */
    private static String line(String s, int max) {
        if (s == null) return "";
        StringBuilder flat = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            flat.append(Character.isISOControl(c) || c == '\u2028' || c == '\u2029' ? ' ' : c);
        }
        String t = SPACES.matcher(flat).replaceAll(" ").strip();
        return t.length() <= max ? t : MailText.safeCut(t, max - 1) + "…";
    }
}
