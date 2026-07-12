package com.vladoose.nir.integration.skpharmacy;

import java.util.List;

/** Доступ к файлам техспецификации объявления СК-Фармации (fms.ecc.kz). Мокается в тестах сервиса. */
public interface SkTechSpecClient {

    /** documents-tab → docReqId «Техническая спецификация» → modal → per-lot ссылки; нет строки ТЗ → пустой список. */
    List<SkTechSpecRef> fetchTechSpecRefs(String announceId);

    /** Скачать PDF; 404 (файл удалён/протух) → null; сеть/не-200 → UpstreamException. */
    byte[] downloadFile(String url);

    /** Токен не нужен (портал открытый) — всегда true; для симметрии с goszakup-веткой. */
    boolean isConfigured();
}
