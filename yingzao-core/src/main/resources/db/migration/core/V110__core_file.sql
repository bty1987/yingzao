-- F1 文件存储抽象(docs/15 R10):内核文件元数据表。
-- 物理文件由 FileStorageProvider 落盘(local 首发,storage_key 相对键),本表只持元数据与业务挂接;
-- status 软删留痕(0 正常 1 已删),业务侧(施工日记/工地相册/审批附件)存 yz_file.id 即完成挂接。
-- MySQL 5.6 向下兼容:索引列避开长 varchar(storage_key 不建索引)。
CREATE TABLE yz_file (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  org_id BIGINT NOT NULL,
  file_name VARCHAR(255) NOT NULL COMMENT '原始文件名(去路径)',
  content_type VARCHAR(127) NOT NULL DEFAULT 'application/octet-stream',
  size_bytes BIGINT NOT NULL,
  sha256 CHAR(64) NULL,
  storage_key VARCHAR(300) NOT NULL COMMENT 'provider 相对键(yyyyMM/时间戳-uuid.ext)',
  biz_type VARCHAR(64) NULL COMMENT '业务挂接类型(插件自定,如 construction/diary)',
  biz_id BIGINT NULL,
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0 正常 1 已删',
  uploaded_by BIGINT NULL COMMENT '上传人 yz_user.id(C 端不开放上传)',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_yzfile_org (org_id, status),
  KEY idx_yzfile_biz (biz_type, biz_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '文件元数据(F1 内核 file 模块)';
