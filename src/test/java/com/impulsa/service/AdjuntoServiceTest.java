package com.impulsa.service;

import com.impulsa.dao.AdjuntoDao;
import com.impulsa.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class AdjuntoServiceTest {
    @Mock AdjuntoDao dao;@Mock Access access;
    private AdjuntoService service;
    @BeforeEach void setup(){service=new AdjuntoService(dao,access);}
    private void owner(String state){
        when(dao.lockTicket(10L)).thenReturn(Map.of("id_usuario_creador",6,"estado",state));
        when(access.current()).thenReturn(new SessionUser(6,"CLIENTE","hash"));
    }
    private MockMultipartFile pdf(){return new MockMultipartFile("archivo","../evidencia.pdf","application/pdf","%PDF-1.7 prueba".getBytes(StandardCharsets.US_ASCII));}
    @Test void storesBinaryAndAuditWithSafeFileName() throws Exception {
        owner("ABIERTO");when(dao.save(eq(10L),eq("evidencia.pdf"),eq("application/pdf"),any(byte[].class))).thenReturn(2L);
        assertEquals(2L,service.upload(10L,pdf()));verify(dao).audit(10L,6,"Archivo adjunto: evidencia.pdf");
    }
    @Test void rejectsContentSpoofingSizeAndExcessAttachments(){
        owner("ABIERTO");
        var fake=new MockMultipartFile("archivo","foto.png","image/png","<script>bad</script>".getBytes(StandardCharsets.UTF_8));
        assertEquals(400,assertThrows(ResponseStatusException.class,()->service.upload(10L,fake)).getStatusCode().value());
        var huge=new MockMultipartFile("archivo","big.pdf","application/pdf",new byte[AdjuntoService.MAX_BYTES+1]);
        assertThrows(ResponseStatusException.class,()->service.upload(10L,huge));
        when(dao.count(10L)).thenReturn(5);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.upload(10L,pdf())).getStatusCode().value());
        verify(dao,never()).save(anyLong(),anyString(),anyString(),any(byte[].class));
    }
    @Test void readOnlyAdministratorAndClosedCasesCannotUpload(){
        owner("CERRADO");assertEquals(409,assertThrows(ResponseStatusException.class,()->service.upload(10L,pdf())).getStatusCode().value());
        when(access.current()).thenReturn(new SessionUser(1,"ADMINISTRADOR","hash"));
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.upload(10L,pdf())).getStatusCode().value());
    }
    @Test void missingHistoricalBinaryDoesNotExposeStoragePath(){
        when(dao.find(10L,2L)).thenReturn(Map.of("ruta_almacenamiento","/private/file"));
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.download(10L,2L)).getStatusCode().value());
    }
}
