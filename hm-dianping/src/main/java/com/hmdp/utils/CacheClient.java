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

/**
 * 缓存工具类。
 *
 * <p>把两套通用的缓存套路抽成公共方法，用函数式参数 {@code dbFallback} 把"查库逻辑"交给调用方，
 * 这样商铺、优惠券等任何实体都能复用，不必每种实体各写一份。</p>
 *
 * <p>注意：{@link #set} 存的是裸 JSON，{@link #setWithLogicalExpire} 存的是 RedisData 包装，
 * 两者结构不同却常共用同一个键，切换方案前必须先删旧键，否则解出的 expireTime 为 null 会直接 NPE。</p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Component
public class CacheClient {

    /** 缓存重建专用线程池：逻辑过期方案靠它异步重建，避免占用 Tomcat 请求线程 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 普通写入：值序列化成 JSON 存进去，键带真实 TTL，到期由 Redis 自动删除 */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /** 逻辑过期写入：值包一层 {@link RedisData}，键<b>不设 TTL</b>，过期与否由值里的 expireTime 字段决定 */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //把过期时间塞进值里，而不是交给 Redis 的 TTL
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入redis（不设 TTL，键常驻）
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 缓存空对象方案，防<b>缓存穿透</b>（请求的 id 数据库里压根不存在）。
     * 数据库查不到时也往缓存写一个空串标记，让后续同类请求在缓存层被挡掉。
     *
     * @param dbFallback 缓存未命中时用于查库的回调，由调用方以方法引用传入，如 {@code this::getById}
     * @return 命中正常值返回对象；命中空值标记或查库无结果返回 null
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix,ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.查缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.命中真实数据，反序列化后直接返回
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }

        //3.走到这里说明 isNotBlank 为 false：要么是 null（未命中），要么是缓存的 "" 空值标记
        //  两步判断的顺序不能颠倒，否则会把空串当成数据去反序列化
        if(json != null){
            //命中空值标记 → 该 id 确认不存在，直接返回 null，不再查库
            return null;
        }

        //4.未命中，查数据库
        R r = dbFallback.apply(id);
        if(r == null){
            //4.1 库里也没有：缓存空值作为"不存在"的标记，给一个较短的 TTL
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //5.库里查到，回填缓存
        this.set(key, r, time, unit);

        return r;
    }


    /**
     * 逻辑过期方案，防<b>热点 key 击穿</b>，且重建过程不阻塞任何请求。
     *
     * <p>思路：键不设真实 TTL 所以永远命中；发现数据过期时先把<b>旧值</b>返回给用户，
     * 再抢锁交给后台线程异步重建，下一批请求就能拿到新数据。</p>
     *
     * <p>前提：缓存必须先用 {@link #setWithLogicalExpire} 预热。未命中时本方法不查库，
     * 直接返回 null，所以没预热过的数据会被当成"不存在"。</p>
     *
     * @param dbFallback 缓存重建时用于查库的回调，如 {@code this::getById}
     * @return 缓存里有时返回数据（可能已逻辑过期，但内容仍是旧值）；缓存没有时返回 null
     */
    public  <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.查缓存
        String json = stringRedisTemplate.opsForValue().get(key);

        //2.未命中：本方案不查库，直接返回 null（没预热过的数据就会走到这里）
        if(StrUtil.isBlank(json)){
            return null;
        }

        //3.命中：先解开 RedisData 外壳，拿到数据本身 + 逻辑过期时间
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4.未过期 → 数据还新鲜，直接返回
        if(expireTime.isAfter(LocalDateTime.now())){
            return r;
        }

        //4.2已过期需要缓存重建
        //5.缓存重建
        //【已修复】原先这里写的是 `String lockKey = keyPrefix + id;`，
        //  拼出来的锁键和上面的缓存键 key 完全相同 —— 而此时缓存键必然存在，
        //  于是 setIfAbsent 必然返回 false → 永远抢不到锁 → 重建任务永远不会提交，
        //  过期数据永久停留在旧值；更糟的是若真抢到，会把缓存值覆盖成 "1"，
        //  下次再查就会 JSON 解析失败。
        //  修法：锁键用独立的 "lock:" 前缀命名，与缓存键彻底区分开。
        String lockKey = LOCK_SHOP_KEY + id;
        //5.1 抢锁：保证只有一个线程去重建，避免缓存刚过期就有一批线程同时打数据库
        boolean isLock = tryLock(lockKey);
        //5.2 抢到锁 → 提交后台线程重建，注意没有 join，主线程立刻往下走
        if(isLock){
            //5.3 成功开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                try {
                    //重建缓存：查库后按逻辑过期格式重新写入，得到一份"新鲜旧值"
                    R r1 = dbFallback.apply(id);
                    //【加固】查库可能返回 null（数据被删了）。此时若照旧写入，
                    //  RedisData.data 为 null，下次解析时 (JSONObject) null 会抛异常。
                    //  这里改为：查不到就删掉缓存键，让它下次走"未命中"分支。
                    if (r1 == null) {
                        stringRedisTemplate.delete(key);
                        log.warn("缓存重建时数据库无此数据，已删除缓存键：{}", key);
                    } else {
                        this.setWithLogicalExpire(key, r1, time, unit);
                    }
                } catch (Exception e) {
                    //异步线程里抛异常没人接，重建会静默失败；这里记日志便于排查
                    log.error("缓存重建失败，key={}", key, e);
                } finally {
                    //释放锁：成功失败都要放，否则后续请求永远抢不到锁、数据再也不会更新
                    unLock(lockKey);
                }
            });
        }
        //5.4 没抢到锁的线程什么都不做，直接用手里这份旧数据（这就是"不阻塞"的由来）
        return r;
    }

    /** 抢锁：SetNX + 10 秒 TTL，对应 Redis 的 SET key 1 NX EX 10；TTL 用于防止持锁线程宕机造成死锁 */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //setIfAbsent 可能返回 null（连接异常等），用 BooleanUtil 做空安全判断
        return BooleanUtil.isTrue(flag);
    }

    /** 放锁：直接删键。未校验持有者，业务耗时超过锁 TTL 后会误删别人的锁，生产需改用 Lua 原子校验 */
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

}
