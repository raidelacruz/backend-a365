package com.impulsa.config;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<?> status(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("success", false, "error", ex.getReason() == null ? "Solicitud no válida." : ex.getReason()));
    }
    @ExceptionHandler(AccessDeniedException.class) public ResponseEntity<?> denied() { return ResponseEntity.status(403).body(Map.of("success", false, "error", "No tienes permiso para realizar esta acción.")); }
    @ExceptionHandler(DataIntegrityViolationException.class) public ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("success", false, "error", "Los datos ya existen o no cumplen las restricciones del registro.")); }
    @ExceptionHandler({IllegalArgumentException.class, org.springframework.http.converter.HttpMessageNotReadableException.class}) public ResponseEntity<?> invalid() { return ResponseEntity.badRequest().body(Map.of("success", false, "error", "Revisa los datos de la solicitud.")); }
    @ExceptionHandler(Exception.class) public ResponseEntity<?> failure(Exception ex) {
        org.slf4j.LoggerFactory.getLogger(ApiErrors.class).error("Fallo de API: {}", ex.getClass().getSimpleName());
        return ResponseEntity.status(500).body(Map.of("success", false, "error", "No se pudo completar la operación. Inténtalo nuevamente."));
    }
}
