package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;

class MailNotificationComposerTest {

    static final OffsetDateTime QUEUED = OffsetDateTime.parse("2026-10-05T09:01:00Z");
    static final ComposeContext KZ = new ComposeContext("zakup@westmed.kz", Market.KZ, "https://ais.westmed.kz", QUEUED);

    static KpSnapshot kp(String status, BigDecimal price, KpSnapshot.LotLine... lots) {
        return new KpSnapshot(534, "ТОО «Медтехника»", "sales@medtech.kz", 77, "17295275-1", false,
                List.of(lots), status, price);
    }

    static Classification sup() { return new Classification(MailClass.SUPPLIER_RESPONSE, 534L); }

    @Test
    void supplierResponse_priceParsed_exactText() {
        ParsedMail m = mail().from("Иван Петров <ivan@medtech.kz>")
                .subject("Re: [КП-534] Запрос коммерческого предложения")
                .text("Добрый день!\nЦена 3 450 000 тг, срок 30 дней.\n\nС уважением, Иван\n\n> Здравствуйте! Просим КП")
                .attachments("КП.pdf").build();

        MailNotification n = MailNotificationComposer.compose(m, sup(),
                kp("RESPONDED", new BigDecimal("3450000"), new KpSnapshot.LotLine("Аппарат УЗИ", 1)),
                KpOutcome.PRICE_PARSED, KZ);

        assertThat(n.silent()).isFalse();
        assertThat(n.text()).isEqualTo("""
                📩 Ответ поставщика · ТОО «Медтехника»
                Запрос КП №534 · тендер 17295275-1
                Лот: Аппарат УЗИ — 1 шт.
                💡 Цена распознана: 3 450 000,00 ₸ — проверьте
                От: Иван Петров <ivan@medtech.kz>
                Тема: Re: [КП-534] Запрос коммерческого предложения
                Вложения: КП.pdf

                Добрый день!
                Цена 3 450 000 тг, срок 30 дней.

                С уважением, Иван

                Открыть в АИС: https://ais.westmed.kz/tenders?openId=77&market=KZ""");
    }

    @Test
    void bounce_withRequest_exactText() {
        ParsedMail m = mail().from("MAILER-DAEMON@corp.mail.ru").subject("Undelivered Mail Returned to Sender")
                .bounce("sales@medtech.kz", "5.1.1", "550 5.1.1 <sales@medtech.kz>: Recipient address rejected: User unknown",
                        "[КП-534] Запрос КП").build();

        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.BOUNCE, 534L),
                kp("SENT", null), null, KZ);

        assertThat(n.silent()).isFalse();
        assertThat(n.text()).isEqualTo("""
                ⚠️ Письмо не доставлено · ТОО «Медтехника» (sales@medtech.kz)
                Запрос КП №534 · тендер 17295275-1
                Причина: 550 5.1.1 <sales@medtech.kz>: Recipient address rejected: User unknown
                Исправьте адрес в карточке поставщика и нажмите «Переслать» в запросах КП тендера.

                Открыть в АИС: https://ais.westmed.kz/tenders?openId=77&market=KZ""");
    }

    @Test
    void bounce_withoutRequest_recipientAndSubject_linkToInbound() {
        ParsedMail m = mail().from("postmaster@x.kz").subject("Mail failure").bounce("a@b.kz", "5.0.0", null, null).build();
        String t = MailNotificationComposer.compose(m, new Classification(MailClass.BOUNCE, null), null, null, KZ).text();
        assertThat(t).startsWith("⚠️ Письмо не доставлено · a@b.kz\nТема возврата: Mail failure\nПричина: 5.0.0")
                .endsWith("Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ");
    }

    @Test
    void outcomes_lines() {
        ParsedMail m = mail().build();
        KpSnapshot one = kp("SENT", null, new KpSnapshot.LotLine("А", 1));
        assertThat(MailNotificationComposer.compose(m, sup(), one, KpOutcome.DECLINED, KZ).text()).contains("\n⛔ Поставщик отказался\n");
        assertThat(MailNotificationComposer.compose(m, sup(), one, KpOutcome.NO_PRICE, KZ).text()).contains("\nЦену не распознали — введите вручную\n");
        assertThat(MailNotificationComposer.compose(m, sup(), kp("RESPONDED", new BigDecimal("10"), new KpSnapshot.LotLine("А", 1)),
                KpOutcome.PRICE_SET, KZ).text()).contains("\nЦена в АИС уже введена: 10,00 ₸\n");
        assertThat(MailNotificationComposer.compose(m, sup(), kp("ACCEPTED", null), KpOutcome.UNCHANGED, KZ).text())
                .contains("\nПовторное письмо — статус «Принят» не меняли\n");
        KpSnapshot three = kp("RESPONDED", null, new KpSnapshot.LotLine("А", 1), new KpSnapshot.LotLine("Б", 2),
                new KpSnapshot.LotLine("В", null));
        assertThat(MailNotificationComposer.compose(m, sup(), three, KpOutcome.MULTI_LOT, KZ).text())
                .contains("\nЛотов: 3\nЛотов несколько — цены вручную\n");
    }

    @Test
    void twoLots_listed() {
        KpSnapshot two = kp("RESPONDED", null, new KpSnapshot.LotLine("А", 1), new KpSnapshot.LotLine("Б", null));
        assertThat(MailNotificationComposer.compose(mail().build(), sup(), two, KpOutcome.MULTI_LOT, KZ).text())
                .contains("\nЛоты: А — 1 шт.; Б\n");
    }

    @Test
    void notFound_headerFromSender_linkToInbound() {
        String t = MailNotificationComposer.compose(mail().from("x@y.kz").build(),
                new Classification(MailClass.SUPPLIER_RESPONSE, 999L), null, KpOutcome.NOT_FOUND, KZ).text();
        assertThat(t).startsWith("📩 Ответ поставщика · x@y.kz\nЗапрос КП №999 в АИС не найден\n")
                .endsWith("Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ");
    }

    @Test
    void privateRequest_labelAndLink() {
        KpSnapshot pr = new KpSnapshot(5, "Дистр", null, 42, "ЧЗ-2026-0007", true, List.of(), "SENT", null);
        String t = MailNotificationComposer.compose(mail().build(), sup(), pr, KpOutcome.NO_PRICE, KZ).text();
        assertThat(t).contains("Запрос КП №5 · частная заявка ЧЗ-2026-0007")
                .endsWith("https://ais.westmed.kz/private-requests?openId=42&market=KZ");
    }

    @Test
    void autoReply_silent_shortExcerpt() {
        ParsedMail m = mail().subject("Автоответ").text("Я в отпуске до 12.10. " + "очень ".repeat(100)).build();
        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.AUTO_REPLY, 534L),
                kp("SENT", null), null, KZ);
        assertThat(n.silent()).isTrue();
        assertThat(n.text()).startsWith("🤖 Автоответ · ТОО «Медтехника»\nНа запрос КП №534 — статус запроса не меняли\n\nЯ в отпуске");
        String excerpt = n.text().split("\n\n")[1];
        assertThat(excerpt.length()).isLessThanOrEqualTo(MailNotificationComposer.AUTO_EXCERPT);
        assertThat(excerpt).endsWith("…");
    }

    @Test
    void other_silent_headerWithMailbox_attachmentsCapped() {
        ParsedMail m = mail().from("Иван <ivan@x.kz>").subject("Прайс октябрь")
                .attachments("1.pdf", "2.pdf", "3.pdf", "4.pdf", "5.pdf", "6.pdf", "7.pdf").text("Высылаем прайс").build();
        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.UNMATCHED, null), null, null, KZ);
        assertThat(n.silent()).isTrue();
        assertThat(n.text()).isEqualTo("""
                ✉️ Письмо на zakup@westmed.kz · Иван <ivan@x.kz>
                Тема: Прайс октябрь
                Вложения: 1.pdf, 2.pdf, 3.pdf, 4.pdf, 5.pdf и ещё 2

                Высылаем прайс

                Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ""");
    }

    @Test
    void broken_silent() {
        MailNotification n = MailNotificationComposer.composeBroken(
                new BrokenMail(3, null, null, null, QUEUED, "ParseException"), KZ);
        assertThat(n.silent()).isTrue();
        assertThat(n.text()).isEqualTo("""
                ✉️ Письмо на zakup@westmed.kz · отправитель не прочитан
                Тема: (тема не прочитана)
                Письмо не удалось разобрать — откройте его в почте Mail.ru.

                Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ""");
    }

    @Test
    void delayedLetter_receivedLineInMarketZone() {
        ParsedMail m = mail().receivedAt(OffsetDateTime.parse("2026-10-03T09:20:00Z")).build();
        String t = MailNotificationComposer.compose(m, new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(t).contains("\nПолучено: 03.10 14:20\n");     // Asia/Oral = UTC+5
        String fresh = MailNotificationComposer.compose(mail().build(), new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(fresh).doesNotContain("Получено:");
    }

    @Test
    void noPublicUrl_noLink_rfMarketCurrency() {
        ComposeContext rf = new ComposeContext("zakup@westmed.kz", Market.RF, "", QUEUED);
        String t = MailNotificationComposer.compose(mail().build(), sup(),
                kp("RESPONDED", new BigDecimal("100"), new KpSnapshot.LotLine("А", 1)), KpOutcome.PRICE_PARSED, rf).text();
        assertThat(t).doesNotContain("Открыть в АИС").contains("100,00 ₽");
    }

    @Test
    void hugeBody_cappedTotal_andHtmlWithoutCss() {
        ParsedMail big = mail().text("слово ".repeat(5000)).build();
        String t = MailNotificationComposer.compose(big, new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(t.length()).isLessThanOrEqualTo(MailNotificationComposer.MAX_TEXT);
        assertThat(t).contains("…");

        ParsedMail html = mail().html("<style>.x{color:red}</style><p>Цена 7 000 тг</p><blockquote>наше письмо</blockquote>").build();
        String h = MailNotificationComposer.compose(html, new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
        assertThat(h).contains("Цена 7 000 тг").doesNotContain("color").doesNotContain("наше письмо");
    }

    /** Ответ поставщика без найденного запроса и без отправителя — в шапке не пустое место. */
    @Test
    void notFound_blankSender_placeholderInHeader() {
        String t = MailNotificationComposer.compose(mail().from("").build(),
                new Classification(MailClass.SUPPLIER_RESPONSE, 999L), null, KpOutcome.NOT_FOUND, KZ).text();
        assertThat(t).startsWith("📩 Ответ поставщика · отправитель не указан\nЗапрос КП №999 в АИС не найден\n");
    }

    /**
     * Отчёт об отложенной доставке (Status 4.x.x: сервер получателя ещё повторяет попытки) — не «не доставлено»:
     * без звука и без совета исправить адрес — адрес, может быть, верный.
     */
    @Test
    void delayedDsn_withRequest_notUndelivered_silent() {
        ParsedMail m = mail().from("MAILER-DAEMON@corp.mail.ru").subject("Delayed Mail (still being retried)")
                .bounce("sales@medtech.kz", "4.4.1", "421 4.4.1 Connection timed out", "[КП-534] Запрос КП").build();

        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.BOUNCE, 534L),
                kp("SENT", null), null, KZ);

        assertThat(n.silent()).isTrue();
        assertThat(n.text()).startsWith("⏳ Доставка задерживается · ТОО «Медтехника» (sales@medtech.kz)")
                .contains("Сервер получателя ещё повторяет доставку")
                .doesNotContain("Исправьте адрес")
                .doesNotContain("не доставлено");
        assertThat(n.text()).isEqualTo("""
                ⏳ Доставка задерживается · ТОО «Медтехника» (sales@medtech.kz)
                Запрос КП №534 · тендер 17295275-1
                Причина: 421 4.4.1 Connection timed out
                Сервер получателя ещё повторяет доставку — если не получится, придёт отдельное уведомление.

                Открыть в АИС: https://ais.westmed.kz/tenders?openId=77&market=KZ""");
    }

    @Test
    void delayedDsn_withoutRequest_silent_linkToInbound() {
        ParsedMail m = mail().from("postmaster@x.kz").subject("Delivery delayed").bounce("a@b.kz", "4.7.1", null, null).build();

        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.BOUNCE, null), null, null, KZ);

        assertThat(n.silent()).isTrue();
        assertThat(n.text()).isEqualTo("""
                ⏳ Доставка задерживается · a@b.kz
                Тема возврата: Delivery delayed
                Причина: 4.7.1
                Сервер получателя ещё повторяет доставку — если не получится, придёт отдельное уведомление.

                Открыть в АИС: https://ais.westmed.kz/inbound?market=KZ""");
    }

    /** Возврат без кода статуса (узнан по отправителю, частей DSN нет) — «не доставлено», со звуком, адрес — из запроса. */
    @Test
    void bounceWithoutStatus_undelivered_withSound() {
        ParsedMail m = mail().from("MAILER-DAEMON@x.kz").subject("Undeliverable: [КП-534] Запрос КП").build();

        MailNotification n = MailNotificationComposer.compose(m, new Classification(MailClass.BOUNCE, 534L),
                kp("SENT", null), null, KZ);

        assertThat(n.silent()).isFalse();
        assertThat(n.text()).startsWith("⚠️ Письмо не доставлено · ТОО «Медтехника» (sales@medtech.kz)\n")
                .contains("\nИсправьте адрес в карточке поставщика");
    }

    /**
     * Срез поля не разрезает эмодзи: одиночная половинка суррогатной пары — невалидный текст, Telegram отклонит
     * уведомление (400), и очередь встанет на нём. Граница среза темы (300) не угадывается, а перебирается: эмодзи
     * встаёт на каждое место вокруг неё.
     */
    @Test
    void subjectCut_neverSplitsEmoji() {
        for (int k = 290; k <= 302; k++) {
            ParsedMail m = mail().subject("x".repeat(k) + "😀" + "y").build();
            String t = MailNotificationComposer.compose(m, new Classification(MailClass.UNMATCHED, null), null, null, KZ).text();
            assertThat(loneSurrogate(t)).as("эмодзи после %d символов темы", k).isEqualTo(-1);
        }
    }

    /** Общий предел текста режет так же: поле без своего среза (имя поставщика из справочника) упирается в MAX_TEXT. */
    @Test
    void totalCap_neverSplitsEmoji() {
        for (int k = 3470; k <= 3500; k++) {
            KpSnapshot kp = new KpSnapshot(534, "x".repeat(k) + "😀" + "y".repeat(100), "sales@medtech.kz", 77,
                    "17295275-1", false, List.of(), "SENT", null);
            String t = MailNotificationComposer.compose(mail().build(), sup(), kp, KpOutcome.NO_PRICE, KZ).text();
            assertThat(t.length()).isLessThanOrEqualTo(MailNotificationComposer.MAX_TEXT);
            assertThat(t).endsWith("…");
            assertThat(loneSurrogate(t)).as("эмодзи после %d символов имени", k).isEqualTo(-1);
        }
    }

    /** Индекс одиночной половинки суррогатной пары; -1 — таких нет. */
    private static int loneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return i;
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return i;
            }
        }
        return -1;
    }
}
