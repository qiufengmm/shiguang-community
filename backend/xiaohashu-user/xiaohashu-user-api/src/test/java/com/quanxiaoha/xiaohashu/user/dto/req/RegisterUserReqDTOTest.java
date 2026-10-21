package com.quanxiaoha.xiaohashu.user.dto.req;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterUserReqDTOTest {

    private final Validator validator = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory().getValidator();

    @Test
    void acceptsAValidPhoneNumber() {
        RegisterUserReqDTO request = RegisterUserReqDTO.builder().phone("13800138000").build();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMissingAndMalformedPhoneNumbers() {
        RegisterUserReqDTO missing = RegisterUserReqDTO.builder().phone(" ").build();
        RegisterUserReqDTO malformed = RegisterUserReqDTO.builder().phone("12345").build();

        assertFalse(validator.validate(missing).isEmpty());
        assertFalse(validator.validate(malformed).isEmpty());
    }
}
