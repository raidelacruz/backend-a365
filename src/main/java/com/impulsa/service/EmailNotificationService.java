package com.impulsa.service;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

@Service
public class EmailNotificationService {

    private final BrevoEmailSender brevo;

    public EmailNotificationService(BrevoEmailSender brevo) {
        this.brevo = brevo;
    }

    @Value("${app.mail.provider:smtp}")
    private String mailProvider;

    public boolean isCorreoConfigurado() {
        return "brevo".equalsIgnoreCase(mailProvider) ? brevo.isConfigured()
                : "smtp".equalsIgnoreCase(mailProvider) && isSmtpConfigurado();
    }

    public String getMailProvider() { return mailProvider; }

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String smtpHost;

    @Value("${spring.mail.port:587}")
    private int smtpPort;

    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    @Value("${app.security.log-codes:false}")
    private boolean logCodes;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    public void actualizarCredencialesSmtp(String host, int port, String username, String password) {
        if (host != null && !host.isBlank()) this.smtpHost = host.trim();
        if (port > 0) this.smtpPort = port;
        this.smtpUsername = username != null ? username.trim() : "";
        this.smtpPassword = password != null ? password.replace(" ", "").trim() : "";
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public boolean isSmtpConfigurado() {
        return smtpUsername != null && !smtpUsername.isBlank() && smtpPassword != null && !smtpPassword.isBlank();
    }

    /**
     * 1. Envía el código de Doble Autenticación (2FA) al correo que ingresó la persona
     */
    public boolean enviarCodigo2FA(String correoDestino, String nombreUsuario, String codigoOtp) {
        String asunto = "[IMPULSA A365] Código de Verificación en 2 Pasos: " + codigoOtp;
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 520px; margin: 0 auto; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden;">
                    <div style="background: #0f172a; color: #ffffff; padding: 20px; text-align: center;">
                        <h2 style="margin: 0; font-size: 20px;">IMPULSA <span style="color: #dc2626;">A365</span></h2>
                        <p style="margin: 4px 0 0; font-size: 12px; color: #94a3b8;">Seguridad y Doble Autenticación (2FA)</p>
                    </div>
                    <div style="padding: 24px; color: #1e293b;">
                        <p>Hola <strong>%s</strong>,</p>
                        <p>Se está intentando iniciar sesión en tu cuenta (<strong>%s</strong>). Ingresa el siguiente código de verificación de 6 dígitos para continuar:</p>
                        <div style="background: #f1f5f9; border: 2px dashed #2563eb; border-radius: 8px; padding: 16px; text-align: center; margin: 20px 0;">
                            <span style="font-size: 28px; font-weight: bold; letter-spacing: 6px; color: #1e40af;">%s</span>
                        </div>
                        <p style="font-size: 13px; color: #64748b;">Este código expira en 10 minutos. Si no intentaste iniciar sesión, ignora este mensaje.</p>
                    </div>
                </div>
                """.formatted(nombreUsuario, correoDestino, codigoOtp);

        imprimirEnConsola("DOBLE AUTENTICACIÓN (2FA)", correoDestino, asunto, "Código OTP: " + codigoOtp);
        return enviarCorreoReal(correoDestino, asunto, html);
    }

    /**
     * 2. Envía un aviso al correo del usuario cuando alguien ha ingresado a su cuenta
     */
    public boolean enviarAlertaInicioSesion(String correoDestino, String nombreUsuario, String ip, String dispositivo) {
        String fechaHora = LocalDateTime.now().format(FMT);
        String asunto = "[IMPULSA A365] Alerta de Seguridad: Nuevo inicio de sesión en tu cuenta";
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 520px; margin: 0 auto; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden;">
                    <div style="background: #0f172a; color: #ffffff; padding: 20px; text-align: center;">
                        <h2 style="margin: 0; font-size: 20px;">IMPULSA <span style="color: #dc2626;">A365</span></h2>
                        <p style="margin: 4px 0 0; font-size: 12px; color: #94a3b8;">Aviso de Acceso a tu Cuenta</p>
                    </div>
                    <div style="padding: 24px; color: #1e293b;">
                        <p>Hola <strong>%s</strong>,</p>
                        <p>Te informamos que se acaba de iniciar sesión exitosamente en tu cuenta (<strong>%s</strong>) de <strong>IMPULSA A365</strong>:</p>
                        <ul style="background: #f8fafc; padding: 16px 32px; border-radius: 8px; border: 1px solid #e2e8f0; font-size: 14px;">
                            <li><strong>Fecha y hora:</strong> %s</li>
                            <li><strong>Dispositivo:</strong> %s</li>
                            <li><strong>Dirección IP:</strong> %s</li>
                        </ul>
                        <p style="font-size: 13px; color: #dc2626;">Si no reconoces este acceso, cambia tu contraseña inmediatamente desde la opción "¿Olvidaste tu contraseña?".</p>
                    </div>
                </div>
                """.formatted(nombreUsuario, correoDestino, fechaHora, dispositivo, ip);

        imprimirEnConsola("AVISO DE INICIO DE SESIÓN", correoDestino, asunto, "Acceso detectado el " + fechaHora + " desde " + dispositivo);
        return enviarCorreoReal(correoDestino, asunto, html);
    }

    /**
     * 3. Envía la invitación por correo para recuperar/cambiar la contraseña
     */
    public boolean enviarInvitacionRecuperarClave(String correoDestino, String nombreUsuario, String codigoOtp) {
        String asunto = "[IMPULSA A365] Invitación para cambiar tu contraseña";
        String html = """
                <div style="font-family: Arial, sans-serif; max-width: 520px; margin: 0 auto; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden;">
                    <div style="background: #0f172a; color: #ffffff; padding: 20px; text-align: center;">
                        <h2 style="margin: 0; font-size: 20px;">IMPULSA <span style="color: #dc2626;">A365</span></h2>
                        <p style="margin: 4px 0 0; font-size: 12px; color: #94a3b8;">Invitación de Cambio de Contraseña</p>
                    </div>
                    <div style="padding: 24px; color: #1e293b;">
                        <p>Hola <strong>%s</strong>,</p>
                        <p>Recibimos una solicitud para cambiar la contraseña asociada a tu correo <strong>%s</strong>.</p>
                        <p>Ingresa este código de invitación en la pantalla de recuperación para definir tu nueva contraseña:</p>
                        <div style="background: #fef2f2; border: 2px dashed #dc2626; border-radius: 8px; padding: 16px; text-align: center; margin: 20px 0;">
                            <span style="font-size: 28px; font-weight: bold; letter-spacing: 6px; color: #b91c1c;">%s</span>
                        </div>
                        <p style="font-size: 12px; color: #64748b;">Este código es válido por 30 minutos. Vuelve a la misma ventana donde solicitaste la recuperación.</p>
                    </div>
                </div>
                """.formatted(nombreUsuario, correoDestino, codigoOtp);

        imprimirEnConsola("INVITACIÓN CAMBIO DE CONTRASEÑA", correoDestino, asunto, "Código de invitación: " + codigoOtp);
        return enviarCorreoReal(correoDestino, asunto, html);
    }

    private boolean enviarCorreoReal(String destino, String asunto, String html) {
        if ("brevo".equalsIgnoreCase(mailProvider)) return brevo.send(destino, asunto, html);
        if (!"smtp".equalsIgnoreCase(mailProvider)) return false;
        if (!isSmtpConfigurado()) {
            return false;
        }
        try {
            JavaMailSenderImpl dynamicSender = new JavaMailSenderImpl();
            dynamicSender.setHost(smtpHost);
            dynamicSender.setPort(smtpPort);
            dynamicSender.setUsername(smtpUsername);
            dynamicSender.setPassword(smtpPassword);
            dynamicSender.setDefaultEncoding("UTF-8");

            Properties props = dynamicSender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.connectiontimeout", "8000");
            props.put("mail.smtp.timeout", "8000");

            MimeMessage message = dynamicSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(smtpUsername, "IMPULSA A365 Seguridad");
            helper.setTo(destino);
            helper.setSubject(asunto);
            helper.setText(html, true);
            dynamicSender.send(message);
            System.out.println("✅ [SMTP REAL] Correo entregado exitosamente a: " + destino);
            return true;
        } catch (Exception e) {
            System.err.println("⚠️ [SMTP] Error enviando a " + destino + ": " + e.getMessage());
            return false;
        }
    }

    private void imprimirEnConsola(String tipo, String destino, String asunto, String detalle) {
        if (!logCodes) return;
        System.out.println("\n======================================================================");
        System.out.println("📧 [DESPACHO DE CORREO - " + tipo + "]");
        System.out.println("Destinatario : " + destino);
        System.out.println("Asunto       : " + asunto);
        System.out.println("Contenido    : " + detalle);
        System.out.println("======================================================================\n");
    }
}
