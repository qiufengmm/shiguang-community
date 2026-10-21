package com.quanxiaoha.xiaohashu.user.biz.controller;

import com.quanxiaoha.framework.common.response.Response;
import com.quanxiaoha.xiaohashu.user.biz.exception.GlobalExceptionHandler;
import com.quanxiaoha.xiaohashu.user.biz.model.vo.UpdateUserInfoReqVO;
import com.quanxiaoha.xiaohashu.user.biz.service.UserService;
import com.quanxiaoha.xiaohashu.user.dto.req.RegisterUserReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.FindUserByPhoneReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.req.UpdateUserPasswordReqDTO;
import com.quanxiaoha.xiaohashu.user.dto.resp.FindUserByPhoneRspDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserController userController;

    @Test
    void bindsMultipartProfileFieldsAndPassesThemToService() throws Exception {
        when(userService.updateUserInfo(any())).thenReturn(Response.success());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(userController).build();

        MockMultipartFile avatar = new MockMultipartFile(
                "avatar", "avatar.png", "image/png", new byte[]{1, 2, 3});
        mockMvc.perform(multipart("/user/update")
                        .file(avatar)
                        .param("userId", "42")
                        .param("nickname", "Alice")
                        .param("sex", "1")
                        .param("birthday", "2000-01-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<UpdateUserInfoReqVO> requestCaptor =
                ArgumentCaptor.forClass(UpdateUserInfoReqVO.class);
        verify(userService).updateUserInfo(requestCaptor.capture());
        assertEquals("Alice", requestCaptor.getValue().getNickname());
        assertEquals(42L, requestCaptor.getValue().getUserId());
        assertEquals(1, requestCaptor.getValue().getSex());
        assertEquals(LocalDate.of(2000, 1, 2), requestCaptor.getValue().getBirthday());
        assertNotNull(requestCaptor.getValue().getAvatar());
    }

    @Test
    void returnsFieldValidationMessageWhenUserIdIsMissingFromUpdateForm() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(userController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(multipart("/user/update").param("nickname", "Alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("USER-10001"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("用户 ID 不能为空")));
    }

    @Test
    void registersUserFromJsonRequest() throws Exception {
        when(userService.register(any())).thenReturn(Response.success(99L));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(userController).build();

        mockMvc.perform(post("/user/register")
                        .contentType("application/json")
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(99));

        ArgumentCaptor<RegisterUserReqDTO> requestCaptor = ArgumentCaptor.forClass(RegisterUserReqDTO.class);
        verify(userService).register(requestCaptor.capture());
        assertEquals("13800138000", requestCaptor.getValue().getPhone());
    }

    @Test
    void findsUserByPhoneFromJsonRequest() throws Exception {
        FindUserByPhoneRspDTO result = FindUserByPhoneRspDTO.builder().id(17L).password("encoded").build();
        when(userService.findByPhone(any())).thenReturn(Response.success(result));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(userController).build();

        mockMvc.perform(post("/user/findByPhone")
                        .contentType("application/json")
                        .content("{\"phone\":\"13800138000\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(17))
                .andExpect(jsonPath("$.data.password").value("encoded"));

        ArgumentCaptor<FindUserByPhoneReqDTO> requestCaptor =
                ArgumentCaptor.forClass(FindUserByPhoneReqDTO.class);
        verify(userService).findByPhone(requestCaptor.capture());
        assertEquals("13800138000", requestCaptor.getValue().getPhone());
    }

    @Test
    void updatesPasswordFromJsonRequest() throws Exception {
        when(userService.updatePassword(any())).thenReturn(Response.success());
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(userController).build();

        mockMvc.perform(post("/user/password/update")
                        .contentType("application/json")
                        .content("{\"encodePassword\":\"encoded-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        ArgumentCaptor<UpdateUserPasswordReqDTO> requestCaptor =
                ArgumentCaptor.forClass(UpdateUserPasswordReqDTO.class);
        verify(userService).updatePassword(requestCaptor.capture());
        assertEquals("encoded-password", requestCaptor.getValue().getEncodePassword());
    }
}
