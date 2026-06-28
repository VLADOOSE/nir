package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import java.util.List;

public interface GoszakupClient {
    /** cursor == null → первая страница; иначе значение next_page из прошлого ответа. */
    TrdBuyPageDto fetchTrdBuyPage(String cursor);
    List<LotDto> fetchLots(String numberAnno);
    /** null, если организация не найдена. */
    SubjectDto fetchSubject(String bin);
    /** true, если задан токен (иначе импорт выключен). */
    boolean isConfigured();
}
