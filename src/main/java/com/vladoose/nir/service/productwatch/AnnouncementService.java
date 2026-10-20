package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.dto.response.AnnouncementResult;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.dto.ContractInfo;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.http.UpstreamRetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/** «Итоги ›» в «Найти тендеры» (спека §11.2): договор объявления и название поставщика по БИН. */
@Service
public class AnnouncementService {

    private final GoszakupSearchClient search;
    private final GoszakupClient client;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public AnnouncementService(GoszakupSearchClient search, GoszakupClient client) {
        this(search, client, UpstreamRetry.REAL);
    }

    public AnnouncementService(GoszakupSearchClient search, GoszakupClient client, UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.client = client; this.sleeper = sleeper;
    }

    public AnnouncementResult result(String numberAnno) {
        List<ContractInfo> cs = UpstreamRetry.call(sleeper, () -> search.contracts(numberAnno));
        if (cs.isEmpty()) return new AnnouncementResult("договор ещё не опубликован", null, null, null, null);
        ContractInfo c = cs.get(0);
        String name = null;
        if (c.supplierBiin() != null) {
            try {
                SubjectDto s = UpstreamRetry.call(sleeper, () -> client.fetchSubject(c.supplierBiin()));
                if (s != null) name = s.getNameRu();
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) throw e;   // название — необязательно, стоп-коды — нет
            }
        }
        BigDecimal sum = c.contractSumWnds() != null ? c.contractSumWnds() : c.contractSum();
        String date = c.signDate() == null || c.signDate().length() < 10 ? c.signDate() : c.signDate().substring(0, 10);
        return new AnnouncementResult("договор есть", name, c.supplierBiin(), sum, date);
    }
}
