package com.vladoose.nir.integration;

import com.vladoose.nir.entity.TenderLot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Индекс существующих лотов тендера для слияния при переимпорте: по коду лота площадки,
 * с запасным матчем по наименованию.
 *
 * <p>Общий для обоих импорт-райтеров намеренно: goszakup и СК-Фармация должны сливать лоты
 * ОДИНАКОВО. Когда та же логика жила в двух методах, половина молча не работала (у goszakup
 * ключом был разобранный в int номер лота, а живые номера вида «87197521-ОИ2» в int не
 * разбираются — ключ был null у 1304 из 1305 лотов, и слияние не срабатывало НИ РАЗУ).
 *
 * <p><b>Три правила, каждое куплено дефектом:</b>
 * <ul>
 *   <li><b>Забрать можно один раз.</b> Лоты хранятся очередью на ключ, claim вынимает из очереди.
 *       Иначе два лота площадки с одинаковым ключом ссылались бы на ОДНУ сущность, она попадала
 *       бы в коллекцию дважды, и в БД оставалась одна строка вместо двух — лот молча пропадал.
 *       Симметрично: два СУЩЕСТВУЮЩИХ лота с одинаковым ключом раньше затирали друг друга в
 *       карте (last-wins), и незабранный удалялся orphanRemoval.</li>
 *   <li><b>Сначала ВСЕ коды, потом имена</b> ({@link #matchAll}). Жадный «код-или-имя» на каждом
 *       лоте по очереди позволял лоту A забрать ПО ИМЕНИ строку, которую следующий лот B забрал бы
 *       ПО КОДУ — и разобранное ТЗ строки переезжало на чужой лот. Код — точный ключ, он имеет
 *       приоритет над именем глобально, а не в пределах одной итерации.</li>
 *   <li><b>По имени — только если имя РАЗЛИЧАЕТ.</b> Имена внутри тендера не уникальны: на живой
 *       БД 283 лота из 1305 сидят в 97 группах одинаковых имён (до 24 «Набор реагентов» в одном
 *       тендере). При первом переимпорте кода нет ни у кого, то есть слияние шло бы на 100% по
 *       имени — ровно там, где имя не различает. Кого с кем спарит, решал бы порядок строк в куче
 *       (у {@code Tender.lots} нет {@code @OrderBy}, у {@code tender_lot} нет индекса по
 *       {@code tender_id} → Seq Scan), а строки с работой оператора — как раз те, что были
 *       UPDATE'нуты и уехали в конец кучи. Итог был бы худшим из возможных: ТЗ затирается коротким
 *       описанием И одновременно предъявляется под чужим лотом с его количеством и ценой.
 *       Поэтому при неоднозначном имени лот НЕ сливается: он будет пересоздан, как раньше.
 *       <b>Потерять лучше, чем незаметно переклеить</b> — потерянное ТЗ видно и разбирается заново,
 *       переклеенное не видно и не чинится.</li>
 * </ul>
 */
public final class LotMergeIndex {

    private final Map<String, Deque<TenderLot>> byCode = new LinkedHashMap<>();
    private final Map<String, Deque<TenderLot>> byName = new LinkedHashMap<>();
    /** Сущности сравниваем по ссылке: один лот лежит в обоих индексах и забирается только раз. */
    private final Set<TenderLot> claimed = Collections.newSetFromMap(new IdentityHashMap<>());

    public LotMergeIndex(Collection<TenderLot> existing) {
        if (existing == null) return;
        for (TenderLot l : existing) {
            index(byCode, l.getSourceLotCode(), l);
            index(byName, l.getEquipName(), l);
        }
    }

    /**
     * Сопоставляет входящие лоты существующим: сперва ВСЕ по коду, затем оставшиеся — по
     * однозначному имени. Возвращает список того же размера и порядка; null — соответствия нет
     * (лот будет создан заново).
     */
    public <T> List<TenderLot> matchAll(List<T> incoming, Function<T, String> codeFn, Function<T, String> nameFn) {
        List<TenderLot> matched = new ArrayList<>(Collections.nCopies(incoming.size(), null));
        for (int i = 0; i < incoming.size(); i++) {
            matched.set(i, claimByCode(codeFn.apply(incoming.get(i))));
        }
        for (int i = 0; i < incoming.size(); i++) {
            if (matched.get(i) == null) matched.set(i, claimByUniqueName(nameFn.apply(incoming.get(i))));
        }
        return matched;
    }

    /** Забирает существующий лот с таким кодом площадки. null — нечего забирать. */
    public TenderLot claimByCode(String code) {
        Deque<TenderLot> queue = byCode.get(norm(code));
        while (queue != null && !queue.isEmpty()) {
            TenderLot lot = queue.pollFirst();
            if (claimed.add(lot)) return lot;   // уже забранный по другому ключу — пропускаем
        }
        return null;
    }

    /**
     * Забирает существующий лот по имени, ТОЛЬКО если такой незабранный ровно один.
     * Неоднозначное имя соответствием не считается — см. третье правило в описании класса.
     */
    public TenderLot claimByUniqueName(String name) {
        Deque<TenderLot> queue = byName.get(norm(name));
        if (queue == null) return null;
        queue.removeIf(claimed::contains);      // забранные по коду в счёт однозначности не идут
        if (queue.size() != 1) return null;
        TenderLot lot = queue.pollFirst();
        claimed.add(lot);
        return lot;
    }

    private static void index(Map<String, Deque<TenderLot>> index, String key, TenderLot lot) {
        String k = norm(key);
        if (k != null) index.computeIfAbsent(k, x -> new ArrayDeque<>()).addLast(lot);
    }

    /** Ключи площадок регистронезависимы («A-Т1» = «a-т1»); пустое значение ключом не является. */
    private static String norm(String s) {
        if (s == null) return null;
        String t = s.trim().toLowerCase();
        return t.isEmpty() ? null : t;
    }
}
