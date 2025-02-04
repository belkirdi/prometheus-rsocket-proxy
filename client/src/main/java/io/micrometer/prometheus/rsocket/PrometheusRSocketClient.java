/**
 * Copyright 2019 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.prometheus.rsocket;

import io.micrometer.core.instrument.Counter;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.rsocket.*;
import io.rsocket.transport.ClientTransport;
import io.rsocket.transport.netty.client.TcpClientTransport;
import io.rsocket.util.DefaultPayload;
import org.xerial.snappy.Snappy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Establishes a persistent bidirectional RSocket connection to a Prometheus RSocket proxy.
 * Prometheus scrapes each proxy instance. Proxies in turn use the connection to pull metrics
 * from each client.
 */
public class PrometheusRSocketClient {
  private final PrometheusMeterRegistry registry;
  private final Disposable connection;
  private AtomicReference<ByteBuffer> latestKey = new AtomicReference<>();
  private final AbstractRSocket rsocket = new AbstractRSocket() {
    @Override
    public Mono<Payload> requestResponse(Payload payload) {
      ByteBuffer key = payload.getData();
      latestKey.set(key);
      return Mono.just(scrapePayload(key));
    }

    @Override
    public Mono<Void> fireAndForget(Payload payload) {
      latestKey.set(payload.getData());
      return Mono.empty();
    }
  };
  private boolean pushOnDisconnect = false;
  private RSocket sendingSocket;

  public PrometheusRSocketClient(PrometheusMeterRegistry registry, ClientTransport transport,
                                 UnaryOperator<Flux<Void>> customizeAndRetry) {
    this.registry = registry;
    Counter attempts = Counter.builder("prometheus.connection.attempts")
      .description("Attempts at making an outbound RSocket connection to the Prometheus proxy")
      .baseUnit("attempts")
      .register(registry);
    attempts.increment();

    this.connection = customizeAndRetry.apply(RSocketFactory.connect()
      .acceptor(r -> {
        this.sendingSocket = r;
        return rsocket;
      })
      .transport(transport)
      .start()
      .doOnCancel(() -> {
        if (pushOnDisconnect) {
          ByteBuffer key = latestKey.get();
          if (key != null) {
            sendingSocket
              .fireAndForget(scrapePayload(key))
              .block(Duration.ofSeconds(10));
          }
        }
      })
      .flatMap(Closeable::onClose)
      .repeat(() -> {
        attempts.increment();
        return true;
      })
    ).subscribe();
  }

  public void close() {
    connection.dispose();
  }

  public void pushAndClose() {
    this.pushOnDisconnect = true;
    close();
  }

  private Payload scrapePayload(ByteBuffer encodedKeyBuffer) {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(128);
      SecretKey secKey = generator.generateKey();

      Cipher aesCipher = Cipher.getInstance("AES");
      aesCipher.init(Cipher.ENCRYPT_MODE, secKey);
      byte[] encryptedMetrics = aesCipher.doFinal(Snappy.compress(registry.scrape()));

      byte[] encodedKey = new byte[encodedKeyBuffer.capacity()];
      encodedKeyBuffer.get(encodedKey, 0, encodedKey.length);

      X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encodedKey);
      KeyFactory keyFactory = KeyFactory.getInstance("RSA");
      PublicKey publicKey = keyFactory.generatePublic(keySpec);

      Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
      cipher.init(Cipher.PUBLIC_KEY, publicKey);
      byte[] encryptedPublicKey = cipher.doFinal(secKey.getEncoded());

      return DefaultPayload.create(encryptedMetrics, encryptedPublicKey);
    } catch (Throwable e) {
      throw new IllegalArgumentException(e);
    }
  }
}
