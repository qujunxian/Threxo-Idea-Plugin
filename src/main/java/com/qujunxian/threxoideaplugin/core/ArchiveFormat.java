package com.qujunxian.threxoideaplugin.core;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 可解压的归档格式。复合后缀（如 .tar.gz）必须排在单后缀（.gz）之前。
 */
public enum ArchiveFormat {

    TAR_GZ("tar.gz", "tgz"),
    TAR_BZ2("tar.bz2", "tbz2", "tbz"),
    TAR_XZ("tar.xz", "txz"),
    TAR("tar"),
    SEVEN_Z("7z"),
    ZIP("zip", "jar", "war", "ear"),
    GZIP("gz"),
    BZIP2("bz2"),
    XZ("xz");

    private final String[] extensions;

    ArchiveFormat(String... extensions) {
        this.extensions = extensions;
    }

    /**
     * 按文件名后缀识别格式，识别不了返回 {@code null}。
     */
    public static @Nullable ArchiveFormat fromFileName(@NotNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (ArchiveFormat format : values()) {
            for (String extension : format.extensions) {
                String suffix = "." + extension;
                if (lower.endsWith(suffix) && lower.length() > suffix.length()) {
                    return format;
                }
            }
        }
        return null;
    }

    /**
     * 去掉压缩包自身后缀后的名字：{@code a.tar.gz} → {@code a}，{@code b.zip} → {@code b}。
     * 不是归档时只去掉最后一个普通后缀。
     */
    public static @NotNull String archiveStem(@NotNull String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        for (ArchiveFormat format : values()) {
            for (String extension : format.extensions) {
                String suffix = "." + extension;
                if (lower.endsWith(suffix) && fileName.length() > suffix.length()) {
                    return fileName.substring(0, fileName.length() - suffix.length());
                }
            }
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /**
     * GZ/BZ2/XZ 只包一个文件，解压时直接产出文件而不是目录树。
     */
    public boolean isSingleFile() {
        return this == GZIP || this == BZIP2 || this == XZ;
    }

    public @NotNull String primaryExtension() {
        return extensions[0];
    }
}
