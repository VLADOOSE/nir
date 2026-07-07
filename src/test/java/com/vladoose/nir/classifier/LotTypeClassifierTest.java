package com.vladoose.nir.classifier;

import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.LotTypeClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class LotTypeClassifierTest {

    @Autowired LotTypeClassifier classifier;

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
}
