package com.impulsa.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BrevoEmailSender {
    private final RestClient client;
    private final String apiKey;
    private final String senderEmail;

    @org.springframework.beans.factory.annotation.Autowired
    public BrevoEmailSender(@Value("${app.mail.brevo.api-key:}") String apiKey,
                            @Value("${app.mail.from:}") String senderEmail) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(8).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(12).toMillis());
        this.client = RestClient.builder().baseUrl("https://api.brevo.com/v3")
                .requestFactory(factory).build();
        this.apiKey = apiKey;
        this.senderEmail = senderEmail;
    }

    BrevoEmailSender(RestClient client, String apiKey, String senderEmail) {
        this.client = client;
        this.apiKey = apiKey;
        this.senderEmail = senderEmail;
    }

    public boolean isConfigured() {
        return !apiKey.isBlank() && !senderEmail.isBlank();
    }

    public boolean send(String destination, String subject, String html) {
        if (!isConfigured()) return false;
        try {
            var response = client.post().uri("/smtp/email")
                    .header("api-key", apiKey)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(Map.of("sender", Map.of("name", "IMPULSA A365", "email", senderEmail),
                            "to", List.of(Map.of("email", destination)),
                            "subject", subject, "htmlContent", html))
                    .retrieve().toBodilessEntity();
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            // No imprimir respuestas del proveedor: pueden contener datos personales o códigos.
            System.err.println("[BREVO] No se pudo enviar el correo (" + e.getClass().getSimpleName() + ").");
            return false;
        }
    }
}
