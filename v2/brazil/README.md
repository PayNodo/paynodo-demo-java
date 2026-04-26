# PayNodo Brazil V2 Java Demo

Backend-only Java demo for PayNodo Brazil V2.

## Requirements

- Java 17+

No Maven or Gradle dependencies are required.

## Setup

```shell
cp .env.example .env
```

Edit `.env` and replace sandbox values with the credentials from the merchant cabinet.
Save the merchant private key as `merchant-private-key.pem`, or set `PAYNODO_PRIVATE_KEY_PEM` directly in `.env`.

## Generate a signed PayIn preview

```shell
javac -d build/classes src/main/java/com/paynodo/demo/*.java
java -cp build/classes com.paynodo.demo.Demo sign-payin
```

## Send sandbox requests

```shell
java -cp build/classes com.paynodo.demo.Demo payin
java -cp build/classes com.paynodo.demo.Demo payout
java -cp build/classes com.paynodo.demo.Demo status
java -cp build/classes com.paynodo.demo.Demo balance
java -cp build/classes com.paynodo.demo.Demo methods
```

## Verify a callback signature

```shell
PAYNODO_CALLBACK_BODY='{"orderNo":"ORDPI2026000001","status":"SUCCESS"}' \
PAYNODO_CALLBACK_TIMESTAMP='2026-04-17T13:25:10.000Z' \
PAYNODO_CALLBACK_SIGNATURE='replace_with_callback_signature' \
java -cp build/classes com.paynodo.demo.Demo verify-callback
```

The private key and merchant secret must stay on the merchant backend.
