package com.impulsa.service;

import com.impulsa.dao.ReporteDao;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ReporteServiceTest {
    private final ReporteDao dao=mock(ReporteDao.class);
    private final ReporteService service=new ReporteService(dao);
    private Map<String,Object> row(){return Map.of("codigo","TK-001","asunto","=HYPERLINK(\"bad\")","categoria","Accesos","prioridad","Alta","estado","RESUELTO","solicitante","Ana","responsable","Luis","fecha","2026-10-24 09:00");}
    @Test void excelHasRealWorkbookDataMetricsAndNoExecutableFormulas() throws Exception {
        when(dao.tickets(null,null)).thenReturn(List.of(row()));
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(service.export("xlsx",null,null)))){
            assertEquals(2,book.getNumberOfSheets());
            var cell=book.getSheet("Tickets").getRow(1).getCell(1);
            assertEquals(CellType.STRING,cell.getCellType());assertEquals(row().get("asunto"),cell.getStringCellValue());
            assertEquals(1,book.getSheet("Indicadores").getRow(3).getCell(1).getNumericCellValue());
        }
    }
    @Test void pdfContainsReportAndPaginatesWithoutLosingTickets() throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<35;i++){var row=new HashMap<>(row());row.put("codigo","TK-"+i);row.put("asunto","Revisión de acceso 😀");rows.add(row);}
        when(dao.tickets(null,null)).thenReturn(rows);
        try(var document=Loader.loadPDF(service.export("pdf",null,null))){
            assertTrue(document.getNumberOfPages()>1);String text=new PDFTextStripper().getText(document);
            assertTrue(text.contains("TK-0"));assertTrue(text.contains("TK-34"));assertTrue(text.contains("Revisión"));
        }
    }
    @Test void validatesDateRangeFormatAndExportLimit(){
        var day=LocalDate.of(2026,10,24);
        assertThrows(ResponseStatusException.class,()->service.export("pdf",day,day.minusDays(1)));
        assertThrows(ResponseStatusException.class,()->service.export("html",null,null));
        verifyNoInteractions(dao);
        when(dao.tickets(null,null)).thenReturn(Collections.nCopies(2001,row()));
        assertThrows(ResponseStatusException.class,()->service.export("xlsx",null,null));
    }
}
