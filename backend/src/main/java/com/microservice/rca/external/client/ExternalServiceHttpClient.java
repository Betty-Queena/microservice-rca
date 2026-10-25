package com.microservice.rca.external.client;

import com.microservice.rca.external.config.ExternalServiceProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import javax.net.ssl.SSLException;
import org.springframework.stereotype.Component;

/**
 * 外部服务只读 HTTP 客户端。
 *
 * <p>安全约束：每次发送前重新校验目标地址；禁止跟随重定向；连接与读取都有超时；
 * 响应体有硬上限，超出部分直接截断，避免大响应拖垮诊断线程。</p>
 */
@Component
public class ExternalServiceHttpClient {

    private final ExternalServiceUrlValidator validator;
    private final ExternalServiceProperties properties;
    private final HttpClient httpClient;

    public ExternalServiceHttpClient(ExternalServiceUrlValidator validator,
                                     ExternalServiceProperties properties) {
        this.validator = validator;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public HttpResult get(URI uri) {
        validator.validateRequestUri(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(properties.readTimeoutMs()))
                .header("Accept", "application/json")
                .GET()
                .build();

        long startedAt = System.nanoTime();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            BodyRead body = readBounded(response.body(), properties.maxResponseBytes());
            long latencyMs = elapsedMs(startedAt);
            return new HttpResult(response.statusCode(), body.text(), body.truncated(), latencyMs);
        } catch (HttpTimeoutException ex) {
            throw new ExternalServiceException("请求超时（"
                    + properties.readTimeoutMs() + " ms）");
        } catch (ConnectException ex) {
            throw new ExternalServiceException("连接被拒绝或服务未启动");
        } catch (UnknownHostException ex) {
            throw new ExternalServiceException("无法解析主机");
        } catch (SSLException ex) {
            throw new ExternalServiceException("TLS 握手失败");
        } catch (IOException ex) {
            throw new ExternalServiceException("网络请求失败: " + ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ExternalServiceException("请求被中断");
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof ConnectException) {
                throw new ExternalServiceException("连接被拒绝或服务未启动");
            }
            throw new ExternalServiceException("网络请求失败: "
                    + (cause != null ? cause.getClass().getSimpleName() : "UNKNOWN"));
        }
    }

    private BodyRead readBounded(InputStream stream, int maxBytes) throws IOException {
        try (stream) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
            byte[] chunk = new byte[4096];
            int total = 0;
            boolean truncated = false;
            int read;
            while ((read = stream.read(chunk)) != -1) {
                int remaining = maxBytes - total;
                if (remaining <= 0) {
                    truncated = true;
                    break;
                }
                int toWrite = Math.min(read, remaining);
                buffer.write(chunk, 0, toWrite);
                total += toWrite;
                if (toWrite < read) {
                    truncated = true;
                    break;
                }
            }
            return new BodyRead(buffer.toString(StandardCharsets.UTF_8), truncated);
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    private record BodyRead(String text, boolean truncated) {
    }

    public record HttpResult(int status, String body, boolean truncated, long latencyMs) {
    }
}
