package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import io.netty.util.internal.StringUtil;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {

    @Resource
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){//逻辑过期解决缓存击穿问题
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(String prefixKey, ID id, Class<R> type, Function<ID,R> dbFallback,
                                         Long time,TimeUnit unit){//解决缓存穿透
        String key= prefixKey+id;
        String json = stringRedisTemplate.opsForValue().get(key);

        if(!StringUtil.isNullOrEmpty(json)){
            R r= JSONUtil.toBean(json,type);
            return r;
        }

        if(json!=null && json.isEmpty()){//是空值
            return null;
        }

        R r= dbFallback.apply(id);
        if(r==null){
            this.set(key,"",time,unit);
            return null;
        }
        this.set(key,r,time,unit);
        return r;
    }

    //缓存重建的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public <R,ID> R queryWithLogicalExpire(String prefixLockKey,String prefixKey,ID id,Class<R> type,Function<ID,R> dbFallback,
                                           Long time,TimeUnit unit){//通过逻辑过期解决缓存击穿问题
        String key=prefixKey+id;
        String json=stringRedisTemplate.opsForValue().get(key);
        if(StringUtil.isNullOrEmpty(json)){ //如果是空的直接返回，但这种情况理论不存在，因为最开始我们已经预热了
            return null;
        }
        RedisData redisData= JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r=JSONUtil.toBean(data,type);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){//还没有过期
            return r;
        }

        //过期了
        String lockKey=prefixLockKey+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){//得到了锁
            //先再次判断redis中有没有
            json=stringRedisTemplate.opsForValue().get(key);
            redisData= JSONUtil.toBean(json, RedisData.class);
            data = (JSONObject) redisData.getData();
            r=JSONUtil.toBean(data,type);
            expireTime = redisData.getExpireTime();

            if(expireTime.isAfter(LocalDateTime.now())){
                unLock(lockKey);
                return r;
            }
            //开启一个新线程去查询数据库并更新缓存
            CACHE_REBUILD_EXECUTOR.execute(()->{
                try {
                    R r1=dbFallback.apply(id);
                    this.setWithLogicalExpire(key,r1,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //没有得到锁直接返回旧数据
        return r;
    }

    private boolean tryLock(String key){ //得到锁
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    private void unLock(String key){//释放锁
        stringRedisTemplate.delete(key);
    }
}
