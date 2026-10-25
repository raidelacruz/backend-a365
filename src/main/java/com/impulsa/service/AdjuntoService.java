package com.impulsa.service;

import com.impulsa.dao.AdjuntoDao;
import com.impulsa.security.Access;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdjuntoService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private final AdjuntoDao dao;
    private final Access access;
    public AdjuntoService(AdjuntoDao dao, Access access) { this.dao = dao; this.access = access; }
    public List<Map<String,Object>> list(long id) { return dao.list(id); }
    @Transactional public long upload(long id, MultipartFile file) throws IOException {
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw error(400,"Adjunta un archivo de hasta 2 MB.");
        var ticket = dao.lockTicket(id);
        if (ticket == null) throw error(404,"Ticket no encontrado.");
        boolean owner = ((Number)ticket.get("id_usuario_creador")).intValue() == access.current().id();
        if (!(owner || access.current().role().equals("SUPERVISOR") || access.canAttendTicket(id, access.authentication())))
            throw error(403,"No puedes adjuntar archivos a este caso.");
        if (Set.of("CERRADO","CANCELADO").contains(ticket.get("estado"))) throw error(409,"El caso está cerrado.");
        if (dao.count(id) >= 5) throw error(409,"Cada ticket admite como máximo cinco adjuntos.");
        byte[] bytes = file.getBytes();
        String name = Objects.toString(file.getOriginalFilename(), "archivo").replace('\\','/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isBlank() || name.length() > 180) throw error(400,"Nombre de archivo no válido.");
        String mime = detect(name, bytes);
        long result = dao.save(id, name, mime, bytes);
        dao.audit(id, access.current().id(), "Archivo adjunto: " + name);
        return result;
    }
    public Map<String,Object> download(long ticket, long attachment) {
        var result = dao.find(ticket, attachment);
        if (result == null || !(result.get("contenido") instanceof byte[])) throw error(404,"El archivo no está disponible.");
        return result;
    }
    static String detect(String name, byte[] data) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf") && data.length >= 5 && new String(data,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) return "application/pdf";
        if (lower.endsWith(".png") && data.length >= 8 && Arrays.equals(Arrays.copyOf(data,8),new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && data.length >= 3 && data[0] == (byte)255 && data[1] == (byte)216 && data[2] == (byte)255) return "image/jpeg";
        throw error(400,"Solo se admiten PDF, PNG y JPEG cuyo contenido corresponda al formato.");
    }
    private static ResponseStatusException error(int code,String message) { return new ResponseStatusException(HttpStatusCode.valueOf(code),message); }
}
