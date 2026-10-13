package com.vladoose.nir.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vladoose.nir.entity.MailCursor;
import com.vladoose.nir.entity.MailCursorKey;
import com.vladoose.nir.repository.MailCursorRepository;
import com.vladoose.nir.service.mail.*;
import jakarta.mail.Folder;
import jakarta.mail.FolderNotFoundException;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.*;
import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClientMailReceiveServiceTest {

    static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");
    static final String BOX = "info@westmed.kz";

    ClientMailbox mailbox = mock(ClientMailbox.class);
    MailboxSession inbox = mock(MailboxSession.class);
    MailboxSession sent = mock(MailboxSession.class);
    ClientMailWriter writer = mock(ClientMailWriter.class);
    MailCursorRepository cursors = mock(MailCursorRepository.class);
    Logger logger = (Logger) LoggerFactory.getLogger(ClientMailReceiveService.class);
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

    static ClientMailSettings settings(boolean enabled) {
        return new ClientMailSettings(enabled, "imap.mail.ru", 993, BOX, "pw", "imaps", "KZ", 7, 25, "*.mail.ru",
                "info@westmed.kz", "zakup@westmed.kz");
    }

    ClientMailReceiveService service(boolean enabled) {
        return new ClientMailReceiveService(settings(enabled), mailbox, writer, cursors, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static ClientMailWriter.Result result(ClientMailWriter.Kind kind) { return new ClientMailWriter.Result(kind, null); }

    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        logger.addAppender(logs);
        logger.setAdditive(false);
        when(mailbox.open(MailFolder.INBOX)).thenReturn(inbox);
        when(mailbox.open(MailFolder.SENT)).thenReturn(sent);
        when(inbox.uidValidity()).thenReturn(5L);
        when(sent.uidValidity()).thenReturn(9L);
        when(inbox.isAlive()).thenReturn(true);
        when(sent.isAlive()).thenReturn(true);
        when(writer.write(any(), any(), anyLong())).thenReturn(result(ClientMailWriter.Kind.CONVERSATION));
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logger.setAdditive(true);
    }

    void cursor(MailFolder f, long validity, long last) {
        when(cursors.findById(new MailCursorKey(BOX, f.cursorKey()))).thenReturn(Optional.of(
                MailCursor.builder().mailbox(BOX).folder(f.cursorKey()).uidValidity(validity).lastUid(last).build()));
    }

    @Test
    void disabled_noConnection() throws Exception {
        assertThat(service(false).poll().enabled()).isFalse();
        verify(mailbox, never()).open(any());
    }

    /** Сначала «Входящие» (письмо клиента заводит обращение), потом «Отправленные» (наш ответ берёт его в работу). */
    @Test
    void inboxThenSent_eachWithItsCursor() throws Exception {
        cursor(MailFolder.INBOX, 5, 10);
        cursor(MailFolder.SENT, 9, 3);
        when(inbox.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(sent.uidsAfter(3, 100)).thenReturn(List.of(4L));
        when(inbox.fetch(11L)).thenReturn(mail().uid(11).build());
        when(sent.fetch(4L)).thenReturn(mail().uid(4).build());
        when(writer.write(any(), eq(MailFolder.SENT), anyLong())).thenReturn(result(ClientMailWriter.Kind.OUTGOING));

        ClientMailReceiveService.Result r = service(true).poll();

        InOrder order = inOrder(writer);
        order.verify(writer).write(argThat(m -> m != null && m.uid() == 11), eq(MailFolder.INBOX), eq(5L));
        order.verify(writer).write(argThat(m -> m != null && m.uid() == 4), eq(MailFolder.SENT), eq(9L));
        assertThat(r.conversations()).isEqualTo(1);
        assertThat(r.outgoing()).isEqualTo(1);
        assertThat(r.stopped()).isNull();
    }

    @Test
    void firstRun_windowIsSevenDays_cursorsToMax() throws Exception {
        when(cursors.findById(any())).thenReturn(Optional.empty());
        when(inbox.maxUid()).thenReturn(20L);
        when(sent.maxUid()).thenReturn(0L);
        Instant window = NOW.minus(Duration.ofDays(7));
        when(inbox.uidsReceivedSince(window, 100)).thenReturn(List.of(19L));
        when(sent.uidsReceivedSince(window, 100)).thenReturn(List.of());
        when(inbox.fetch(19L)).thenReturn(mail().uid(19).build());

        service(true).poll();

        verify(writer).write(any(), eq(MailFolder.INBOX), eq(5L));
        verify(writer).moveCursorTo(MailFolder.INBOX, 5L, 20L);
        verify(writer).moveCursorTo(MailFolder.SENT, 9L, 0L);
    }

    @Test
    void newUidValidity_isFirstRunAgain() throws Exception {
        cursor(MailFolder.INBOX, 4, 100);
        cursor(MailFolder.SENT, 9, 3);
        when(inbox.maxUid()).thenReturn(2L);
        when(inbox.uidsReceivedSince(any(), anyInt())).thenReturn(List.of());

        service(true).poll();

        verify(inbox, never()).uidsAfter(anyLong(), anyInt());
        verify(writer).moveCursorTo(MailFolder.INBOX, 5L, 2L);
    }

    @Test
    void sentFolderMissing_inboxStillProcessed_warnOnce() throws Exception {
        cursor(MailFolder.INBOX, 5, 10);
        when(inbox.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(inbox.fetch(11L)).thenReturn(mail().uid(11).build());
        when(mailbox.open(MailFolder.SENT)).thenThrow(new FolderNotFoundException((Folder) null, "папка «Отправленные» не найдена"));
        ClientMailReceiveService s = service(true);

        ClientMailReceiveService.Result r = s.poll();
        s.poll();

        assertThat(r.stopped()).isNull();
        verify(writer, times(2)).write(any(), eq(MailFolder.INBOX), anyLong());
        assertThat(logs.list).filteredOn(ev -> ev.getFormattedMessage().contains("«Отправленные»: папка не найдена")).hasSize(1);
    }

    @Test
    void inboxLoginFails_sentNotOpened_serverTextKept() throws Exception {
        when(mailbox.open(MailFolder.INBOX)).thenThrow(new MessagingException("AUTHENTICATIONFAILED"));

        ClientMailReceiveService.Result r = service(true).poll();

        assertThat(r.stopped()).isEqualTo("ошибка почты: AUTHENTICATIONFAILED");
        verify(mailbox, never()).open(MailFolder.SENT);
    }

    @Test
    void connectionLostInInbox_sentNotOpened() throws Exception {
        cursor(MailFolder.INBOX, 5, 10);
        when(inbox.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(inbox.fetch(11L)).thenThrow(new MessagingException("BYE"));
        when(inbox.isAlive()).thenReturn(false);

        assertThat(service(true).poll().stopped()).isEqualTo(MailFolderPass.CONNECTION_LOST);
        verify(mailbox, never()).open(MailFolder.SENT);
    }

    @Test
    void dbDownOnCursor_stopsWithDbText_noAddressesInLog() throws Exception {
        when(cursors.findById(any())).thenThrow(new DataAccessResourceFailureException("db down: client@clinic.kz"));

        ClientMailReceiveService.Result r = service(true).poll();

        assertThat(r.stopped()).isEqualTo("база данных недоступна (DataAccessResourceFailureException)");
        verify(mailbox, never()).open(MailFolder.SENT);
        assertThat(logs.list).allSatisfy(ev -> assertThat(ev.getFormattedMessage()).doesNotContain("client@clinic.kz"));
    }
}
