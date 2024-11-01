package com.hmdp.service.impl;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        String key=CACHE_SHOP_TYPE_KEY;
        String type = stringRedisTemplate.opsForValue().get(key);
        if(!StringUtils.isEmpty(type)){
            List<ShopType> typelist = JSONUtil.toList(type, ShopType.class);
            return Result.ok(typelist);
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();
        if(typeList==null){
            return Result.fail("未查询到种类列表");
        }
        String typeJson= JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set(key,typeJson);
        return Result.ok(typeList);
    }
}
