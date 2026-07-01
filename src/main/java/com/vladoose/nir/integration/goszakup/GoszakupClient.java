package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.KatoRefPageDto;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyPageDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyV3PageDto;
import java.util.List;

public interface GoszakupClient {
    /** cursor == null → первая страница; иначе значение next_page из прошлого ответа. */
    TrdBuyPageDto fetchTrdBuyPage(String cursor);
    /** v3 GraphQL: лента, серверно суженная до точных КАТО-кодов (регион). after == null → первая страница. */
    TrdBuyV3PageDto fetchTrdBuyPageByKato(List<String> katoCodes, Long after);
    /** Страница справочника /v2/refs/ref_kato (cursor как у fetchTrdBuyPage). */
    KatoRefPageDto fetchKatoPage(String cursor);
    List<LotDto> fetchLots(String numberAnno);
    /** null, если организация не найдена. */
    SubjectDto fetchSubject(String bin);
    /** true, если задан токен (иначе импорт выключен). */
    boolean isConfigured();
}
