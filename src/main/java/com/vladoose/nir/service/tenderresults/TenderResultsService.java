package com.vladoose.nir.service.tenderresults;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.GoszakupResultsClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.dto.ContractRow;
import com.vladoose.nir.integration.goszakup.dto.ResultBuy;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.service.CompanyProfileService;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import com.vladoose.nir.service.tenderresults.TenderResultsWriter.Candidate;
import com.vladoose.nir.service.tenderresults.TenderResultsWriter.ProtocolJob;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolParser;
import com.vladoose.nir.service.tenderresults.protocol.ProtocolResult;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Проход итогов (спека 2026-10-22-tender-results-market-prices §4): объявления пачками → протоколы → договоры → события → контакты
 * заказчиков для «звонить». Сеть — здесь, вне транзакций; запись — {@link TenderResultsWriter}. 401 / 403 / 429 — стоп прохода
 * (токен общий с продом), прочий сбой пачки — ошибка прохода и дальше; протокол — не ошибка прохода (решение 6).
 */
@Service
public class TenderResultsService {

    private static final Logger log = LoggerFactory.getLogger(TenderResultsService.class);

    static final int CANDIDATES = 200;
    static final int BUY_BATCH = 50;
    static final int CONTRACT_BATCH = 100;
    static final int NAMES_PER_PASS = 100;
    static final int CONTACTS_PER_PASS = 20;
    static final long PAUSE_MS = 300;

    /** Завести объявление в АИС (повтор закупки, задача 6) — путь «Завести в АИС» поиска нашей продукции. */
    public interface Importer {
        long importAnnouncement(String numberAnno);
    }

    private final GoszakupResultsClient client;
    private final TenderResultsWriter writer;
    private final GoszakupSubjects subjects;
    private final Supplier<String> ourBin;
    private final Importer importer;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public TenderResultsService(GoszakupResultsClient client, TenderResultsWriter writer, GoszakupSubjects subjects,
                                CompanyProfileService profiles, ProductWatchService productWatch) {
        this(client, writer, subjects, () -> bin(profiles.forMarket(Market.KZ).getBinInn()), productWatch::importAnnouncement,
                Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public TenderResultsService(GoszakupResultsClient client, TenderResultsWriter writer, GoszakupSubjects subjects,
                                Supplier<String> ourBin, Importer importer, Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.client = client;
        this.writer = writer;
        this.subjects = subjects;
        this.ourBin = ourBin;
        this.importer = importer;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** БИН West-Med из «Реквизитов» KZ — по нему наша заявка в протоколе; не 12 цифр — не ищем. */
    static String bin(String s) {
        String d = s == null ? "" : s.replaceAll("\\D", "");
        return d.length() == 12 ? d : null;
    }

    public void run(ImportSummary sum) {
        pass(null, sum);
    }

    /** «Проверить итоги» (спека §4): тот же проход по одному тендеру. */
    public void check(long tenderId, ImportSummary sum) {
        pass(tenderId, sum);
    }

    /** Можно ли проверять тендер — до постановки в очередь (400 с текстом). */
    public void checkable(long tenderId) {
        writer.candidate(tenderId, nowLocal());
    }

    private LocalDateTime nowLocal() {
        return LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
    }

    private void pass(Long tenderId, ImportSummary sum) {
        if (!client.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        LocalDateTime nowLocal = nowLocal();
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean silent = writer.firstRun();
        List<Candidate> cands = tenderId == null ? writer.candidates(nowLocal, CANDIDATES) : List.of(writer.candidate(tenderId, nowLocal));
        sum.setFetched(cands.size());
        int events = 0;
        try {
            List<ProtocolJob> jobs = buys(cands, now, sum);
            String our = ourBin.get();
            for (ProtocolJob j : jobs) protocol(j, our);
            events += contracts(tenderId, nowLocal, now, silent, sum);
            events += writer.settle(tenderId, nowLocal, now, silent);
            if (tenderId == null) writer.closeStale(nowLocal, now);
            contacts();
        } catch (RuntimeException e) {
            sum.addError((ProductWatchService.stopping(e) ? "проход остановлен: " : "") + ErrorText.of(e));
            log.warn("Итоги тендеров: проход прерван: {}", ErrorText.of(e));
        }
        sum.setCreated(events);
        sum.setMessage("Итоги: тендеров проверено " + cands.size() + ", событий " + events
                + (silent ? " (первый проход — без сообщений)" : "")
                + (sum.getErrors() > 0 ? "; ошибок " + sum.getErrors() + ": " + sum.getLastError() : ""));
    }

    private List<ProtocolJob> buys(List<Candidate> cands, OffsetDateTime now, ImportSummary sum) {
        List<ProtocolJob> jobs = new ArrayList<>();
        for (int i = 0; i < cands.size(); i += BUY_BATCH) {
            Map<Long, Candidate> byBuy = cands.subList(i, Math.min(cands.size(), i + BUY_BATCH)).stream()
                    .collect(Collectors.toMap(Candidate::buyId, c -> c, (a, b) -> a, LinkedHashMap::new));
            List<ResultBuy> buys;
            try {
                buys = call(() -> client.announcements(new ArrayList<>(byBuy.keySet())));
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) throw e;
                sum.addError("объявления: " + ErrorText.of(e));
                continue;
            }
            for (ResultBuy b : buys) {
                Candidate c = byBuy.get(b.id());
                if (c == null) continue;
                jobs.addAll(writer.applyBuy(c, b, now));
                sum.setUpdated(sum.getUpdated() + 1);
            }
        }
        return jobs;
    }

    /** Файл протокола: не скачался / не разобрался — попытка (до 5), стоп-код — наверх. */
    private void protocol(ProtocolJob job, String our) {
        byte[] bytes;
        try {
            bytes = call(() -> client.downloadProtocol(job.url()));
        } catch (RuntimeException e) {
            if (ProductWatchService.stopping(e)) throw e;
            writer.protocolFailed(job, "не скачан: " + ErrorText.of(e));
            return;
        }
        if (bytes == null) {
            writer.protocolFailed(job, "файла нет на площадке");
            return;
        }
        ProtocolResult parsed;
        try {
            parsed = ProtocolParser.parse(bytes);
        } catch (RuntimeException e) {
            writer.protocolFailed(job, "не разобран: " + ErrorText.of(e));
            return;
        }
        writer.applyProtocol(job, parsed, our);
    }

    private int contracts(Long tenderId, LocalDateTime nowLocal, OffsetDateTime now, boolean silent, ImportSummary sum) {
        List<Long> buys = writer.buysAwaitingContract(tenderId);
        int n = 0;
        for (int i = 0; i < buys.size(); i += CONTRACT_BATCH) {
            List<Long> batch = buys.subList(i, Math.min(buys.size(), i + CONTRACT_BATCH));
            List<ContractRow> rows;
            try {
                rows = call(() -> client.contractsByBuyIds(batch));
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) throw e;
                sum.addError("договоры: " + ErrorText.of(e));
                continue;
            }
            subjects.names(rows.stream().map(ContractRow::supplierBin).filter(Objects::nonNull).toList(), NAMES_PER_PASS);
            n += writer.applyContracts(rows, nowLocal, now, silent);
        }
        return n;
    }

    /** Телефон и почта заказчика — до сообщения «звонить» (справочник площадки, раз в 30 дней). */
    private void contacts() {
        for (String bin : writer.pendingCustomerBins(CONTACTS_PER_PASS)) subjects.get(bin);
    }

    private <T> T call(Supplier<T> s) {
        try {
            return UpstreamRetry.call(sleeper, s);
        } finally {
            pause();
        }
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("проход прерван");
        }
    }
}
