package com.impulsa.security;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AttemptLimiter {
    private record Window(long until, int count) { }
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    public void check(String key, int maximum) {
        long now = System.currentTimeMillis();
        if (windows.size() >= 10000) windows.entrySet().removeIf(entry -> entry.getValue().until() <= now);
        if (windows.size() >= 10000 && !windows.containsKey(key)) throw limited();
        Window result = windows.compute(key, (ignored, old) -> old == null || old.until() <= now ? new Window(now + 900000, 1) : new Window(old.until(), old.count() + 1));
        if (result.count() > maximum) throw limited();
    }
    private ResponseStatusException limited() { return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Espera 15 minutos e inténtalo de nuevo."); }
}
