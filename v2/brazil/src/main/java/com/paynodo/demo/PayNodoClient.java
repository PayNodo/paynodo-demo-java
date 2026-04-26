package com.paynodo.demo;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PayNodoClient {
  public static final String DEFAULT_BASE_URL = "https://sandbox-api.paynodo.com";

  private final String baseUrl;
  private final String merchantId;
  private final String merchantSecret;
  private final String privateKeyPem;
  private final HttpClient httpClient;
  private final TimestampSupplier now;

  public PayNodoClient(String baseUrl, String merchantId, String merchantSecret, String privateKeyPem) {
    this(baseUrl, merchantId, merchantSecret, privateKeyPem, HttpClient.newHttpClient(), () -> OffsetDateTime.now().toString());
  }

  public PayNodoClient(
      String baseUrl,
      String merchantId,
      String merchantSecret,
      String privateKeyPem,
      HttpClient httpClient,
      TimestampSupplier now
  ) {
    if (merchantId == null || merchantId.isBlank()) {
      throw new IllegalArgumentException("merchantId is required");
    }
    if (merchantSecret == null || merchantSecret.isBlank()) {
      throw new IllegalArgumentException("merchantSecret is required");
    }
    if (privateKeyPem == null || privateKeyPem.isBlank()) {
      throw new IllegalArgumentException("privateKeyPem is required");
    }
    this.baseUrl = trimRight(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl, "/");
    this.merchantId = merchantId;
    this.merchantSecret = merchantSecret;
    this.privateKeyPem = privateKeyPem;
    this.httpClient = httpClient;
    this.now = now;
  }

  public static void loadDotEnv(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    for (String line : Files.readAllLines(path)) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
        continue;
      }
      int index = trimmed.indexOf('=');
      String key = trimmed.substring(0, index).trim();
      String value = trimmed.substring(index + 1).trim().replaceAll("^[\"']|[\"']$", "");
      if (!key.isEmpty() && System.getenv(key) == null) {
        System.setProperty(key, value);
      }
    }
  }

  public static String env(String key, String fallback) {
    String value = System.getenv(key);
    if (value == null || value.isEmpty()) {
      value = System.getProperty(key);
    }
    return value == null || value.isEmpty() ? fallback : value;
  }

  public static String readPem(String valueOrPath) throws IOException {
    if (valueOrPath == null || valueOrPath.isBlank()) {
      throw new IllegalArgumentException("Missing PEM value or path");
    }
    if (valueOrPath.contains("-----BEGIN")) {
      return valueOrPath.replace("\\n", "\n");
    }
    return Files.readString(Path.of(valueOrPath));
  }

  public static String readText(Path path) throws IOException {
    return Files.readString(path);
  }

  public static String minifyJson(String json) {
    if (json == null || json.isBlank()) {
      return "{}";
    }

    StringBuilder builder = new StringBuilder(json.length());
    boolean inString = false;
    boolean escaped = false;

    for (int index = 0; index < json.length(); index++) {
      char ch = json.charAt(index);
      if (escaped) {
        builder.append(ch);
        escaped = false;
        continue;
      }
      if (ch == '\\' && inString) {
        builder.append(ch);
        escaped = true;
        continue;
      }
      if (ch == '"') {
        inString = !inString;
        builder.append(ch);
        continue;
      }
      if (!inString && Character.isWhitespace(ch)) {
        continue;
      }
      builder.append(ch);
    }

    return builder.toString();
  }

  public static String buildStringToSign(String timestamp, String merchantSecret, String jsonBody) {
    return String.join("|", timestamp, merchantSecret, minifyJson(jsonBody));
  }

  public static Map<String, String> signPayload(
      String timestamp,
      String merchantSecret,
      String jsonBody,
      String privateKeyPem
  ) throws Exception {
    String stringToSign = buildStringToSign(timestamp, merchantSecret, jsonBody);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(parsePrivateKey(privateKeyPem));
    signer.update(stringToSign.getBytes(StandardCharsets.UTF_8));

    Map<String, String> result = new LinkedHashMap<>();
    result.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
    result.put("stringToSign", stringToSign);
    result.put("body", minifyJson(jsonBody));
    return result;
  }

  public static Map<String, Object> signedHeaders(
      String merchantId,
      String timestamp,
      String merchantSecret,
      String jsonBody,
      String privateKeyPem
  ) throws Exception {
    Map<String, String> signed = signPayload(timestamp, merchantSecret, jsonBody, privateKeyPem);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Content-Type", "application/json");
    headers.put("X-PARTNER-ID", merchantId);
    headers.put("X-TIMESTAMP", timestamp);
    headers.put("X-SIGNATURE", signed.get("signature"));

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("headers", headers);
    result.put("body", signed.get("body"));
    result.put("stringToSign", signed.get("stringToSign"));
    return result;
  }

  public static boolean verifyCallback(
      String rawBody,
      String timestamp,
      String signature,
      String platformPublicKeyPem
  ) throws Exception {
    String stringToVerify = timestamp + "|" + minifyJson(rawBody);
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(parsePublicKey(platformPublicKeyPem));
    verifier.update(stringToVerify.getBytes(StandardCharsets.UTF_8));
    return verifier.verify(Base64.getDecoder().decode(signature));
  }

  public ApiResponse request(String method, String endpoint, String jsonBody) throws Exception {
    String normalizedMethod = method.toUpperCase();
    String signatureBody = normalizedMethod.equals("GET") ? "{}" : jsonBody;
    Map<String, Object> signed = signedHeaders(merchantId, now.get(), merchantSecret, signatureBody, privateKeyPem);
    @SuppressWarnings("unchecked")
    Map<String, String> headers = (Map<String, String>) signed.get("headers");

    HttpRequest.Builder builder = HttpRequest.newBuilder()
        .uri(URI.create(baseUrl + endpoint));
    for (Map.Entry<String, String> header : headers.entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }
    if (normalizedMethod.equals("GET")) {
      builder.GET();
    } else {
      builder.method(normalizedMethod, HttpRequest.BodyPublishers.ofString((String) signed.get("body")));
    }

    HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    return new ApiResponse(response.statusCode(), response.headers().map(), response.body());
  }

  public ApiResponse createPayIn(String jsonBody) throws Exception {
    return request("POST", "/v2.0/transaction/pay-in", jsonBody);
  }

  public ApiResponse createPayOut(String jsonBody) throws Exception {
    return request("POST", "/v2.0/disbursement/pay-out", jsonBody);
  }

  public ApiResponse inquiryStatus(String jsonBody) throws Exception {
    return request("POST", "/v2.0/inquiry-status", jsonBody);
  }

  public ApiResponse inquiryBalance(String jsonBody) throws Exception {
    return request("POST", "/v2.0/inquiry-balance", jsonBody);
  }

  public ApiResponse paymentMethods() throws Exception {
    return request("GET", "/v2.0/payment-methods", "{}");
  }

  private static PrivateKey parsePrivateKey(String privateKeyPem) throws Exception {
    String body = stripPem(privateKeyPem);
    byte[] decoded = Base64.getDecoder().decode(body);
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decoded));
  }

  private static PublicKey parsePublicKey(String publicKeyPem) throws Exception {
    String body = stripPem(publicKeyPem);
    byte[] decoded = Base64.getDecoder().decode(body);
    return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decoded));
  }

  private static String stripPem(String pem) {
    return pem
        .replaceAll("-----BEGIN [^-]+-----", "")
        .replaceAll("-----END [^-]+-----", "")
        .replaceAll("\\s+", "");
  }

  private static String trimRight(String value, String suffix) {
    String result = value;
    while (result.endsWith(suffix)) {
      result = result.substring(0, result.length() - suffix.length());
    }
    return result;
  }

  public interface TimestampSupplier {
    String get();
  }

  public record ApiResponse(int status, Map<String, java.util.List<String>> headers, String body) {
  }
}
