package com.quanxiaoha.framework.jackson.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JacksonAutoConfigurationTest {

    @Test
    void serializesLocalDateUsingTheProjectDateFormat() throws Exception {
        ObjectMapper objectMapper = new JacksonAutoConfiguration().objectMapper();
        LocalDate birthday = LocalDate.of(2024, 4, 5);

        String json = objectMapper.writeValueAsString(birthday);
        LocalDate parsed = objectMapper.readValue(json, LocalDate.class);

        assertEquals("\"2024-04-05\"", json);
        assertEquals(birthday, parsed);
    }
}
