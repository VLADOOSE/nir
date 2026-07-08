package com.vladoose.nir.service;

import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.EquipmentTypeSynonym;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.EquipmentTypeSynonymRepository;
import com.vladoose.nir.util.LotQueryTokenizer;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Классификатор вида МИ лота по обучаемому словарю equipment_type_synonym: подстрочный матч
 * терминов в (имя + ТЗ). Совпадение в имени весит сильнее, чем в ТЗ. Пустой результат допустим.
 */
@Service
public class LotTypeClassifier {

    private final EquipmentTypeSynonymRepository synonymRepository;

    public LotTypeClassifier(EquipmentTypeSynonymRepository synonymRepository) {
        this.synonymRepository = synonymRepository;
    }

    public record TypeGuess(Long typeId, String typeName, double confidence, List<TypeGuess> alternatives) {}

    private static final TypeGuess UNKNOWN = new TypeGuess(null, null, 0.0, List.of());

    public TypeGuess classify(TenderLot lot) {
        String name = lot.getEquipName() == null ? "" : lot.getEquipName().toLowerCase();
        String spec = lot.getRequiredSpec() == null ? "" : lot.getRequiredSpec().toLowerCase();
        String all = (name + " " + spec).trim();
        if (all.isBlank()) return UNKNOWN;

        Map<Long, Double> score = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (EquipmentTypeSynonym syn : synonymRepository.findAll()) {
            String term = syn.getTermNorm();
            if (term == null || term.isBlank() || !all.contains(term)) continue;
            double w = term.length();
            if (name.contains(term)) w *= 1.5;              // сигнал из имени сильнее
            Long tid = syn.getEquipmentType().getId();
            score.merge(tid, w, Double::sum);
            names.putIfAbsent(tid, syn.getEquipmentType().getName());
        }
        if (score.isEmpty()) return UNKNOWN;

        double total = score.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<Long, Double>> sorted = score.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();
        Map.Entry<Long, Double> top = sorted.get(0);
        List<TypeGuess> alts = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            Long tid = sorted.get(i).getKey();
            alts.add(new TypeGuess(tid, names.get(tid), sorted.get(i).getValue() / total, List.of()));
        }
        return new TypeGuess(top.getKey(), names.get(top.getKey()), top.getValue() / total, alts);
    }

    /**
     * Best-effort обучение: головной токен имени лота → выбранный тип (UPSERT, последняя правка
     * побеждает). Оператор открывает селектор именно чтобы ИСПРАВИТЬ ошибочную авто-классификацию,
     * поэтому существующий термин ПЕРЕзаписываем на новый тип, а не пропускаем.
     */
    public void learn(TenderLot lot, EquipmentType type) {
        if (type == null || lot == null) return;
        List<LotQueryTokenizer.WeightedToken> toks = LotQueryTokenizer.tokenize(lot.getEquipName(), null);
        if (toks.isEmpty()) return;
        String head = toks.get(0).token();
        if (head == null || head.length() < 4) return;
        EquipmentTypeSynonym syn = synonymRepository.findByTermNorm(head)
                .orElseGet(() -> EquipmentTypeSynonym.builder().termNorm(head).build());
        syn.setEquipmentType(type);
        synonymRepository.save(syn);
    }
}
