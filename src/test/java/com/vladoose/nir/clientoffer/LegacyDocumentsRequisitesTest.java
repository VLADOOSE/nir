package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.service.CompanyInfoProvider;
import com.vladoose.nir.service.JasperReportService;
import com.vladoose.nir.service.ProfitabilityExcelService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Спека §7.4: старые PDF «Заявки» и Excel рентабельности печатают реквизиты СВОЕГО рынка, а не всегда Регион-Мед.
 * Реквизиты в nirdb — данные оператора (их правят страница «Реквизиты и печать» и живые проверки), поэтому тест сам
 * ставит обоим рынкам заведомо разные реквизиты внутри своей транзакции — она откатывается.
 */
@SpringBootTest
@Transactional
class LegacyDocumentsRequisitesTest {

    private static final String KZ_FULL_NAME = "Товарищество с ограниченной ответственностью «Тест-KZ»";
    private static final String KZ_IDS = "БИН 999 888 777 666";
    private static final String KZ_BANK = "АО \"Тест Банк KZ\"";
    private static final String KZ_DIRECTOR = "Казбеков Тест Тестович";
    private static final String KZ_CONTACTS = "моб: 87000000001, e-mail: test-kz@example.kz";

    private static final String RF_FULL_NAME = "Общество с ограниченной ответственностью «Тест-RF»";
    private static final String RF_IDS = "ИНН 1112223334 КПП 111222333 ОГРН 1112223334445";
    private static final String RF_BANK = "АО «Тест Банк RF»";
    private static final String RF_DIRECTOR = "Россиянов Тест Тестович";

    @Autowired CompanyInfoProvider provider;
    @Autowired JasperReportService reports;
    @Autowired ProfitabilityExcelService excel;
    @Autowired CompanyProfileRepository profiles;

    @BeforeEach
    void knownRequisites() {
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setShortName("ТОО «Тест-KZ»");
        kz.setFullName(KZ_FULL_NAME);
        kz.setIdsLine(KZ_IDS);
        kz.setAddress("Республика Казахстан, 090000, Тестовая область,\nгород Тестовск, ул. Пробная 1");
        kz.setAccounts("KZ00 TEST 0000 0000 0001 (тенге)");
        kz.setBankName(KZ_BANK);
        kz.setBik("TESTKZKA");
        kz.setPhone("87000000001");
        kz.setEmail("test-kz@example.kz");
        kz.setDirectorTitle("Директор");
        kz.setDirectorName(KZ_DIRECTOR);
        kz.setSignoffContacts(KZ_CONTACTS);
        profiles.saveAndFlush(kz);

        CompanyProfile rf = profiles.findByMarket(Market.RF).orElseThrow();
        rf.setShortName("ООО «Тест-RF»");
        rf.setFullName(RF_FULL_NAME);
        rf.setIdsLine(RF_IDS);
        rf.setAddress("Российская Федерация, 100000, Тестовая область,\nг. Тестоград, ул. Пробная, д. 2");
        rf.setAccounts("р/с 40702810000000000002, к/с 30101810000000000002");
        rf.setBankName(RF_BANK);
        rf.setBik("044000002");
        rf.setPhone("+7 (900) 000-00-02");
        rf.setEmail("test-rf@example.ru");
        rf.setDirectorTitle("Генеральный директор");
        rf.setDirectorName(RF_DIRECTOR);
        rf.setSignoffContacts("моб.: +7 900 000-00-02, e-mail: test-rf@example.ru");
        profiles.saveAndFlush(rf);
    }

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    @Test
    void providerReturnsMarketRequisites() {
        MarketContext.set(Market.KZ);
        CompanyInfoProvider.Company kz = provider.current();
        assertThat(kz.shortName()).isEqualTo("ТОО «Тест-KZ»");
        assertThat(kz.fullName()).isEqualTo(KZ_FULL_NAME);
        assertThat(kz.lines()).contains(KZ_IDS, "Банк: " + KZ_BANK + " БИК: TESTKZKA")
                .noneMatch(line -> line.contains("1112223334"));
        assertThat(kz.directorTitleLine()).isEqualTo("Директор ТОО «Тест-KZ»");
        assertThat(kz.directorName()).isEqualTo(KZ_DIRECTOR);
        assertThat(kz.contacts()).isEqualTo(KZ_CONTACTS);
        assertThat(kz.currencyShort()).isEqualTo("тг");
        assertThat(kz.currencySymbol()).isEqualTo("₸");

        MarketContext.set(Market.RF);
        CompanyInfoProvider.Company rf = provider.current();
        assertThat(rf.lines()).contains(RF_IDS, "Банк: " + RF_BANK + " БИК: 044000002")
                .noneMatch(line -> line.contains("999 888 777 666"));
        assertThat(rf.directorTitleLine()).isEqualTo("Генеральный директор ООО «Тест-RF»");
        assertThat(rf.currencyShort()).isEqualTo("руб.");
        assertThat(rf.currencySymbol()).isEqualTo("₽");
    }

    /** Полное наименование и должность на странице реквизитов необязательны — шапка и подпись не печатают «null». */
    @Test
    void providerFallsBackToShortNameWithoutFullNameAndTitle() {
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setFullName(null);
        kz.setDirectorTitle(null);
        profiles.saveAndFlush(kz);

        MarketContext.set(Market.KZ);
        CompanyInfoProvider.Company company = provider.current();
        assertThat(company.fullName()).isEqualTo("ТОО «Тест-KZ»");
        assertThat(company.directorTitleLine()).isEqualTo("ТОО «Тест-KZ»");
    }

    @Test
    void applyPdfOnKzPrintsKzRequisitesAndTenge() throws Exception {
        MarketContext.set(Market.KZ);
        String text = KpTestSupport.text(reports.generateApplyReport(apply(), List.of(item())));
        assertThat(text).contains(KZ_FULL_NAME, KZ_IDS, "Тест Банк KZ", "TESTKZKA", " тг",
                        "Директор ТОО «Тест-KZ»", KZ_DIRECTOR, "test-kz@example.kz")
                .doesNotContain("1112223334", "Тест Банк RF", RF_DIRECTOR, "₽", "руб.");
    }

    @Test
    void applyPdfOnRfPrintsRfRequisitesAndRoubles() throws Exception {
        MarketContext.set(Market.RF);
        String text = KpTestSupport.text(reports.generateApplyReport(apply(), List.of(item())));
        assertThat(text).contains(RF_FULL_NAME, RF_IDS, "Тест Банк RF", " руб.",
                        "Генеральный директор ООО «Тест-RF»", RF_DIRECTOR)
                .doesNotContain("999 888 777 666", "Тест Банк KZ", KZ_DIRECTOR, " тг", "₽");
    }

    @Test
    void profitabilityExcelOnKzHasKzHeaderAndTengeFormat() throws Exception {
        MarketContext.set(Market.KZ);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel.generate()))) {
            Sheet sheet = wb.getSheet("Сводка");
            StringBuilder header = new StringBuilder();
            for (int r = 0; r < 12; r++) {
                Row row = sheet.getRow(r);
                Cell cell = row == null ? null : row.getCell(0);
                if (cell != null && cell.getCellType() == CellType.STRING) header.append(cell.getStringCellValue()).append('\n');
            }
            assertThat(header.toString()).contains(KZ_FULL_NAME, KZ_IDS, "Банк: " + KZ_BANK + " БИК: TESTKZKA")
                    .doesNotContain("1112223334", "Тест Банк RF");
            boolean tenge = false;
            boolean rouble = false;
            for (Row row : sheet) {
                for (Cell cell : row) {
                    String format = cell.getCellStyle().getDataFormatString();
                    if (format.contains("₸")) tenge = true;
                    if (format.contains("₽")) rouble = true;
                }
            }
            assertThat(tenge).isTrue();
            assertThat(rouble).isFalse();
        }
    }

    private static ActivityApply apply() {
        Tender tender = new Tender();
        tender.setTenderNumber("ТЕСТ-1");
        ActivityApply apply = new ActivityApply();
        apply.setTender(tender);
        apply.setStatus("DRAFT");
        return apply;
    }

    private static ApplyItem item() {
        ApplyItem item = new ApplyItem();
        item.setOfferedCost(new BigDecimal("100.00"));
        item.setQuantity(2);
        return item;
    }
}
