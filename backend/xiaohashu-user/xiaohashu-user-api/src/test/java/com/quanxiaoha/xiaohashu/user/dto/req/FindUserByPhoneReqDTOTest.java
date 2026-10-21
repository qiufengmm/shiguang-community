package com.quanxiaoha.xiaohashu.user.dto.req;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FindUserByPhoneReqDTOTest {

    private final Validator validator = Validation.byDefaultProvider().configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory().getValidator();

    @Test
    void acceptsAValidPhoneNumber() {
        FindUserByPhoneReqDTO request = FindUserByPhoneReqDTO.builder().phone("13800138000").build();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsMissingAndMalformedPhoneNumbers() {
        FindUserByPhoneReqDTO missing = FindUserByPhoneReqDTO.builder().phone(" ").build();
        FindUserByPhoneReqDTO malformed = FindUserByPhoneReqDTO.builder().phone("12345").build();

        assertFalse(validator.validate(missing).isEmpty());
        assertFalse(validator.validate(malformed).isEmpty());
    }
}
