package com.impulsa.service;

import com.impulsa.dao.ClienteDao;
import com.impulsa.security.PasswordService;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClienteService {
    private final ClienteDao dao;
    private final PasswordService passwords;
    public ClienteService(ClienteDao dao, PasswordService passwords) { this.dao = dao; this.passwords = passwords; }
    public List<Map<String,Object>> list(String q, Boolean active, LocalDate from, LocalDate to) {
        if (q.length() > 150 || (from != null && to != null && from.isAfter(to))) throw invalid("Filtros no válidos.");
        return dao.list(q.trim(), active, from, to);
    }
    @Transactional public long create(Map<String,Object> input) {
        var data = validate(input);
        String hash = passwords.encode(Objects.toString(input.get("password"), ""));
        int user = dao.createAccount(data, hash, "CLI-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return dao.createClient(user, data);
    }
    @Transactional public void update(long id, Map<String,Object> input) {
        var data = validate(input);
        Integer user = dao.lockUser(id);
        if (user == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado.");
        dao.update(id, user, data);
    }
    private Map<String,String> validate(Map<String,Object> input) {
        Map<String,String> data = new LinkedHashMap<>();
        var limits = Map.of("razonSocial",150,"contactoNombre",100,"tipoDocumento",10,"documento",20,"correo",120,"telefono",20,"direccion",200);
        limits.forEach((key, max) -> {
            String value = Objects.toString(input.get(key), "").trim();
            if (value.length() > max) throw invalid("El campo " + key + " supera la longitud permitida.");
            data.put(key, value);
        });
        String type = data.get("tipoDocumento");
        if (!Set.of("DNI","RUC").contains(type) || !data.get("documento").matches(type.equals("DNI") ? "[0-9]{8}" : "[0-9]{11}"))
            throw invalid("El documento debe tener 8 dígitos para DNI u 11 para RUC.");
        data.put("correo", data.get("correo").toLowerCase(Locale.ROOT));
        if (data.get("razonSocial").isBlank() || data.get("contactoNombre").isBlank() || !data.get("correo").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw invalid("Completa razón social, contacto y correo válido.");
        return data;
    }
    private ResponseStatusException invalid(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, message); }
}
