package com.impulsa.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RequestValidator {
    private final Validator validator;

    public RequestValidator(Validator validator) {
        this.validator = validator;
    }

    public <T> T validate(T target) {
        Set<ConstraintViolation<T>> violations = validator.validate(target);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        return target;
    }
}
