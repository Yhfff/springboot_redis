package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import io.netty.util.internal.StringUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

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
        String key = "cache:listType";
        //从redis中查询类型缓存
        List<String> typeListString = stringRedisTemplate.opsForList().range(key, 0, -1);
        //判断是否存在
        if(ObjectUtil.isNotEmpty(typeListString)){
            return Result.ok();
        }


        //否则，通过数据库查询所有店铺类型
        List<ShopType> typeList =  query().orderByAsc("sort").list();
        //添加至redis
       //stringRedisTemplate.opsForList().set();

        return Result.ok();
    }
}
