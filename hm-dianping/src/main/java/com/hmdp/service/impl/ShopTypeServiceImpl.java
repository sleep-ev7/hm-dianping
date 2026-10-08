package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        // 1. 从redis中查询商铺类型列表
        String typeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        //2.是否存在
        if(StrUtil.isNotBlank(typeJson)) {
            List<ShopType> typeList = JSONUtil.toList(typeJson, ShopType.class);
            return Result.ok(typeList);
        }

        //不存在返回错误
        List<ShopType> typeList = query().orderByAsc("sort").list();

        // 数据库查询结果为空时返回错误（MyBatis-Plus 的 list() 只会返回空集合，不会返回 null）
        if (CollUtil.isEmpty(typeList)) {
            return Result.fail("类型不存在！");
        }

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList));

        return Result.ok(typeList);
    }
}
