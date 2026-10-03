package com.vladoose.nir.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;

import java.awt.Color;

/** Шапка и подпись старых PDF (заявка, отчёт по тендерам) — реквизиты активного рынка (CompanyInfoProvider). */
public final class PdfCompanyHeader {

    private static final Color LINE_GRAY = new Color(180, 180, 180);
    private static final Color LABEL_GRAY = new Color(100, 100, 100);

    private PdfCompanyHeader() {}

    public static void addTo(Document document, BaseFont bf, CompanyInfoProvider.Company company) throws DocumentException {
        Font companyFont = new Font(bf, 12, Font.BOLD, new Color(20, 40, 90));
        Font detailFont = new Font(bf, 8, Font.NORMAL, new Color(60, 60, 60));

        Paragraph name = new Paragraph(company.fullName(), companyFont);
        name.setSpacingAfter(3f);
        document.add(name);
        for (String line : company.lines()) {
            Paragraph p = new Paragraph(line, detailFont);
            p.setLeading(10f);
            document.add(p);
        }

        PdfPTable divider = new PdfPTable(1);
        divider.setWidthPercentage(100);
        PdfPCell rule = new PdfPCell();
        rule.setFixedHeight(1f);
        rule.setBorderWidthTop(1f);
        rule.setBorderColorTop(LINE_GRAY);
        rule.setBorderWidthBottom(0);
        rule.setBorderWidthLeft(0);
        rule.setBorderWidthRight(0);
        divider.addCell(rule);
        divider.setSpacingBefore(4f);
        divider.setSpacingAfter(10f);
        document.add(divider);
    }

    /** Блок подписи директора в конце документа. */
    public static void addDirectorSignature(Document document, BaseFont bf, CompanyInfoProvider.Company company)
            throws DocumentException {
        Font labelFont = new Font(bf, 9, Font.NORMAL, LABEL_GRAY);
        Font valueFont = new Font(bf, 10, Font.NORMAL);
        Font boldFont = new Font(bf, 10, Font.BOLD);

        Paragraph spacer = new Paragraph(" ", valueFont);
        spacer.setSpacingBefore(20f);
        document.add(spacer);

        Paragraph title = new Paragraph(company.directorTitleLine(), boldFont);
        title.setSpacingAfter(2f);
        document.add(title);

        Paragraph signLine = new Paragraph();
        signLine.add(new Phrase("____________________   ", valueFont));
        signLine.add(new Phrase(company.directorName() == null ? "" : company.directorName(), boldFont));
        signLine.setSpacingAfter(6f);
        document.add(signLine);

        if (company.contacts() != null && !company.contacts().isBlank()) {
            document.add(new Paragraph(company.contacts(), labelFont));
        }
    }
}
