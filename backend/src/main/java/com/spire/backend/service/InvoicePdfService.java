package com.spire.backend.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.spire.backend.config.BrandConfig;
import com.spire.backend.entity.Invoice;
import com.spire.backend.entity.PaymentLedger;
import com.spire.backend.entity.PaymentPlan;
import com.spire.backend.entity.User;
import com.spire.backend.repository.InvoiceRepository;
import com.spire.backend.repository.PaymentLedgerRepository;
import com.spire.backend.repository.PaymentPlanRepository;
import com.spire.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Checklist 5.2: an invoice as a PDF, for the participant and Finance —
 * who it's for, the instalment, what's been paid, waived or reversed, and
 * the balance due, in US dollars.
 */
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final Color INK = new Color(31, 41, 55);
    private static final Color MUTED = new Color(107, 114, 128);
    private static final Color LIGHT_BG = new Color(249, 250, 251);
    private static final Color RULE = new Color(229, 231, 235);

    private final BrandConfig brandConfig;
    private final UserRepository userRepository;
    private final PaymentPlanRepository planRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentLedgerRepository ledgerRepository;

    /** The PDF as a download named after the invoice number. */
    public ResponseEntity<byte[]> response(Invoice invoice) {
        byte[] pdf = render(invoice);
        String name = (invoice.getInvoiceNumber() == null ? "invoice" : invoice.getInvoiceNumber())
                .replaceAll("[^A-Za-z0-9._-]", "_") + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .body(pdf);
    }

    public byte[] render(Invoice invoice) {
        User user = userRepository.findById(invoice.getUserId()).orElse(null);
        PaymentPlan plan = invoice.getPaymentPlanId() == null ? null
                : planRepository.findById(invoice.getPaymentPlanId()).orElse(null);
        List<PaymentLedger> entries = new ArrayList<>(ledgerRepository.findByInvoiceIdOrderByCreatedAtAsc(invoice.getId()));
        entries.sort(Comparator.comparing(PaymentLedger::getId));

        Color brand = brandColor();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.LETTER, 54, 54, 54, 54);
        try {
            PdfWriter.getInstance(doc, out);
            doc.addTitle("Invoice " + invoice.getInvoiceNumber());
            doc.open();

            // Header: the company on the left, INVOICE on the right.
            PdfPTable head = new PdfPTable(new float[]{3, 2});
            head.setWidthPercentage(100);
            PdfPCell company = cell();
            company.addElement(new Paragraph(brandConfig.getName(), new Font(Font.HELVETICA, 16, Font.BOLD, brand)));
            String legal = brandConfig.getLegalName();
            if (legal != null && !legal.isBlank() && !legal.equals(brandConfig.getName())) {
                company.addElement(new Paragraph(legal, small(MUTED)));
            }
            for (String line : addressLines()) company.addElement(new Paragraph(line, small(MUTED)));
            if (brandConfig.getContactEmail() != null) company.addElement(new Paragraph(brandConfig.getContactEmail(), small(MUTED)));
            head.addCell(company);
            PdfPCell title = cell();
            title.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph t = new Paragraph("INVOICE", new Font(Font.HELVETICA, 20, Font.BOLD, INK));
            t.setAlignment(Element.ALIGN_RIGHT);
            title.addElement(t);
            for (String[] kv : new String[][]{
                    {"Invoice", invoice.getInvoiceNumber()},
                    {"Issued", date(invoice.getIssueDate())},
                    {"Due", date(invoice.getDueDate())},
                    {"Status", statusLabel(invoice.getStatus())}}) {
                Paragraph p = new Paragraph(kv[0] + ": " + (kv[1] == null ? "—" : kv[1]), small(INK));
                p.setAlignment(Element.ALIGN_RIGHT);
                title.addElement(p);
            }
            head.addCell(title);
            doc.add(head);
            doc.add(spacer(14));

            // Bill to.
            doc.add(new Paragraph("BILL TO", new Font(Font.HELVETICA, 8, Font.BOLD, MUTED)));
            doc.add(new Paragraph(user == null ? "—" : nz(user.getFullName()), new Font(Font.HELVETICA, 11, Font.BOLD, INK)));
            if (user != null) {
                if (user.getParticipantId() != null) doc.add(new Paragraph("Participant ID: " + user.getParticipantId(), small(INK)));
                if (user.getEmail() != null) doc.add(new Paragraph(user.getEmail(), small(MUTED)));
            }
            doc.add(spacer(14));

            // Lines.
            PdfPTable lines = new PdfPTable(new float[]{5, 2});
            lines.setWidthPercentage(100);
            lines.addCell(headerCell("Description", Element.ALIGN_LEFT));
            lines.addCell(headerCell("Amount", Element.ALIGN_RIGHT));
            lines.addCell(bodyCell(description(invoice, plan), Element.ALIGN_LEFT, false));
            lines.addCell(bodyCell(Money.usd(invoice.getAmount()), Element.ALIGN_RIGHT, false));
            for (PaymentLedger l : entries) {
                String type = PaymentService.typeOf(l);
                String label = switch (type) {
                    case PaymentService.ENTRY_PAYMENT -> "Payment received " + date(l.getReceiptDate()) + " (" + method(l.getMethod()) + ")";
                    case PaymentService.ENTRY_WAIVER -> "Amount waived " + date(l.getReceiptDate());
                    case PaymentService.ENTRY_REVERSAL -> "Payment reversed " + date(l.getReceiptDate());
                    default -> "Payment attempt that didn't go through " + date(l.getReceiptDate()) + " (not applied)";
                };
                BigDecimal amount = l.getAmountReceived() == null ? BigDecimal.ZERO : l.getAmountReceived();
                String shown = switch (type) {
                    case PaymentService.ENTRY_PAYMENT, PaymentService.ENTRY_WAIVER -> "−" + Money.usd(amount);
                    case PaymentService.ENTRY_REVERSAL -> "+" + Money.usd(amount);
                    default -> Money.usd(amount);
                };
                lines.addCell(bodyCell(label, Element.ALIGN_LEFT, false));
                lines.addCell(bodyCell(shown, Element.ALIGN_RIGHT, false));
            }
            lines.addCell(bodyCell("Balance due", Element.ALIGN_LEFT, true));
            lines.addCell(bodyCell(Money.usd(PaymentService.balanceOf(invoice)), Element.ALIGN_RIGHT, true));
            doc.add(lines);
            doc.add(spacer(18));

            doc.add(new Paragraph("All amounts are in US dollars.", small(MUTED)));
            doc.add(new Paragraph("Pay by check (mail it and add the tracking details in your portal) or as agreed with "
                    + "our finance team. Your dashboard shows this invoice's status.", small(MUTED)));
            String contact = brandConfig.getContactEmail();
            if (contact != null && !contact.isBlank()) {
                doc.add(new Paragraph("Questions about this invoice: " + contact, small(MUTED)));
            }
            doc.close();
        } catch (DocumentException e) {
            throw new IllegalStateException("Couldn't create the invoice PDF: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    /** "Installment 2 of 6 — plan PLAN-2026-00001". */
    private String description(Invoice invoice, PaymentPlan plan) {
        if (plan == null) return "Program fee";
        int position = 0;
        List<Invoice> all = new ArrayList<>(invoiceRepository.findByPaymentPlanId(plan.getId()));
        all.sort(Comparator.comparing(Invoice::getId));
        for (int i = 0; i < all.size(); i++) if (all.get(i).getId().equals(invoice.getId())) position = i + 1;
        int of = plan.getInstallments() == null ? all.size() : plan.getInstallments();
        return "Program fee — installment " + (position == 0 ? "" : position + " of " + of) + " (plan " + plan.getPlanId() + ")";
    }

    private List<String> addressLines() {
        List<String> lines = new ArrayList<>();
        if (notBlank(brandConfig.getAddressLine1())) lines.add(brandConfig.getAddressLine1());
        if (notBlank(brandConfig.getAddressLine2())) lines.add(brandConfig.getAddressLine2());
        String cityLine = String.join(" ", List.of(
                notBlank(brandConfig.getCity()) ? brandConfig.getCity() + "," : "",
                nz(brandConfig.getState()), nz(brandConfig.getPostalCode()))).trim();
        if (!cityLine.isBlank() && !cityLine.equals(",")) lines.add(cityLine.replaceAll(",$", ""));
        return lines;
    }

    private Color brandColor() {
        try {
            String hex = brandConfig.getPrimaryColor();
            int rgb = Integer.parseInt(hex.replace("#", ""), 16);
            return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } catch (Exception e) {
            return new Color(27, 42, 92);
        }
    }

    private static PdfPCell cell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private static PdfPCell headerCell(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 9, Font.BOLD, MUTED)));
        c.setHorizontalAlignment(align);
        c.setBackgroundColor(LIGHT_BG);
        c.setBorderColor(RULE);
        c.setPadding(7);
        return c;
    }

    private static PdfPCell bodyCell(String text, int align, boolean bold) {
        PdfPCell c = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 10, bold ? Font.BOLD : Font.NORMAL, INK)));
        c.setHorizontalAlignment(align);
        c.setBorderColor(RULE);
        c.setPadding(7);
        if (bold) c.setBackgroundColor(LIGHT_BG);
        return c;
    }

    private static Font small(Color color) {
        return new Font(Font.HELVETICA, 9, Font.NORMAL, color);
    }

    private static Paragraph spacer(float height) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingAfter(height - 10);
        return p;
    }

    private static String date(LocalDate d) {
        return d == null ? "—" : d.format(US_DATE);
    }

    private static String statusLabel(String status) {
        if (status == null) return "—";
        return switch (status) {
            case "PAID" -> "Paid";
            case "PARTIAL" -> "Part-paid";
            case "OVERDUE" -> "Overdue";
            case "VOID" -> "Void";
            default -> "Unpaid";
        };
    }

    private static String method(String m) {
        if (m == null) return "—";
        return switch (m) {
            case "CHEQUE" -> "check";
            case "BANK_TRANSFER" -> "bank transfer";
            case "CARD" -> "card";
            case "CASH" -> "cash";
            case "ONLINE" -> "online";
            default -> m.toLowerCase();
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
