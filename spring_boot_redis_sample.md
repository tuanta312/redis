# Spring Boot + Redis: Connection Pool, Distributed Lock, Global Exception Handler

Dưới đây là một project mẫu (pom + cấu hình + các lớp Java) minh họa cách:
- cấu hình connection pool cho Lettuce (Spring Data Redis),
- dùng Redisson để thao tác distributed lock (đơn giản, an toàn),
- và định nghĩa một global exception handler cho REST API.

> Bạn có thể copy từng file vào project Spring Boot của mình.

---

## `pom.xml`

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.example</groupId>
  <artifactId>spring-boot-redis-sample</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.1.4</version>
    <relativePath/>
  </parent>

  <dependencies>
    <!-- Spring Web -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Spring Data Redis (Lettuce) -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>

    <!-- Redisson for distributed locks -->
    <dependency>
      <groupId>org.redisson</groupId>
      <artifactId>redisson-spring-boot-starter</artifactId>
      <version>3.20.0</version>
    </dependency>

    <!-- Optional: Lombok -->
    <dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <optional>true</optional>
    </dependency>

    <!-- For runtime: validation -->
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>

    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>

    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <properties>
    <java.version>17</java.version>
  </properties>

</project>
```

---

## `application.yml`

```yaml
spring:
  redis:
    host: localhost
    port: 6379

# Redisson config can be provided via spring.redis.* or redisson YAML/JSON; below is a simple single server example
redisson:
  address: "redis://localhost:6379"
```

---

## `RedisConfig.java` — Lettuce with pooling

```java
package com.example.redisconfig;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import io.lettuce.core.resource.DefaultClientResources;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "shutdown")
    public DefaultClientResources lettuceClientResources() {
        return DefaultClientResources.create();
    }

    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration serverConfig = new RedisStandaloneConfiguration("localhost", 6379);

        GenericObjectPoolConfig<?> poolConfig = new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(50);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(1);

        LettuceClientConfiguration clientConfig = LettucePoolingClientConfiguration.builder()
            .clientResources(lettuceClientResources())
            .poolConfig(poolConfig)
            .build();

        return new LettuceConnectionFactory(serverConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
```

> `LettucePoolingClientConfiguration` kết hợp với `GenericObjectPoolConfig` tạo connection pool.

---

## `DistributedLockService.java` — dùng Redisson

```java
package com.example.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class DistributedLockService {

    private final RedissonClient redissonClient;

    public DistributedLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * Thực thi một tác vụ có lock. Trả về true nếu thực thi thành công.
     */
    public boolean runWithLock(String lockKey, long waitTimeSec, long leaseTimeSec, Runnable task) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // tryLock(waitTime, leaseTime, TimeUnit.SECONDS)
            if (lock.tryLock(waitTimeSec, leaseTimeSec, TimeUnit.SECONDS)) {
                try {
                    task.run();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
                return true;
            } else {
                return false; // không lấy được lock
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
```

> Redisson giữ lock an toàn (re-entrant, lease timeout) và xử lý failover tốt hơn so với tự implement SET NX.

---

## `ExampleService.java` — ví dụ sử dụng lock

```java
package com.example.service;

import com.example.lock.DistributedLockService;
import org.springframework.stereotype.Service;

@Service
public class ExampleService {

    private final DistributedLockService lockService;

    public ExampleService(DistributedLockService lockService) {
        this.lockService = lockService;
    }

    public String incrementWithLock() {
        boolean ok = lockService.runWithLock("counter_lock", 5, 20, () -> {
            // TODO: đo đọc-ghi redis hoặc DB ở đây
            System.out.println("Đang thực hiện công việc quan trọng... ");
        });
        return ok ? "DONE" : "LOCKED_BY_OTHER";
    }
}
```

---

## `GlobalExceptionHandler.java` — xử lý ngoại lệ chung cho REST

```java
package com.example.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError err : ex.getBindingResult().getFieldErrors()) {
            errors.put(err.getField(), err.getDefaultMessage());
        }
        return ResponseEntity.badRequest().body(Map.of("errors", errors));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<?> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<?> handleAll(Exception ex) {
        ex.printStackTrace(); // hoặc log rõ ràng
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error", "internal_error",
                        "message", ex.getMessage()
                ));
    }
}
```

---

## `ExampleController.java`

```java
package com.example.controller;

import com.example.service.ExampleService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ExampleController {

    private final ExampleService exampleService;

    public ExampleController(ExampleService exampleService) {
        this.exampleService = exampleService;
    }

    @PostMapping("/inc")
    public String increment() {
        return exampleService.incrementWithLock();
    }
}
```

---

## Ghi chú & best-practices

- **Connection pool:** Lettuce là async-first và dùng pooling wrapper (`LettucePoolingClientConfiguration`) cho use-case sync. Tuy nhiên Lettuce về bản chất không cần pool cho workload async; pool hữu dụng khi bạn dùng Redis connections sync.
- **Distributed lock:** Redisson là lựa chọn an toàn, xử lý auto-renewal, reentrant lock, cluster-aware. Nếu muốn lightweight: có thể dùng SET key value NX PX ttl + lua script để release an toàn (so sánh value).
- **Exception handler:** `@ControllerAdvice` + các `@ExceptionHandler` giúp thống nhất format lỗi API.
- **Timeouts/lease:** luôn đặt `leaseTime` hợp lý để tránh deadlock khi instance chết.

---

Nếu bạn muốn mình tạo 1 project mẫu đầy đủ (maven structure, package, file) để bạn chạy luôn — mình có thể tạo sẵn trong canvas này theo cấu trúc chi tiết (pom, src/main/java/..., application.yml). Nói cho mình biết nếu bạn muốn thêm ví dụ Lua SETNX release, hoặc thay Redisson bằng implement thủ công.

