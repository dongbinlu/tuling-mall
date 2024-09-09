package com.tuling.tulingmall.service.impl;


import com.tuling.tulingmall.common.api.CommonResult;
import com.tuling.tulingmall.dao.FlashPromotionProductDao;
import com.tuling.tulingmall.domain.CartPromotionItem;
import com.tuling.tulingmall.domain.PmsProductParam;
import com.tuling.tulingmall.mapper.PmsSkuStockMapper;
import com.tuling.tulingmall.mapper.SmsFlashPromotionProductRelationMapper;
import com.tuling.tulingmall.model.PmsSkuStock;
import com.tuling.tulingmall.model.SmsFlashPromotionProductRelation;
import com.tuling.tulingmall.service.PmsProductService;
import com.tuling.tulingmall.service.StockManageService;
import com.tuling.tulingmall.util.RedisOpsUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.Date;
import java.util.List;


/**
 *
 * @author ：杨过
 * @date ：Created in 2020/2/25
 * @version: V1.0
 * @slogan: 天下风云出我辈，一入代码岁月催
 * @description:
 **/
@Service
@Slf4j
public class StockManageServiceImpl implements StockManageService {

    @Autowired
    private PmsSkuStockMapper skuStockMapper;

    @Autowired
    private SmsFlashPromotionProductRelationMapper flashPromotionProductRelationMapper;

    @Autowired
    private FlashPromotionProductDao flashPromotionProductDao;

    @Autowired
    private PmsProductService productService;

    @Autowired
    private RedisOpsUtil redisOpsUtil;

    @Override
    public Integer incrStock(Long productId, Long skuId, Integer quanlity, Integer miaosha, Long flashPromotionRelationId) {
        return null;
    }

    @Override
    public Integer descStock(Long productId, Long skuId, Integer quanlity, Integer miaosha, Long flashPromotionRelationId) {
        return null;
    }

    /**
     * 获取产品库存
     * @param productId
     * @param flashPromotionRelationId
     * @return
     */
    @Override
    public CommonResult<Integer> selectStock(Long productId, Long flashPromotionRelationId) {

        SmsFlashPromotionProductRelation miaoshaStock = flashPromotionProductRelationMapper.selectByPrimaryKey(flashPromotionRelationId);
        if(ObjectUtils.isEmpty(miaoshaStock)){
            return CommonResult.failed("不存在该秒杀商品！");
        }

        return CommonResult.success(miaoshaStock.getFlashPromotionCount());
    }

    /**
     * 锁定库存---什么意思呢
     * 也就是说下单的时候，扣减库存，扣减的是lock_stock
     *
     * 模拟： 假设stock=100，lock_stock=0
     *,第一次,假设购买的数量为1
     * update pms_sku_stock set lock_stock = lock_stock + quantity where id = 90;
     * 结果lock_stock = 1,stock=100 。
     *
     * 支付成功后(也就是支付宝回调成功后，见支付成功回调接口)，此时才修改订单支付状态和恢复所有下单商品的库存锁定，扣减真实库存
     * 根据订单ID查询到订单详情，订单详情里面有订单ID，商品ID，购买的数量quantity
     * 扣减真实库存
     * update pms_sku_stock set stock = stock - quantity where id = 90;
     * 结果stock = 99;
     * 恢复库存锁定
     * update pms_sku_stock set lock_stock = lock_stock - quantity where id = 90;
     * 结果lock_stock = 0;
     *
     * 第二次，假设购买数量为3
     * update pms_sku_stock set lock_stock = lock_stock + quantity where id = 90;
     * 结果lock_stock = 3,stock = 99;
     * 支付成功后，开始减库存
     * update pms_sku_stock set stock = stock - 3 where id = 90;
     * 结果stock = 96;
     * 恢复库存锁定
     * update pms_sku_stock set lock_stock = lock_stock - quantity where id = 90;
     * 结果lock_stock = 0;
     *
     * 第三次，假设购买的数量为2（假设还没有恢复库存锁定）
     * update pms_sku_stock set lock_stock = lock_stock + quantity where id = 90;
     * 结果lock_stock=5，stock=96
     * 支付成功后开始减库存
     * update pms_sku_stock set stock = stock - 2 where id = 90;
     * 结果stock=94
     * 恢复库存锁定
     * update pms_sku_stock set lock_stock = lock_stock - quantity where id = 90;
     * 结果lock_stock=3;(等第二次支付成功后，才恢复库存锁定)
     *
     *
     * 扣减库存和恢复库存锁定代码
     * UPDATE pms_sku_stock
     *         SET
     *             stock = CASE id
     *             <foreach collection="itemList" item="item">
     *               WHEN #{item.productSkuId} THEN stock - #{item.productQuantity}
     *             </foreach>
     *             END,
     *             lock_stock = CASE id
     *             <foreach collection="itemList" item="item">
     *               WHEN #{item.productSkuId} THEN lock_stock - #{item.productQuantity}
     *             </foreach>
     *             END
     *         WHERE
     *             id IN
     *         <foreach collection="itemList" item="item" separator="," open="(" close=")">
     *             #{item.productSkuId}
     *         </foreach>
     *
     * @param cartPromotionItemList
     * @return
     */
    @Override
    public CommonResult lockStock(List<CartPromotionItem> cartPromotionItemList) {
        try {

            for (CartPromotionItem cartPromotionItem : cartPromotionItemList) {
                PmsSkuStock skuStock = skuStockMapper.selectByPrimaryKey(cartPromotionItem.getProductSkuId());
                skuStock.setLockStock(skuStock.getLockStock() + cartPromotionItem.getQuantity());
                skuStockMapper.updateByPrimaryKeySelective(skuStock);
            }
            return CommonResult.success(true);
        }catch (Exception e) {
            log.error("锁定库存失败...");
            return CommonResult.failed();
        }
    }

    //验证秒杀时间
    private boolean volidateMiaoShaTime(PmsProductParam product) {
        //当前时间
        Date now = new Date();
        //todo 查看是否有秒杀商品,秒杀商品库存
        if(product.getFlashPromotionStatus() != 1
                || product.getFlashPromotionEndDate() == null
                || product.getFlashPromotionStartDate() == null
                || now.after(product.getFlashPromotionEndDate())
                || now.before(product.getFlashPromotionStartDate())){
            return false;
        }
        return true;
    }

}
