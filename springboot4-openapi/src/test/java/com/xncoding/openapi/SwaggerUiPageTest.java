package com.xncoding.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * swagger-ui 页面可达性：/swagger-ui.html 是 302 短链，真正渲染的是
 * /swagger-ui/index.html。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SwaggerUiPageTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void swagger_ui_html_redirects_to_index() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
    }

    @Test
    void swagger_ui_index_page_served() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
