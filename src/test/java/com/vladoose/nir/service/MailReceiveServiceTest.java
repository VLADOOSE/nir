package com.vladoose.nir.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vladoose.nir.dto.response.PollResultResponse;
import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailReceiveServiceTest {

    static final Instant NOW = Instant.parse("2026-10-05T09:00:00Z");

    MailboxConnector connector = mock(MailboxConnector.class);
    MailboxSession session = mock(MailboxSession.class);
    MailIngestWriter writer = mock(MailIngestWriter.class);
    MailCursorRepository cursors = mock(MailCursorRepository.class);
    MailTelegramNotifier notifier = mock(MailTelegramNotifier.class);
    /** Логи сервиса — в список, а не в вывод сборки: тесты нарочно ломают проход, и WARN со стеком там ожидаемы. */
    Logger logger = (Logger) LoggerFactory.getLogger(MailReceiveService.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    MailReceiveService service(boolean enabled) {
        return new MailReceiveService(connector, writer, cursors, notifier, enabled, Market.KZ, 60,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static MailIngestWriter.WriteResult ok(MailClass c) { return new MailIngestWriter.WriteResult(c, null, 1L, false); }

    /**
     * Письмо с этим UID — для нескольких заглушек одного метода подряд. Null-безопасно: ставя следующую заглушку, Mockito
     * прогоняет прежние сопоставители по её аргументу-заготовке null, и «m.uid()» упал бы NPE прямо в подготовке теста.
     */
    static ParsedMail withUid(long uid) { return argThat(m -> m != null && m.uid() == uid); }

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
        logger.setAdditive(false);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
    }

    @BeforeEach
    void setUp() throws Exception {
        when(writer.mailbox()).thenReturn("zakup@westmed.kz");
        when(notifier.flush()).thenReturn(new MailTelegramNotifier.FlushResult(0, 0, null));
        when(connector.open()).thenReturn(session);
        when(session.uidValidity()).thenReturn(7L);
        when(session.isAlive()).thenReturn(true);
        when(writer.write(any(), anyLong())).thenReturn(ok(MailClass.UNMATCHED));
    }

    void cursorAt(long uidValidity, long lastUid) {
        when(cursors.findById("zakup@westmed.kz")).thenReturn(Optional.of(
                MailCursor.builder().mailbox("zakup@westmed.kz").uidValidity(uidValidity).lastUid(lastUid).build()));
    }

    @Test
    void disabled_noConnection() throws Exception {
        PollResultResponse r = service(false).poll();
        assertThat(r.isEnabled()).isFalse();
        assertThat(r.isOk()).isFalse();
        verify(connector, never()).open();
    }

    @Test
    void firstRun_windowThenCursorToMaxTakenBeforeWindow() throws Exception {
        when(cursors.findById("zakup@westmed.kz")).thenReturn(Optional.empty());
        when(session.maxUid()).thenReturn(50L);
        when(session.uidsReceivedSince(NOW.minus(Duration.ofMinutes(60)), MailReceiveService.MAX_PER_PASS)).thenReturn(List.of(48L, 49L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());

        service(true).poll();

        InOrder order = inOrder(session, writer);
        order.verify(session).maxUid();
        order.verify(session).uidsReceivedSince(any(), anyInt());
        order.verify(writer).write(argThat(m -> m.uid() == 48), eq(7L));
        order.verify(writer).write(argThat(m -> m.uid() == 49), eq(7L));
        order.verify(writer).moveCursorTo(7L, 50L);
    }

    /** Письмо пришло во время первого прохода (UID больше снятого max): его не трогаем — уйдёт следующим проходом. */
    @Test
    void firstRun_letterAfterMaxSnapshot_leftForNextPass() throws Exception {
        when(cursors.findById("zakup@westmed.kz")).thenReturn(Optional.empty());
        when(session.maxUid()).thenReturn(50L);
        when(session.uidsReceivedSince(any(), anyInt())).thenReturn(List.of(49L, 51L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());

        service(true).poll();

        verify(session, never()).fetch(51L);
        verify(writer, times(1)).write(any(), anyLong());
        verify(writer).moveCursorTo(7L, 50L);
    }

    @Test
    void newUidValidity_isFirstRun() throws Exception {
        cursorAt(6, 100);
        when(session.maxUid()).thenReturn(3L);
        when(session.uidsReceivedSince(any(), anyInt())).thenReturn(List.of());

        service(true).poll();

        verify(session, never()).uidsAfter(anyLong(), anyInt());
        verify(writer).moveCursorTo(7L, 3L);
    }

    @Test
    void normalPass_afterCursor_noMoveToMax() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(10, MailReceiveService.MAX_PER_PASS)).thenReturn(List.of(11L, 12L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());

        PollResultResponse r = service(true).poll();

        verify(writer, times(2)).write(any(), eq(7L));
        verify(writer, never()).moveCursorTo(anyLong(), anyLong());
        assertThat(r.getFetched()).isEqualTo(2);
        assertThat(r.isOk()).isTrue();
        assertThat(r.isPending()).isFalse();
    }

    @Test
    void brokenMessage_whileAlive_writesBrokenAndContinues() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad mime"));
        when(session.fetch(12L)).thenReturn(mail().uid(12).build());
        BrokenMail broken = new BrokenMail(11, null, "a@b.kz", "S", OffsetDateTime.now(), "MessagingException");
        when(session.envelope(eq(11L), any())).thenReturn(broken);

        PollResultResponse r = service(true).poll();

        verify(writer).writeBroken(broken, 7L);
        verify(writer).write(argThat(m -> m.uid() == 12), eq(7L));
        assertThat(r.getBroken()).isEqualTo(1);
        assertThat(r.isOk()).isTrue();                          // битое письмо — не сбой прохода
        assertThat(r.getMessage()).isEqualTo("Новых писем: 2 (прочих — 1, не разобрано — 1)");
        // письмо разбирается один раз (курсор уходит дальше) — в лог со стеком: дефект разбора без него не найти
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getThrowableProxy()).isNotNull());
    }

    @Test
    void fetchFails_connectionDead_stopsPass() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("connection reset"));
        when(session.isAlive()).thenReturn(false);

        PollResultResponse r = service(true).poll();

        verify(writer, never()).writeBroken(any(), anyLong());
        verify(session, never()).fetch(12L);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).isEqualTo("Новых писем: 0; связь с почтой оборвалась"
                + " — проход остановлен, повтор следующим проходом");
    }

    @Test
    void writerInfrastructureFailure_stopsPass_noBrokenRow() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());
        when(writer.write(argThat(m -> m.uid() == 11), anyLong())).thenThrow(new DataAccessResourceFailureException("db down"));

        PollResultResponse r = service(true).poll();

        verify(writer, never()).writeBroken(any(), anyLong());
        verify(session, never()).fetch(12L);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).isEqualTo("Новых писем: 0; база данных недоступна"
                + " (DataAccessResourceFailureException) — проход остановлен, повтор следующим проходом");
        // база лежит — проход повторяется раз в минуту: в лог только класс, без стека и текста исключения
        assertThat(logs.list).isNotEmpty().allSatisfy(ev -> {
            assertThat(ev.getThrowableProxy()).isNull();
            assertThat(ev.getFormattedMessage()).doesNotContain("db down");
        });
    }

    @Test
    void writerOtherFailure_brokenRowFromParsedFields_continues() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).from("x@y.kz").subject("Тема").build());
        when(writer.write(argThat(m -> m.uid() == 11), anyLong())).thenThrow(new IllegalStateException("bug"));

        service(true).poll();

        verify(writer).writeBroken(argThat(b -> b.uid() == 11 && "x@y.kz".equals(b.from()) && "Тема".equals(b.subject())
                && "IllegalStateException".equals(b.errorClass())), eq(7L));
        verify(writer).write(argThat(m -> m.uid() == 12), eq(7L));
    }

    /**
     * Короткая строка с полями письма не записалась (не база): в полях и бывает причина (символ, который база не хранит),
     * поэтому ещё раз — минимальная строка без них. Записалась — письмо пройдено, следующее идёт дальше (R21).
     */
    @Test
    void shortRowFailsOnce_minimalRowWritten_nextUidProcessed() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad mime"));
        when(session.fetch(12L)).thenReturn(mail().uid(12).build());
        OffsetDateTime at = OffsetDateTime.parse("2026-10-05T08:30:00Z");
        when(session.envelope(eq(11L), any())).thenReturn(new BrokenMail(11, "<x@y.kz>", "a@b.kz", "S", at, "MessagingException"));
        doThrow(new IllegalStateException("bad value")).doNothing().when(writer).writeBroken(any(), anyLong());

        PollResultResponse r = service(true).poll();

        ArgumentCaptor<BrokenMail> rows = ArgumentCaptor.forClass(BrokenMail.class);
        verify(writer, times(2)).writeBroken(rows.capture(), eq(7L));
        assertThat(rows.getAllValues().get(1)).isEqualTo(new BrokenMail(11, null, null, null, at, "MessagingException"));
        verify(writer).write(argThat(m -> m.uid() == 12), eq(7L));
        assertThat(r.getBroken()).isEqualTo(1);
        assertThat(r.isOk()).isTrue();
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getThrowableProxy()).isNotNull());   // разовый сбой — со стеком
    }

    /** Без даты получения минимальная строка получает «сейчас». */
    @Test
    void minimalRow_withoutReceivedAt_usesNow() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad mime"));
        when(session.envelope(eq(11L), any())).thenReturn(new BrokenMail(11, null, "a@b.kz", "S", null, "MessagingException"));
        doThrow(new IllegalStateException("bad value")).doNothing().when(writer).writeBroken(any(), anyLong());

        service(true).poll();

        verify(writer).writeBroken(new BrokenMail(11, null, null, null, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
                "MessagingException"), 7L);
    }

    /**
     * Не записалась и минимальная строка — проход стоп, в ответе — UID письма. То же письмо упадёт и на следующем
     * проходе (раз в минуту), поэтому в лог — без стека: класс и UID.
     */
    @Test
    void shortRowAndMinimalRowFail_stopsPass_namesUid_logWithoutStack() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad"));
        when(session.envelope(eq(11L), any())).thenReturn(new BrokenMail(11, null, "a@b.kz", "S", OffsetDateTime.now(), "X"));
        doThrow(new IllegalStateException("db")).when(writer).writeBroken(any(), anyLong());

        PollResultResponse r = service(true).poll();

        verify(writer, times(2)).writeBroken(any(), anyLong());
        verify(session, never()).fetch(12L);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).isEqualTo("Новых писем: 0; письмо UID 11 не записалось (IllegalStateException)"
                + " — проход остановлен, повтор следующим проходом");
        assertThat(logs.list).isNotEmpty().allSatisfy(ev -> assertThat(ev.getThrowableProxy()).isNull());
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getFormattedMessage())
                .contains("UID 11").contains("IllegalStateException"));
    }

    /** Короткая строка не записалась из-за базы — без второй попытки: стоп с текстом про базу. */
    @Test
    void shortRowInfraFailure_stopsWithDbText_noRetry() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad"));
        when(session.envelope(eq(11L), any())).thenReturn(new BrokenMail(11, null, "a@b.kz", "S", OffsetDateTime.now(), "X"));
        doThrow(new DataAccessResourceFailureException("db down")).when(writer).writeBroken(any(), anyLong());

        PollResultResponse r = service(true).poll();

        verify(writer, times(1)).writeBroken(any(), anyLong());
        verify(session, never()).fetch(12L);
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).isEqualTo("Новых писем: 0; база данных недоступна"
                + " (DataAccessResourceFailureException) — проход остановлен, повтор следующим проходом");
    }

    /** База легла на второй, минимальной попытке — тоже стоп с текстом про базу. */
    @Test
    void minimalRowInfraFailure_stopsWithDbText() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenThrow(new MessagingException("bad"));
        when(session.envelope(eq(11L), any())).thenReturn(new BrokenMail(11, null, "a@b.kz", "S", OffsetDateTime.now(), "X"));
        doThrow(new IllegalStateException("bad value")).doThrow(new DataAccessResourceFailureException("db down"))
                .when(writer).writeBroken(any(), anyLong());

        PollResultResponse r = service(true).poll();

        verify(writer, times(2)).writeBroken(any(), anyLong());
        verify(session, never()).fetch(12L);
        assertThat(r.getMessage()).isEqualTo("Новых писем: 0; база данных недоступна"
                + " (DataAccessResourceFailureException) — проход остановлен, повтор следующим проходом");
    }

    @Test
    void deletedBetweenSearchAndFetch_skipped() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L));
        when(session.fetch(11L)).thenReturn(null);
        when(session.fetch(12L)).thenReturn(mail().uid(12).build());

        service(true).poll();

        verify(writer, times(1)).write(any(), anyLong());
    }

    @Test
    void connectFails_flushStillRuns_messageSaysSo() throws Exception {
        when(connector.open()).thenThrow(new MessagingException("AUTHENTICATIONFAILED"));
        when(notifier.flush()).thenReturn(new MailTelegramNotifier.FlushResult(2, 0, null));

        PollResultResponse r = service(true).poll();

        verify(notifier).flush();
        assertThat(r.getMessage()).startsWith("Ошибка подключения к почте: AUTHENTICATIONFAILED")
                .contains("Telegram: отправлено 2, ждут 0");
        assertThat(r.getTelegramSent()).isEqualTo(2);
        assertThat(r.isOk()).isFalse();
        assertThat(r.isPending()).isFalse();
    }

    /**
     * База легла до первого письма (курсор не прочитался): это не «ошибка подключения к почте», и текст исключения базы
     * в ответ не идёт — свой текст (спека §3.4).
     */
    @Test
    void cursorReadFails_db_ownText_notMailConnectionError() throws Exception {
        when(cursors.findById(anyString())).thenThrow(
                new DataAccessResourceFailureException("Connection to 127.0.0.1:5432 refused"));

        PollResultResponse r = service(true).poll();

        verify(writer, never()).write(any(), anyLong());
        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).isEqualTo("Новых писем: 0; база данных недоступна"
                + " (DataAccessResourceFailureException) — проход остановлен, повтор следующим проходом");
    }

    /**
     * Внутренняя ошибка прохода (не почта и не база): в ответ — свой текст, без текста исключения (в нём бывают SQL,
     * значения, адреса); подробности — в логе (спека §3.4).
     */
    @Test
    void passInternalError_ownText_noRawText() throws Exception {
        when(cursors.findById(anyString())).thenThrow(
                new InvalidDataAccessResourceUsageException("bad SQL grammar [select * from mail_cursor where secret]"));

        PollResultResponse r = service(true).poll();

        assertThat(r.isOk()).isFalse();
        assertThat(r.getMessage()).isEqualTo("Ошибка приёма почты: внутренняя ошибка"
                + " (InvalidDataAccessResourceUsageException) — подробности в логе сервера");
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getFormattedMessage())
                .contains("InvalidDataAccessResourceUsageException").contains("bad SQL grammar"));
    }

    /** Сбой самой очереди Telegram (база) не съедает итог прохода по почте (R18); в лог — только класс. */
    @Test
    void flushThrows_imapSummaryKept_queueUnavailable_logClassOnly() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());
        when(notifier.flush()).thenThrow(new DataAccessResourceFailureException("db down: секретные подробности"));

        PollResultResponse r = service(true).poll();

        assertThat(r.getFetched()).isEqualTo(1);
        assertThat(r.isOk()).isTrue();                          // почта прочитана; Telegram — своей строкой
        assertThat(r.getTelegramSent()).isZero();
        assertThat(r.getMessage()).isEqualTo("Новых писем: 1 (прочих — 1); Telegram: отправлено 0, ждут 0"
                + " — Telegram: очередь недоступна (DataAccessResourceFailureException)");
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getFormattedMessage())
                .contains("DataAccessResourceFailureException"));
        assertThat(logs.list).allSatisfy(ev -> {
            assertThat(ev.getFormattedMessage()).doesNotContain("секретные");
            assertThat(ev.getThrowableProxy()).isNull();
        });
    }

    @Test
    void countsAndSummary() throws Exception {
        cursorAt(7, 10);
        when(session.uidsAfter(anyLong(), anyInt())).thenReturn(List.of(11L, 12L, 13L, 14L));
        when(session.fetch(anyLong())).thenAnswer(inv -> mail().uid(inv.getArgument(0)).build());
        when(writer.write(withUid(11), anyLong())).thenReturn(ok(MailClass.SUPPLIER_RESPONSE));
        when(writer.write(withUid(12), anyLong())).thenReturn(ok(MailClass.BOUNCE));
        when(writer.write(withUid(13), anyLong())).thenReturn(ok(MailClass.SITE_NOTIFICATION));
        when(writer.write(withUid(14), anyLong())).thenReturn(new MailIngestWriter.WriteResult(null, null, null, true));
        when(notifier.flush()).thenReturn(new MailTelegramNotifier.FlushResult(1, 1, "Telegram: HTTP 400 — Bad Request"));

        PollResultResponse r = service(true).poll();

        assertThat(r.getFetched()).isEqualTo(2);
        assertThat(r.getSupplierResponses()).isEqualTo(1);
        assertThat(r.getBounces()).isEqualTo(1);
        assertThat(r.getSkippedSiteNotifications()).isEqualTo(1);
        assertThat(r.getMessage()).isEqualTo("Новых писем: 2 (ответов поставщиков — 1, не доставлено — 1); "
                + "уведомлений сайта о заявках пропущено: 1; Telegram: отправлено 1, ждут 1 — Telegram: HTTP 400 — Bad Request");
    }
}
