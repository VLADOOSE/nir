package com.vladoose.nir.integration;

import com.vladoose.nir.integration.goszakup.GoszakupImportScheduler;
import com.vladoose.nir.integration.skpharmacy.SkPharmacyImportScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Автозапуск импорта тендеров обеих площадок в рабочие часы (по умолчанию — ежечасно в :10, 8:10–19:10 пн–сб по
 * Уральску; {@code tenders.auto-import.*}). Тендеры ЗКО живут 2–4 дня — о них надо узнавать в день публикации, а не
 * когда кто-то нажмёт «Обновить тендеры». Тик только ставит прогоны в их собственные потоки (startAsync) и сразу
 * возвращается: общий поток планировщика (почта, опрос сайта, калитка) не занят. Прогон, который уже идёт,
 * не дублируется. Выключено по умолчанию ({@code TENDERS_AUTO_IMPORT_ENABLED}).
 */
@Component
public class TenderAutoImportScheduler {

    private static final Logger log = LoggerFactory.getLogger(TenderAutoImportScheduler.class);

    /** Ежечасно в :10, с 8 до 19 часов, пн–сб (пояс — {@code tenders.auto-import.zone}, по умолчанию Уральск). */
    static final String DEFAULT_CRON = "0 10 8-19 * * MON-SAT";

    private final GoszakupImportScheduler goszakup;
    private final SkPharmacyImportScheduler skPharmacy;
    private final boolean enabled;

    public TenderAutoImportScheduler(GoszakupImportScheduler goszakup, SkPharmacyImportScheduler skPharmacy,
                                     @Value("${tenders.auto-import.enabled:false}") boolean enabled) {
        this.goszakup = goszakup;
        this.skPharmacy = skPharmacy;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${tenders.auto-import.cron:" + DEFAULT_CRON + "}", zone = "${tenders.auto-import.zone:Asia/Oral}")
    public void tick() {
        if (!enabled) return;
        log.info("Автоимпорт тендеров: прогоны goszakup и СК-Фармации поставлены");
        goszakup.startAsync(null);   // все мониторимые больницы KZ
        skPharmacy.startAsync();
    }
}
