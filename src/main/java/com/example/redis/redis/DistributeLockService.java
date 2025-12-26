package com.example.redis.redis;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
public class DistributeLockService {

    private final RedissonClient redissonClient;

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

    public void runWithLock2(String lockKey, long waitTimeSec, long leaseTimeSec, Runnable task) {
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
            } else {
                throw new RuntimeException("");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public <T> T runWithLock2(String lockKey, long waitTimeSec, long leaseTimeSec, Supplier<T> task) {
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // tryLock(waitTime, leaseTime, TimeUnit.SECONDS)
            if (lock.tryLock(waitTimeSec, leaseTimeSec, TimeUnit.SECONDS)) {
                try {
                    return task.get();
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } else {
                throw new RuntimeException("");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }
}
