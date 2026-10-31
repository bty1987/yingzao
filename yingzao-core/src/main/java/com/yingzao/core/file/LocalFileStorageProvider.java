package com.yingzao.core.file;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地磁盘存储(缺省后端):{base-dir}/{storageKey} 落盘。
 * storageKey 虽由内核生成,读取仍按穿越防御(normalize 后必须仍在 base-dir 内)。
 */
@Component
@ConditionalOnProperty(name = "yingzao.file.storage", havingValue = "local", matchIfMissing = true)
public class LocalFileStorageProvider implements FileStorageProvider {

    public static final String KEY = "local";

    private final Path baseDir;

    public LocalFileStorageProvider(@Value("${yingzao.file.local.base-dir:./data/files}") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.baseDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create file base dir: " + baseDir, e);
        }
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public void store(String storageKey, byte[] bytes) {
        try {
            Path target = resolve(storageKey);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new IllegalStateException("file store failed: " + storageKey, e);
        }
    }

    @Override
    public byte[] read(String storageKey) {
        try {
            return Files.readAllBytes(resolve(storageKey));
        } catch (IOException e) {
            throw new IllegalStateException("file read failed: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new IllegalStateException("file delete failed: " + storageKey, e);
        }
    }

    private Path resolve(String storageKey) {
        Path p = baseDir.resolve(storageKey).normalize();
        if (!p.startsWith(baseDir)) {
            throw new IllegalArgumentException("illegal storage key: " + storageKey);
        }
        return p;
    }
}
