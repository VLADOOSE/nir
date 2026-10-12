package com.vladoose.nir.dto.request;

/** Поле шапки частной заявки, которое сохраняется по выходу из поля (спека §5.2). */
public enum PrivateRequestField {
    /** клиент из «Учреждений»: value — id учреждения или пусто */
    FACILITY,
    /** клиент текстом (не из справочника) */
    CUSTOMER_NAME,
    CONTACT_NAME, CONTACT_PHONE, CONTACT_EMAIL, DELIVERY_ADDRESS, NOTE
}
