package com.thegamecellar.recommendationservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.thegamecellar.recommendationservice.model.entity.UserProfileSnapshot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        GenericJackson2JsonRedisSerializer json = new GenericJackson2JsonRedisSerializer(mapper);

        RedisTemplate<String, Object> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(cf);
        tpl.setKeySerializer(StringRedisSerializer.UTF_8);
        tpl.setValueSerializer(json);
        tpl.setHashKeySerializer(StringRedisSerializer.UTF_8);
        tpl.setHashValueSerializer(json);
        tpl.afterPropertiesSet();
        return tpl;
    }

    // Typed, so a cached snapshot reads back as a UserProfileSnapshot. The template above stores no
    // type information, so a snapshot read through it comes back as a Map.
    @Bean
    public RedisTemplate<String, UserProfileSnapshot> userProfileRedisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, UserProfileSnapshot> tpl = new RedisTemplate<>();
        tpl.setConnectionFactory(cf);
        tpl.setKeySerializer(StringRedisSerializer.UTF_8);
        tpl.setValueSerializer(new JacksonJsonRedisSerializer<>(UserProfileSnapshot.class));
        tpl.afterPropertiesSet();
        return tpl;
    }
}
