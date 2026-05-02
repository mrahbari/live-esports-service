package com.abioscase.live.config;

import java.time.Duration;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
@EnableConfigurationProperties({AbiosProperties.class, LiveDataProperties.class})
public class AppConfiguration {

    @Bean
    public RestClient abiosRestClient(AbiosProperties abios) {
        PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
        cm.setMaxTotal(20);
        cm.setDefaultMaxPerRoute(10);

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(abios.getConnectTimeoutMs()))
                .setResponseTimeout(Timeout.ofMilliseconds(abios.getReadTimeoutMs()))
                .build();

        var httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(requestConfig)
                .build();

        return RestClient.builder()
                .baseUrl(abios.getBaseUrl())
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "abioscase-live/1.0")
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "app.live.cache-mode", havingValue = "redis")
    public ProxyManager<byte[]> redisProxyManager(RedisConnectionFactory connectionFactory) {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuceFactory)) {
            throw new IllegalStateException("RedisConnectionFactory is not Lettuce-based: " + connectionFactory.getClass());
        }

        Object nativeClient = lettuceFactory.getNativeClient();
        if (nativeClient == null) {
            throw new IllegalStateException("Native Lettuce client is not available");
        }

        ExpirationAfterWriteStrategy expirationStrategy = ExpirationAfterWriteStrategy
                .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(1));

        if (nativeClient instanceof RedisClusterClient clusterClient) {
            return LettuceBasedProxyManager.builderFor(clusterClient)
                    .withExpirationStrategy(expirationStrategy)
                    .build();
        } else if (nativeClient instanceof RedisClient client) {
            return LettuceBasedProxyManager.builderFor(client)
                    .withExpirationStrategy(expirationStrategy)
                    .build();
        }

        throw new IllegalStateException("Unsupported native Lettuce client type: " + nativeClient.getClass());
    }
}
