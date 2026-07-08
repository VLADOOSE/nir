package com.vladoose.nir.classifier;

import com.vladoose.nir.entity.EquipmentType;
import com.vladoose.nir.entity.EquipmentTypeSynonym;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.repository.EquipmentTypeRepository;
import com.vladoose.nir.repository.EquipmentTypeSynonymRepository;
import com.vladoose.nir.service.LotTypeClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LotTypeClassifierTest {

    @Autowired LotTypeClassifier classifier;
    @Autowired EquipmentTypeRepository equipmentTypeRepository;
    @Autowired EquipmentTypeSynonymRepository synonymRepository;

    private TenderLot lot(String name, String spec) {
        TenderLot l = new TenderLot();
        l.setEquipName(name);
        l.setRequiredSpec(spec);
        return l;
    }

    @Test
    void detectsIvlFromName() {
        LotTypeClassifier.TypeGuess g = classifier.classify(
                lot("Аппарат искусственной вентиляции лёгких экспертного класса", null));
        assertThat(g.typeId()).isNotNull();
        assertThat(g.typeName()).isEqualTo("ИВЛ");
        assertThat(g.confidence()).isGreaterThan(0.0);
    }

    @Test
    void detectsUziFromSpec() {
        LotTypeClassifier.TypeGuess g = classifier.classify(
                lot("Диагностический комплекс", "Стационарный ультразвуковой сканер с конвексным датчиком"));
        assertThat(g.typeName()).isEqualTo("УЗИ");
    }

    @Test
    void unknownForGarbage() {
        LotTypeClassifier.TypeGuess g = classifier.classify(lot("Услуга по поставке товара", null));
        assertThat(g.typeId()).isNull();
        assertThat(g.confidence()).isEqualTo(0.0);
    }

    /**
     * Оператор поправил ошибочную авто-классификацию: второй learn для того же головного токена
     * должен ПЕРЕзаписать тип (last-write-wins), а не быть no-op. На старой skip-if-exists логике
     * второй learn ничего не делал → classify возвращал typeA → тест падал.
     */
    @Test
    void learnUpsertsLastCorrectionWins() {
        List<EquipmentType> types = equipmentTypeRepository.findAll();
        assertThat(types.size()).isGreaterThanOrEqualTo(2); // нужны два разных типа из сида
        EquipmentType typeA = types.get(0);
        EquipmentType typeB = types.get(1);

        // головной токен "крамбозавр" заведомо отсутствует в сидовом словаре
        TenderLot lot = lot("Крамбозавр экспертный", null);
        assertThat(synonymRepository.findByTermNorm("крамбозавр")).isEmpty();

        classifier.learn(lot, typeA);          // ошибочная классификация
        classifier.learn(lot, typeB);          // оператор исправил
        synonymRepository.flush();

        // ровно одна строка на термин, и она указывает на исправленный тип B
        assertThat(synonymRepository.findAll().stream()
                .filter(s -> "крамбозавр".equals(s.getTermNorm())).count()).isEqualTo(1L);
        assertThat(synonymRepository.findByTermNorm("крамбозавр"))
                .get().extracting(s -> s.getEquipmentType().getId()).isEqualTo(typeB.getId());

        LotTypeClassifier.TypeGuess g = classifier.classify(lot);
        assertThat(g.typeId()).isEqualTo(typeB.getId());
    }
}
