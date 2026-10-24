-- V802:批量收款 N:1(docs/11 A3,第 83 次)——
-- 一笔到账收 N 张款项单:每笔一张子收据(1:1 与回执/confirm 链不变),同批共享 batch_no;
-- 渠道行按序贪心拆到各子收据(单张自洽 Σ渠道==该笔应收,整批渠道构成可聚合还原)。
-- 老系统 FK-in-payment(srid/mrid 指向一张收据)不抄:营造收据自带方向/金额/双人,批标识用组号;
-- null=单笔收款收据(现行为零变化)。老「先写后验」反模式不迁:批收整体先验后写,任一笔非法整批拒。
alter table payment_receipt add column batch_no varchar(24) null
    comment '批收组号(collectBatch 同批共享;null=单笔收款收据)'
    after bill_id,
  add key idx_receipt_batch (batch_no);
