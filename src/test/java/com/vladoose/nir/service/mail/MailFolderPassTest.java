package com.vladoose.nir.service.mail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vladoose.nir.entity.MailCursor;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.vladoose.nir.service.mail.TestMails.mail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MailFolderPassTest {

    MailboxSession session = mock(MailboxSession.class);
    @SuppressWarnings("unchecked")
    MailFolderPass.Target<String> target = mock(MailFolderPass.Target.class);
    Logger logger = (Logger) LoggerFactory.getLogger("mail-folder-pass-test");
    ListAppender<ILoggingEvent> logs = new ListAppender<>();

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

    /** У info@ две папки со своими UID: строки лога несут приставку where — ящик и папку. */
    @Test
    void connectionLost_stopsWithWherePrefixInLog() throws Exception {
        when(session.uidValidity()).thenReturn(7L);
        when(target.cursor()).thenReturn(Optional.of(MailCursor.builder().mailbox("info@westmed.kz")
                .folder(MailCursor.SENT).uidValidity(7).lastUid(10).build()));
        when(session.uidsAfter(10, 100)).thenReturn(List.of(11L));
        when(session.fetch(11L)).thenThrow(new MessagingException("BYE"));
        when(session.isAlive()).thenReturn(false);

        String stopped = MailFolderPass.run(session, target, Instant.now(), 100, Clock.systemUTC(), logger,
                "info@westmed.kz «Отправленные»: ");

        assertThat(stopped).isEqualTo(MailFolderPass.CONNECTION_LOST);
        assertThat(logs.list).anySatisfy(ev -> assertThat(ev.getFormattedMessage())
                .startsWith("info@westmed.kz «Отправленные»: Связь с ящиком оборвалась на письме UID 11"));
        verify(target, never()).write(any(), anyLong());
    }

    /** Первый запуск: окно, письмо, пришедшее во время прохода (UID > max), — следующим проходом; курсор — на max. */
    @Test
    void firstRun_windowThenCursorToMaxTakenBeforeWindow() throws Exception {
        Instant window = Instant.parse("2026-10-06T09:00:00Z");
        when(session.uidValidity()).thenReturn(7L);
        when(target.cursor()).thenReturn(Optional.empty());
        when(session.maxUid()).thenReturn(50L);
        when(session.uidsReceivedSince(window, 100)).thenReturn(List.of(49L, 51L));
        when(session.fetch(49L)).thenReturn(mail().uid(49).build());
        when(target.write(any(), eq(7L))).thenReturn("записано");

        assertThat(MailFolderPass.run(session, target, window, 100, Clock.systemUTC(), logger, "")).isNull();

        verify(session, never()).fetch(51L);
        verify(target).written("записано");
        verify(target).moveCursorTo(7L, 50L);
    }
}
