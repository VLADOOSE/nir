package com.vladoose.nir.passkeys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Эмулятор ключа входа (passkey) для тестов: ES256 и аттестация «none» — как у Face ID / Touch ID.
 * Тела запросов — ровно в формате эталонного клиента Spring (spring-security-webauthn.js) и нашего фронта
 * (services/passkey.service.ts), включая поле credType при входе. Спека passkeys-login §6, §11.
 * Собран на webauthn4j-core и JDK: webauthn4j-test не берём — он тянет BouncyCastle и ещё три модуля.
 */
final class VirtualPasskey {

    static final String ORIGIN = "http://localhost:4200";   // passkeys.allowed-origins в application.yaml
    static final String RP_ID = "localhost";                 // passkeys.rp-id

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectConverter CBOR = new ObjectConverter();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private byte[] userHandle;          // user.id из параметров регистрации: устройство хранит его вместе с ключом
    private long signCount;

    VirtualPasskey() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        this.keys = g.generateKeyPair();
        RANDOM.nextBytes(credentialId);
    }

    String credentialId() {
        return b64(credentialId);
    }

    /** Ответ POST /webauthn/register/options → тело POST /webauthn/register. */
    String registrationBody(String optionsJson, String label, String origin) throws Exception {
        JsonNode options = JSON.readTree(optionsJson);
        userHandle = Base64.getUrlDecoder().decode(options.at("/user/id").asText());
        byte[] clientData = clientData("webauthn.create", options.at("/challenge").asText(), origin);

        AttestedCredentialData attested = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
        AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authData = new AuthenticatorData<>(
                rpIdHash(), (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV | AuthenticatorData.BIT_AT),
                signCount, attested);
        byte[] attestationObject = new AttestationObjectConverter(CBOR)
                .convertToBytes(new AttestationObject(authData, new NoneAttestationStatement()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("attestationObject", b64(attestationObject));
        response.put("clientDataJSON", b64(clientData));
        response.put("transports", List.of("internal"));
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", b64(credentialId));
        credential.put("rawId", b64(credentialId));
        credential.put("response", response);
        credential.put("type", "public-key");
        credential.put("clientExtensionResults", Map.of());
        credential.put("authenticatorAttachment", "platform");
        return JSON.writeValueAsString(Map.of("publicKey", Map.of("credential", credential, "label", label)));
    }

    /** Ответ POST /webauthn/authenticate/options → тело POST /login/webauthn. */
    String assertionBody(String optionsJson, String origin) throws Exception {
        JsonNode options = JSON.readTree(optionsJson);
        byte[] clientData = clientData("webauthn.get", options.at("/challenge").asText(), origin);
        signCount++;
        byte[] authData = new AuthenticatorDataConverter(CBOR).convert(
                new AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(
                        rpIdHash(), (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV), signCount));

        Signature s = Signature.getInstance("SHA256withECDSA");         // ES256: подпись в DER, как у WebAuthn
        s.initSign(keys.getPrivate());
        s.update(ByteBuffer.allocate(authData.length + 32).put(authData).put(sha256(clientData)).array());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticatorData", b64(authData));
        response.put("clientDataJSON", b64(clientData));
        response.put("signature", b64(s.sign()));
        response.put("userHandle", b64(userHandle));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", b64(credentialId));
        body.put("rawId", b64(credentialId));
        body.put("response", response);
        body.put("credType", "public-key");        // так шлёт эталонный клиент Spring — повторяем как есть
        body.put("clientExtensionResults", Map.of());
        body.put("authenticatorAttachment", "platform");
        return JSON.writeValueAsString(body);
    }

    /** Чужой ключ, выдающий себя за этот: тот же id и userHandle, но своя пара ключей — подпись не сойдётся. */
    VirtualPasskey impostor() throws GeneralSecurityException {
        VirtualPasskey other = new VirtualPasskey();
        System.arraycopy(credentialId, 0, other.credentialId, 0, credentialId.length);
        other.userHandle = userHandle;
        other.signCount = signCount;
        return other;
    }

    private static byte[] clientData(String type, String challenge, String origin) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("challenge", challenge);
        m.put("origin", origin);
        m.put("crossOrigin", false);
        return JSON.writeValueAsBytes(m);
    }

    private static byte[] rpIdHash() throws GeneralSecurityException {
        return sha256(RP_ID.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
