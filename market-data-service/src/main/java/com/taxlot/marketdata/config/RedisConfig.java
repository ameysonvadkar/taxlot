package com.taxlot.marketdata.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taxlot.marketdata.domain.LatestPrice;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    /**
     * Prices are stored as JSON under string keys.
     *
     * <p>The default template serialises with JDK serialization, which produces binary blobs that
     * are unreadable with {@code redis-cli} and break the moment a class changes shape. JSON keeps
     * the cache inspectable during a demo and survives a field being added.
     *
     * <p>{@link Jackson2JsonRedisSerializer} is given the concrete type rather than using the
     * generic variant, so no Java type information is embedded in the stored value — the cache
     * holds data, not serialised Java.
     */
    @Bean
    public RedisTemplate<String, LatestPrice> latestPriceRedisTemplate(
            RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {

        RedisTemplate<String, LatestPrice> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<LatestPrice> valueSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, LatestPrice.class);

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
