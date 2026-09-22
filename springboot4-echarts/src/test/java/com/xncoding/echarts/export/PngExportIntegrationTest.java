package com.xncoding.echarts.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出接口端到端测试：RANDOM_PORT 起真实服务（无头浏览器要真发 HTTP），
 * 断言响应头、PNG 魔数与 IHDR 像素尺寸。测试依赖本机 venv python + playwright，
 * 运行前提见工程 README。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PngExportIntegrationTest {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    @Autowired
    private WebServerApplicationContext context;

    private String baseUrl() {
        int port = context.getWebServer().getPort();
        return "http://127.0.0.1:" + port;
    }

    @Test
    @DisplayName("POST /api/export/png：返回 image/png，魔数与 IHDR 尺寸与请求一致")
    void exportReturnsPngWithRequestedSize() throws Exception {
        String body = "{\"type\":\"daily\",\"days\":7,\"width\":900,\"height\":480}";
        HttpResponse<byte[]> response = post(baseUrl() + "/api/export/png", body);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).startsWith("image/png"));
        assertThat(response.headers().firstValue("Content-Disposition"))
                .hasValueSatisfying(cd -> assertThat(cd).contains(".png"));

        byte[] png = response.body();
        assertThat(png.length).isGreaterThan(1000);
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            assertThat(png[i]).as("PNG 魔数第 %d 字节", i).isEqualTo(PNG_MAGIC[i]);
        }
        // IHDR：第 16~19 字节大端宽度，第 20~23 字节大端高度
        int width = ((png[16] & 0xFF) << 24) | ((png[17] & 0xFF) << 16) | ((png[18] & 0xFF) << 8) | (png[19] & 0xFF);
        int height = ((png[20] & 0xFF) << 24) | ((png[21] & 0xFF) << 16) | ((png[22] & 0xFF) << 8) | (png[23] & 0xFF);
        assertThat(width).isEqualTo(900);
        assertThat(height).isEqualTo(480);
    }

    @Test
    @DisplayName("POST /api/export/png：type 非法返回 400 problem+json")
    void exportRejectsInvalidType() throws Exception {
        HttpResponse<byte[]> response = post(baseUrl() + "/api/export/png",
                "{\"type\":\"radar\",\"width\":900,\"height\":480}");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                ct -> assertThat(ct).contains("application/problem+json"));
    }

    private HttpResponse<byte[]> post(String url, String jsonBody) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(3))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
}
