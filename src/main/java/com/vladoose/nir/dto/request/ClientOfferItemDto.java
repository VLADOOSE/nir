package com.vladoose.nir.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vladoose.nir.dto.response.RegistryCandidateResponse;
import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.OfferRegistrationStatus;
import com.vladoose.nir.service.offer.ItemCalc;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Строка КП: в запросе — ввод оператора, в ответе — плюс calc. Пустое наименование допустимо (оператор печатает); вид
 * строки обязателен (проверяет сервис — с номером строки в тексте ошибки). Связи волны 2: строку ответа поставщика PUT
 * принимает с гардом (этап 3 частной заявки), позицию заявки и поставщика отдаёт только для чтения, позицию каталога — нет.
 */
@Data
public class ClientOfferItemDto {
    /**
     * Числа — не длиннее 15 цифр до запятой и 10 после (проверка тела, до округления до точности колонок): округление
     * (setScale) числа вроде 1e200000000 или 1e-200000000 заняло бы процессор на минуты. 15, а не 13 цифр денежных колонок:
     * чуть большие суммы получают от сервиса ошибку с номером позиции.
     */
    public static final String DIGITS = "Слишком длинное число: до 15 цифр до запятой и 10 после";

    private Long id;
    @Size(max = 64) private String key;
    private Integer lineNo;
    private ClientOfferItemKind kind;
    @Size(max = 4000) private String name;
    @Size(max = 255) private String model;
    @Size(max = 500) private String manufacturer;
    @Size(max = 200) private String country;
    @Size(max = 30) private String unit;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal quantity;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal purchasePrice;
    /** Расчёт с 2026-10-05 не читает (НДС поставщика не учитывается, спека §5.1); поля — для совместимости. */
    private Boolean purchaseVatSame;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal purchaseVatRate;
    @Size(max = 255) private String supplierName;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal markupPct;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal priceOverride;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal vatRate;
    private OfferRegistrationStatus registrationStatus;
    @Size(max = 1000) private String registrationText;
    /** № РУ (спека 2026-10-13 §4): у CONFIRMED / SUGGESTED сервер сверяет с реестром; у остальных статусов не хранится. */
    @Size(max = 100) private String regNumber;
    /** Оценка подсказки (0…1, три знака); у остальных статусов не хранится. */
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal suggestionScore;
    /** Позиция каталога: пишется только у новой строки (своего рынка, иначе 400), у сохранённой с клиента не меняется. */
    private Long medEquipmentId;
    /** Запись реестра для № строки — показать запись и срок; только для чтения. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private RegistryCandidateResponse registry;
    @Size(max = 4000) private String note;
    /** Позиция заявки, из которой строка (ставит сервер при создании КП из заявки); с клиента не меняется. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long tenderLotId;
    /**
     * Строка ответа поставщика, из которой взята закупка (спека 2026-10-12 §8). Пишется: только ответ заявки этого КП по
     * позиции этой строки и с ценой, иначе 400; пусто — ссылки нет; ручная правка закупки на экране её снимает.
     */
    private Long priceRequestItemId;
    /** Поставщик ответа — выводит сервер из priceRequestItemId; с клиента не принимается. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long distributorId;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ItemCalc calc;
}
