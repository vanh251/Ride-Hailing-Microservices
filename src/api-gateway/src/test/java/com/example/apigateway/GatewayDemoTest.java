package com.example.apigateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayDemoTest {
    @Value("${local.server.port}")
    int port;

    @ParameterizedTest
    @ValueSource(strings = {"index.html", "styles.css", "app.js", "client.mjs"})
    void demoAssetsAreAvailableWithoutAnApiToken(String asset) throws Exception {
        var response = get("/demo/" + asset);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isNotBlank();
        assertThat(response.headers().firstValue("content-type").orElse("")).doesNotContain("application/json");
    }

    @Test
    void publicDemoDoesNotExposeTripApi() throws Exception {
        assertThat(get("/api/trips/11111111-1111-4111-8111-111111111111").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
