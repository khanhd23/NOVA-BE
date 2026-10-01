package com.nova.backend.social;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ThreadDeletionTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private AccountService accountService;

    @Autowired
    private SocialService socialService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void deleteForMeHidesHistoryOnlyForTheDeleter() throws Exception {
        String token = loginAccessToken();
        String peerId = accountService.upsertSocialUser(new SocialIdentity(
                SocialProvider.GOOGLE, "test:delete-peer", "delete-peer@nova.test", "Delete Peer", ""));
        String threadId = openDirectThread(token, peerId);

        sendMessage(token, threadId, "first message");
        assertThat(peerTexts(peerId, threadId)).contains("first message");

        // Delete for me: gone from my list, still there for the peer.
        mockMvc.perform(delete("/api/v1/threads/" + threadId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/threads").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data[?(@.id=='%s')]".formatted(threadId)).doesNotExist());
        assertThat(peerTexts(peerId, threadId)).contains("first message");

        // A new message brings the thread back for me, without the deleted history.
        sendMessage(token, threadId, "second message");
        assertThat(myTexts(token, threadId)).containsExactly("second message");
        assertThat(peerTexts(peerId, threadId)).contains("first message", "second message");
    }

    private String loginAccessToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/social/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider": "GOOGLE", "providerToken": "dev:current", "deviceId": "delete-test", "appVersion": "1.0.0"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("tokens").path("accessToken").asText();
    }

    private String openDirectThread(String token, String peerId) throws Exception {
        String response = mockMvc.perform(post("/api/v1/threads/new-direct-thread/calls")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"callType": "VOICE", "direction": "OUTGOING", "peerUserId": "%s"}
                                """.formatted(peerId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("threadId").asText();
    }

    private void sendMessage(String token, String threadId, String text) throws Exception {
        mockMvc.perform(post("/api/v1/threads/" + threadId + "/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\": \"%s\"}".formatted(text)))
                .andExpect(status().isOk());
    }

    private List<String> myTexts(String token, String threadId) throws Exception {
        String response = mockMvc.perform(get("/api/v1/threads/" + threadId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode messages = objectMapper.readTree(response).path("data").path("messages");
        return textsOf(messages);
    }

    private List<String> peerTexts(String peerId, String threadId) throws Exception {
        JsonNode messages = objectMapper.valueToTree(socialService.thread(peerId, threadId, 20, null)).path("messages");
        return textsOf(messages);
    }

    private static List<String> textsOf(JsonNode messages) {
        return java.util.stream.StreamSupport.stream(messages.spliterator(), false)
                .map(node -> node.path("text").asText())
                .filter(text -> !text.isBlank())
                .toList();
    }
}
