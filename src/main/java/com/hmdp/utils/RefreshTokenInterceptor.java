package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import org.apache.ibatis.plugin.Interceptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.Time;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class RefreshTokenInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate=stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token=request.getHeader("authorization");
        if(!StringUtils.isEmpty(token)){
            token=RedisConstants.LOGIN_USER_KEY+token;
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(token);
            if(!userMap.isEmpty()){
                UserDTO userDTO= BeanUtil.fillBeanWithMap(userMap,new UserDTO(),false);
                UserHolder.saveUser(userDTO);
                stringRedisTemplate.expire(token,RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
                stringRedisTemplate.persist(token);
            }

        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
