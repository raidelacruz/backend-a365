package com.impulsa.service;

import com.impulsa.dao.ReporteDao;
import java.io.*;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReporteService {
    private final ReporteDao dao;
    public ReporteService(ReporteDao dao) { this.dao = dao; }
    public byte[] export(String format, LocalDate from, LocalDate to) throws IOException {
        if (!Set.of("pdf","xlsx").contains(format)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Formato no válido.");
        if (from != null && to != null && from.isAfter(to)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"El período no es válido.");
        var rows = dao.tickets(from,to);
        if (rows.size() > 2000) throw new ResponseStatusException(HttpStatus.CONFLICT,"Selecciona un período con un máximo de 2000 tickets para exportar.");
        return format.equals("pdf") ? pdf(rows,from,to) : xlsx(rows,from,to);
    }
    private Map<String,Object> metrics(List<Map<String,Object>> rows) {
        long resolved = rows.stream().filter(row -> Set.of("RESUELTO","CERRADO").contains(row.get("estado"))).count();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("Tickets registrados",rows.size()); result.put("Resueltos o cerrados",resolved);
        result.put("Tasa de resolución",rows.isEmpty() ? "Sin datos" : String.format(Locale.ROOT,"%.1f %%",100.0*resolved/rows.size()));
        return result;
    }
    private String period(LocalDate from,LocalDate to) { return (from == null ? "Inicio" : from.toString()) + " a " + (to == null ? "Hoy" : to.toString()); }
    private byte[] xlsx(List<Map<String,Object>> rows,LocalDate from,LocalDate to) throws IOException {
        try (var book = new XSSFWorkbook(); var bytes = new ByteArrayOutputStream()) {
            var summary = book.createSheet("Indicadores");
            summary.createRow(0).createCell(0).setCellValue("IMPULSA A365 - Reporte de tickets");
            summary.createRow(1).createCell(0).setCellValue("Período: " + period(from,to));
            int i=3;
            for (var metric:metrics(rows).entrySet()) {
                var row = summary.createRow(i++); row.createCell(0).setCellValue(metric.getKey());
                if (metric.getValue() instanceof Number n) row.createCell(1).setCellValue(n.doubleValue());
                else row.createCell(1).setCellValue(metric.getValue().toString());
            }
            summary.setColumnWidth(0,11000); summary.setColumnWidth(1,6000);
            var sheet = book.createSheet("Tickets");
            String[] labels={"Código","Asunto","Categoría","Prioridad","Estado","Solicitante","Responsable","Fecha"};
            String[] keys={"codigo","asunto","categoria","prioridad","estado","solicitante","responsable","fecha"};
            var header=sheet.createRow(0);
            var style=book.createCellStyle(); var font=book.createFont(); font.setBold(true); style.setFont(font);
            for(int col=0;col<labels.length;col++) { var cell=header.createCell(col); cell.setCellValue(labels[col]); cell.setCellStyle(style); sheet.setColumnWidth(col,col==1 ? 15000 : 6500); }
            int index=1;
            for(var data:rows) {
                var row=sheet.createRow(index++);
                for(int col=0;col<keys.length;col++) row.createCell(col).setCellValue(Objects.toString(data.get(keys[col]),""));
            }
            // setCellValue(String) crea texto, nunca interpreta fórmulas del usuario.
            sheet.createFreezePane(0,1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,rows.size()),0,7));
            book.write(bytes); return bytes.toByteArray();
        }
    }
    private byte[] pdf(List<Map<String,Object>> rows,LocalDate from,LocalDate to) throws IOException {
        List<String> lines=new ArrayList<>();
        lines.add("IMPULSA A365 - Reporte de tickets"); lines.add("Periodo: " + period(from,to));
        for (var metric : metrics(rows).entrySet()) lines.add(metric.getKey() + ": " + metric.getValue());
        lines.add("");
        for(var row:rows) {
            String title=Objects.toString(row.get("codigo"),"") + " | " + Objects.toString(row.get("asunto"),"");
            lines.add(title);
            lines.add("Estado: " + row.get("estado") + " | Prioridad: " + row.get("prioridad") + " | Fecha: " + row.get("fecha"));
            String detail="Solicitante: " + row.get("solicitante") + " | Responsable: " + row.get("responsable") + " | Categoria: " + row.get("categoria");
            lines.add(detail);
            lines.add("");
        }
        try(var document=new PDDocument();var bytes=new ByteArrayOutputStream()) {
            var font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<String> wrapped = new ArrayList<>();
            for (String line : lines) {
                String safe = new String(line.getBytes(Charset.forName("windows-1252")), Charset.forName("windows-1252")).replaceAll("[\\p{Cntrl}]", " ");
                StringBuilder part = new StringBuilder();
                for (char character : safe.toCharArray()) {
                    if (font.getStringWidth(part.toString() + character) / 1000 * 9 > 515) { wrapped.add(part.toString()); part.setLength(0); }
                    part.append(character);
                }
                wrapped.add(part.toString());
            }
            lines = wrapped;
            for(int offset=0;offset<lines.size();offset+=47) {
                var page=new PDPage(PDRectangle.A4); document.addPage(page);
                try(var content=new PDPageContentStream(document,page)) {
                    content.beginText();content.setFont(font,9);content.setLeading(15);content.newLineAtOffset(38,800);
                    for(String line:lines.subList(offset,Math.min(lines.size(),offset+47))) {
                        content.showText(line);content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(bytes);return bytes.toByteArray();
        }
    }
}
