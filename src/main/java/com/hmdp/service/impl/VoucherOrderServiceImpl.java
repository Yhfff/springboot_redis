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
import lombok.Synchronized;
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import javax.print.DocFlavor;
import java.time.LocalDateTime;

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
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result seckillVoucher(Long voucherId) {
        //1. 根据优惠券id查信息
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        //2. 判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始!");
        }

        //3. 是否结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已经结束！");
        }

        //4. 库存是否充足
        if (voucher.getStock()<1) {
            return Result.fail("库存不足！");
        }

        Long userId = UserHolder.getUser().getId();
        //intern()方法 锁定范围
       // synchronized (userId.toString().intern()){
            //获取代理对象(事务)
            //aspectjweaver

        //创建锁对象
        SimpleRedisLock lock = new SimpleRedisLock("order" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(1200);
        if(!isLock){
            //获取失败
            return Result.fail("不允许重复下单!");
        }

        try{
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createOrder(voucherId);
        }finally {
            lock.unlock();
        }


       //}
    }

    @Transactional
    public Result createOrder(Long voucherId) {
        //5. 一人一单
        Long userId = UserHolder.getUser().getId();

        //5.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //5.2 判断是否存在
        if(count>0){
            //用户已经购买过了
            return Result.fail("用户已经购买过一次!");
        }

        //CAS乐观锁解决超卖
        //6. 扣减库存  mybatis-plus  针对seckill-voucher表
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0) //where条件  gt判断stock是否>0
                .update();
        if(!success){
            return Result.fail("库存不足!");
        }


        //7. 创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //7.1 订单ID
        long orderID = redisIdWorker.nextId("order");
        voucherOrder.setId(orderID);

        //7.2 用户ID

        voucherOrder.setUserId(userId);

        //7.3 代金券ID
        voucherOrder.setVoucherId(voucherId);

        //order表
        save(voucherOrder);

        return Result.ok(orderID);


    }
}
