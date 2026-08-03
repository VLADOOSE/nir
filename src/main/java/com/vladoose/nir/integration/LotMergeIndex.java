package com.vladoose.nir.integration;

import com.vladoose.nir.entity.TenderLot;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Индекс существующих лотов тендера для слияния при переимпорте: по коду лота площадки,
 * с запасным матчем по наименованию.
 *
 * <p>Общий для обоих импорт-райтеров намеренно: goszakup и СК-Фармация должны сливать лоты
 * ОДИНАКОВО. Когда та же логика жила в двух методах, половина молча не работала (у goszakup
 * ключом был разобранный в int номер лота, а живые номера вида «87197521-ОИ2» в int не
 * разбираются — ключ был null у 1304 из 1305 лотов, и слияние не срабатывало НИ РАЗУ).
 *
 * <p>Два свойства, ради которых это класс, а не пара строк на месте:
 * <ul>
 *   <li><b>Забрать можно один раз.</b> Лоты хранятся очередью на ключ, {@link #claim} вынимает
 *       из очереди. Иначе два лота площадки с одинаковым ключом ссылались бы на ОДНУ сущность,
 *       она попадала бы в коллекцию дважды, и в БД оставалась одна строка вместо двух —
 *       лот молча пропадал. Симметрично: два СУЩЕСТВУЮЩИХ лота с одинаковым ключом раньше
 *       затирали друг друга в карте (last-wins), и незабранный удалялся orphanRemoval.</li>
 *   <li><b>Запасной матч по имени.</b> У лотов, импортированных до появления кода площадки,
 *       {@code source_lot_code} пуст (в живой БД это все goszakup-лоты). Без запасного ключа
 *       первый же переимпорт после этой правки пересоздал бы их — то есть уничтожил ровно ту
 *       работу, ради сохранения которой всё и делается. По имени они находятся, получают код
 *       и дальше сливаются уже по нему.</li>
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

    /** Забирает существующий лот под этот код (или, если по коду нет, под это имя). null — нечего забирать. */
    public TenderLot claim(String code, String name) {
        TenderLot byCodeHit = take(byCode, code);
        return byCodeHit != null ? byCodeHit : take(byName, name);
    }

    private TenderLot take(Map<String, Deque<TenderLot>> index, String key) {
        Deque<TenderLot> queue = index.get(norm(key));
        while (queue != null && !queue.isEmpty()) {
            TenderLot lot = queue.pollFirst();
            if (claimed.add(lot)) return lot;   // уже забранный по другому ключу — пропускаем
        }
        return null;
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
