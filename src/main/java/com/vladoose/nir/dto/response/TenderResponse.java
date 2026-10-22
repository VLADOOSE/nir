package com.vladoose.nir.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class TenderResponse {
    private Long id;
    private String tenderNumber;
    private FacilityResponse facility;
    private String status;
    private String purchaseType;
    private LocalDate deadline;
    /** Время окончания приёма с площадки — местное время площадки; пусто у ручных тендеров (B2). */
    private LocalDateTime deadlineAt;
    private LocalDate publishDate;
    private BigDecimal totalCost;
    private String currency;
    private String source;
    private String platform;
    private String description;
    private String deliveryAddress;
    private String region;
    private String regionKato;
    private String customerName;
    private String customerBin;
    private String contactLastName;
    private String contactFirstName;
    private String contactMiddleName;
    private String contactPhone;
    private String contactEmail;
    private List<TenderLotShortResponse> lots = new ArrayList<>();
    /** Заведён поиском по нашей продукции по стране (спека 2026-10-19-product-watch §7.3). */
    private boolean foundByProductSearch;
    /** «Наш товар»: пусто — отметок нет (ставит контроллер одним заходом на весь список). */
    private OurProductSummary ourProduct;
    /** Модели в работе лотов — для чипа «📌 …» и фильтра (спека 2026-10-20-lot-model-in-work §7). */
    private List<String> modelsInWork = new ArrayList<>();
    /** У лота тендера вывод ИИ «аналог» (наш не подходит, можно чужой моделью) — фильтр «🔁 Аналог (по ИИ)». */
    private boolean aiAnalog;

    /** Итог тендера для чипа и фильтра «Итоги» (одним запросом на список). */
    private TenderResultSummary result;
}
