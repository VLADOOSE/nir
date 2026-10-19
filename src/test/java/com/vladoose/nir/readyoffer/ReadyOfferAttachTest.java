package com.vladoose.nir.readyoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.ReadyOfferService;
import com.vladoose.nir.service.readyoffer.ReadyOfferFixtures;
import com.vladoose.nir.service.supplierreply.ReplyFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** «Занести готовое КП» (спека 2026-10-19 §3.2, §4, §8; Review Focus 1, 3). */
@SpringBootTest
@Transactional
class ReadyOfferAttachTest {

    @Autowired ReadyOfferService service;
    @Autowired PrivateRequestService requests;
    @Autowired ClientOfferRequestService offersFromRequest;
    @Autowired ClientOfferService clientOffers;
    @Autowired ClientOfferRepository offers;
    @Autowired ClientOfferFileRepository files;
    @Autowired CompanyProfileRepository profiles;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired DealEventRepository events;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired EntityManager em;

    static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    static final List<String[]> ROWS = List.of(
            new String[]{"Пульсоксиметр QMP-PO70", "3", "105 600,00", "316 800,00"},
            new String[]{"Гигрометр ВИТ-2", "4", "13 515,00", "54 060,00"});

    @BeforeEach
    void kz() {
        MarketContext.set(Market.KZ);
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setVatDefault(new BigDecimal("16"));
        kz.setVatRegistered(new BigDecimal("5"));
        kz.setDefaultMarkupPct(new BigDecimal("20.00"));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static ReadyOfferService.Upload word(byte[] b) {
        return new ReadyOfferService.Upload("КП West-Med 24.09.2026.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", b);
    }

    static byte[] fatherKp() throws Exception {
        return ReadyOfferFixtures.fatherKp("24.09.2026", ROWS, "370 860,00");
    }

    private TenderLot lot(Tender t, String name) {
        return requests.findById(t.getId()).getLots().stream().filter(l -> name.equals(l.getEquipName())).findFirst().orElseThrow();
    }

    private PriceRequestItem answer(Tender t, Distributor d, TenderLot lot, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private Chat chat() {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.EMAIL).account("info@westmed.kz")
                .externalChatId("zz" + System.nanoTime() + "@clinic.kz").title("Клиника").build());
    }

    private void lead(Chat c, Tender t, OffsetDateTime receivedAt) {
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос КП").contactName("Клиент").status(LeadStatus.CONVERTED).chat(c).privateRequest(t)
                .receivedAt(receivedAt).build());
    }

    private ChatAttachment file(Chat c, LeadDirection dir, OffsetDateTime at, String subject, String name, byte[] content) {
        ChatMessage m = messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(dir)
                .type(ChatMessageType.EMAIL).subject(subject).body("Добрый день! Во вложении КП.").sentAt(at).build());
        return attachments.save(ChatAttachment.builder().message(m).fileName(name).mimeType("application/octet-stream")
                .sizeBytes((long) content.length).content(content).build());
    }

    @Test
    void emptyRequest_wordKp_linesPositionsTotalFile_counterUntouched() throws Exception {
        Tender t = requests.createEmpty();
        int counter = profiles.findByMarket(Market.KZ).orElseThrow().getNextNumber();

        ClientOffer o = service.attach(t.getId(), word(fatherKp()), null, ClientOfferStatus.SENT, DAY, "vlad");

        assertThat(o.getOrigin()).isEqualTo(ClientOfferOrigin.FILE);
        assertThat(o.getNumber()).isNull();
        assertThat(o.getOfferDate()).isEqualTo(LocalDate.of(2026, 9, 24));
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(o.getSentAt().atZoneSameInstant(Market.KZ.zone()).toLocalDate()).isEqualTo(DAY);
        assertThat(o.getTenderId()).isEqualTo(t.getId());
        assertThat(o.getItems()).extracting(ClientOfferItem::getName).containsExactly("Пульсоксиметр QMP-PO70", "Гигрометр ВИТ-2");
        ClientOfferItem first = o.getItems().get(0);
        assertThat(first.getPriceOverride()).isEqualByComparingTo("105600");
        assertThat(first.getQuantity()).isEqualByComparingTo("3");
        assertThat(first.getVatRate()).isEqualByComparingTo("5");
        assertThat(o.getTotalAmount()).isEqualByComparingTo("370860");
        assertThat(requests.findById(t.getId()).getLots()).extracting(TenderLot::getEquipName)
                .containsExactlyInAnyOrder("Пульсоксиметр QMP-PO70", "Гигрометр ВИТ-2");
        assertThat(o.getItems()).allSatisfy(it -> assertThat(it.getTenderLotId()).isNotNull());
        ClientOfferFile f = files.findByOfferId(o.getId()).orElseThrow();
        assertThat(f.getFileTotal()).isEqualByComparingTo("370860");
        assertThat(f.getKind()).isEqualTo(ReplyFileKind.WORD);
        assertThat(f.getParseStatus()).isEqualTo(ReplyParseStatus.PARSED);
        assertThat(f.getTables()).hasSize(1);
        assertThat(f.getUploadedBy()).isEqualTo("vlad");
        assertThat(profiles.findByMarket(Market.KZ).orElseThrow().getNextNumber()).isEqualTo(counter);
    }

    @Test
    void positionsMatched_purchaseFromBestAnswer_noNewPositions() throws Exception {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Пульсоксиметр QMP-PO70", null, 3, null, null, null),
                input("Тонометр механический", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot pulse = lot(t, "Пульсоксиметр QMP-PO70");
        Distributor d = distributors.save(Distributor.builder().name("ZZ Готовое КП " + System.nanoTime()).email("g@zz.kz").build());
        answer(t, d, pulse, "80000");
        em.flush();

        ClientOffer o = service.attach(t.getId(), word(fatherKp()), null, ClientOfferStatus.SENT, DAY, "vlad");

        ClientOfferItem first = o.getItems().get(0), second = o.getItems().get(1);
        assertThat(first.getTenderLotId()).isEqualTo(pulse.getId());
        assertThat(first.getPurchasePrice()).isEqualByComparingTo("80000");
        assertThat(first.getDistributorId()).isEqualTo(d.getId());
        assertThat(second.getTenderLotId()).isNull();            // «Гигрометр» — не позиция заявки
        assertThat(requests.findById(t.getId()).getLots()).hasSize(2);
    }

    @Test
    void acceptedAtOnce_writesEvent() throws Exception {
        Tender t = requests.createEmpty();
        ClientOffer o = service.attach(t.getId(), word(fatherKp()), null, ClientOfferStatus.ACCEPTED, DAY, "vlad");
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.ACCEPTED);
        assertThat(events.findByTenderIdOrderByOccurredAtAscIdAsc(t.getId())).extracting(DealEvent::getText)
                .containsExactly("КП от 24.09.2026: клиент согласился");
    }

    @Test
    void pdf_linesFromPositionsWithoutClientPrice() throws Exception {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Кушетка", null, 2, null, null, null), input("Ширма", null, 1, null, null, null)),
                LinesMode.APPEND);
        ClientOffer o = service.attach(t.getId(), new ReadyOfferService.Upload("КП.pdf", "application/pdf", ReplyFixtures.pdfBytes(2)),
                null, ClientOfferStatus.SENT, DAY, "vlad");
        assertThat(o.getItems()).hasSize(2).allSatisfy(it -> assertThat(it.getPriceOverride()).isNull());
        assertThat(o.getTotalAmount()).isEqualByComparingTo("0");
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getPages()).isEqualTo(2);
    }

    @Test
    void aisOwnPdfIsRefused() throws Exception {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Кушетка", null, 1, null, null, null)), LinesMode.APPEND);
        ClientOffer built = offersFromRequest.createFromPrivateRequest(t.getId(), "vlad");
        ClientOfferService.OfferFile pdf = clientOffers.pdf(built.getId());
        assertThatThrownBy(() -> service.attach(t.getId(), new ReadyOfferService.Upload(pdf.fileName(), "application/pdf", pdf.bytes()),
                null, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("из конструктора");
    }

    @Test
    void foreignCurrency_noPricesAndNote() throws Exception {
        Tender t = requests.createEmpty();
        byte[] x = ReadyOfferFixtures.xlsx(List.of(List.of("Наименование", "Кол-во", "Цена, руб."), List.of("Консоль", "1", "940 000")));
        ClientOffer o = service.attach(t.getId(), new ReadyOfferService.Upload("КП.xlsx", null, x), null, ClientOfferStatus.SENT, DAY, "vlad");
        assertThat(o.getItems()).singleElement().satisfies(it -> assertThat(it.getPriceOverride()).isNull());
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getParseNote()).contains("руб.");
    }

    @Test
    void refusals() throws Exception {
        Tender t = requests.createEmpty();
        byte[] kp = fatherKp();
        LocalDate tomorrow = LocalDate.now(Market.KZ.zone()).plusDays(1);
        assertThatThrownBy(() -> service.attach(t.getId(), word(kp), null, ClientOfferStatus.SENT, tomorrow, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Дата отправки ещё не наступила");
        assertThatThrownBy(() -> service.attach(t.getId(), new ReadyOfferService.Upload("КП.txt", "text/plain", "КП".getBytes()),
                null, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Нужен Word, Excel, PDF или картинка");
        assertThatThrownBy(() -> service.attach(t.getId(), null, null, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessage("Выберите файл: с компьютера или из переписки");
        assertThatThrownBy(() -> service.attach(t.getId(), word(kp), null, ClientOfferStatus.DRAFT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class);
        assertThat(offers.findByTenderIdOrderByIdDesc(t.getId())).isEmpty();
    }

    @Test
    void moreThan500LinesRefusedBeforeWrite() throws Exception {   // Review Focus 1
        Tender t = requests.createEmpty();
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Наименование", "Кол-во", "Цена, тг"));
        for (int i = 1; i <= 501; i++) rows.add(List.of("Позиция " + i, "1", "100"));
        byte[] x = ReadyOfferFixtures.xlsx(rows);
        // заявка с позицией: позиции из КП не заводятся — без предела КП из 501 строки записалось бы
        Tender withLine = requests.createEmpty();
        requests.addLines(withLine.getId(), List.of(input("Кушетка", null, 1, null, null, null)), LinesMode.APPEND);
        assertThatThrownBy(() -> service.attach(withLine.getId(), new ReadyOfferService.Upload("КП.xlsx", null, x), null,
                ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("больше 500 строк");
        assertThat(offers.findByTenderIdOrderByIdDesc(withLine.getId())).isEmpty();
        assertThatThrownBy(() -> service.attach(t.getId(), new ReadyOfferService.Upload("КП.xlsx", null, x), null,
                ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("больше 500 строк");
        assertThat(offers.findByTenderIdOrderByIdDesc(t.getId())).isEmpty();
        assertThat(requests.findById(t.getId()).getLots()).isEmpty();
    }

    @Test
    void fromCorrespondence_sentAtFromMessage_attachmentLinked() throws Exception {
        Tender t = requests.createEmpty();
        Chat c = chat();
        lead(c, t, OffsetDateTime.parse("2026-10-14T09:00:00Z"));
        OffsetDateTime sent = OffsetDateTime.parse("2026-10-15T07:30:00Z");
        ChatAttachment a = file(c, LeadDirection.OUT, sent, "Коммерческое предложение", "КП West-Med.docx", fatherKp());

        ClientOffer o = service.attach(t.getId(), null, a.getId(), ClientOfferStatus.SENT, DAY, "vlad");

        assertThat(o.getSentAt()).isAtSameInstantAs(sent);
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getChatAttachmentId()).isEqualTo(a.getId());
        assertThat(files.findByOfferId(o.getId()).orElseThrow().getFileName()).isEqualTo("КП West-Med.docx");
    }

    @Test
    void notOurFileIs404() throws Exception {   // Review Focus 3
        Tender t = requests.createEmpty();
        Chat c = chat();
        lead(c, t, OffsetDateTime.parse("2026-10-14T09:00:00Z"));
        byte[] kp = fatherKp();
        ChatAttachment incoming = file(c, LeadDirection.IN, OffsetDateTime.parse("2026-10-15T07:30:00Z"), "Re: КП", "КП.docx", kp);
        ChatAttachment before = file(c, LeadDirection.OUT, OffsetDateTime.parse("2026-10-13T07:30:00Z"), "КП", "КП.docx", kp);
        ChatAttachment otherChat = file(chat(), LeadDirection.OUT, OffsetDateTime.parse("2026-10-15T07:30:00Z"), "КП", "КП.docx", kp);
        for (ChatAttachment a : List.of(incoming, before, otherChat)) {
            assertThatThrownBy(() -> service.attach(t.getId(), null, a.getId(), ClientOfferStatus.SENT, DAY, "vlad"))
                    .isInstanceOf(NotFoundException.class).hasMessage("Файл переписки не найден");
        }
        assertThatThrownBy(() -> service.attach(t.getId(), null, Long.MAX_VALUE, ClientOfferStatus.SENT, DAY, "vlad"))
                .isInstanceOf(NotFoundException.class);
    }
}
