package com.vladoose.nir.privaterequest;

import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.service.RegistryMatchService;
import com.vladoose.nir.service.privaterequest.PrivateRequestRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Калибровка PrivateRequestRegistry.SUGGEST_MIN на живом реестре nirdb: печатает топ-1 для типичных позиций частных
 * заявок (товары westmed.kz и то, что называют клиники). Запуск:
 * REG_CALIBRATION=1 ./gradlew test --tests '*PrivateRequestRegistryCalibrationTest' -i | grep CALIB
 */
@SpringBootTest
@Transactional
class PrivateRequestRegistryCalibrationTest {

    @Autowired RegistryMatchService matcher;

    private static final String[][] LINES = {
            {"Рециркулятор бактерицидный настенный РБм 3Х15", "Завод ЭМА"},
            {"Облучатель бактерицидный ОБПЕ-450", "Завод ЭМА"},
            {"Светильник медицинский ЭМАЛЕД 100П", "Завод ЭМА"},
            {"Дефибриллятор-монитор ДКИ-Н-11", "Аксион"},
            {"Электрокардиограф ЭК3ТЦ-3/6-04", "Аксион"},
            {"Аппарат ИВЛ А-ИВЛ-Э-03", "Аксион"},
            {"Холтеровский монитор ЭКГ КРН-01", "НПП Монитор"},
            {"Спирограф СМП-02-Р-Д", "НПП Монитор"},
            {"Стерилизатор озоновый Орион", "Орион-Си"},
            {"Электрокардиограф BeneHeart R12", "Mindray"},
            {"Монитор пациента iMEC10", "Mindray"},
            {"Тонометр OMRON M2", "OMRON"},
            {"Небулайзер OMRON CompAir", "OMRON"},
            {"Аппарат УЗИ Vivid T8", "GE"},
            {"Холодильник фармацевтический ХФ-400", "POZIS"},
            {"Криосауна CryoSpace", "КриоСпейс"},
            {"Кушетка медицинская", null},
            {"Стол упаковочный 1600", null},
            {"Ростомер", null},
            {"Термометр min/max", null},
    };

    @Test
    void printTopCandidates() {
        assumeTrue(System.getenv("REG_CALIBRATION") != null, "отчёт калибровки — только с REG_CALIBRATION=1");
        System.out.println("CALIB порог SUGGEST_MIN = " + PrivateRequestRegistry.SUGGEST_MIN);
        for (String[] l : LINES) {
            List<RegistryCandidateResponse> top = matcher.findCandidates(l[0], l[1], 1);
            if (top.isEmpty()) {
                System.out.printf("CALIB %-50s | %-12s | — нет кандидатов%n", l[0], l[1]);
                continue;
            }
            RegistryCandidateResponse c = top.get(0);
            boolean model = PrivateRequestRegistry.mentionsModel(l[0], c.getName());
            System.out.printf("CALIB %-50s | %-12s | %.3f | модель %s | %s | %s | %s%n", l[0], l[1], c.getScore(),
                    model ? "да " : "нет", c.getRegNumber(),
                    c.getName().length() > 80 ? c.getName().substring(0, 80) + "…" : c.getName(), c.getProducer());
        }
    }
}
