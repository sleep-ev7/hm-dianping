package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合返回错误信息
            return Result.fail("手机格式错误!");
        }

        //3.符合生成验证码
        String code = RandomUtil.randomNumbers(6);
//        //4.保存验证码到session
//        session.setAttribute("code",code);
        //4.保存验证码到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, 2, TimeUnit.MINUTES);

        log.debug("发送验证码成功：" + code);
        //5.返回成功信息
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        String phone = loginForm.getPhone();

        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {

            return Result.fail("手机号错误!");

        }

        //2.校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);

        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //3.不一致报错
            return Result.fail("验证码错误!");
        }
        //4.根据手机号查询用户
        User user = query().eq("phone", phone).one();

        //5.查询用户是否存在
        if (user == null) {
            //6.不存在创建新的用户
            user = createUserWithPhone(phone);
        }

        //7.保存用户信息到redis

        //随机生成token
        String token = UUID.randomUUID().toString(true);
        // TODO 将User对象转为HashMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

        // 存储
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);

        //设置token有效时间
        stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);

        //返回token
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);

        return Result.ok();
    }

    @Override
    public Result signCount() {

        //1.获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截至今天为止的所有签到数据(是一个十进制数据)
        //BITFIELD KEY GET u14 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)

        );
        if (result.isEmpty() || result == null) {
            return Result.ok(0);
        }

        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }


        int count = 0;
        //6.循环遍历
        while (true) {
            //6.1让这个数据与数字1与运算，得到数字的最后一位 //判断这个bit位是否为0
            if ((num & 1) == 0) {
                //如果为0 说明未签到，结束
                break;
            } else {
                //如果不为0，计数器+1
                count++;
            }
            //向右移1位 继续下一个bit位
            num >>>= 1;

        }

        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {

        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));

        save(user);
        return user;
    }

}
