package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.ReplyColumnRole;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.repository.SupplierReplyFileRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static com.vladoose.nir.service.supplierreply.ReplyFixtures.parsed;
import static com.vladoose.nir.service.supplierreply.ReplyFixtures.table;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Файл ответа меняют разные запросы: курс (rate), роли колонок (parsed), ссылка на письмо (inbound_email_id). Каждый
 * должен записать только своё (@DynamicUpdate): иначе запрос, закоммиченный вторым, вернул бы чужое поле к старому
 * значению — и переписывал бы байты файла (до 15 МБ) на каждое изменение. Без общей транзакции теста: перекрытие —
 * вложенной REQUIRES_NEW; созданное удаляется в @AfterEach.
 */
@SpringBootTest
class SupplierReplyFileOverlappingSavesTest {

    @Autowired PrivateRequestService requests;
    @Autowired SupplierReplyService replies;
    @Autowired SupplierReplyFileRepository files;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository items;
    @Autowired DistributorRepository distributors;
    @Autowired TenderRepository tenders;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private TransactionTemplate txNew;
    private Long tenderId, distributorId, prId, fileId;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tx = new TransactionTemplate(txManager);
        txNew = new TransactionTemplate(txManager);
        txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.execute(s -> {
            Tender t = requests.createEmpty();
            requests.addLines(t.getId(), List.of(input("ZZ Наложение файла кушетка смотровая", null, 1, null, null, null)), LinesMode.APPEND);
            Distributor d = distributors.save(Distributor.builder().name("ZZ Наложение файла " + System.nanoTime()).build());
            PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("SENT").sentAt(OffsetDateTime.now()).build());
            for (TenderLot l : t.getLots()) {
                pr.getItems().add(items.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(l).requestedQuantity(1).build()));
            }
            fileId = replies.ingest(pr, List.of(parsed("kp.xls", table("RUB", "ZZ Наложение файла кушетка смотровая КС-1", "10 000"))), null)
                    .fileIds().get(0);
            tenderId = t.getId();
            distributorId = d.getId();
            prId = pr.getId();
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        tx.execute(s -> {
            priceRequests.deleteById(prId);           // файлы ответа — каскадом в базе
            tenders.deleteById(tenderId);
            distributors.deleteById(distributorId);
            return null;
        });
        MarketContext.clear();
    }

    @Test
    void rateAndColumnRolesSavedConcurrently_bothKept() {
        tx.execute(s -> {
            SupplierReplyFile first = files.findById(fileId).orElseThrow();      // курс загрузил файл
            first.setRate(new BigDecimal("6"));
            first.setRateCurrency("RUB");
            txNew.execute(s2 -> {                                                 // роли колонок — раньше
                replies.setColumns(prId, fileId, List.of(new ReplyColumnRole(1, LineField.IGNORE)));
                return null;
            });
            return null;                                                          // курс коммитится последним
        });

        SupplierReplyFile back = tx.execute(s -> files.findById(fileId).orElseThrow());
        assertThat(back.getRate()).isEqualByComparingTo("6");
        assertThat(back.getParsed().columns().get(1).role()).isEqualTo(LineField.IGNORE);
    }
}
