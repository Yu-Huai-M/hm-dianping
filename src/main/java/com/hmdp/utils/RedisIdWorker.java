package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP=1640995200;
    private StringRedisTemplate stringRedisTemplate;
    private static final int COUNT_BITS=32;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextID(String prefixKey){
        LocalDateTime now=LocalDateTime.now();
        long nowSecond=now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowSecond-BEGIN_TIMESTAMP;//生成时间戳

        String date=now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));//今天的时间
        long count = stringRedisTemplate.opsForValue().increment("icr:" + prefixKey + ":"+date);
        return timeStamp << COUNT_BITS | count;
    }

    public static void main(String[] args) {
        LocalDateTime dateTime=LocalDateTime.of(2022,1,1,0,0,0);
        long second= dateTime.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second = " + second);
    }
}
