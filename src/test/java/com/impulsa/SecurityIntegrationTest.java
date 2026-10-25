package com.impulsa;

import com.impulsa.config.*;
import com.impulsa.controller.*;
import com.impulsa.security.*;
import com.impulsa.service.EmailNotificationService;
import com.impulsa.service.ClienteService;
import com.impulsa.service.AdjuntoService;
import com.impulsa.service.ReporteService;
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

@WebMvcTest(controllers = {AuthController.class, UsuarioController.class, TicketController.class, IncidenciaDashboardController.class, ClienteController.class, AdjuntoController.class, ReporteController.class})
@Import({SecurityConfig.class, CorsConfig.class, ApiErrors.class, Access.class, AccountService.class, PasswordService.class, AttemptLimiter.class})
class SecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired PasswordService passwords;
    @MockBean JdbcTemplate jdbc;
    @MockBean EmailNotificationService email;
    @MockBean ClienteService clients;
    @MockBean AdjuntoService attachments;
    @MockBean ReporteService reports;
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
        doReturn(Optional.of(account(3,"TECNICO"))).when(accounts).byId(3);
        doReturn(Optional.of(account(4,"EMPLEADO"))).when(accounts).byId(4);
        doReturn(Optional.of(account(6,"CLIENTE"))).when(accounts).byId(6);
        when(email.enviarCodigo2FA(anyString(),anyString(),anyString())).thenReturn(true);
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", 10L))
            .thenReturn(List.of(ticket("ABIERTO")));
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
        mvc.perform(get("/api/config/smtp").with(actor(3,"TECNICO"))).andExpect(status().isForbidden());
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
        verify(jdbc).update(contains("INSERT INTO ticket_mensajes"),eq(10L),eq(6),eq("Usuario Prueba"),eq("CLIENTE"),eq(false),eq("Ayuda"));
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
    private Map<String,Object> ticket(String state) {
        return new HashMap<>(Map.of("estado", state, "id_usuario_creador", 6,
                "id_empleado_asignado", 7, "prioridad", "Media"));
    }
    private void assignedToEmployee() {
        when(jdbc.queryForObject(contains("JOIN empleados e"), eq(Integer.class), eq(10L), eq(3), eq(3))).thenReturn(1);
    }
    @Test void employeeInboxIsScopedEvenWhenSpoofingOwnerFilter() throws Exception {
        mvc.perform(get("/api/tickets").with(actor(3,"TECNICO"))).andExpect(status().isOk());
        verify(jdbc).queryForList(contains("WHERE agente.id_usuario = ?"), eq(3), eq(3));
        mvc.perform(get("/api/tickets?idUsuario=1").with(actor(3,"TECNICO"))).andExpect(status().isOk());
        verify(jdbc).queryForList(contains("WHERE t.id_usuario_creador = ?"), eq(3));
    }
    @Test void unassignedEmployeeCannotReadConversationOrHistoryOrModifyTicket() throws Exception {
        mvc.perform(get("/api/tickets/10/mensajes").with(actor(3,"TECNICO"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/10/historial").with(actor(3,"TECNICO"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"EN_PROCESO\",\"motivo\":\"Intento ajeno\"}")).andExpect(status().isForbidden());
        verify(jdbc,never()).update(contains("UPDATE tickets"), any(Object[].class));
    }
    @Test void assignedAgentMustStartBeforeResolvingAndCannotAssignOrClose() throws Exception {
        assignedToEmployee();
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", 10L)).thenReturn(List.of(ticket("ASIGNADO")));
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"RESUELTO\",\"motivo\":\"Sin iniciar\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"EN_PROCESO\",\"motivo\":\"Diagnóstico iniciado\"}")).andExpect(status().isOk());
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", 10L)).thenReturn(List.of(ticket("EN_PROCESO")));
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"RESUELTO\",\"motivo\":\"Acceso restaurado\"}")).andExpect(status().isOk());
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"CERRADO\",\"motivo\":\"Cerrar\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(jdbc).update(contains("INSERT INTO ticket_historial"), eq(10L), eq(3), eq("CAMBIO_ESTADO"), eq("EN_PROCESO"), eq("RESUELTO"), eq("Acceso restaurado"));
    }
    @Test void onlySupervisorCanAssignActiveSupportAgentAndPriority() throws Exception {
        when(jdbc.queryForList(contains("FOR SHARE OF u, e"), eq(8))).thenReturn(List.of(Map.of("id_usuario", 3)));
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json")
                .content("{\"idEmpleado\":8,\"prioridad\":\"Alta\",\"motivo\":\"Especialista disponible\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ASIGNADO"));
        verify(jdbc).update(contains("UPDATE tickets SET id_empleado_asignado"), eq(8), eq("Alta"), eq("ASIGNADO"), eq(false), eq(10L));
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(1,"ADMINISTRADOR")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/agentes").with(actor(6,"CLIENTE"))).andExpect(status().isForbidden());
    }
    @Test void assignmentRejectsInactiveAgentAndSelfAssignmentAndTerminalTickets() throws Exception {
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json")
                .content("{\"idEmpleado\":8,\"motivo\":\"Inactivo\"}")).andExpect(status().isBadRequest());
        when(jdbc.queryForList(contains("FOR SHARE OF u, e"), eq(8))).thenReturn(List.of(Map.of("id_usuario", 6)));
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json")
                .content("{\"idEmpleado\":8,\"motivo\":\"Propio\"}")).andExpect(status().isBadRequest());
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", 10L)).thenReturn(List.of(ticket("RESUELTO")));
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json")
                .content("{\"idEmpleado\":8,\"motivo\":\"Cerrado\"}")).andExpect(status().isConflict());
    }
    @Test void historicalOpenTicketWithSameAgentBecomesAssignedAndReopenClearsAssignment() throws Exception {
        when(jdbc.queryForList(contains("FOR SHARE OF u, e"), eq(7))).thenReturn(List.of(Map.of("id_usuario", 3)));
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json")
                .content("{\"idEmpleado\":7,\"motivo\":\"Confirmar asignación histórica\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("ASIGNADO"));
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", 10L)).thenReturn(List.of(ticket("CERRADO")));
        mvc.perform(patch("/api/tickets/10/estado").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"ABIERTO\",\"motivo\":\"Reaparece el problema\"}")).andExpect(status().isOk());
        verify(jdbc).update(contains("id_empleado_asignado = CASE WHEN ? = 'ABIERTO' THEN NULL"),
                eq("ABIERTO"), eq("ABIERTO"), eq("ABIERTO"), eq("ABIERTO"), eq(10L));
    }
    @Test void supervisorCannotDoAgentWorkAndAdminHasReadOnlyTicketAccess() throws Exception {
        mvc.perform(patch("/api/tickets/10/estado").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"EN_PROCESO\",\"motivo\":\"Atender\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/tickets/10/estado").with(actor(1,"ADMINISTRADOR")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"RESUELTO\",\"motivo\":\"Resolver\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/tickets/10/mensajes").with(actor(1,"ADMINISTRADOR")).with(csrf()).contentType("application/json")
                .content("{\"contenido\":\"Respuesta\"}")).andExpect(status().isForbidden());
    }
    @Test void ownerConfirmsResolvedTicketButCannotSeeOrPublishInternalNotes() throws Exception {
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tickets"), eq(Integer.class), eq(10L), eq(6))).thenReturn(1);
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", 10L)).thenReturn(List.of(ticket("RESUELTO")));
        mvc.perform(post("/api/tickets/10/mensajes").with(actor(6,"CLIENTE")).with(csrf()).contentType("application/json")
                .content("{\"contenido\":\"Oculto\",\"esInterno\":true}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/10/historial").with(actor(6,"CLIENTE"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/tickets/10/estado").with(actor(6,"CLIENTE")).with(csrf()).contentType("application/json")
                .content("{\"estado\":\"CERRADO\",\"motivo\":\"Confirmo que funciona\"}")).andExpect(status().isOk());
    }
    @Test void agentCanPublishInternalNoteAndClosedConversationsRejectWrites() throws Exception {
        assignedToEmployee();
        mvc.perform(post("/api/tickets/10/mensajes").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
                .content("{\"contenido\":\"Diagnóstico privado\",\"esInterno\":true}")).andExpect(status().isCreated());
        verify(jdbc).update(contains("INSERT INTO ticket_mensajes"), eq(10L), eq(3), eq("Usuario Prueba"), eq("TECNICO"), eq(true), eq("Diagnóstico privado"));
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE", 10L)).thenReturn(List.of(ticket("CERRADO")));
        mvc.perform(post("/api/tickets/10/mensajes").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
                .content("{\"contenido\":\"Fuera de plazo\"}")).andExpect(status().isConflict());
    }
    @Test void incidentReviewIsExclusiveToSupervisorAndAgentOnlyListsOwn() throws Exception {
        mvc.perform(get("/api/incidencias").with(actor(3,"TECNICO"))).andExpect(status().isOk());
        verify(jdbc).queryForList(contains("WHERE id_usuario_registro = ?"), eq(3));
        mvc.perform(post("/api/incidencias").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/incidencias/INC-1/estado").with(actor(1,"ADMINISTRADOR")).with(csrf()).contentType("application/json").content("{\"estado\":\"Aprobada\"}"))
                .andExpect(status().isForbidden());
        when(jdbc.update(contains("UPDATE incidencias SET estado"), eq("Aprobada"), eq("INC-1"), eq(2))).thenReturn(1);
        mvc.perform(patch("/api/incidencias/INC-1/estado").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json").content("{\"estado\":\"Aprobada\"}"))
                .andExpect(status().isOk());
        when(jdbc.update(contains("UPDATE incidencias SET estado"), eq("Aprobada"), eq("INC-1"), eq(2))).thenReturn(0);
        mvc.perform(patch("/api/incidencias/INC-1/estado").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json").content("{\"estado\":\"Aprobada\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/incidencias/INC-1/estado").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json").content("{\"estado\":\"Inventado\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void internalEmployeeIsRequesterNotSupport() throws Exception {
        mvc.perform(get("/api/tickets?idUsuario=1").with(actor(4,"EMPLEADO"))).andExpect(status().isOk());
        verify(jdbc).queryForList(contains("WHERE t.id_usuario_creador = ?"),eq(4));
        for(String path:List.of("/api/incidencias","/api/tickets/agentes","/api/usuarios","/api/clientes","/api/reportes/exportar?formato=xlsx"))
            mvc.perform(get(path).with(actor(4,"EMPLEADO"))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/tickets/10/asignacion").with(actor(4,"EMPLEADO")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
    }
    @Test void technicianCanRequestInformationAndResumeButCannotSkipToResolution() throws Exception {
        assignedToEmployee();
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE",10L)).thenReturn(List.of(ticket("EN_PROCESO")));
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
            .content("{\"estado\":\"PENDIENTE_CLIENTE\",\"motivo\":\"Enviar captura del error\"}")).andExpect(status().isOk());
        when(jdbc.queryForList("SELECT * FROM tickets WHERE id_ticket = ? FOR UPDATE",10L)).thenReturn(List.of(ticket("PENDIENTE_CLIENTE")));
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
            .content("{\"estado\":\"RESUELTO\",\"motivo\":\"Sin revisar respuesta\"}")).andExpect(status().isConflict());
        mvc.perform(patch("/api/tickets/10/estado").with(actor(3,"TECNICO")).with(csrf()).contentType("application/json")
            .content("{\"estado\":\"EN_PROCESO\",\"motivo\":\"Captura recibida\"}")).andExpect(status().isOk());
    }
    @Test void onlyAdministratorWritesCorporateDirectory() throws Exception {
        mvc.perform(get("/api/clientes?q=Empresa").with(actor(2,"SUPERVISOR"))).andExpect(status().isOk());
        mvc.perform(post("/api/clientes").with(actor(2,"SUPERVISOR")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        when(clients.create(anyMap())).thenReturn(1L);
        mvc.perform(post("/api/clientes").with(actor(1,"ADMINISTRADOR")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isCreated());
        mvc.perform(put("/api/clientes/1").with(actor(1,"ADMINISTRADOR")).with(csrf()).contentType("application/json").content("{}"))
            .andExpect(status().isOk());
    }
    @Test void attachmentRoutesEnforceTicketOwnershipAndCsrf() throws Exception {
        mvc.perform(get("/api/tickets/10/adjuntos").with(actor(6,"CLIENTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/tickets/10/adjuntos/2").with(actor(3,"TECNICO"))).andExpect(status().isForbidden());
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM tickets"),eq(Integer.class),eq(10L),eq(6))).thenReturn(1);
        mvc.perform(get("/api/tickets/10/adjuntos").with(actor(6,"CLIENTE"))).andExpect(status().isOk());
        var file=new org.springframework.mock.web.MockMultipartFile("archivo","evidencia.pdf","application/pdf","%PDF-1.7".getBytes());
        mvc.perform(multipart("/api/tickets/10/adjuntos").file(file).with(actor(6,"CLIENTE"))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/tickets/10/adjuntos").file(file).with(actor(6,"CLIENTE")).with(csrf())).andExpect(status().isCreated());
    }
    @Test void reportDownloadHasAttachmentHeaderAndIsRestrictedToManagers() throws Exception {
        when(reports.export("xlsx",null,null)).thenReturn(new byte[]{80,75});
        mvc.perform(get("/api/reportes/exportar?formato=xlsx").with(actor(2,"SUPERVISOR"))).andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition","attachment; filename=\"tickets-a365.xlsx\""))
            .andExpect(header().string("Cache-Control","private, no-store"));
        mvc.perform(get("/api/reportes/exportar?formato=pdf").with(actor(6,"CLIENTE"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/reportes/exportar?formato=xlsx").with(actor(3,"TECNICO"))).andExpect(status().isForbidden());
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
