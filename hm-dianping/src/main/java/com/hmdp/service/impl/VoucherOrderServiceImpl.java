package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
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
import java.util.Collections;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
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

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    //11
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                // 1.获取队列中的订单信息
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }

    private IVoucherOrderService proxy;

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        //获取锁
        boolean isLock = lock.tryLock();

        if (!isLock) {
            //获取锁失败，返回错误或重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }



    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //2.判断是否为0
        int r = result.intValue();
        if (r != 0) {
            //2.1 不为0,代表没有资格
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        //2.2 为0 有资格 把下单信息保存到阻塞队列
        // TODO 保存阻塞队列
        long orderId = redisIdWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);

        //获取代理对象
        // 必须先拿到代理再入队：消费线程一取到订单就会用 proxy 去调事务方法，
        // 如果 proxy 还是 null，就会 NPE，被 catch 吞掉，订单静默丢失（页面却提示抢购成功）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //放入阻塞队列
        orderTasks.add(voucherOrder);

        //返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    @Transactional
//    public Result seckillVoucher(Long voucherId) {
//        // 1.查询优惠卷
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//
//        // 1.1 优惠券不存在时直接返回，否则下面 voucher.getXxx() 会抛 NullPointerException
//        //     （传了 tb_voucher 里没有的 id，或该券在 tb_seckill_voucher 里没有对应记录）
//        if (voucher == null) {
//            return Result.fail("优惠券不存在！");
//        }
//
//        // 2.判断秒杀卷是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("秒杀未开始！");
//        }
//        // 3.判断秒杀卷是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("秒杀已经结束！");
//        }
//        // 4.判断秒杀卷是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("秒杀卷不足！");
//        }
//
//        Long userId = UserHolder.getUser().getId();
//        //创建锁对象
//        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//
//        //获取锁
//        boolean isLock = lock.tryLock();
//
//        if (!isLock) {
//            //获取锁失败，返回错误或重试
//            return Result.fail("不允许重复下单");
//        }
//
//        try {
//            synchronized (userId.toString().intern()) {
//                //获取代理对象（事务）
//                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//                return proxy.createVoucherOrder(voucherId);
//            }
//        } finally {
//            lock.unlock();
//        }
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 6.一人一单
        Long userId = voucherOrder.getUserId();

        // 6.1查询订单
        //     注意：这里要传【券id】voucherOrder.getVoucherId()，
        //     直接传 voucherOrder 整个对象的话，MyBatis 会把对象序列化后拿去做 voucher_id = ? 比较，
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId()).count();

        if (count > 0) {
            log.error("用户已经购买一次！");
            return;
        }

        //5.扣减库存（同样是券id，不是对象）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();

        if (!success) {
            log.error("库存不足！");
            return;
        }
        save(voucherOrder);
    }
}
