package com.yingzao.addons.api.event;

/**
 * 商品档案领域事件(契约,发布方 addon-basedata):商品建档后发布。
 * 事件只携带 ID 与不可变快照;消费方(addon-quotation 规格/addon-sales 销售/addon-inventory 库存)
 * 据此缓存失效或预建关联,禁止反向依赖实现模块(军规 2)。
 */
public record GoodsCreatedEvent(Long goodsId, Long orgId, String no, String name,
        Long categoryId, Long brandId) {
}
