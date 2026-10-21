package com.impulsa.controller;

import com.impulsa.security.*;
import com.impulsa.service.EmailNotificationService;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.SecureRandom;
import java.io.Serializable;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final String LOGIN = "A365_LOGIN_CHALLENGE";
    private static final String RESET = "A365_RESET_CHALLENGE";
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final PasswordService passwords;
    private final EmailNotificationService email;
    private final AttemptLimiter limiter;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository csrf;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();
    @Value("${app.security.log-codes:false}") private boolean localCodes;

    public AuthController(JdbcTemplate jdbc, AccountService accounts, PasswordService passwords,
            EmailNotificationService email, AttemptLimiter limiter, HttpSessionSecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrf, TransactionTemplate transactions) {
        this.jdbc = jdbc; this.accounts = accounts; this.passwords = passwords; this.email = email;
        this.limiter = limiter; this.contexts = contexts; this.csrf = csrf; this.transactions = transactions;
    }
    private static class Challenge implements Serializable {
        final int id; final String email; final String hash; final String version; final long deadline;
        int attempts;
        Challenge(int id, String email, String hash, String version, long duration) {
            this.id = id; this.email = email; this.hash = hash; this.version = version;
            this.deadline = System.currentTimeMillis() + duration;
        }
    }
    private String text(Map<String,Object> body, String key) { return Objects.toString(body.get(key), "").trim(); }
    private ResponseStatusException invalidCode() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código inválido, expirado o sin una verificación pendiente. Vuelve a iniciar el proceso."); }
    private String otp() { return String.valueOf(100000 + random.nextInt(900000)); }

    @GetMapping("/csrf")
    public Map<String,String> csrf(CsrfToken token) { return Map.of("token", token.getToken(), "headerName", token.getHeaderName()); }

    @GetMapping("/me")
    public Map<String,Object> me() {
        var principal = (SessionUser) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return Map.of("usuario", accounts.publicUser(accounts.require(principal.id())));
    }

    @PostMapping("/register-cliente")
    public ResponseEntity<?> register(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        limiter.check("register:" + request.getRemoteAddr(), 10);
        String name = text(body,"razonSocial"), document = text(body,"documento"), address = text(body,"correo").toLowerCase(Locale.ROOT);
        String password = Objects.toString(body.get("password"), ""), phone = text(body,"telefono");
        passwords.validate(password);
        if (name.isEmpty() || name.length() > 100 || !document.matches("(?:[0-9]{8}|[0-9]{11})") || !address.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || address.length()>120)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Revisa el nombre, documento y correo.");
        String hash = passwords.encode(password);
        transactions.executeWithoutResult(status -> {
            Integer role = jdbc.queryForObject("SELECT id_rol FROM roles WHERE nombre = 'CLIENTE'", Integer.class);
            String code = "CLI-" + UUID.randomUUID().toString().replace("-", "").substring(0,16);
            Integer id = jdbc.queryForObject("""
                INSERT INTO usuarios (codigo_usuario,correo,password_hash,nombres,apellidos,dni_ruc,telefono,departamento,cargo,id_rol,doble_factor_activo,alerta_login_activa,activo)
                VALUES (?,?,?,?,'',?,?,'Externo','Cliente',?,TRUE,TRUE,TRUE) RETURNING id_usuario
                """, Integer.class, code,address,hash,name,document,phone,role);
            jdbc.update("""
                INSERT INTO clientes (id_usuario,tipo_documento,numero_documento,razon_social,contacto_nombre,telefono)
                VALUES (?,?,?,?,?,?)
                """, id, document.length()==11 ? "RUC":"DNI",document,name,name,phone);
        });
        return ResponseEntity.status(201).body(Map.of("success",true,"mensaje","Cuenta creada. Ya puedes iniciar sesión."));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String,Object> body, HttpServletRequest request, HttpServletResponse response) {
        String identifier = text(body,"identificador");
        limiter.check("login-ip:" + request.getRemoteAddr(), 40);
        limiter.check("login-user:" + identifier.toLowerCase(Locale.ROOT), 15);
        var found = identifier.length()>120 ? Optional.<Map<String,Object>>empty() : accounts.byLogin(identifier);
        var account = found.orElse(Map.of());
        boolean matched = passwords.matches(Objects.toString(body.get("password"),""), accounts.hash(account));
        if (!matched || !Boolean.TRUE.equals(account.get("activo")))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Correo, código o contraseña incorrectos, o cuenta no disponible.");
        HttpSession session = request.getSession(true);
        synchronized(session) {
            session.removeAttribute(LOGIN); session.removeAttribute(RESET);
            SecurityContextHolder.clearContext();
            contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
            request.changeSessionId();
            if (!Boolean.TRUE.equals(account.get("dobleFactorActivo"))) return complete(account,request,response);
            String code = otp();
            boolean sent = email.enviarCodigo2FA(String.valueOf(account.get("correo")),accounts.name(account),code);
            if (!sent && !localCodes) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"No se pudo enviar el código. Revisa la configuración de correo o inténtalo más tarde.");
            session.setAttribute(LOGIN,new Challenge(accounts.id(account),String.valueOf(account.get("correo")),passwords.migrate(code),accounts.hash(account),600000));
            return ResponseEntity.ok(Map.of("success",true,"requires2FA",true,"correo",account.get("correo"),"smtpEnviado",sent,
                    "mensaje",sent ? "Revisa tu correo para continuar." : "Modo local: consulta el código en la consola del backend."));
        }
    }

    @PostMapping("/verify-2fa")
    public ResponseEntity<?> verify(@RequestBody Map<String,Object> body, HttpServletRequest request, HttpServletResponse response) {
        limiter.check("verify:" + request.getRemoteAddr(),40);
        HttpSession session = request.getSession(false);
        if (session == null) throw invalidCode();
        synchronized(session) {
            Challenge challenge = check(session,LOGIN,text(body,"codigoOtp"));
            var account = accounts.require(challenge.id);
            if (!accounts.hash(account).equals(challenge.version)) { session.removeAttribute(LOGIN); throw invalidCode(); }
            session.removeAttribute(LOGIN);
            return complete(account,request,response);
        }
    }
    private Challenge check(HttpSession session, String key, String code) {
        Object raw = session.getAttribute(key);
        if (!(raw instanceof Challenge challenge)) throw invalidCode();
        if (challenge.deadline < System.currentTimeMillis() || challenge.attempts >= 5) { session.removeAttribute(key); throw invalidCode(); }
        challenge.attempts++;
        if (!code.matches("[0-9]{6}") || !passwords.matches(code,challenge.hash)) {
            if (challenge.attempts>=5) session.removeAttribute(key);
            throw invalidCode();
        }
        return challenge;
    }
    private ResponseEntity<?> complete(Map<String,Object> account,HttpServletRequest request,HttpServletResponse response) {
        request.getSession(true); request.changeSessionId();
        var principal = new SessionUser(accounts.id(account),String.valueOf(account.get("rol")),accounts.hash(account));
        var auth = UsernamePasswordAuthenticationToken.authenticated(principal,null,List.of(new SimpleGrantedAuthority("ROLE_"+principal.role())));
        var context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
                jdbc.update("UPDATE usuarios SET ultimo_login = CURRENT_TIMESTAMP WHERE id_usuario = ?",principal.id());
        boolean sent = Boolean.TRUE.equals(account.get("alertaLoginActiva")) && email.enviarAlertaInicioSesion(String.valueOf(account.get("correo")),accounts.name(account),request.getRemoteAddr(),"Navegador web");
        jdbc.update("INSERT INTO sesiones_alertas (id_usuario,ip_acceso,dispositivo,correo_alerta_enviado) VALUES (?,?,?,?)",principal.id(),request.getRemoteAddr(),"Navegador web",sent);
        SecurityContextHolder.setContext(context); contexts.saveContext(context,request,response);
        csrf.saveToken(null,request,response);
        return ResponseEntity.ok(Map.of("success",true,"usuario",accounts.publicUser(account),"alertaCorreoEnviada",sent));
    }

    @PostMapping("/recuperar-clave")
    public ResponseEntity<?> recover(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String address = text(body,"correo").toLowerCase(Locale.ROOT);
        limiter.check("recover-ip:"+request.getRemoteAddr(),10); limiter.check("recover-user:"+address,5);
        HttpSession session = request.getSession(true);
        synchronized(session) {
            session.removeAttribute(RESET);
            var found = accounts.byLogin(address).filter(a -> address.equalsIgnoreCase(String.valueOf(a.get("correo"))) && Boolean.TRUE.equals(a.get("activo")));
            String code = otp();
            var account = found.orElse(Map.of());
            session.setAttribute(RESET,new Challenge(found.isPresent()?accounts.id(account):0,address,passwords.migrate(code),accounts.hash(account),1800000));
            if (found.isPresent()) email.enviarInvitacionRecuperarClave(address,accounts.name(account),code);
        }
        return ResponseEntity.ok(Map.of("success",true,"mensaje","Si el correo corresponde a una cuenta activa, recibirás las instrucciones. Usa esta misma ventana para completar la recuperación."));
    }
    @PostMapping("/restablecer-clave")
    public ResponseEntity<?> reset(@RequestBody Map<String,Object> body,HttpServletRequest request) {
        String address = text(body,"correo").toLowerCase(Locale.ROOT);
        limiter.check("reset-ip:"+request.getRemoteAddr(),20); limiter.check("reset-user:"+address,5);
        String password = Objects.toString(body.get("nuevaPassword"),""); passwords.validate(password);
        HttpSession session = request.getSession(false);
        if (session==null) throw invalidCode();
        synchronized(session) {
            Challenge challenge=check(session,RESET,text(body,"codigoOtp"));
            if (challenge.id==0 || !challenge.email.equals(address)) throw invalidCode();
            var account=accounts.require(challenge.id);
            if (!accounts.hash(account).equals(challenge.version)) { session.removeAttribute(RESET); throw invalidCode(); }
            String hash=passwords.encode(password);
            int changed=jdbc.update("UPDATE usuarios SET password_hash = ?, actualizado_en = CURRENT_TIMESTAMP WHERE id_usuario = ? AND password_hash = ?",hash,challenge.id,challenge.version);
            session.removeAttribute(RESET);
            if(changed!=1) throw invalidCode();
            session.invalidate(); SecurityContextHolder.clearContext();
        }
        return ResponseEntity.ok(Map.of("success",true,"mensaje","Contraseña actualizada. Inicia sesión nuevamente."));
    }
}
