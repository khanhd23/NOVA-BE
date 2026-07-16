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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class CommerceSmokeTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    @Test
    void commerceCatalogAndPurchaseFlowWork() throws Exception {
        mockMvc.perform(get("/api/v1/commerce/catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vipTiers.length()").value(7))
                .andExpect(jsonPath("$.data.diamondPackages.length()").value(6));

        String loginBody = """
                {
                  "provider": "GOOGLE",
                  "providerToken": "dev:current",
                  "deviceId": "test-device",
                  "appVersion": "1.0.0"
                }
                """;

        String loginResponse = mockMvc.perform(post("/api/v1/auth/social/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode loginJson = objectMapper.readTree(loginResponse);
        String accessToken = loginJson.path("data").path("tokens").path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        String vipOrderBody = """
                {
                  "productId": "vip_3",
                  "purchaseType": "VIP",
                  "provider": "DEMO",
                  "note": "VIP test"
                }
                """;

        String vipOrderResponse = mockMvc.perform(post("/api/v1/commerce/orders")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(vipOrderBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value("vip_3"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode vipOrderJson = objectMapper.readTree(vipOrderResponse);
        String vipOrderId = vipOrderJson.path("data").path("orderId").asText();
        assertThat(vipOrderId).isNotBlank();

        mockMvc.perform(post("/api/v1/commerce/orders/" + vipOrderId + "/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "success": true,
                                  "transactionId": "txn-vip-1",
                                  "message": "sandbox success"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/commerce/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.vipActive").value(true))
                .andExpect(jsonPath("$.data.vipTierId").value("vip_3"));

        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.premium").value(true));

        String diamondOrderBody = """
                {
                  "productId": "diamond_250",
                  "purchaseType": "DIAMOND",
                  "provider": "DEMO",
                  "note": "Diamond test"
                }
                """;

        String diamondOrderResponse = mockMvc.perform(post("/api/v1/commerce/orders")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(diamondOrderBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value("diamond_250"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode diamondOrderJson = objectMapper.readTree(diamondOrderResponse);
        String diamondOrderId = diamondOrderJson.path("data").path("orderId").asText();
        assertThat(diamondOrderId).isNotBlank();

        mockMvc.perform(post("/api/v1/commerce/orders/" + diamondOrderId + "/confirm")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "success": true,
                                  "transactionId": "txn-diamond-1",
                                  "message": "sandbox success"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/commerce/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.diamondBalance").value(250));
    }
}
