package com.xncoding.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * springdoc.api-docs.path 改名行为：文档挂到新路径，默认路径腾空。
 */
@SpringBootTest(properties = "springdoc.api-docs.path=/internal/api-docs")
@AutoConfigureMockMvc
class ApiDocsPathRenameTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void renamed_path_serves_docs_and_default_is_gone() throws Exception {
        mockMvc.perform(get("/internal/api-docs"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isNotFound());
    }
}
