package com.impulsa.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BrevoEmailSenderTest {
    @Test void sendsAuthenticatedJsonOverHttps() {
        var builder = RestClient.builder().baseUrl("https://api.brevo.com/v3");
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = new BrevoEmailSender(builder.build(), "test-key", "sender@example.com");
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("api-key", "test-key"))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.sender.email").value("sender@example.com"))
                .andExpect(jsonPath("$.to[0].email").value("user@example.com"))
                .andExpect(jsonPath("$.htmlContent").value("<p>123456</p>"))
                .andRespond(withStatus(HttpStatus.CREATED));
        assertTrue(sender.send("user@example.com", "Código", "<p>123456</p>"));
        server.verify();
    }

    @Test void quotaAndProviderFailuresDoNotReportSuccess() {
        var builder = RestClient.builder().baseUrl("https://api.brevo.com/v3");
        var server = MockRestServiceServer.bindTo(builder).build();
        var sender = new BrevoEmailSender(builder.build(), "test-key", "sender@example.com");
        server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertFalse(sender.send("user@example.com", "Código", "<p>123456</p>"));
        server.verify();
    }

    @Test void missingCredentialsDoesNotAttemptNetwork() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        assertFalse(new BrevoEmailSender(builder.build(), "", "sender@example.com")
                .send("user@example.com", "Código", "<p>123456</p>"));
        assertFalse(new BrevoEmailSender(builder.build(), "test-key", "").isConfigured());
        server.verify();
    }
}
