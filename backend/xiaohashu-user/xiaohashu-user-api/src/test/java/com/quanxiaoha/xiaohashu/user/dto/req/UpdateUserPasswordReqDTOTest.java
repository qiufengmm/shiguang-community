package com.quanxiaoha.xiaohashu.user.dto.req;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateUserPasswordReqDTOTest {

    private final Validator validator = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory().getValidator();

    @Test
    void acceptsAnEncodedPassword() {
        UpdateUserPasswordReqDTO request = UpdateUserPasswordReqDTO.builder()
                .encodePassword("$2a$encoded")
                .build();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsABlankEncodedPassword() {
        UpdateUserPasswordReqDTO request = UpdateUserPasswordReqDTO.builder().encodePassword(" ").build();

        assertFalse(validator.validate(request).isEmpty());
    }
}
