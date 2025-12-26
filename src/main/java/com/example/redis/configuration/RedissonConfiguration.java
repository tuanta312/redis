package com.example.redis.configuration;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfiguration {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Bean
    public Config config() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(String.format("redis://%s:%d", host, port))
                .setUsername(null)
                .setPassword(null)
                .setDatabase(0)
                .setConnectionPoolSize(10);
        return config;
    }

    @Bean
    public RedissonClient redissonClient() {
        return Redisson.create(config());
    }
}
