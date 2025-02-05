package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import io.lettuce.core.api.async.RedisGeoAsyncCommands;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es= Executors.newFixedThreadPool(500);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testIDWorker() throws InterruptedException {
        CountDownLatch latch=new CountDownLatch(300);
        Runnable test =() ->{
            for (int i=0;i<100;i++){
                long id=redisIdWorker.nextID("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin=System.currentTimeMillis();
        for (int i=0;i<300;i++){
            es.submit(test);
        }
        latch.await();
        long end=System.currentTimeMillis();
        System.out.println((end-begin));
    }

    @Test
    void testSaveShop() throws InterruptedException {//数据预热
//        System.out.println(2);
        shopService.saveShop2Redis(1L,10L);
    }

    @Test
    void loadShopData(){
        List<Shop> list = shopService.list();

        //按照typeId分组，放到redis中
        Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));

        for(Map.Entry<Long,List<Shop>> entry:map.entrySet()){
            //获取类型id
            Long typeId=entry.getKey();
            String key="shop:geo:"+typeId;
            List<Shop> value=entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations=new ArrayList<>(value.size());
            for(Shop shop:value){
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }

            stringRedisTemplate.opsForGeo().add(key,locations);
        }

    }
}
