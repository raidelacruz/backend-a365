package com.impulsa.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Locale;

public final class UsuarioRequests {
    private UsuarioRequests() { }

    public record CreateUserRequest(
            @NotBlank(message = "Los nombres son obligatorios.")
            @Size(max = 100, message = "Los nombres superan los 100 caracteres.")
            String nombres,

            @Size(max = 100, message = "Los apellidos superan los 100 caracteres.")
            String apellidos,

            @NotBlank(message = "El correo electrónico es obligatorio.")
            @Size(max = 120, message = "El correo electrónico supera la longitud permitida.")
            @Email(regexp = ValidationPatterns.EMAIL_REGEX, message = "El correo electrónico ingresado no tiene un formato válido.")
            String correo,

            @NotBlank(message = "La contraseña inicial es obligatoria.")
            @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
            String password,

            @Size(max = 30)
            String rol,

            @Size(max = 80)
            String departamento,

            @Size(max = 20)
            String dni,

            @Size(max = 20)
            String telefono
    ) {
        public CreateUserRequest {
            nombres = nombres == null ? "" : nombres.trim();
            apellidos = apellidos == null ? "" : apellidos.trim();
            correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
            password = password == null ? "" : password;
            rol = (rol == null || rol.isBlank()) ? "EMPLEADO" : rol.trim().toUpperCase(Locale.ROOT);
            departamento = (departamento == null || departamento.isBlank()) ? "General" : departamento.trim();
            dni = dni == null ? "" : dni.trim();
            telefono = telefono == null ? "" : telefono.trim();
        }
    }

    public record UpdateUserRequest(
            @NotBlank(message = "Los nombres son obligatorios.")
            @Size(max = 100, message = "Los nombres superan los 100 caracteres.")
            String nombres,

            @Size(max = 100, message = "Los apellidos superan los 100 caracteres.")
            String apellidos,

            @NotBlank(message = "El correo electrónico es obligatorio.")
            @Size(max = 120, message = "El correo electrónico supera la longitud permitida.")
            @Email(regexp = ValidationPatterns.EMAIL_REGEX, message = "El correo electrónico ingresado no tiene un formato válido.")
            String correo,

            @Size(max = 30)
            String rol,

            @Size(max = 80)
            String departamento,

            @Size(max = 20)
            String telefono
    ) {
        public UpdateUserRequest {
            nombres = nombres == null ? "" : nombres.trim();
            apellidos = apellidos == null ? "" : apellidos.trim();
            correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
            rol = (rol == null || rol.isBlank()) ? "EMPLEADO" : rol.trim().toUpperCase(Locale.ROOT);
            departamento = (departamento == null || departamento.isBlank()) ? "General" : departamento.trim();
            telefono = telefono == null ? "" : telefono.trim();
        }
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "El nombre completo es obligatorio.")
            @Size(max = 150, message = "El nombre completo supera la longitud permitida.")
            String nombreCompleto,

            @NotBlank(message = "El correo electrónico es obligatorio.")
            @Size(max = 120, message = "El correo electrónico supera la longitud permitida.")
            @Email(regexp = ValidationPatterns.EMAIL_REGEX, message = "El correo electrónico ingresado no tiene un formato válido.")
            String correo,

            @Size(max = 20, message = "El teléfono supera la longitud permitida.")
            String telefono,

            @Size(max = 20, message = "El DNI o RUC supera la longitud permitida.")
            String dniRuc
    ) {
        public UpdateProfileRequest {
            nombreCompleto = nombreCompleto == null ? "" : nombreCompleto.trim();
            correo = correo == null ? "" : correo.trim().toLowerCase(Locale.ROOT);
            telefono = telefono == null ? "" : telefono.trim();
            dniRuc = dniRuc == null ? "" : dniRuc.trim();
        }
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "Ingresa tu contraseña actual.")
            @Size(max = 72, message = "La contraseña actual supera la longitud permitida.")
            String passwordActual,

            @NotBlank(message = "Ingresa la nueva contraseña.")
            @Size(min = 8, max = 72, message = "La nueva contraseña debe tener entre 8 y 72 caracteres.")
            String nuevaPassword
    ) {
        public ChangePasswordRequest {
            passwordActual = passwordActual == null ? "" : passwordActual;
            nuevaPassword = nuevaPassword == null ? "" : nuevaPassword;
        }
    }

    public record SmtpConfigRequest(
            @NotBlank(message = "Ingresa el servidor SMTP.")
            @Size(max = 150, message = "El host SMTP supera la longitud permitida.")
            String host,

            @NotNull(message = "Ingresa el puerto SMTP.")
            @Min(value = 1, message = "El puerto SMTP debe estar comprendido estrictamente entre 1 y 65535.")
            @Max(value = 65535, message = "El puerto SMTP debe estar comprendido estrictamente entre 1 y 65535.")
            Integer port,

            @Size(max = 120, message = "El correo remitente supera la longitud permitida.")
            @Pattern(regexp = ValidationPatterns.OPTIONAL_EMAIL_REGEX, message = "El correo remitente SMTP no tiene un formato válido.")
            String username,

            @Size(max = 200, message = "La contraseña de aplicación supera la longitud permitida.")
            String password
    ) {
        public SmtpConfigRequest {
            host = (host == null || host.isBlank()) ? "smtp.gmail.com" : host.trim();
            port = port == null ? 587 : port;
            username = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
            password = password == null ? "" : password;
        }
    }
}
