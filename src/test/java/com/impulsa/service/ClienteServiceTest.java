package com.impulsa.service;

import com.impulsa.dao.ClienteDao;
import com.impulsa.security.PasswordService;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class ClienteServiceTest {
    @Mock ClienteDao dao;
    private ClienteService service;
    private final PasswordService passwords=new PasswordService();
    @BeforeEach void setup(){service=new ClienteService(dao,passwords);}
    private Map<String,Object> input(){return new HashMap<>(Map.of("razonSocial","Empresa Prueba SAC","contactoNombre","Ana Torres","tipoDocumento","RUC","documento","20123456789","correo","Ana@Empresa.test","telefono","999888777","direccion","Lima","password","Password123!"));}
    @Test void registersCorporateFieldsAndStoresOnlyHash(){
        when(dao.createAccount(anyMap(),anyString(),startsWith("CLI-"))).thenReturn(9);
        when(dao.createClient(eq(9),anyMap())).thenReturn(12L);
        assertEquals(12L,service.create(input()));
        ArgumentCaptor<String> hash=ArgumentCaptor.forClass(String.class);
        verify(dao).createAccount(argThat(data->data.get("correo").equals("ana@empresa.test")),hash.capture(),anyString());
        assertTrue(passwords.matches("Password123!",hash.getValue()));
        verify(dao).createClient(eq(9),argThat(data->data.get("razonSocial").equals("Empresa Prueba SAC") && data.get("documento").equals("20123456789")));
    }
    @Test void validatesDocumentAndRequiredFieldsBeforeWriting(){
        var data=input();data.put("documento","123");assertThrows(ResponseStatusException.class,()->service.create(data));
        data.put("documento","20123456789");data.put("contactoNombre","");assertThrows(ResponseStatusException.class,()->service.create(data));
        verifyNoInteractions(dao);
    }
    @Test void editsExistingCorporateRecordAndRejectsMissingClient(){
        when(dao.lockUser(12L)).thenReturn(9);service.update(12L,input());
        verify(dao).update(eq(12L),eq(9),argThat(data->data.get("direccion").equals("Lima")));
        when(dao.lockUser(404L)).thenReturn(null);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->service.update(404L,input())).getStatusCode().value());
    }
    @Test void filtersUseValidatedDatesAndNormalizedSearch(){
        var from=LocalDate.of(2026,10,1);var to=LocalDate.of(2026,10,24);
        service.list(" SAC ",true,from,to);verify(dao).list("SAC",true,from,to);
        assertThrows(ResponseStatusException.class,()->service.list("",null,to,from));
    }
}
