package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 商铺服务实现类
 * </p>
 *
 * <p>
 * 缓存相关的通用能力（缓存空对象、逻辑过期 + 异步重建）都已下沉到 {@link CacheClient}，
 * 所以本类现在只保留商铺自己的业务：按 id 查询、更新。
 * </p>、
 *
 * <p>
 * {@link #queryWithMutex(Long)} 是早期写在本地、未抽成通用方法的互斥锁实现，
 * 目前不被任何人调用，仅作学习参考保留；它依赖下面的 {@link #tryLock} / {@link #unLock}。
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    /** 操作 Redis 的模板；用 String 版是因为缓存里存的是 JSON 字符串，便于人肉排查 */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 根据 id 查询商铺详情（对外统一入口）。
     *
     * <p>当前走 {@link CacheClient#queryWithLogicalExpire} —— <b>逻辑过期</b>方案，防热点 key 击穿，
     * 缓存过期时返回旧值并异步重建，不阻塞请求。</p>
     *
     * <p>
     * 【前提】该方案要求缓存已预热（见测试类 {@code HmDianPingApplicationTests} 里的
     * {@code cacheClient.setWithLogicalExpire(...)}）。缓存未命中时它不查数据库，
     * 直接返回 null，所以没预热过的商铺会被报成"店铺不存在"，哪怕库里真的有。
     * </p>
     *
     * <p>
     * 【切换方案】改调 {@link CacheClient#queryWithPassThrough} 就是缓存空对象方案（防穿透）。
     * 但两者写入的缓存值结构不同（裸 JSON vs RedisData 包装），切换前必须先删掉
     * {@code cache:shop:*} 旧键，否则解出的 expireTime 为 null 会直接 NPE。
     * </p>
     *
     * @param id 商铺 id
     * @return 成功返回 Result.ok(Shop)；商铺不存在返回 Result.fail("店铺不存在")
     */
    @Override
    public Result queryById(Long id) {
        // 逻辑过期方案（缓存需先预热）

//        Shop shop =
//                cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 若改用缓存空对象方案，把这行换成：
        Shop shop =
         cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 【仅作参考，当前无人调用】互斥锁，解决缓存击穿。
     *
     * <p>
     * 缓存击穿 = 某个<b>热点 key</b> 突然过期，同一瞬间一大批请求同时未命中缓存，
     * 全部涌向数据库。这里的应对办法是：只让"抢到锁"的那一个线程去查库重建缓存，
     * 其余线程短暂休眠后重试，从而把数据库压力收敛成一次查询。
     * </p>
     *
     * <p>执行流程：查缓存 → 命中返回值 / 命中空值返回 null → 未命中则抢互斥锁 →
     * 抢到锁的线程查库、回填缓存 → 没抢到的线程 sleep 50ms 后递归重试 →
     * 最后无论成功失败都释放锁。</p>
     *
     * <p>
     * 【本方法存在两处缺陷，若要启用需要先修】<br>
     * ① 拿不到锁时的递归调用 {@code queryWithMutex(id);} <b>没有 return</b>，
     * 递归拿到的结果被丢弃，代码会继续往下走，最终返回的 {@code shop} 仍是 null，
     * 表现为"并发下偶尔报店铺不存在"。正确写法是 {@code return queryWithMutex(id);}<br>
     * ② {@code finally} 中无条件 {@code unLock(lockKey)}，<b>没抢到锁的线程也会删锁</b>，
     * 会把别人正在持有的锁删掉，互斥直接失效。正确做法是把释放锁挪进 {@code if (isLock)} 分支里。
     * </p>
     *
     * @param id 商铺 id
     * @return 店铺存在返回 Shop；不存在返回 null
     */
    private Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis查询商店缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            //2.1 缓存命中，反序列化后直接返回，注意这里完全不会碰数据库
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中是否为空值
        if(shopJson != null){
            //返回一个错误信息
            //3.命中的是缓存的空值标记，确认不存在，直接返回 null
            return null;
        }

        //4.实现缓存重建
        //4.1获取互斥锁
        //4.1 缓存未命中，准备抢锁，只让一个线程去查库
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);

            //4.2判断是否成功
            if(!isLock){
                //4.3失败，则休眠重试
                //4.3 没抢到锁：说明已有线程在重建，等一小会儿再重试（此处缺少 return，见方法说明）
                Thread.sleep(50);
                queryWithMutex(id);
            }

            //4.4成功根据id查询数据库
            //4.4 抢到锁（或上面重试失败）后查数据库
            shop = getById(id);
            // 模拟重建延时
            //（教学用：放大重建耗时，方便观察并发时的现象，真实项目里应删掉）
            Thread.sleep(200);
            //5.不存在返回数据
            if(shop == null){
                //将空值写入数据库
                //5.1 数据库也没有：缓存空值防穿透，同样返回 null
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在，写入redis
            //5.2 查到数据：回填缓存并设置正常 TTL
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);


        } catch (InterruptedException e) {
            //捕获中断异常转成运行时异常，避免把受检异常一路往上抛
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            //（此处无论是否抢到锁都会执行，见方法说明中的缺陷 ②）
            unLock(lockKey);
        }

        //8.返回
        return shop;
    }

    /**
     * 尝试获取互斥锁（基于 Redis 的 SETNX）。
     *
     * <p>
     * {@code setIfAbsent(key, "1", 10, TimeUnit.SECONDS)} 对应 Redis 命令
     * {@code SET key 1 NX EX 10}：只有 key 不存在时才写入成功，
     * 返回 true 表示抢到锁，false 表示锁已被别人持有。
     * </p>
     *
     * <p>
     * 过期时间（10 秒）是必须的：如果持锁线程在释放前宕机，这个键会一直存在，
     * 所有人永远抢不到锁，缓存再也无法重建（死锁）。加了 TTL 后最坏情况也只是等 10 秒。
     * </p>
     *
     * @param key 锁的键名，本项目格式为 {@code lock:shop:{id}}
     * @return true 表示抢锁成功
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //setIfAbsent 可能返回 null（连接异常等），用 BooleanUtil 做空安全判断
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁。
     *
     * <p>
     * 直接删 key 即可。但要留意：这个实现<b>不校验锁的持有者</b>，
     * 如果业务执行超过了锁的 10 秒 TTL，锁已经自动过期并被别的线程重新抢到，
     * 此时删除就会误删别人的锁。生产环境下应该把"判断锁是自己的"和"删除"写成
     * 一段 Lua 脚本交给 Redis 原子执行（锁的 value 存线程标识而不是固定的 "1"）。
     * </p>
     *
     * @param key 要释放的锁键名
     */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 更新商铺：先改数据库，再删缓存（Cache Aside 模式）。
     *
     * <p>
     * 顺序很关键 —— <b>必须操作数据库在前、删缓存在后</b>。删缓存而不是更新缓存，是因为：
     * 更新缓存需要把数据重新组装一遍，可能存在并发覆盖；而删除只是让缓存失效，
     * 下次读请求自然会从数据库重新加载最新值，更简单也更不容易出错。
     * </p>
     *
     * @param shop 要更新的商铺对象，其 id 不能为空
     * @return 固定返回成功，不返回具体数据
     */
    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();

        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        //删除而不是更新：让下一次查询去重建，避免并发写导致缓存里留下旧值
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
    }
}
