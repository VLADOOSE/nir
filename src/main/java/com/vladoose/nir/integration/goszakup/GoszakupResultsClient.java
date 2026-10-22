package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Итоги, договоры, цепочки попыток и цены рынка на goszakup (спека 2026-10-22-tender-results-market-prices §3). Свой бин со
 * своим экземпляром {@link GoszakupHttpClient} (тот же адрес и токен) — как поиск по стране: мок {@link GoszakupClient} в
 * тестах не задевает итоги.
 */
public interface GoszakupResultsClient {
    boolean isConfigured();
    List<ResultBuy> announcements(List<Long> buyIds);
    List<ContractRow> contractsByBuyIds(List<Long> buyIds);
    List<ContractRow> contractsByCustomer(String bin, LocalDate from, LocalDate to);
    List<AttemptLot> lotsByPlanPoints(List<Long> pointIds);
    SearchPage<ClosedLot> closedLots(String word, LocalDate from, LocalDate to, Long after);
    List<ClosedLot> lotsByIds(List<Long> lotIds);
    /** null — файла нет (404). */
    byte[] downloadProtocol(String url);
    /** null — площадка БИН не знает. */
    SubjectInfo subject(String bin);
}
