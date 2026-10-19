package com.vladoose.nir.execution;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.DealFileMeta;
import com.vladoose.nir.dto.response.ExecutionFileCandidate;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.execution.DealExecutionService;
import com.vladoose.nir.service.execution.DealExecutionService.PaymentInput;
import com.vladoose.nir.service.execution.DealExecutionService.Upload;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.execution.ExecutionTestData.line;
import static com.vladoose.nir.execution.ExecutionTestData.offer;
import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Файлы этапов (спека 2026-10-19-deal-execution §4.4, §7): место, повтор, переписка в обе стороны, гарды. */
@SpringBootTest
@Transactional
@WithMockUser(username = "vlad", roles = "ADMIN")
class DealExecutionFilesTest {

    @Autowired DealExecutionService service;
    @Autowired PrivateRequestService requests;
    @Autowired DistributorRepository distributors;
    @Autowired ClientOfferRepository offers;
    @Autowired DealFileRepository files;
    @Autowired DealPaymentRepository payments;
    @Autowired DealExecutionRepository executions;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.now(Market.KZ.zone());
    static final OffsetDateTime LEAD_AT = OffsetDateTime.parse("2026-10-14T09:00:00Z");
    static final byte[] PDF = "%PDF-1.4 договор".getBytes(StandardCharsets.UTF_8);

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist() {
        return distributors.save(Distributor.builder().name("ZZ Файлы " + System.nanoTime()).build());
    }

    private Tender accepted(Distributor... ds) {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Аппарат", null, 1, null, null, null)), LinesMode.APPEND);
        ClientOffer o = offer(t, ClientOfferStatus.ACCEPTED, "1000", 1);
        for (Distributor d : ds) line(o, d.getId(), "100", "1");
        offers.save(o);
        return t;
    }

    /** Переписка обращения заявки: почтовый чат с файлом клиента (IN) и нашим (OUT). */
    private Chat chatOf(Tender t) {
        Chat c = chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account("info@westmed.kz")
                .externalChatId("zz" + System.nanoTime() + "@clinic.kz").title("Клиника").build());
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос").contactName("Клиент").status(LeadStatus.CONVERTED).chat(c).privateRequest(t)
                .receivedAt(LEAD_AT).build());
        return c;
    }

    private ChatAttachment chatFile(Chat c, LeadDirection dir, String at, String name) {
        ChatMessage m = messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(dir)
                .type(ChatMessageType.EMAIL).subject("Договор").body("Во вложении").sentAt(OffsetDateTime.parse(at)).build());
        return attachments.save(ChatAttachment.builder().message(m).fileName(name).mimeType("application/pdf")
                .sizeBytes((long) PDF.length).content(PDF).build());
    }

    private List<DealFileMeta> meta(Tender t) {
        em.flush();
        return files.findMetaByTenderId(t.getId());
    }

    @Test
    void sameFileTwiceSamePlace_oneRow_otherPlace_newRow() {
        Tender t = accepted();
        Upload up = new Upload("Договор 42.pdf", "application/pdf", PDF);
        service.attach(t.getId(), up, null, DealFileStage.CONTRACT, null, null, "vlad");
        service.attach(t.getId(), up, null, DealFileStage.CONTRACT, null, null, "vlad");
        assertThat(meta(t)).hasSize(1);
        service.attach(t.getId(), up, null, DealFileStage.DOCS, null, null, "vlad");
        assertThat(meta(t)).extracting(DealFileMeta::stage).containsExactly(DealFileStage.CONTRACT, DealFileStage.DOCS);
        assertThat(meta(t).get(0).uploadedBy()).isEqualTo("vlad");
        assertThat(executions.findById(t.getId())).isPresent();   // файл заводит строку исполнения (решение 7)
    }

    @Test
    void candidatesBothDirections_attachFromChatKeepsLink() {
        Tender t = accepted();
        Chat c = chatOf(t);
        chatFile(c, LeadDirection.OUT, "2026-10-15T07:00:00Z", "Счёт.pdf");
        ChatAttachment signed = chatFile(c, LeadDirection.IN, "2026-10-16T07:00:00Z", "Договор подписан.pdf");
        chatFile(c, LeadDirection.IN, "2026-10-13T07:00:00Z", "до обращения.pdf");   // раньше эпизода

        List<ExecutionFileCandidate> list = service.candidates(t.getId());
        assertThat(list).extracting(ExecutionFileCandidate::fileName).containsExactly("Договор подписан.pdf", "Счёт.pdf");
        assertThat(list).extracting(ExecutionFileCandidate::direction).containsExactly("IN", "OUT");

        service.attach(t.getId(), null, signed.getId(), DealFileStage.CONTRACT, null, null, "vlad");
        em.flush();
        DealFile f = files.findById(meta(t).get(0).id()).orElseThrow();
        assertThat(f.getChatAttachmentId()).isEqualTo(signed.getId());
        assertThat(f.getFileName()).isEqualTo("Договор подписан.pdf");
        assertThat(f.getContent()).isEqualTo(PDF);
    }

    @Test
    void chatFileOfOtherDeal_404() {
        Tender a = accepted(), b = accepted();
        ChatAttachment foreign = chatFile(chatOf(a), LeadDirection.IN, "2026-10-16T07:00:00Z", "чужой.pdf");
        chatOf(b);
        assertThatThrownBy(() -> service.attach(b.getId(), null, foreign.getId(), DealFileStage.CONTRACT, null, null, "vlad"))
                .isInstanceOf(NotFoundException.class).hasMessage("Файл переписки не найден");
    }

    @Test
    void placeRules_paymentAndSupplier() {
        Distributor fotek = dist();
        Tender t = accepted(fotek);
        Upload up = new Upload("платёжка.pdf", "application/pdf", PDF);
        service.addPayment(t.getId(), new PaymentInput(null, BigDecimal.TEN, DAY, null));
        service.addPayment(t.getId(), new PaymentInput(fotek.getId(), BigDecimal.ONE, DAY, null));
        em.flush();
        List<DealPayment> ps = payments.findByTenderIdIn(List.of(t.getId()));
        Long clientPay = ps.stream().filter(p -> p.getDealSupplierId() == null).findFirst().orElseThrow().getId();
        Long supplierPay = ps.stream().filter(p -> p.getDealSupplierId() != null).findFirst().orElseThrow().getId();

        assertThatThrownBy(() -> service.attach(t.getId(), up, null, DealFileStage.CLIENT_PAYMENT, supplierPay, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Это платёж поставщику");
        assertThatThrownBy(() -> service.attach(t.getId(), up, null, DealFileStage.CONTRACT, clientPay, null, "vlad"))
                .isInstanceOf(BadRequestException.class);
        service.attach(t.getId(), up, null, DealFileStage.CLIENT_PAYMENT, clientPay, null, "vlad");
        service.attach(t.getId(), up, null, DealFileStage.SUPPLIER, supplierPay, fotek.getId(), "vlad");
        assertThat(meta(t)).hasSize(2);

        service.deletePayment(t.getId(), clientPay);   // файлы платежа — каскадом
        em.flush();
        em.clear();
        assertThat(files.findMetaByTenderId(t.getId())).extracting(DealFileMeta::stage).containsExactly(DealFileStage.SUPPLIER);
    }

    @Test
    void fileOfOtherDeal_404_delete() {
        Tender a = accepted(), b = accepted();
        service.attach(a.getId(), new Upload("a.pdf", null, PDF), null, DealFileStage.DELIVERY, null, null, "vlad");
        Long fileId = meta(a).get(0).id();
        assertThatThrownBy(() -> service.file(b.getId(), fileId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteFile(b.getId(), fileId)).isInstanceOf(NotFoundException.class);
        assertThat(service.file(a.getId(), fileId).getContent()).isEqualTo(PDF);
        service.deleteFile(a.getId(), fileId);
        assertThat(meta(a)).isEmpty();
    }

    @Test
    void sourceRules() {
        Tender t = accepted();
        assertThatThrownBy(() -> service.attach(t.getId(), null, null, DealFileStage.CONTRACT, null, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Выберите файл: с компьютера или из переписки");
        assertThatThrownBy(() -> service.attach(t.getId(), new Upload("x.pdf", null, new byte[0]), null, DealFileStage.CONTRACT, null, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Файл пустой");
        assertThatThrownBy(() -> service.attach(t.getId(), new Upload("x.pdf", null, PDF), null, null, null, null, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Не указано, к чему файл");
    }
}
