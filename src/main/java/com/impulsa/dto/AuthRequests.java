package com.impulsa.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public final class AuthRequests {
    private AuthRequests() { }

    public record LoginRequest(
            @NotBlank(message = "Ingresa tu correo electrónico o código de usuario.")
            @Size(max = 120, message = "El identificador supera la longitud permitida.")
            @Pattern(regexp = ValidationPatterns.LOGIN_IDENTIFIER_REGEX, flags = Pattern.Flag.CASE_INSENSITIVE,
                    message = "Ingresa un correo electrónico válido o un código de usuario (USR-... / CLI-...).")
            String identificador,

            @NotBlank(message = "Ingresa tu contraseña.")
            @Size(max = 72, message = "La contraseña supera la longitud permitida.")
            String password
    ) {
        public LoginRequest {
            identificador = identificador == null ? "" : identificador.trim();
            password = password == null ? "" : password;
        }
    }

    public record Verify2FaRequest(
            @NotBlank(message = "Ingresa el código de verificación.")
            @Pattern(regexp = ValidationPatterns.OTP_REGEX, message = "El código de verificación debe tener 6 dígitos numéricos.")
            String codigoOtp
    ) {
        public Verify2FaRequest {
            codigoOtp = codigoOtp == null ? "" : codigoOtp.trim();
        }
    }

    public record RecoverRequest(
            @NotBlank(message = "Ingresa tu correo electrónico.")
            @Size(max = 120, message = "El correo electrónico supera la longitud permitida.")
            @Email(regexp = ValidationPatterns.EMAIL_REGEX, message = "Ingresa un correo electrónico válido.")
            String correo
    ) {
        public RecoverRequest {
            correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
        }
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "Ingresa tu correo electrónico.")
            @Size(max = 120, message = "El correo electrónico supera la longitud permitida.")
            @Email(regexp = ValidationPatterns.EMAIL_REGEX, message = "Ingresa un correo electrónico válido.")
            String correo,

            @NotBlank(message = "Ingresa el código de recuperación.")
            @Pattern(regexp = ValidationPatterns.OTP_REGEX, message = "El código de recuperación debe tener 6 dígitos numéricos.")
            String codigoOtp,

            @NotBlank(message = "Ingresa la nueva contraseña.")
            @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
            String nuevaPassword
    ) {
        public ResetPasswordRequest {
            correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
            codigoOtp = codigoOtp == null ? "" : codigoOtp.trim();
            nuevaPassword = nuevaPassword == null ? "" : nuevaPassword;
        }
    }

    public record RegisterClienteRequest(
            @NotBlank(message = "Ingresa tu nombre o razón social.")
            @Size(max = 100, message = "El nombre o razón social supera los 100 caracteres.")
            String razonSocial,

            @NotBlank(message = "Ingresa tu DNI o RUC.")
            @Pattern(regexp = ValidationPatterns.DOCUMENT_REGEX, message = "El documento debe tener 8 dígitos para DNI u 11 para RUC.")
            String documento,

            @NotBlank(message = "Ingresa tu correo electrónico.")
            @Size(max = 120, message = "El correo electrónico supera la longitud permitida.")
            @Email(regexp = ValidationPatterns.EMAIL_REGEX, message = "Ingresa un correo electrónico válido.")
            String correo,

            @NotBlank(message = "Ingresa una contraseña.")
            @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
            String password,

            @Size(max = 20, message = "El teléfono supera la longitud permitida.")
            String telefono
    ) {
        public RegisterClienteRequest {
            razonSocial = razonSocial == null ? "" : razonSocial.trim();
            documento = documento == null ? "" : documento.trim();
            correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
            password = password == null ? "" : password;
            telefono = telefono == null ? "" : telefono.trim();
        }
    }
}
