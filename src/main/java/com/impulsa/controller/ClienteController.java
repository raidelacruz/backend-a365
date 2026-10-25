package com.impulsa.controller;

import com.impulsa.service.ClienteService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/clientes")
@PreAuthorize("hasAnyRole('ADMINISTRADOR','SUPERVISOR')")
public class ClienteController {
    private final ClienteService clients;
    public ClienteController(ClienteService clients) { this.clients = clients; }
    @GetMapping public Object list(@RequestParam(defaultValue="") String q, @RequestParam(required=false) Boolean activo,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate hasta) {
        return clients.list(q, activo, desde, hasta);
    }
    @PostMapping @PreAuthorize("hasRole('ADMINISTRADOR')")
    public ResponseEntity<?> create(@RequestBody Map<String,Object> body) {
        return ResponseEntity.status(201).body(Map.of("success",true,"idCliente",clients.create(body),"mensaje","Cliente registrado."));
    }
    @PutMapping("/{id}") @PreAuthorize("hasRole('ADMINISTRADOR')")
    public Object update(@PathVariable long id, @RequestBody Map<String,Object> body) {
        clients.update(id, body);
        return Map.of("success",true,"mensaje","Ficha corporativa actualizada.");
    }
}
