package com.vladoose.nir.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.OfferRegistrationStatus;
import com.vladoose.nir.service.offer.ItemCalc;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Строка КП: в запросе — ввод оператора, в ответе — плюс calc. Пустое наименование допустимо (оператор печатает); вид
 * строки обязателен (проверяет сервис — с номером строки в тексте ошибки). Связей волны 2 (поставщик, лот, строка ответа,
 * позиция каталога) здесь нет: PUT волны 1 их не принимает и не меняет.
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
    private Boolean purchaseVatSame;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal purchaseVatRate;
    @Size(max = 255) private String supplierName;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal markupPct;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal priceOverride;
    @Digits(integer = 15, fraction = 10, message = DIGITS) private BigDecimal vatRate;
    private OfferRegistrationStatus registrationStatus;
    @Size(max = 1000) private String registrationText;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String regNumber;
    @Size(max = 4000) private String note;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ItemCalc calc;
}
