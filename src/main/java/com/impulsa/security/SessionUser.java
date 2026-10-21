package com.impulsa.security;

import java.io.Serializable;

/** Identidad interna; nunca se serializa en respuestas de la API. */
public record SessionUser(int id, String role, String credentialVersion) implements Serializable { }
