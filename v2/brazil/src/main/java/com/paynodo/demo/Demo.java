package com.paynodo.demo;

import java.nio.file.Path;
import java.util.Map;

public final class Demo {
  public static void main(String[] args) throws Exception {
    Path rootDir = Path.of(".");
    PayNodoClient.loadDotEnv(rootDir.resolve(".env"));

    String command = args.length > 0 ? args[0] : "sign-payin";
    String merchantId = PayNodoClient.env("PAYNODO_MERCHANT_ID", "replace_with_merchant_id");
    String merchantSecret = PayNodoClient.env("PAYNODO_MERCHANT_SECRET", "replace_with_merchant_secret");

    String payIn = payInPayload(merchantId);
    String payOut = payOutPayload(merchantId);
    String status = statusPayload();
    String balance = balancePayload();

    if (command.equals("verify-callback")) {
      String publicKey = PayNodoClient.readPem(PayNodoClient.env(
          "PAYNODO_PLATFORM_PUBLIC_KEY_PEM",
          PayNodoClient.env("PAYNODO_PLATFORM_PUBLIC_KEY_PATH", rootDir.resolve("paynodo-public-key.pem").toString())
      ));
      boolean valid = PayNodoClient.verifyCallback(
          requiredEnv("PAYNODO_CALLBACK_BODY"),
          requiredEnv("PAYNODO_CALLBACK_TIMESTAMP"),
          requiredEnv("PAYNODO_CALLBACK_SIGNATURE"),
          publicKey
      );
      System.out.println("{\"valid\":" + valid + "}");
      return;
    }

    String privateKey = PayNodoClient.readPem(PayNodoClient.env(
        "PAYNODO_PRIVATE_KEY_PEM",
        PayNodoClient.env("PAYNODO_PRIVATE_KEY_PATH", rootDir.resolve("merchant-private-key.pem").toString())
    ));

    if (command.equals("sign-payin")) {
      String timestamp = PayNodoClient.env("PAYNODO_TIMESTAMP", "2026-04-17T16:20:30-03:00");
      printMap(PayNodoClient.signedHeaders(merchantId, timestamp, merchantSecret, payIn, privateKey));
      return;
    }

    PayNodoClient client = new PayNodoClient(
        PayNodoClient.env("PAYNODO_BASE_URL", PayNodoClient.DEFAULT_BASE_URL),
        merchantId,
        merchantSecret,
        privateKey
    );

    PayNodoClient.ApiResponse response;
    switch (command) {
      case "payin" -> response = client.createPayIn(payIn);
      case "payout" -> response = client.createPayOut(payOut);
      case "status" -> response = client.inquiryStatus(status);
      case "balance" -> response = client.inquiryBalance(balance);
      case "methods" -> response = client.paymentMethods();
      default -> throw new IllegalArgumentException("Unknown command. Use one of: sign-payin, verify-callback, payin, payout, status, balance, methods");
    }

    System.out.println("status=" + response.status());
    System.out.println(response.body());
  }

  private static String payInPayload(String merchantId) {
    return """
        {"orderNo":%s,"purpose":%s,"merchant":{"merchantId":%s,"merchantName":%s},"money":{"currency":"BRL","amount":%d},"payer":{"pixAccount":%s},"paymentMethod":%s,"expiryPeriod":%d,"redirectUrl":%s,"callbackUrl":%s}
        """.formatted(
        quote(PayNodoClient.env("PAYNODO_PAYIN_ORDER_NO", "ORDPI2026000001")),
        quote(PayNodoClient.env("PAYNODO_PAYIN_PURPOSE", "customer payment")),
        quote(merchantId),
        quote(PayNodoClient.env("PAYNODO_MERCHANT_NAME", "Integrated Merchant")),
        intEnv("PAYNODO_PAYIN_AMOUNT", 12000),
        quote(PayNodoClient.env("PAYNODO_PAYER_PIX_ACCOUNT", "48982488880")),
        quote(PayNodoClient.env("PAYNODO_PAYIN_METHOD", "PIX")),
        intEnv("PAYNODO_EXPIRY_PERIOD", 3600),
        quote(PayNodoClient.env("PAYNODO_REDIRECT_URL", "https://merchant.example/return")),
        quote(PayNodoClient.env("PAYNODO_CALLBACK_URL", "https://merchant.example/webhooks/paynodo"))
    );
  }

  private static String payOutPayload(String merchantId) {
    return """
        {"additionalParam":{},"cashAccount":%s,"receiver":{"taxNumber":%s,"accountName":%s},"merchant":{"merchantId":%s},"money":{"amount":%d,"currency":"BRL"},"orderNo":%s,"paymentMethod":%s,"purpose":%s,"callbackUrl":%s}
        """.formatted(
        quote(PayNodoClient.env("PAYNODO_PAYOUT_CASH_ACCOUNT", "12532481501")),
        quote(PayNodoClient.env("PAYNODO_RECEIVER_TAX_NUMBER", "12345678909")),
        quote(PayNodoClient.env("PAYNODO_RECEIVER_NAME", "Betty")),
        quote(merchantId),
        intEnv("PAYNODO_PAYOUT_AMOUNT", 10000),
        quote(PayNodoClient.env("PAYNODO_PAYOUT_ORDER_NO", "ORDPO2026000001")),
        quote(PayNodoClient.env("PAYNODO_PAYOUT_METHOD", "CPF")),
        quote(PayNodoClient.env("PAYNODO_PAYOUT_PURPOSE", "Purpose For Disbursement from API")),
        quote(PayNodoClient.env("PAYNODO_CALLBACK_URL", "https://merchant.example/webhooks/paynodo"))
    );
  }

  private static String statusPayload() {
    return """
        {"tradeType":%d,"orderNo":%s}
        """.formatted(
        intEnv("PAYNODO_STATUS_TRADE_TYPE", 1),
        quote(PayNodoClient.env("PAYNODO_STATUS_ORDER_NO", PayNodoClient.env("PAYNODO_PAYIN_ORDER_NO", "ORDPI2026000001")))
    );
  }

  private static String balancePayload() {
    return """
        {"accountNo":%s,"balanceTypes":["BALANCE"]}
        """.formatted(quote(PayNodoClient.env("PAYNODO_ACCOUNT_NO", "YOUR_ACCOUNT_NO")));
  }

  private static String quote(String value) {
    return "\"" + value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r") + "\"";
  }

  private static int intEnv(String key, int fallback) {
    try {
      return Integer.parseInt(PayNodoClient.env(key, Integer.toString(fallback)));
    } catch (NumberFormatException error) {
      return fallback;
    }
  }

  private static String requiredEnv(String key) {
    String value = System.getenv(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private static void printMap(Map<String, Object> value) {
    System.out.println(value);
  }
}
