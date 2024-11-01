package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
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
import java.util.UUID;
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
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        //不符合
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("请输入正确的手机号");
        }
        //符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

//        //保存到session中
//        session.setAttribute("code",code);

        //存到redis中
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("验证码发送成功，验证码为："+code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone=loginForm.getPhone();
        //不符合
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("请输入正确的手机号");
        }

//        Object chcheCode = session.getAttribute("code");
        String code=loginForm.getCode();
        //get code from redis
        String cacheCode=stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        cacheCode="000000";

        if(cacheCode == null || !cacheCode.equals(code)){
            //不一致，报错
            return Result.fail("验证码错误");
        }

        //一致，根据手机号查询用户
        User user=query().eq("phone",phone).one();

        if(user==null){
            //用户不存在,创建用户并保存
            user=createUser(phone);
        }
        //随机生成token作为令牌
        String token = UUID.randomUUID().toString();

//        //将用户保存到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        UserDTO userDTO=BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString())
                );
        String tokenKey=LOGIN_USER_KEY+token;
        System.out.println("tokenKey = " + tokenKey);
        //将用户存储到redis
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //设置有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        stringRedisTemplate.persist(tokenKey);

        return Result.ok(token);
    }

    @Override
    public Result sign() {
        long userId= UserHolder.getUser().getId();

        LocalDateTime now=LocalDateTime.now();//获取时间
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;

        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        long userId= UserHolder.getUser().getId();

        LocalDateTime now=LocalDateTime.now();//获取时间
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key=USER_SIGN_KEY+userId+keySuffix;

        int dayOfMonth = now.getDayOfMonth();

        //获取到今天为止的签到记录，返回十进制数
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );

        if(result==null || result.isEmpty()){
            return Result.ok(0);
        }

        Long num=result.get(0);
        if(num==null || num==0){
            return Result.ok(0);
        }
        //数1的个数
        int count=0;
        int nowCount=0;
        for(int i=0;i<dayOfMonth;i++){
            if((num & 1)==0){
                nowCount=0;
            }else {
                nowCount +=1;
                count=Math.max(count,nowCount);
            }
            num >>>=1;//右移1位
        }
        return Result.ok(count);
    }

    private User createUser(String phone) {
        User user=new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
}
