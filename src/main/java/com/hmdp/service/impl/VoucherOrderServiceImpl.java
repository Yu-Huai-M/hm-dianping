package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker idWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT=new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR= Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.execute(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while(true){
                try {
                    //获取队列中的订单
                    VoucherOrder voucherOrder=orderTasks.take();
                    // 处理订单
                    handlerVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }
    }

    private void handlerVoucherOrder(VoucherOrder voucherOrder){

        Long userID = voucherOrder.getUserId();
        RLock lock = redissonClient.getLock("lock:order:" + userID);
        boolean tryLock = lock.tryLock();
        if(!tryLock){
            log.error("处理订单异常");
        }
        try{
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    private IVoucherOrderService proxy;
    @Override
    public Result seckiVoucher(Long voucherId) {
        Long userID=UserHolder.getUser().getId();
        Long result=stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),userID.toString()
        );

        if(result.intValue()!=0){
            return result.intValue()==1?Result.fail("库存不足"):Result.fail("您已经下过单了");
        }

        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderID = idWorker.nextID("order");

        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setId(orderID);
        voucherOrder.setUserId(userID);

        //放入阻塞队列
        orderTasks.add(voucherOrder);

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();//获取当前对象的代理对象,预防事务失效
        Long orderId=idWorker.nextID("order");
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckiVoucher(Long voucherId) {
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("活动还未开始");
//        }
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("活动已结束");
//        }
//        if(voucher.getStock()<=0){
//            return Result.fail("库存不足");
//        }
//        Long userID = UserHolder.getUser().getId();
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userID, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userID);
////        synchronized (userID.toString().intern()){//加锁，但是这样在集群的情况下会有问题
//        boolean tryLock = lock.tryLock();
//        if(!tryLock){
//            return Result.fail("请勿重复下单");
//        }
//        try{
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();//获取当前对象的代理对象,预防事务失效
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
////        }
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long userID = voucherOrder.getUserId();
        Integer count = query().eq("user_id", userID).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if(count>0){ //一人一单
            log.error("您已经购买过了");
        }

        boolean success = seckillVoucherService.update().setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock",0) //乐观锁，大于0才会减少
                .update();
        if(!success){
            log.error("库存不足");
        }

        save(voucherOrder);

    }

//    @Transactional
//    public Result createVoucherOrder(Long voucherId){
//        Long userID = UserHolder.getUser().getId();
//        Integer count = query().eq("user_id", userID).eq("voucher_id", voucherId).count();
//        if(count>0){ //一人一单
//            return Result.fail("您已经购买过了");
//        }
//
//        boolean success = seckillVoucherService.update().setSql("stock = stock-1")
//                .eq("voucher_id", voucherId)
//                .gt("stock",0) //乐观锁，大于0才会减少
//                .update();
//        if(!success){
//            return Result.fail("库存不足");
//        }
//
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderID = idWorker.nextID("order");
//
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setId(orderID);
//        voucherOrder.setUserId(userID);
//        save(voucherOrder);
//
//        return Result.ok("秒杀成功，订单号："+orderID);
//    }
}
