package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.netty.util.internal.StringUtil;
import org.apache.tomcat.util.buf.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        //解决缓存穿透
        Shop shop=cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id, Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
//        Shop shop=queryWithMutex(id);通过互斥锁解决缓存击穿问题
//        Shop shop=cacheClient.queryWithLogicalExpire(RedisConstants.LOCK_SHOP_KEY,RedisConstants.CACHE_SHOP_KEY,id, Shop.class,this::getById,10L,TimeUnit.SECONDS);//通过逻辑过期解决缓存击穿问题
        if(shop==null) return Result.fail("店铺不存在");
        return Result.ok(shop);
    }


    public Shop queryWithPassThrough(Long id){//解决缓存穿透
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJason = stringRedisTemplate.opsForValue().get(key);
        if(!StringUtil.isNullOrEmpty(shopJason)){
            Shop shop= JSONUtil.toBean(shopJason,Shop.class);
            return shop;
        }

        if(shopJason!=null && shopJason.isEmpty()){//是空值
            return null;
        }

        Shop shop=getById(id);

        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    public Shop queryWithMutex(Long id) {//通过互斥锁解决缓存击穿问题
        String key= RedisConstants.CACHE_SHOP_KEY+id;
        String shopJason = stringRedisTemplate.opsForValue().get(key);
        if(!StringUtil.isNullOrEmpty(shopJason)){
            Shop shop= JSONUtil.toBean(shopJason,Shop.class);
            return shop;
        }

        if(shopJason!=null && shopJason.isEmpty()){//是空值
            return null;
        }
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);//得到锁

        while (!isLock) { //没有得到锁
            try {
                Thread.sleep(10);
            } catch (InterruptedException e){
                e.printStackTrace();
            }
            isLock= tryLock(lockKey);
        }

        shopJason = stringRedisTemplate.opsForValue().get(key);//得到锁之后要再次检验redis
        Shop shop;

        if(!StringUtil.isNullOrEmpty(shopJason)){
            shop= JSONUtil.toBean(shopJason,Shop.class);
        } else if(shopJason!=null && shopJason.isEmpty()){//是空值
            shop=null;
        } else {
            shop = getById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            }
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }

        unLock(lockKey);
        return shop;
    }

    public void saveShop2Redis(Long id,Long expireData) {
        Shop shop=getById(id);
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireData));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));

    }

    private boolean tryLock(String key){ //得到锁
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    private void unLock(String key){//释放锁
        stringRedisTemplate.delete(key);
    }

    //缓存重建的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id){//通过逻辑过期解决缓存击穿问题
        String key=RedisConstants.CACHE_SHOP_KEY+id;
        String shopJson=stringRedisTemplate.opsForValue().get(key);
        if(StringUtil.isNullOrEmpty(shopJson)){ //如果是空的直接返回，但这种情况理论不存在，因为最开始我们已经预热了
            return null;
        }
        RedisData redisData= JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop=JSONUtil.toBean(data,Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){//还没有过期
            return shop;
        }

        //过期了
        String lockKey=RedisConstants.LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){//得到了锁
            //先再次判断redis中有没有
            shopJson=stringRedisTemplate.opsForValue().get(key);
            redisData= JSONUtil.toBean(shopJson, RedisData.class);
            data = (JSONObject) redisData.getData();
            shop=JSONUtil.toBean(data,Shop.class);
            expireTime = redisData.getExpireTime();
            if(expireTime.isAfter(LocalDateTime.now())){
                unLock(lockKey);
                return shop;
            }

            //开启一个新线程去查询数据库并更新缓存
            CACHE_REBUILD_EXECUTOR.execute(()->{
                try {
                    saveShop2Redis(id,RedisConstants.CACHE_SHOP_TTL);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }

        //没有得到锁直接返回旧数据
        return shop;

    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id=shop.getId();
        if(id==null){
            return Result.fail("店铺id不能为空");
        }
        //更新数据库
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+id);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //判断是否根据坐标查询
        if(x==null || y==null){
            Page<Shop> page=query().eq("type_id",typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        //计算分页参数
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;

        //从redis中根据距离，分页查询
        String key= SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),//圆心
                        new Distance(5000),//半径
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );

        if(results==null){
            return Result.ok();
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        //截取from到end中的一部分
        list.stream().skip(from).forEach(result->{
            String shopIdStr=result.getContent().getName();//店铺id
            Distance distance=result.getDistance();//距离
            distanceMap.put(shopIdStr,distance);
        });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);

    }
}
