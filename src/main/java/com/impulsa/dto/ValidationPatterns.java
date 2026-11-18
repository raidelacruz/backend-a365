package com.impulsa.dto;

public final class ValidationPatterns {
    private ValidationPatterns() { }

    public static final String EMAIL_REGEX = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    public static final String OPTIONAL_EMAIL_REGEX = "^$|^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";
    public static final String LOGIN_IDENTIFIER_REGEX = "^([A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}|(?:USR|CLI)-[A-Za-z0-9]{3,32})$";
    public static final String DOCUMENT_REGEX = "^(?:[0-9]{8}|[0-9]{11})$";
    public static final String OTP_REGEX = "^[0-9]{6}$";
}
