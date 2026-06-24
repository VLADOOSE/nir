package com.vladoose.nir.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;

import java.awt.Color;

/**
 * Шапка фирменного бланка ООО «Регион-Мед». Рендерится в начале PDF.
 *
 * Состоит из: левой колонки с названием/реквизитами, правой колонки с банковскими и
 * контактными данными, тонкой горизонтальной линии-разделителя.
 */
public final class PdfCompanyHeader {

    private static final Color LINE_GRAY = new Color(180, 180, 180);
    private static final Color LABEL_GRAY = new Color(100, 100, 100);

    private PdfCompanyHeader() {}

    /**
     * Добавляет шапку в документ, используя реквизиты активного рынка.
     * Вызывается из Spring-сервисов, передающих {@code provider.current()}.
     */
    public static void addTo(Document document, BaseFont bf, CompanyInfoProvider.Company company)
            throws com.lowagie.text.DocumentException {
        addToInternal(document, bf,
                company.fullName(), company.addressLine1() + " " + company.addressLine2(),
                CompanyInfo.INN + " / " + CompanyInfo.KPP, CompanyInfo.OGRN, CompanyInfo.OKVED,
                CompanyInfo.BANK_NAME, CompanyInfo.BANK_ACCOUNT,
                CompanyInfo.BANK_BIK + " / " + CompanyInfo.BANK_CORR_ACC,
                CompanyInfo.BANK_LEGAL_ADDR, CompanyInfo.PHONE, CompanyInfo.EMAIL);
    }

    /** Обратная совместимость — использует статичные RF-реквизиты. */
    public static void addTo(Document document, BaseFont bf) throws com.lowagie.text.DocumentException {
        addToInternal(document, bf,
                CompanyInfo.FULL_NAME,
                CompanyInfo.ADDRESS_LINE_1 + " " + CompanyInfo.ADDRESS_LINE_2,
                CompanyInfo.INN + " / " + CompanyInfo.KPP, CompanyInfo.OGRN, CompanyInfo.OKVED,
                CompanyInfo.BANK_NAME, CompanyInfo.BANK_ACCOUNT,
                CompanyInfo.BANK_BIK + " / " + CompanyInfo.BANK_CORR_ACC,
                CompanyInfo.BANK_LEGAL_ADDR, CompanyInfo.PHONE, CompanyInfo.EMAIL);
    }

    private static void addToInternal(Document document, BaseFont bf,
            String fullName, String address, String innKpp, String ogrn, String okved,
            String bankName, String bankAccount, String bikCorr,
            String bankLegalAddr, String phone, String email)
            throws com.lowagie.text.DocumentException {
        Font companyFont   = new Font(bf, 12, Font.BOLD,   new Color(20, 40, 90));
        Font detailFont    = new Font(bf, 8,  Font.NORMAL, new Color(60, 60, 60));
        Font detailBoldFont = new Font(bf, 8, Font.BOLD,   new Color(40, 40, 40));

        PdfPTable header = new PdfPTable(new float[]{55f, 45f});
        header.setWidthPercentage(100);
        header.getDefaultCell().setBorder(0);

        // ----- Левая колонка: название + юр. реквизиты -----
        PdfPCell left = new PdfPCell();
        left.setBorder(0);
        left.setPaddingRight(10f);

        Paragraph nameLine = new Paragraph(fullName, companyFont);
        nameLine.setSpacingAfter(4f);
        left.addElement(nameLine);

        left.addElement(kv("ИНН / КПП:", innKpp,  detailBoldFont, detailFont));
        left.addElement(kv("ОГРН:",       ogrn,    detailBoldFont, detailFont));
        left.addElement(kv("ОКВЭД:",      okved,   detailBoldFont, detailFont));
        left.addElement(kv("Адрес:",      address, detailBoldFont, detailFont));

        header.addCell(left);

        // ----- Правая колонка: банк + контакты -----
        PdfPCell right = new PdfPCell();
        right.setBorder(0);

        right.addElement(kv("Банк:",        bankName,    detailBoldFont, detailFont));
        right.addElement(kv("Расч. счёт:",  bankAccount, detailBoldFont, detailFont));
        right.addElement(kv("БИК / Корр.:", bikCorr,     detailBoldFont, detailFont));
        right.addElement(kv("Юр. адрес банка:", bankLegalAddr, detailBoldFont, detailFont));
        right.addElement(kv("Телефон:",     phone,       detailBoldFont, detailFont));
        right.addElement(kv("E-mail:",      email,       detailBoldFont, detailFont));

        header.addCell(right);

        document.add(header);

        // Разделитель
        PdfPTable divider = new PdfPTable(1);
        divider.setWidthPercentage(100);
        PdfPCell line = new PdfPCell();
        line.setFixedHeight(1f);
        line.setBorderWidthTop(1f);
        line.setBorderColorTop(LINE_GRAY);
        line.setBorderWidthBottom(0);
        line.setBorderWidthLeft(0);
        line.setBorderWidthRight(0);
        divider.addCell(line);
        divider.setSpacingBefore(4f);
        divider.setSpacingAfter(10f);
        document.add(divider);
    }

    /**
     * Блок подписи директора — выводится в конце документа (например, заявки).
     */
    public static void addDirectorSignature(Document document, BaseFont bf) throws com.lowagie.text.DocumentException {
        Font labelFont = new Font(bf, 9, Font.NORMAL, LABEL_GRAY);
        Font valueFont = new Font(bf, 10, Font.NORMAL);
        Font boldFont  = new Font(bf, 10, Font.BOLD);

        Paragraph spacer = new Paragraph(" ", valueFont);
        spacer.setSpacingBefore(20f);
        document.add(spacer);

        Paragraph title = new Paragraph(CompanyInfo.DIRECTOR_TITLE, boldFont);
        title.setSpacingAfter(2f);
        document.add(title);

        Paragraph signLine = new Paragraph();
        signLine.add(new Phrase("____________________   ", valueFont));
        signLine.add(new Phrase(CompanyInfo.DIRECTOR_FIO, boldFont));
        signLine.setSpacingAfter(6f);
        document.add(signLine);

        Paragraph contacts = new Paragraph();
        contacts.add(new Phrase("моб.: ",  labelFont));
        contacts.add(new Phrase(CompanyInfo.DIRECTOR_PHONE_MOBILE + "    ", valueFont));
        contacts.add(new Phrase("раб.: ",  labelFont));
        contacts.add(new Phrase(CompanyInfo.DIRECTOR_PHONE_WORK   + "    ", valueFont));
        contacts.add(new Phrase("e-mail: ", labelFont));
        contacts.add(new Phrase(CompanyInfo.DIRECTOR_EMAIL_1 + " / " + CompanyInfo.DIRECTOR_EMAIL_2, valueFont));
        document.add(contacts);
    }

    private static Paragraph kv(String label, String value, Font labelFont, Font valueFont) {
        Paragraph p = new Paragraph();
        p.setLeading(11f);
        p.add(new Phrase(label + " ", labelFont));
        p.add(new Phrase(value, valueFont));
        return p;
    }
}
