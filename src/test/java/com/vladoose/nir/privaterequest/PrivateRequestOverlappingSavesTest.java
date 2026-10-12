package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.request.PrivateRequestLineField;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
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

import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Страница сохраняет поле по выходу из него — два быстрых выхода дают два наложившихся PATCH разных полей. Каждый
 * должен записать только своё поле (@DynamicUpdate), иначе запрос, закоммиченный вторым, затирает поле первого старым
 * значением. Без общей транзакции теста: перекрытие делается вложенной REQUIRES_NEW; созданное удаляется в @AfterEach.
 */
@SpringBootTest
class PrivateRequestOverlappingSavesTest {

    @Autowired PrivateRequestService service;
    @Autowired TenderRepository tenderRepository;
    @Autowired PlatformTransactionManager txManager;

    private TransactionTemplate tx;
    private TransactionTemplate txNew;
    private Long id;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        tx = new TransactionTemplate(txManager);
        txNew = new TransactionTemplate(txManager);
        txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        id = tx.execute(s -> {
            Tender t = service.createEmpty();
            service.addLines(t.getId(), List.of(input("ZZ Наложение правок", null, 1, null, null, null)), LinesMode.APPEND);
            return t.getId();
        });
    }

    @AfterEach
    void tearDown() {
        tx.execute(s -> { tenderRepository.deleteById(id); return null; });
        MarketContext.clear();
    }

    @Test
    void overlappingHeaderFieldSavesKeepBothFields() {
        tx.execute(s -> {
            Tender first = tenderRepository.findById(id).orElseThrow();      // первый PATCH загрузил заявку
            first.setContactPhone("+7 701 000 00 01");
            txNew.execute(s2 -> service.updateField(id, PrivateRequestField.CONTACT_EMAIL, "zz@clinic.kz"));   // второй — раньше
            return null;                                                       // первый коммитится последним
        });

        Tender back = tx.execute(s -> tenderRepository.findById(id).orElseThrow());
        assertThat(back.getContactPhone()).isEqualTo("+7 701 000 00 01");
        assertThat(back.getContactEmail()).isEqualTo("zz@clinic.kz");
    }

    @Test
    void overlappingLineFieldSavesKeepBothFields() {
        Long lotId = tx.execute(s -> tenderRepository.findById(id).orElseThrow().getLots().get(0).getId());
        tx.execute(s -> {
            TenderLot first = tenderRepository.findById(id).orElseThrow().getLots().get(0);
            first.setQuantity(7);
            txNew.execute(s2 -> service.updateLineField(id, lotId, PrivateRequestLineField.UNIT, "компл"));
            return null;
        });

        TenderLot back = tx.execute(s -> tenderRepository.findById(id).orElseThrow().getLots().get(0));
        assertThat(back.getQuantity()).isEqualTo(7);
        assertThat(back.getUnit()).isEqualTo("компл");
    }
}
