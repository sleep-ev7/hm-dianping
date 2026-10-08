package com.hmdp;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 秒杀下单并发压测：100 张券，让 300 个"用户"同时抢。
 *
 * <p>跑法（IDEA 里直接点方法左边的绿色三角即可）：
 * <pre>
 *   testSeckillVoucherConcurrency()   —— 正式压测，跑完打印《秒杀压测报告》
 * </pre>
 *
 * <p>注意：本测试方法【不要】加 @Transactional！
 * 一旦加了，压测的 300 次调用会全部加入同一个事务，
 * 并发就变成了串行，测试也就失去意义了。
 *
 * <p>实测结论（100 库存 / 300 并发）：
 * <pre>
 *   ┌ 当前写法 .eq("stock", voucher.getStock())  ──  乐观锁 CAS 版
 *   │    成功 30 张，失败 270 张；库存 100 → 70
 *   │    「不超卖，但卖不完」—— 每扣一次库存，其它线程手里读到的 stock 全部作废
 *   │
 *   └ 改成     .gt("stock", 0)                   ──  推荐写法
 *        成功 100 张，失败 200 张；库存 100 → 0，订单 100 条
 *        既不会超卖，也能把券全部卖光
 * </pre>
 */
@SpringBootTest
class VoucherOrderSeckillTest {

    /** 压测用的秒杀券 id（数据库里 10/11 已过期，12 生效期到 2027 年） */
    private static final Long VOUCHER_ID = 12L;

    /** 放券总量：100 张 */
    private static final int STOCK = 100;

    /** 并发线程数：模拟 300 个人同时点"立即抢购" */
    private static final int THREADS = 300;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    // ==================== 1. 每次测试前把数据准备成"100 张券 + 零订单" ====================

    @BeforeEach
    void prepareData() {
        // ① 清掉历史订单，否则结果会被上一轮的数据污染
        voucherOrderService.remove(new QueryWrapper<VoucherOrder>().eq("voucher_id", VOUCHER_ID));

        // ② 重置库存 = 100，并把秒杀时间窗调成"正在进行中"
        //    （时间窗给得很宽，避免因为时区换算导致误判成"未开始/已结束"）
        SeckillVoucher voucher = new SeckillVoucher()
                .setVoucherId(VOUCHER_ID)
                .setStock(STOCK)
                .setBeginTime(LocalDateTime.now().minusHours(1))
                .setEndTime(LocalDateTime.now().plusDays(1));
        seckillVoucherService.saveOrUpdate(voucher);

        // ③ 打印一下准备结果，确认抢购窗口是打开的
        SeckillVoucher db = seckillVoucherService.getById(VOUCHER_ID);
        System.out.println("【准备数据】券 " + VOUCHER_ID
                + " 库存=" + db.getStock()
                + " 生效=" + db.getBeginTime()
                + " 失效=" + db.getEndTime()
                + " 当前时间=" + LocalDateTime.now());
    }

    // ==================== 2. 并发压测 ====================

    @Test
    @DisplayName("100 张券 / 300 并发抢购 —— 观察是否超卖、能否抢完")
    void testSeckillVoucherConcurrency() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        // 三重闩锁：让 300 个线程真正"同时"开抢，而不是排队一个一个来
        CountDownLatch ready = new CountDownLatch(THREADS);  // 线程就绪
        CountDownLatch start = new CountDownLatch(1);        // 发令枪
        CountDownLatch done = new CountDownLatch(THREADS);   // 全部跑完

        AtomicInteger success = new AtomicInteger();
        // 失败原因 -> 次数
        Map<String, AtomicInteger> failReasons = new ConcurrentHashMap<>();

        long begin = System.currentTimeMillis();
        for (int i = 0; i < THREADS; i++) {
            final long userId = 1000L + i;   // 每个线程当做一个不同的用户
            pool.submit(() -> {
                try {
                    // 模拟登录拦截器做的事：把当前用户塞进 ThreadLocal
                    UserDTO user = new UserDTO();
                    user.setId(userId);
                    user.setNickName("user-" + userId);
                    UserHolder.saveUser(user);

                    ready.countDown();
                    start.await();                       // 等发令枪

                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (Boolean.TRUE.equals(result.getSuccess())) {
                        success.incrementAndGet();
                    } else {
                        String reason = result.getErrorMsg() == null ? "未知原因" : result.getErrorMsg();
                        failReasons.computeIfAbsent(reason, k -> new AtomicInteger()).incrementAndGet();
                    }
                } catch (Throwable t) {
                    failReasons.computeIfAbsent("抛异常: " + t.getClass().getSimpleName(), k -> new AtomicInteger())
                            .incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();                                   // 发令，开抢！
        boolean finished = done.await(60, TimeUnit.SECONDS);  // 等所有请求结束
        long cost = System.currentTimeMillis() - begin;
        pool.shutdown();

        // ==================== 3. 结果核对（以数据库为准，不信内存计数） ====================

        SeckillVoucher after = seckillVoucherService.getById(VOUCHER_ID);
        int remainStock = after.getStock();
        long orderCount = voucherOrderService.count(new QueryWrapper<VoucherOrder>().eq("voucher_id", VOUCHER_ID));
        int sold = STOCK - remainStock;

        StringBuilder sb = new StringBuilder();
        sb.append("\n==================== 秒杀压测报告 ====================\n");
        sb.append(String.format("%-16s: %d%n", "秒杀券 id", VOUCHER_ID));
        sb.append(String.format("%-16s: %d 张%n", "放券总量", STOCK));
        sb.append(String.format("%-16s: %d 个%n", "并发线程数", THREADS));
        sb.append(String.format("%-16s: %s%n", "全部跑完", finished ? "是" : "否（超时！）"));
        sb.append(String.format("%-16s: %d ms%n", "总耗时", cost));
        sb.append("-----------------------------------------------------\n");
        sb.append(String.format("%-16s: %d%n", "抢购成功", success.get()));
        sb.append(String.format("%-16s: %d%n", "抢购失败", THREADS - success.get()));
        if (!failReasons.isEmpty()) {
            sb.append("  失败原因分布：\n");
            List<Map.Entry<String, AtomicInteger>> list = new ArrayList<>(failReasons.entrySet());
            list.sort(Comparator.comparingInt((Map.Entry<String, AtomicInteger> e) -> e.getValue().get()).reversed());
            for (Map.Entry<String, AtomicInteger> e : list) {
                sb.append(String.format("    - %-14s: %d%n", e.getKey(), e.getValue().get()));
            }
        }
        sb.append("-----------------------------------------------------\n");
        sb.append(String.format("%-16s: %d → %d%n", "数据库库存", STOCK, remainStock));
        sb.append(String.format("%-16s: %d 条（实际卖出 %d 张）%n", "数据库订单数", orderCount, sold));
        sb.append(String.format("%-16s: %s%n", "是否超卖", orderCount > STOCK ? "★ 超卖了！订单 " + orderCount + " > 库存 " + STOCK
                : "否（订单数 " + orderCount + " ≤ 库存 " + STOCK + "）"));
        sb.append(String.format("%-16s: %s%n", "100 张是否抢完", remainStock == 0
                ? "是，券已抢光"
                : "否！还剩 " + remainStock + " 张没卖出去（有请求却卖不掉）"));
        sb.append("=====================================================\n");
        System.out.println(sb);

        // 唯一必须成立的硬性约束：不能超卖
        assertNotNull(after, "秒杀券不存在，请检查 tb_seckill_voucher 是否有 voucher_id=" + VOUCHER_ID);
        assertTrue(orderCount <= STOCK, "出现超卖！订单数 " + orderCount + " 大于库存 " + STOCK);
        assertTrue(remainStock >= 0, "库存被扣成负数了：" + remainStock);
    }
}
