package com.haohaop.rag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haohaop.rag.service.FeedbackService;
import com.haohaop.rag.service.RAGService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RagControllerTest {

    private final RAGService ragService = mock(RAGService.class);
    private final FeedbackService feedbackService = mock(FeedbackService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new RagController(ragService, feedbackService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()))
            .build();

    @Test
    void feedbackReturnsSuccess() throws Exception {
        doNothing().when(feedbackService).save(anyString(), anyString(), anyString());
        mockMvc.perform(post("/api/rag/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"贷款条件\",\"answer\":\"答案\",\"rating\":\"up\",\"timestamp\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("反馈已记录"));
    }
}
