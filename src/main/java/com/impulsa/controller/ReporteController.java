package com.impulsa.controller;

import com.impulsa.service.ReporteService;
import java.io.IOException;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/reportes")
@PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')")
public class ReporteController {
    private final ReporteService reports;
    public ReporteController(ReporteService reports) { this.reports = reports; }
    @GetMapping("/exportar") public ResponseEntity<byte[]> export(@RequestParam String formato,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hasta) throws IOException {
        byte[] file=reports.export(formato,desde,hasta);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(formato.equals("pdf") ? "application/pdf" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"tickets-a365." + formato + "\"")
                .header(HttpHeaders.CACHE_CONTROL,"private, no-store").header("X-Content-Type-Options","nosniff").body(file);
    }
}
