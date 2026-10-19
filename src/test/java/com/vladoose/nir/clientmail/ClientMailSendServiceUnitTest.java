package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ClientMailSendRequest;
import com.vladoose.nir.dto.response.ClientMailSendResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.UserAccountRepository;
import com.vladoose.nir.service.*;
import com.vladoose.nir.service.mail.ClientMailSender;
import com.vladoose.nir.service.mail.ClientSentCopy;
import com.vladoose.nir.service.mail.ClientSmtpSettings;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.net.ConnectException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Ветки отправки, которые нечем вызвать на настоящем SMTP и базе: обрыв SMTP и упавшая запись (спека §3.5, §13 п. 1–2). */
class ClientMailSendServiceUnitTest {

    final ClientSmtpSettings smtp = new ClientSmtpSettings("127.0.0.1", 3025, false, "info@westmed.kz", "secret", "KZ", "West-Med", true);
    final ClientMailSender sender = mock(ClientMailSender.class);
    final ClientSentCopy copy = mock(ClientSentCopy.class);
    final ClientLetterRecorder recorder = mock(ClientLetterRecorder.class);
    final ReplyTemplateService templates = mock(ReplyTemplateService.class);
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final ClientMailSendService service = new ClientMailSendService(smtp, sender, copy, recorder, mock(ClientMailComposeService.class),
            mock(ChatMessageRepository.class), mock(LeadService.class), mock(PrivateRequestService.class),
            mock(ClientOfferService.class), mock(ReadyOfferService.class), templates, users);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        when(sender.session()).thenReturn(Session.getInstance(new Properties()));
        when(templates.signatureFor(any(), any())).thenReturn("");
        when(users.findByUsername(any())).thenReturn(Optional.empty());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ClientMailSendRequest req(String token) {
        return new ClientMailSendRequest(null, null, "zz@clinic.kz", "Тема", "Текст", null, null, List.of(), token);
    }

    @Test
    void smtpDown_502_notRecordedNoCopy_tokenReleasedForRetry() {
        doThrow(new MailSendException("Mail server connection failed", new MessagingException("x", new ConnectException())))
                .doNothing().when(sender).send(any());
        assertThatThrownBy(() -> service.send(req("t1"), List.of(), "zz")).isInstanceOf(UpstreamException.class)
                .hasMessage(ClientMailSender.DOWN);
        verifyNoInteractions(recorder, copy);
        when(recorder.record(any())).thenReturn(new ClientLetterRecorder.Result(List.of(1L), List.of(2L), List.of()));
        assertThat(service.send(req("t1"), List.of(), "zz").recorded()).isTrue();
    }

    @Test
    void recordFailsAfterSend_200WithWarning_sameTokenStays409() {
        when(recorder.record(any())).thenThrow(new IllegalStateException("db"));
        ClientMailSendResponse r = service.send(req("t2"), List.of(), "zz");
        assertThat(r.recorded()).isFalse();
        assertThat(r.warning()).isEqualTo(ClientMailSendService.NOT_RECORDED);
        verify(sender, times(1)).send(any());
        verify(copy).store(any());
        assertThatThrownBy(() -> service.send(req("t2"), List.of(), "zz")).isInstanceOf(ConflictException.class);
    }
}
