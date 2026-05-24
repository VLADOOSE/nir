package com.vladoose.nir.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.pdf.*;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class JasperReportService {

    private final TenderRepository tenderRepository;

    public JasperReportService(TenderRepository tenderRepository) {
        this.tenderRepository = tenderRepository;
    }

    public byte[] generateTenderReport(String status) throws Exception {
        List<Tender> tenders = tenderRepository.findAll();
        if (status != null && !status.isEmpty()) {
            tenders = tenders.stream().filter(t -> status.equals(t.getStatus())).toList();
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 30, 30, 30, 30);
        PdfWriter.getInstance(document, out);
        document.open();

        BaseFont bf;
        try {
            byte[] fontBytes = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf").readAllBytes();
            bf = BaseFont.createFont("DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
        } catch (Exception e) {
            bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        }

        Font titleFont = new Font(bf, 16, Font.BOLD);
        Font headerFont = new Font(bf, 9, Font.BOLD, Color.WHITE);
        Font cellFont = new Font(bf, 9);
        Font subtitleFont = new Font(bf, 10);
        Font boldFont = new Font(bf, 11, Font.BOLD);

        String title = "Отчёт по тендерам";
        if (status != null) title += " (статус: " + getStatusLabel(status) + ")";
        Paragraph titlePara = new Paragraph(title, titleFont);
        titlePara.setAlignment(Element.ALIGN_CENTER);
        titlePara.setSpacingAfter(5);
        document.add(titlePara);

        Paragraph datePara = new Paragraph(
                "Дата формирования: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")),
                subtitleFont);
        datePara.setAlignment(Element.ALIGN_CENTER);
        datePara.setSpacingAfter(15);
        document.add(datePara);

        PdfPTable table = new PdfPTable(new float[]{6, 15, 20, 12, 12, 10, 15, 5});
        table.setWidthPercentage(100);

        String[] headers = {"№", "Номер тендера", "Заказчик", "Статус", "Способ закупки", "Дата оконч.", "Начальная цена", "Лотов"};
        Color headerBg = new Color(26, 86, 219);
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
            cell.setBackgroundColor(headerBg);
            cell.setPadding(6);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }

        int idx = 1;
        BigDecimal totalSum = BigDecimal.ZERO;
        for (Tender t : tenders) {
            table.addCell(new PdfPCell(new Phrase(String.valueOf(idx++), cellFont)));
            table.addCell(new PdfPCell(new Phrase(t.getTenderNumber() != null ? t.getTenderNumber() : "—", cellFont)));
            table.addCell(new PdfPCell(new Phrase(t.getFacility() != null ? t.getFacility().getName() : "—", cellFont)));
            table.addCell(new PdfPCell(new Phrase(getStatusLabel(t.getStatus()), cellFont)));
            table.addCell(new PdfPCell(new Phrase(getPurchaseTypeLabel(t.getPurchaseType()), cellFont)));
            table.addCell(new PdfPCell(new Phrase(
                    t.getDeadline() != null ? t.getDeadline().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) : "—",
                    cellFont)));

            String cost = "—";
            if (t.getTotalCost() != null) {
                cost = String.format("%,.2f ₽", t.getTotalCost());
                totalSum = totalSum.add(t.getTotalCost());
            }
            PdfPCell costCell = new PdfPCell(new Phrase(cost, cellFont));
            costCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            table.addCell(costCell);

            table.addCell(new PdfPCell(new Phrase(
                    String.valueOf(t.getLots() != null ? t.getLots().size() : 0), cellFont)));
        }

        document.add(table);

        Paragraph summary = new Paragraph(
                "\nИтого тендеров: " + tenders.size()
                        + "     Общая сумма: " + String.format("%,.2f ₽", totalSum),
                boldFont);
        summary.setSpacingBefore(10);
        document.add(summary);

        document.close();
        return out.toByteArray();
    }

    public byte[] generateApplyReport(ActivityApply apply, List<ApplyItem> items) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 30, 30, 30, 30);
        PdfWriter.getInstance(document, out);
        document.open();

        BaseFont bf;
        try {
            byte[] fontBytes = getClass().getResourceAsStream("/fonts/DejaVuSans.ttf").readAllBytes();
            bf = BaseFont.createFont("DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
        } catch (Exception e) {
            bf = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
        }

        Font titleFont = new Font(bf, 16, Font.BOLD);
        Font headerFont = new Font(bf, 10, Font.BOLD, Color.WHITE);
        Font cellFont = new Font(bf, 10);
        Font labelFont = new Font(bf, 10, Font.BOLD);
        Font valueFont = new Font(bf, 10);

        Paragraph title = new Paragraph("Заявка на участие в тендере", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(15);
        document.add(title);

        Tender tender = apply.getTender();
        document.add(new Paragraph("Номер тендера: " + (tender != null ? tender.getTenderNumber() : "—"), labelFont));
        document.add(new Paragraph("Заказчик: " + (tender != null && tender.getFacility() != null ? tender.getFacility().getName() : "—"), valueFont));
        document.add(new Paragraph("Статус заявки: " + getApplyStatusLabel(apply.getStatus()), valueFont));
        document.add(new Paragraph("Дата создания: " + (apply.getCreatedAt() != null ? apply.getCreatedAt().toLocalDate().format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) : "—"), valueFont));
        document.add(new Paragraph(" ", valueFont));

        if (!items.isEmpty()) {
            PdfPTable table = new PdfPTable(new float[]{4, 14, 22, 16, 8, 18, 18});
            table.setWidthPercentage(100);
            String[] headers = {"№", "Лот", "Оборудование", "Производитель", "Кол-во", "Цена за ед.", "Итого"};
            Color headerBg = new Color(26, 86, 219);
            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                cell.setBackgroundColor(headerBg);
                cell.setPadding(6);
                table.addCell(cell);
            }

            int idx = 1;
            BigDecimal total = BigDecimal.ZERO;
            for (ApplyItem item : items) {
                table.addCell(new PdfPCell(new Phrase(String.valueOf(idx++), cellFont)));
                table.addCell(new PdfPCell(new Phrase(item.getTenderLot() != null ? item.getTenderLot().getEquipName() : "—", cellFont)));
                table.addCell(new PdfPCell(new Phrase(item.getMedEquipment() != null ? item.getMedEquipment().getName() : "—", cellFont)));
                table.addCell(new PdfPCell(new Phrase(item.getMedEquipment() != null ? item.getMedEquipment().getManufact() : "—", cellFont)));
                table.addCell(new PdfPCell(new Phrase(item.getQuantity() != null ? String.valueOf(item.getQuantity()) : "—", cellFont)));
                String unitCost = "—";
                String itemTotalStr = "—";
                if (item.getOfferedCost() != null) {
                    unitCost = String.format("%,.2f ₽", item.getOfferedCost());
                    BigDecimal itemTotal = item.getOfferedCost().multiply(BigDecimal.valueOf(item.getQuantity() != null ? item.getQuantity() : 1));
                    itemTotalStr = String.format("%,.2f ₽", itemTotal);
                    total = total.add(itemTotal);
                }
                PdfPCell unitCell = new PdfPCell(new Phrase(unitCost, cellFont));
                unitCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                table.addCell(unitCell);
                PdfPCell totalCell = new PdfPCell(new Phrase(itemTotalStr, cellFont));
                totalCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                table.addCell(totalCell);
            }
            document.add(table);

            Font boldFont = new Font(bf, 12, Font.BOLD);
            Paragraph totalPara = new Paragraph("\nИтого: " + String.format("%,.2f ₽", total), boldFont);
            totalPara.setAlignment(Element.ALIGN_RIGHT);
            document.add(totalPara);
        }

        document.add(new Paragraph("\n\n\nДата: _______________     Подпись: _______________", valueFont));

        document.close();
        return out.toByteArray();
    }

    private String getApplyStatusLabel(String s) {
        if (s == null) return "—";
        return switch (s) {
            case "DRAFT" -> "Черновик";
            case "SUBMITTED" -> "Подана";
            case "WON" -> "Выиграна";
            case "LOST" -> "Проиграна";
            default -> s;
        };
    }

    private String getStatusLabel(String s) {
        if (s == null) return "—";
        return switch (s) {
            case "DRAFT" -> "Подготовка";
            case "ACTIVE" -> "Приём заявок";
            case "COMPLETED" -> "Завершён";
            default -> s;
        };
    }

    private String getPurchaseTypeLabel(String s) {
        if (s == null) return "—";
        return switch (s) {
            case "ELECTRONIC_AUCTION" -> "Электронный аукцион";
            case "PAPER_TENDER" -> "Конкурс с подачей документов";
            default -> s;
        };
    }
}
