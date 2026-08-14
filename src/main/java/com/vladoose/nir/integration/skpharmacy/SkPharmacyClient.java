package com.vladoose.nir.integration.skpharmacy;

/** Доступ к порталу СК-Фармации (fms.ecc.kz) — отдаёт сырой HTML (API у портала нет). */
public interface SkPharmacyClient {
    /** HTML страницы списка объявлений (searchanno), page ≥ 1. */
    String searchPage(int page);

    /** HTML вкладки лотов объявления по числовому announceId, страница ≥ 1 (лоты бывают на нескольких страницах). */
    String lotsPage(String announceId, int page);

    /** HTML вкладки «Общие сведения» (?tab=general) — организатор+БИН, юр. адрес (регион/КАТО), контакт. */
    String generalPage(String announceId);
}
