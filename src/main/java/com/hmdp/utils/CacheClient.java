package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.stringRedisTemplate = redisTemplate;
    }

    public void set(String key, Object value, Long Time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),Time,timeUnit);
    }

    public void setWithLogicExpire(String key, Object value, Long Time, TimeUnit timeUnit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(redisData);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(Time)));
        //写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),Time,timeUnit);
    }

    //泛型  + (有参有返回值)函数 因为不知道是什么类型 函数式编程
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long Time, TimeUnit timeUnit){
        String key = keyPrefix + id;
        //1. 从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 判断redis中是否存在
        //isNotBlank()对于空字符串""的返回值也是false
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }

        //判断命中的是否为空值
        //""!=null 这里判断的是是否为""
        if(json!=null){
            return null;
        }

        //4. Redis中不存在，根据id查询数据库
        R r = dbFallback.apply(id);

        //5. 数据库中不存在，返回错误
        if (r == null) {
            //将空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
            //返回错误信息
            return null;
        }

        //6. 数据库存在，先写入redis 并设置过期时间  再返回
        this.set(key,r,Time,timeUnit);

        return r;
    }


    //使用线程池开启线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //使用逻辑过期时间解决缓存击穿
    public <R,ID> R queryWithLogicExpire(String keyPrefix, ID id,Class<R> type, Function<ID,R> dbFallback,
                                         Long Time, TimeUnit timeUnit){
        //1. 从redis查询商铺缓存
        String Json = stringRedisTemplate.opsForValue().get(keyPrefix+id);
        //2. 判断redis中是否存在
        if(StrUtil.isBlank(Json)){
            //3. 为空
            return null;
        }

        //4. 命中，需要将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(Json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();

        //5. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期 直接返回店铺
            return r;
        }
        //5.2 过期 需要缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if (isLock) {
            //6.3 成功，开启独立线程，实现缓存重建
            //获取锁成功后应该再次检测redis缓存是否过期 做DoubleCheck 如果存在则无需重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查数据库
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicExpire(keyPrefix+id,r1,Time,timeUnit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }

        //6.4 返回过期的商铺信息
        return r;
    }

    //获取互斥锁
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }

}
