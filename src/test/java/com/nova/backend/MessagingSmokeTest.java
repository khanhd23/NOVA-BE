package com.nova.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class MessagingSmokeTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void deleteConversationAndRecallMessageWork() throws Exception {
        String loginResponse = login();
        String accessToken = extractAccessToken(loginResponse);

        mockMvc.perform(get("/api/v1/threads")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(4));

        mockMvc.perform(delete("/api/v1/threads/thread-marcus")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/threads")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[?(@.id=='thread-marcus')]").isEmpty());

        String sendResponse = mockMvc.perform(post("/api/v1/threads/thread-elena/messages")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "text": "This message will be recalled"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sendJson = objectMapper.readTree(sendResponse);
        String messageId = sendJson.path("data").path("id").asText();
        assertThat(messageId).isNotBlank();

        mockMvc.perform(post("/api/v1/threads/thread-elena/messages/" + messageId + "/recall")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RECALLED"))
                .andExpect(jsonPath("$.data.text").value("You unsent a message"));

        mockMvc.perform(get("/api/v1/threads/thread-elena")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messages[?(@.status=='RECALLED')]").exists());

        mockMvc.perform(post("/api/v1/threads/thread-elena/read")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.thread.id").value("thread-elena"));
    }

    private String login() throws Exception {
        String loginBody = """
                {
                  "provider": "GOOGLE",
                  "providerToken": "dev:current",
                  "deviceId": "test-device",
                  "appVersion": "1.0.0"
                }
                """;

        return mockMvc.perform(post("/api/v1/auth/social/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String extractAccessToken(String loginResponse) throws Exception {
        JsonNode loginJson = objectMapper.readTree(loginResponse);
        String accessToken = loginJson.path("data").path("tokens").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();
        return accessToken;
    }
}
