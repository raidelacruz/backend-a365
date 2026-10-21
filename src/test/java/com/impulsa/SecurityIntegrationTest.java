package com.impulsa;

import com.impulsa.config.*;
import com.impulsa.controller.*;
import com.impulsa.security.*;
import com.impulsa.service.EmailNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.junit.jupiter.api.Assertions.*;

@WebMvcTest(controllers = {AuthController.class, UsuarioController.class, TicketController.class, IncidenciaDashboardController.class})
@Import({SecurityConfig.class, CorsConfig.class, ApiErrors.class, Access.class, AccountService.class, PasswordService.class, AttemptLimiter.class})
class SecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordService passwords;
    @MockBean JdbcTemplate jdbc;
    @MockBean EmailNotificationService email;
    @MockBean TransactionTemplate transactions;
    @SpyBean AccountService accounts;
    private static String hash;
    private Map<String,Object> account(int id,String role) {
        return new HashMap<>(Map.of("idUsuario",id,"rol",role,"passwordHash",hash,"activo",true,"dobleFactorActivo",true,
                "alertaLoginActiva",false,"correo","u"+id+"@example.test","nombres","Usuario","apellidos","Prueba"));
    }
    @BeforeEach void setup() {
        if(hash==null) hash=passwords.encode("Password123!");
        doReturn(Optional.of(account(1,"ADMINISTRADOR"))).when(accounts).byId(1);
        doReturn(Optional.of(account(2,"SUPERVISOR"))).when(accounts).byId(2);
        doReturn(Optional.of(account(3,"EMPLEADO"))).when(accounts).byId(3);
        doReturn(Optional.of(account(6,"CLIENTE"))).when(accounts).byId(6);
        when(email.enviarCodigo2FA(anyString(),anyString(),anyString())).thenReturn(true);
    }
    private org.springframework.test.web.servlet.request.RequestPostProcessor actor(int id,String role) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(new SessionUser(id,role,hash),null,List.of(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    @Test void unauthenticatedCannotReadPrivateEndpoints() throws Exception {
        for(String path: List.of("/api/usuarios","/api/tickets","/api/dashboard/resumen","/api/config/smtp","/api/auth/me"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
    }
    @Test void csrfRequiredEvenForPublicLogin() throws Exception {
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{\"identificador\":\"u1\",\"password\":\"Password123!\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(email);
    }
    @Test void onlyAdminCanManageUsersAndSmtp() throws Exception {
        mvc.perform(get("/api/usuarios").with(actor(1,"ADMINISTRADOR"))).andExpect(status().isOk());
        mvc.perform(get("/api/config/smtp").with(actor(3,"EMPLEADO"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/usuarios").with(actor(2,"SUPERVISOR"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/usuarios").with(actor(6,"CLIENTE")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void clientCannotReadOrEditAnotherProfile() throws Exception {
        mvc.perform(get("/api/usuarios/1").with(actor(6,"CLIENTE"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/usuarios/1/perfil").with(actor(6,"CLIENTE")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void clientListIsAlwaysScopedToOwner() throws Exception {
        mvc.perform(get("/api/tickets?idUsuario=1").with(actor(6,"CLIENTE"))).andExpect(status().isOk());
        verify(jdbc).queryForList(contains("WHERE t.id_usuario_creador = ?"),eq(6));
        mvc.perform(patch("/api/tickets/10/estado").with(actor(6,"CLIENTE")).with(csrf()).contentType("application/json").content("{\"estado\":\"RESUELTO\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void clientCannotReadOtherTicketConversation() throws Exception {
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tickets"),eq(Integer.class),eq(10L),eq(6))).thenReturn(0);
        mvc.perform(get("/api/tickets/10/mensajes").with(actor(6,"CLIENTE"))).andExpect(status().isForbidden());
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tickets"),eq(Integer.class),eq(10L),eq(6))).thenReturn(1);
        mvc.perform(get("/api/tickets/10/mensajes").with(actor(6,"CLIENTE"))).andExpect(status().isOk());
        verify(jdbc).queryForList(contains("es_interno = FALSE"),eq(10L),eq(false));
    }
    @Test void disabledAccountAndChangedPasswordInvalidateSession() throws Exception {
        var disabled=account(6,"CLIENTE");disabled.put("activo",false);doReturn(Optional.of(disabled)).when(accounts).byId(6);
        mvc.perform(get("/api/tickets").with(actor(6,"CLIENTE"))).andExpect(status().isUnauthorized());
        var changed=account(1,"ADMINISTRADOR");changed.put("passwordHash","another-hash");doReturn(Optional.of(changed)).when(accounts).byId(1);
        mvc.perform(get("/api/usuarios").with(actor(1,"ADMINISTRADOR"))).andExpect(status().isUnauthorized());
    }
    @Test void roleIsReadFromDatabaseNotFromOldSession() throws Exception {
        doReturn(Optional.of(account(1,"CLIENTE"))).when(accounts).byId(1);
        mvc.perform(get("/api/usuarios").with(actor(1,"ADMINISTRADOR"))).andExpect(status().isForbidden());
    }
    @Test void realCsrfAndTwoFactorCreateServerSessionAndLogoutRevokesIt() throws Exception {
        doReturn(Optional.of(account(6,"CLIENTE"))).when(accounts).byLogin("u6@example.test");
        var tokenResult=mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session=(MockHttpSession)tokenResult.getRequest().getSession();
        String token=json.readTree(tokenResult.getResponse().getContentAsString()).get("token").asText();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN",token).contentType("application/json")
                .content("{\"identificador\":\"u6@example.test\",\"password\":\"Password123!\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requires2FA").value(true))
            .andExpect(jsonPath("$.codigoDemo").doesNotExist()).andExpect(jsonPath("$.idUsuario").doesNotExist());
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);
        verify(email).enviarCodigo2FA(eq("u6@example.test"),anyString(),code.capture());
        mvc.perform(post("/api/auth/verify-2fa").session(session).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(Map.of("codigoOtp",code.getValue(),"idUsuario",1))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.usuario.idUsuario").value(6)).andExpect(jsonPath("$.usuario.passwordHash").doesNotExist());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.usuario.idUsuario").value(6));
        mvc.perform(post("/api/auth/verify-2fa").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("codigoOtp",code.getValue()))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isOk());
        assertTrue(session.isInvalid());
    }
    @Test void otpCannotBeUsedInAnotherBrowserOrWithoutPasswordStep() throws Exception {
        mvc.perform(post("/api/auth/verify-2fa").with(csrf()).contentType("application/json").content("{\"idUsuario\":1,\"codigoOtp\":\"123456\"}"))
            .andExpect(status().isBadRequest());
    }
    @Test void bcryptPreservesHistoricalPasswordAndRejectsPlaintextComparison() {
        String migrated=passwords.migrate("123456");
        assertTrue(passwords.encoded(migrated));assertTrue(passwords.matches("123456",migrated));
        assertFalse(passwords.matches("incorrecta",migrated));assertFalse(passwords.matches("123456","123456"));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->passwords.encode("123456"));
    }
    @Test void changePasswordStoresBcryptNotPlaintext() throws Exception {
        when(jdbc.queryForObject(anyString(),eq(String.class),eq(6))).thenReturn(hash);
        mvc.perform(post("/api/usuarios/6/cambiar-clave").with(actor(6,"CLIENTE")).with(csrf()).contentType("application/json")
            .content("{\"passwordActual\":\"Password123!\",\"nuevaPassword\":\"NewPassword456!\"}"))
            .andExpect(status().isOk());
        ArgumentCaptor<String> stored=ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("SET password_hash"),stored.capture(),eq(6));
        assertTrue(passwords.matches("NewPassword456!",stored.getValue()));
    }
    @Test void messageAuthorCannotBeSpoofedThroughRequestBody() throws Exception {
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tickets"),eq(Integer.class),eq(10L),eq(6))).thenReturn(1);
        mvc.perform(post("/api/tickets/10/mensajes").with(actor(6,"CLIENTE")).with(csrf()).contentType("application/json")
            .content("{\"contenido\":\"Ayuda\",\"idUsuario\":1,\"autorRol\":\"ADMINISTRADOR\",\"autorNombre\":\"Falso\"}"))
            .andExpect(status().isCreated());
        verify(jdbc).update(contains("INSERT INTO ticket_mensajes"),eq(10L),eq(6),eq("Usuario Prueba"),eq("CLIENTE"),eq("Ayuda"));
    }
    @Test void recoveryUsesSessionChallengeAndStoresNewBcryptHash() throws Exception {
        doReturn(Optional.of(account(6,"CLIENTE"))).when(accounts).byLogin("u6@example.test");
        MockHttpSession session=new MockHttpSession();
        mvc.perform(post("/api/auth/recuperar-clave").session(session).with(csrf()).contentType("application/json").content("{\"correo\":\"u6@example.test\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.codigoDemo").doesNotExist()).andExpect(jsonPath("$.tokenEnlace").doesNotExist());
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);
        verify(email).enviarInvitacionRecuperarClave(eq("u6@example.test"),anyString(),code.capture());
        when(jdbc.update(contains("SET password_hash"),anyString(),eq(6),eq(hash))).thenReturn(1);
        mvc.perform(post("/api/auth/restablecer-clave").session(session).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("correo","u6@example.test","codigoOtp",code.getValue(),"nuevaPassword","UpdatedPassword1!"))))
            .andExpect(status().isOk());
        ArgumentCaptor<String> stored=ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("SET password_hash"),stored.capture(),eq(6),eq(hash));
        assertTrue(passwords.matches("UpdatedPassword1!",stored.getValue()));assertTrue(session.isInvalid());
    }
    @Test void migrationPreservesWeakHistoricalPasswordAndDoesNotRehashExistingBcrypt() {
        when(jdbc.queryForList("SELECT id_usuario, password_hash FROM usuarios")).thenReturn(List.of(
            Map.of("id_usuario",6,"password_hash","123456"),Map.of("id_usuario",1,"password_hash",hash)));
        LegacyPasswordMigration migration=new LegacyPasswordMigration(jdbc,passwords); migration.run(null);
        ArgumentCaptor<String> stored=ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("SET password_hash"),stored.capture(),eq(6),eq("123456"));
        assertTrue(passwords.matches("123456",stored.getValue()));
        when(jdbc.queryForList("SELECT id_usuario, password_hash FROM usuarios")).thenReturn(List.of(
            Map.of("id_usuario",6,"password_hash",stored.getValue()),Map.of("id_usuario",1,"password_hash",hash)));
        migration.run(null);
        verify(jdbc,times(1)).update(contains("SET password_hash"),anyString(),eq(6),eq("123456"));
    }
    @Test void fiveWrongOtpAttemptsInvalidateChallenge() throws Exception {
        doReturn(Optional.of(account(6,"CLIENTE"))).when(accounts).byLogin("u6@example.test");
        MockHttpSession session=new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType("application/json").content("{\"identificador\":\"u6@example.test\",\"password\":\"Password123!\"}"))
            .andExpect(status().isOk());
        ArgumentCaptor<String> code=ArgumentCaptor.forClass(String.class);verify(email).enviarCodigo2FA(anyString(),anyString(),code.capture());
        for(int i=0;i<5;i++) mvc.perform(post("/api/auth/verify-2fa").session(session).with(csrf()).contentType("application/json").content("{\"codigoOtp\":\"000000\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/verify-2fa").session(session).with(csrf()).contentType("application/json").content(json.writeValueAsString(Map.of("codigoOtp",code.getValue()))))
            .andExpect(status().isBadRequest());
    }
    @Test void loginRespectsDisabledTwoFactorPreference() throws Exception {
        var plainLogin=account(6,"CLIENTE");plainLogin.put("dobleFactorActivo",false);
        doReturn(Optional.of(plainLogin)).when(accounts).byLogin("u6@example.test");
        MockHttpSession session=new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).with(csrf()).contentType("application/json").content("{\"identificador\":\"u6@example.test\",\"password\":\"Password123!\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.usuario.idUsuario").value(6));
        verify(email,never()).enviarCodigo2FA(anyString(),anyString(),anyString());
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }
}
