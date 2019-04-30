package io.micrometer.prometheus.rsocket;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.rsocket.AbstractRSocket;
import io.rsocket.Payload;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import reactor.core.publisher.Mono;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Establishes a persistent bidirectional RSocket connection to a Prometheus RSocket proxy.
 * Prometheus scrapes each proxy instance. Proxies in turn use the connection to pull metrics
 * from each client.
 */
public class PrometheusRSocketClient {
  public static Mono<RSocket> connect(PrometheusMeterRegistry registry, String bindAddress, int port) {
    return RSocketFactory.connect()
      .acceptor(
        rSocket ->
          new AbstractRSocket() {
            @Override
            public Mono<Payload> requestResponse(Payload payload) {
              try{
                KeyGenerator generator = KeyGenerator.getInstance("AES");
                generator.init(128);
                SecretKey secKey = generator.generateKey();

                Cipher aesCipher = Cipher.getInstance("AES");
                aesCipher.init(Cipher.ENCRYPT_MODE, secKey);
                byte[] encryptedMetrics = aesCipher.doFinal(registry.scrape().getBytes());

                X509EncodedKeySpec keySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(payload.getDataUtf8()));
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                PublicKey publicKey = keyFactory.generatePublic(keySpec);

                Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
                cipher.init(Cipher.PUBLIC_KEY, publicKey);
                byte[] encryptedPublicKey = cipher.doFinal(secKey.getEncoded());

                return Mono.just(DefaultPayload.create(encryptedMetrics, encryptedPublicKey));
              } catch (Throwable e) {
                throw new IllegalArgumentException(e);
              }
            }
          })
      .transport(TcpClientTransport.create(bindAddress, port))
      .start();
  }
}
