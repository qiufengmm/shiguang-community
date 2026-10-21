package com.quanxiaoha.xiaohashu.comment.biz.controller;

import com.quanxiaoha.xiaohashu.comment.biz.service.CommentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

class CommentControllerLikeTest {

    @Test
    void exposesCommentLikeEndpoint() throws Exception {
        CommentController controller = new CommentController();
        ReflectionTestUtils.setField(controller, "commentService", mock(CommentService.class));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/comment/like")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentId\":42}"))
                .andExpect(status().isOk());
    }

    @Test
    void exposesCommentUnlikeEndpoint() throws Exception {
        CommentController controller = new CommentController();
        ReflectionTestUtils.setField(controller, "commentService", mock(CommentService.class));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/comment/unlike")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentId\":42}"))
                .andExpect(status().isOk());
    }

    @Test
    void exposesCommentDeleteEndpoint() throws Exception {
        CommentController controller = new CommentController();
        ReflectionTestUtils.setField(controller, "commentService", mock(CommentService.class));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(post("/comment/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentId\":42}"))
                .andExpect(status().isOk());
    }
}
