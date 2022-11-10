package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

//拦截一切请求 使一切请求都刷新token
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //获取token
        String token = request.getHeader("authorization");
        //System.out.println("token=" + token);
        if (StrUtil.isBlank(token)) {
            //token为空 放行
            return true;
        }

        //基于token获取redis的用户
        String key = RedisConstants.LOGIN_USER_KEY;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key + token);
        if (userMap.isEmpty()) {
            return true;
        }

        //将查询到的hash数据转成UserDto对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //存在 保存用户信息到ThreadLocal
        UserHolder.saveUser((UserDTO) userDTO);

        //刷新token有效期
        stringRedisTemplate.expire(key+token,30, TimeUnit.MINUTES);

        //放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        //移除用户
        UserHolder.removeUser();
    }
}
