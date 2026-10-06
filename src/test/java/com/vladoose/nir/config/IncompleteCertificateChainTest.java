package com.vladoose.nir.config;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сервер, который отдаёт сертификат БЕЗ промежуточного, принимается — как fms.ecc.kz с 2026-09-28
 * (сертификат GoGetSSL без цепочки: Java падала «PKIX path building failed», импорт СК-Фармации
 * и кнопка «ТЗ» не работали). Браузер докачивает промежуточный по адресу из сертификата (AIA);
 * Java — только с флагом, который приложение включает при загрузке класса {@code Nir2Application}.
 *
 * <p>Фикстуры {@code tls/aia}: тестовый корень → промежуточный → лист {@code 127.0.0.1} с AIA
 * {@code http://127.0.0.1:17611/inter.cer}, срок до 2076 г. Сделаны keytool (JDK 17):
 * {@code -genkeypair -keyalg EC -groupname secp256r1}; промежуточный — {@code -gencert -ext bc:c=ca:true,pathlen:0
 * -ext ku:c=keyCertSign,cRLSign}; лист — {@code -gencert -ext san=ip:127.0.0.1 -ext eku=serverAuth
 * -ext aia=caIssuers:uri:http://127.0.0.1:17611/inter.cer}; пароль хранилища {@code changeit}.
 * В {@code leaf.p12} — ключ листа и полная цепочка (сервер ниже отдаёт только лист), {@code root.pem} —
 * единственное, чему доверяет клиент, {@code inter.cer} — промежуточный в DER, его отдаёт «AIA-сервер».
 *
 * <p>⚠️ Флаг JDK читается ОДИН раз на JVM (static final в {@code sun.security.provider.certpath.Builder}):
 * если до этого теста в той же JVM кто-то проверил TLS-цепочку без флага, тест упадёт — и это правда,
 * а не ложная тревога: в приложении было бы то же самое.
 */
class IncompleteCertificateChainTest {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final int AIA_PORT = 17611;   // зашит в сертификат листа

    private HttpServer aiaServer;
    private HttpsServer httpsServer;

    @BeforeAll
    static void loadApplicationClass() throws ClassNotFoundException {
        // Ровно то, что происходит при старте приложения: JVM инициализирует класс приложения до main().
        Class.forName("com.vladoose.nir.Nir2Application", true, IncompleteCertificateChainTest.class.getClassLoader());
    }

    @AfterEach
    void stopServers() {
        if (httpsServer != null) httpsServer.stop(0);
        if (aiaServer != null) aiaServer.stop(0);
    }

    @Test
    void serverWithoutIntermediateCertificateIsAccepted() throws Exception {
        AtomicInteger aiaRequests = new AtomicInteger();
        byte[] intermediate = resource("inter.cer");
        aiaServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), AIA_PORT), 0);
        aiaServer.createContext("/inter.cer", ex -> {
            aiaRequests.incrementAndGet();
            ex.getResponseHeaders().add("Content-Type", "application/pkix-cert");
            ex.sendResponseHeaders(200, intermediate.length);
            ex.getResponseBody().write(intermediate);
            ex.close();
        });
        aiaServer.start();

        httpsServer = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(leafOnlyServerContext()));
        httpsServer.createContext("/", ex -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        httpsServer.start();

        HttpClient http = HttpClient.newBuilder()
                .sslContext(clientTrustingOnlyTestRoot())
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/"))
                        .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("ok");
        // сервер действительно прислал один лист — цепочку достроил AIA, а не сам сервер
        assertThat(response.sslSession().orElseThrow().getPeerCertificates()).hasSize(1);
        assertThat(aiaRequests.get()).isGreaterThanOrEqualTo(1);
    }

    /** Ключ листа и цепочка из ОДНОГО листа — как у fms.ecc.kz. */
    private static SSLContext leafOnlyServerContext() throws Exception {
        KeyStore source = KeyStore.getInstance("PKCS12");
        try (InputStream in = stream("leaf.p12")) {
            source.load(in, PASSWORD);
        }
        PrivateKey key = (PrivateKey) source.getKey("leaf", PASSWORD);
        Certificate leaf = source.getCertificate("leaf");
        KeyStore leafOnly = KeyStore.getInstance("PKCS12");
        leafOnly.load(null, null);
        leafOnly.setKeyEntry("leaf", key, PASSWORD, new Certificate[]{leaf});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(leafOnly, PASSWORD);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** Клиент доверяет только тестовому корню — промежуточного у него нет. */
    private static SSLContext clientTrustingOnlyTestRoot() throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        try (InputStream in = stream("root.pem")) {
            trust.setCertificateEntry("root", CertificateFactory.getInstance("X.509").generateCertificate(in));
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = stream(name)) {
            return in.readAllBytes();
        }
    }

    private static InputStream stream(String name) {
        InputStream in = IncompleteCertificateChainTest.class.getResourceAsStream("/tls/aia/" + name);
        if (in == null) throw new IllegalStateException("нет фикстуры tls/aia/" + name);
        return in;
    }
}
