package com.nova.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nova.backend.account.AccountService;
import com.nova.backend.auth.SocialIdentity;
import com.nova.backend.auth.SocialProvider;
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

    @Autowired
    private AccountService accountService;

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
        String peerUserId = createPeerUser();

        String callResponse = mockMvc.perform(post("/api/v1/threads/new-direct-thread/calls")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "callType": "VOICE",
                                  "direction": "OUTGOING",
                                  "peerUserId": "%s"
                                }
                                """.formatted(peerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String threadId = objectMapper.readTree(callResponse).path("data").path("threadId").asText();
        assertThat(threadId).isNotBlank();

        // Conversations are listed only once they have a message.
        mockMvc.perform(post("/api/v1/threads/" + threadId + "/messages")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"Hello\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/threads")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[?(@.id=='%s')]".formatted(threadId)).exists());

        mockMvc.perform(delete("/api/v1/threads/" + threadId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/v1/threads")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[?(@.id=='%s')]".formatted(threadId)).isEmpty());

        String sendResponse = mockMvc.perform(post("/api/v1/threads/" + threadId + "/messages")
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

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/messages/" + messageId + "/recall")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("RECALLED"))
                .andExpect(jsonPath("$.data.text").value("You unsent a message"));

        mockMvc.perform(get("/api/v1/threads/" + threadId)
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messages[?(@.status=='RECALLED')]").exists());

        mockMvc.perform(post("/api/v1/threads/" + threadId + "/read")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.thread.id").value(threadId));
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

    private String createPeerUser() {
        return accountService.upsertSocialUser(new SocialIdentity(
                SocialProvider.GOOGLE,
                "test:messaging-peer",
                "messaging-peer@nova.test",
                "Messaging Peer",
                ""
        ));
    }
}
