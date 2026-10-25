package com.impulsa.controller;

import com.impulsa.service.AdjuntoService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequestMapping("/api/tickets/{idTicket}/adjuntos")
@PreAuthorize("@access.canReadTicket(#idTicket, authentication)")
public class AdjuntoController {
    private final AdjuntoService attachments;
    public AdjuntoController(AdjuntoService attachments) { this.attachments = attachments; }
    @GetMapping public Object list(@PathVariable long idTicket) { return attachments.list(idTicket); }
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable long idTicket, @RequestParam("archivo") MultipartFile file) throws IOException {
        return ResponseEntity.status(201).body(Map.of("success",true,"idAdjunto",attachments.upload(idTicket,file),"mensaje","Archivo guardado."));
    }
    @GetMapping("/{idAdjunto}") public ResponseEntity<byte[]> download(@PathVariable long idTicket, @PathVariable long idAdjunto) {
        var data = attachments.download(idTicket,idAdjunto);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(data.get("tipo_mime").toString()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(data.get("nombre_original").toString(),StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options","nosniff").header(HttpHeaders.CACHE_CONTROL,"private, no-store")
                .body((byte[])data.get("contenido"));
    }
}
