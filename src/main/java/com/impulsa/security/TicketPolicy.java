package com.impulsa.security;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Reglas del flujo, independientes de los controles visuales. */
public final class TicketPolicy {
    private TicketPolicy() {}
    public static final Set<String> TERMINAL = Set.of("RESUELTO", "CERRADO", "CANCELADO");

    public static void transition(String role, boolean assigned, boolean owner, String previous, String next) {
        boolean allowed;
        if (role.equals("EMPLEADO") && assigned) {
            allowed = (previous.equals("ASIGNADO") && next.equals("EN_PROCESO"))
                    || (previous.equals("EN_PROCESO") && next.equals("RESUELTO"));
        } else if (role.equals("SUPERVISOR")) {
            allowed = (!TERMINAL.contains(previous) && next.equals("CANCELADO"))
                    || (previous.equals("RESUELTO") && next.equals("CERRADO"))
                    || (Set.of("RESUELTO", "CERRADO").contains(previous) && next.equals("ABIERTO"));
        } else {
            allowed = owner && previous.equals("RESUELTO") && next.equals("CERRADO");
        }
        if (!allowed) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Tu rol no permite ese cambio desde el estado actual. Actualiza el caso.");
    }
}
