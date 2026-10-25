package com.microservice.rca.external.client;

import com.microservice.rca.external.config.ExternalServiceProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 外部服务地址与名称校验。
 *
 * <p>安全边界：只允许 http/https、白名单内的回环地址或字面 IP、白名单端口；
 * 拒绝 userinfo、query、fragment、路径穿越和编码后的穿越字符。
 * 不接受普通域名，避免 DNS 重绑定把请求指向未授权地址。</p>
 */
@Component
public class ExternalServiceUrlValidator {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{2,64}");
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");
    private static final Pattern IPV6_PATTERN =
            Pattern.compile("^[0-9a-fA-F:.]+$");
    private static final Set<String> RESERVED_NAMES =
            Set.of("all", "order-service", "inventory-service");

    private final ExternalServiceProperties properties;
    private final Set<String> allowedHosts;
    private final Set<Integer> allowedPorts;

    public ExternalServiceUrlValidator(ExternalServiceProperties properties) {
        this.properties = properties;
        this.allowedHosts = properties.allowedHosts().stream()
                .map(this::normalizeConfiguredHost)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
        this.allowedPorts = Set.copyOf(properties.allowedPorts());
    }

    public String normalizeName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            throw new IllegalArgumentException("外部服务名称不能为空");
        }
        String name = rawName.trim();
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "外部服务名称只允许 2-64 位字母、数字、点、下划线或连字符");
        }
        if (RESERVED_NAMES.contains(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("外部服务名称不能使用系统保留名称: " + name);
        }
        return name;
    }

    public String normalizeBaseUrl(String rawBaseUrl) {
        return validateBaseUrl(rawBaseUrl).toString();
    }

    /**
     * 校验并规范化基地址：去掉末尾斜杠，统一小写 scheme/host，不保留 userinfo/query/fragment。
     */
    public URI validateBaseUrl(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            throw new IllegalArgumentException("外部服务基地址不能为空");
        }
        String raw = rawBaseUrl.trim();
        if (raw.length() > 512) {
            throw new IllegalArgumentException("外部服务基地址不能超过 512 字符");
        }
        if (containsControlCharacter(raw)) {
            throw new IllegalArgumentException("外部服务基地址包含非法控制字符");
        }

        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("外部服务基地址格式不正确");
        }

        String scheme = lower(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("外部服务基地址只允许 http 或 https 协议");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("外部服务基地址不允许包含用户名或密码");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("外部服务基地址不允许包含查询参数或锚点");
        }

        String host = uri.getHost();
        validateHost(host);
        int port = effectivePort(uri, scheme);
        if (!allowedPorts.contains(port)) {
            throw new IllegalArgumentException("外部服务端口不在白名单内: " + port
                    + "，允许端口: " + allowedPorts);
        }

        String normalizedPath = normalizePathValue(uri.getRawPath());
        String authority = formatAuthority(host, uri.getPort());
        URI normalized = URI.create(scheme + "://" + authority + normalizedPath);
        return validateRequestUri(normalized);
    }

    /**
     * 健康/指标端点路径：必须以 / 开头，禁止路径穿越和查询参数。
     */
    public String normalizeEndpointPath(String rawPath, String defaultPath) {
        String path = rawPath == null || rawPath.isBlank() ? defaultPath : rawPath.trim();
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("端点路径不能为空");
        }
        if (path.length() > 256) {
            throw new IllegalArgumentException("端点路径不能超过 256 字符");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("端点路径必须以 / 开头");
        }
        validatePathValue(path);
        return normalizePathValue(path);
    }

    /**
     * 日志数据源地址。默认要求与基地址同源，避免日志地址成为新的 SSRF 入口。
     */
    public String normalizeLogSourceUrl(String rawLogSourceUrl, URI baseUri) {
        if (rawLogSourceUrl == null || rawLogSourceUrl.isBlank()) {
            return null;
        }
        URI logUri = validateBaseUrl(rawLogSourceUrl);
        if (!properties.sameOriginLogSourceOnly()) {
            return logUri.toString();
        }

        String baseScheme = lower(baseUri.getScheme());
        String logScheme = lower(logUri.getScheme());
        String baseHost = validateHost(baseUri.getHost());
        String logHost = validateHost(logUri.getHost());
        int basePort = effectivePort(baseUri, baseScheme);
        int logPort = effectivePort(logUri, logScheme);
        if (!baseScheme.equals(logScheme) || !baseHost.equals(logHost) || basePort != logPort) {
            throw new IllegalArgumentException(
                    "日志数据源必须与外部服务基地址同源（协议、主机、端口一致）");
        }
        return logUri.toString();
    }

    /**
     * 拼接基地址与端点路径。基地址和路径在写入前已校验，这里只做确定性拼接，
     * 不使用 URI.resolve，避免路径替换语义绕过基地址路径。
     */
    public URI resolve(URI baseUri, String endpointPath) {
        String base = baseUri.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = endpointPath.startsWith("/") ? endpointPath : "/" + endpointPath;
        return validateRequestUri(URI.create(base + path));
    }

    /**
     * 请求发送前的再次校验。允许 query，因为日志数据源需要查询参数。
     */
    public URI validateRequestUri(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            throw new IllegalArgumentException("请求地址必须是绝对地址");
        }
        String scheme = lower(uri.getScheme());
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("请求地址只允许 http 或 https 协议");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("请求地址不允许包含用户名或密码");
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("请求地址不允许包含锚点");
        }
        validateHost(uri.getHost());
        int port = effectivePort(uri, scheme);
        if (!allowedPorts.contains(port)) {
            throw new IllegalArgumentException("请求端口不在白名单内: " + port);
        }
        return uri;
    }

    private String validateHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            throw new IllegalArgumentException("外部服务地址缺少主机名");
        }
        String host = stripBrackets(rawHost.trim()).toLowerCase(Locale.ROOT);
        if ("localhost".equals(host)) {
            if (!allowedHosts.contains("localhost")) {
                throw new IllegalArgumentException("主机不在白名单内: localhost");
            }
            return "localhost";
        }
        if (!isIpLiteral(host)) {
            throw new IllegalArgumentException(
                    "外部服务地址必须是 localhost 或白名单中的字面 IP，不允许使用普通域名");
        }
        String canonical = canonicalIp(host);
        if (canonical == null) {
            throw new IllegalArgumentException("无效的 IP 地址: " + host);
        }
        if (!allowedHosts.contains(canonical)) {
            throw new IllegalArgumentException("主机不在白名单内: " + host);
        }
        return canonical;
    }

    private String normalizeConfiguredHost(String rawHost) {
        if (rawHost == null || rawHost.isBlank()) {
            return null;
        }
        String host = stripBrackets(rawHost.trim()).toLowerCase(Locale.ROOT);
        if ("localhost".equals(host)) {
            return "localhost";
        }
        if (!isIpLiteral(host)) {
            // 配置中的普通域名不会与校验阶段匹配；保留原值便于错误提示。
            return host;
        }
        return canonicalIp(host);
    }

    private boolean isIpLiteral(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (IPV4_PATTERN.matcher(host).matches()) {
            String[] parts = host.split("\\.");
            for (String part : parts) {
                try {
                    int value = Integer.parseInt(part);
                    if (value < 0 || value > 255) {
                        return false;
                    }
                } catch (NumberFormatException ex) {
                    return false;
                }
            }
            return true;
        }
        if (host.contains(":") && IPV6_PATTERN.matcher(host).matches()) {
            try {
                InetAddress.getByName(host);
                return true;
            } catch (UnknownHostException ex) {
                return false;
            }
        }
        return false;
    }

    private String canonicalIp(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress().toLowerCase(Locale.ROOT);
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private String formatAuthority(String originalHost, int port) {
        String hostForUri = stripBrackets(originalHost);
        if (hostForUri.contains(":")) {
            hostForUri = "[" + hostForUri + "]";
        }
        return port >= 0 ? hostForUri + ":" + port : hostForUri;
    }

    private int effectivePort(URI uri, String scheme) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equals(scheme) ? 443 : 80;
    }

    private String normalizePathValue(String rawPath) {
        if (rawPath == null || rawPath.isBlank() || "/".equals(rawPath)) {
            return "";
        }
        if (!rawPath.startsWith("/")) {
            throw new IllegalArgumentException("URL 路径必须以 / 开头");
        }
        validatePathValue(rawPath);
        String path = rawPath;
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private void validatePathValue(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        if (containsControlCharacter(path) || path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("URL 路径不允许包含空白或控制字符");
        }
        if (path.contains("\\") || path.contains("?") || path.contains("#") || path.contains("@")) {
            throw new IllegalArgumentException("URL 路径包含非法字符");
        }
        if (path.contains("..") || path.contains("//")) {
            throw new IllegalArgumentException("URL 路径不允许包含 .. 或连续斜杠");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) {
            throw new IllegalArgumentException("URL 路径不允许包含编码后的穿越字符");
        }
    }

    private boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f);
    }

    private String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    private String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
