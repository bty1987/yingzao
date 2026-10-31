package com.yingzao.core.file;

/**
 * 文件存储后端 SPI(F1 文件存储抽象,docs/15 R10):内核只认本接口,
 * local 磁盘首发,OSS/MinIO provider 随部署形态扩展(yingzao.file.storage 一行切换)。
 * storageKey 为 provider 相对键(由 {@link FileService} 生成,形如 yyyyMM/时间戳-uuid.ext),
 * 实现侧必须防御式解析防路径穿越。
 */
public interface FileStorageProvider {

    /** 后端标识(=yingzao.file.storage 配置值,如 local/oss/minio)。 */
    String key();

    /** 写入字节(同 key 覆盖)。失败抛运行时异常,由调用方包装为业务码。 */
    void store(String storageKey, byte[] bytes);

    /** 读取字节;文件缺失抛运行时异常。 */
    byte[] read(String storageKey);

    /** 删除物理文件;不存在时静默。 */
    void delete(String storageKey);
}
