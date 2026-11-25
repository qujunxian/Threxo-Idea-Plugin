package com.qujunxian.threxoideaplugin.core;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.qujunxian.threxoideaplugin.i18n.ThrexoBundle;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 压缩与解压的核心实现，行为对齐 Mac 版 Threxo：
 * 压缩输出 ZIP、重名追加序号；解压铺到压缩包所在目录、同名文件自动改名、绝不覆盖。
 */
public final class ArchiveService {

    private static final String MAC_METADATA_DIR = "__MACOSX";
    private static final int BUFFER_SIZE = 64 * 1024;

    private ArchiveService() {
    }

    /**
     * 将选中的文件/文件夹压缩为 ZIP，输出到第一个选中项所在目录。
     *
     * @return 生成的压缩包路径
     */
    public static @NotNull Path compress(@NotNull List<Path> sources,
                                         @NotNull ProgressIndicator indicator) throws IOException {
        if (sources.isEmpty()) {
            throw new IOException(ThrexoBundle.message("error.no.sources"));
        }

        List<Path> normalized = new ArrayList<>(sources.size());
        for (Path source : sources) {
            normalized.add(source.toAbsolutePath().normalize());
        }
        Path first = normalized.get(0);
        Path destinationDir = first.getParent();
        if (destinationDir == null) {
            throw new IOException(ThrexoBundle.message("error.archive.directory"));
        }

        String baseName = defaultArchiveBaseName(normalized);
        Path destination = uniqueArchivePath(destinationDir, baseName);

        indicator.setIndeterminate(true);
        indicator.setText2(ThrexoBundle.message("task.scanning"));
        List<Entry> entries = collectEntries(normalized, indicator);
        writeZip(entries, destination, indicator);
        return destination;
    }

    /**
     * 将压缩包解压到其所在目录。
     *
     * @return 解压目标目录
     */
    public static @NotNull Path extract(@NotNull Path archive,
                                        @NotNull ProgressIndicator indicator) throws IOException {
        Path file = archive.toAbsolutePath().normalize();
        ArchiveFormat format = ArchiveFormat.fromFileName(file.getFileName().toString());
        if (format == null) {
            throw new IOException(ThrexoBundle.message("error.unsupported.format", file.getFileName()));
        }
        Path destinationDir = file.getParent();
        if (destinationDir == null) {
            throw new IOException(ThrexoBundle.message("error.extract.directory"));
        }

        if (format.isSingleFile()) {
            extractSingleFile(file, destinationDir, format, indicator);
        } else if (format == ArchiveFormat.ZIP) {
            extractZip(file, destinationDir, indicator);
        } else if (format == ArchiveFormat.SEVEN_Z) {
            extractSevenZ(file, destinationDir, indicator);
        } else {
            extractTar(file, destinationDir, format, indicator);
        }
        return destinationDir;
    }

    // ------------------------------------------------------------------ 压缩

    private record Entry(Path source, String name, boolean directory, long size, long lastModified) {
    }

    private static @NotNull List<Entry> collectEntries(@NotNull List<Path> sources,
                                                       @NotNull ProgressIndicator indicator) throws IOException {
        Path base = commonParent(sources);
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();

        for (Path source : sources) {
            if (Files.isDirectory(source)) {
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(@NotNull Path dir,
                                                             @NotNull BasicFileAttributes attrs) {
                        ProgressManager.checkCanceled();
                        if (!dir.equals(source) && isMacMetadataDir(dir.getFileName().toString())) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        addEntry(entries, names, base, dir, true, 0, attrs.lastModifiedTime().toMillis());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(@NotNull Path file,
                                                     @NotNull BasicFileAttributes attrs) {
                        ProgressManager.checkCanceled();
                        if (isAuxiliaryFile(file.getFileName().toString()) || !attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        addEntry(entries, names, base, file, false, attrs.size(),
                                attrs.lastModifiedTime().toMillis());
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else if (Files.isRegularFile(source)) {
                addEntry(entries, names, base, source, false, Files.size(source),
                        Files.getLastModifiedTime(source).toMillis());
            } else {
                throw new IOException(ThrexoBundle.message("error.cannot.compress", source));
            }
        }

        entries.sort(Comparator.comparing(Entry::name));
        indicator.setIndeterminate(false);
        indicator.setFraction(0);
        return entries;
    }

    private static void addEntry(@NotNull List<Entry> entries, @NotNull Set<String> names,
                                 @NotNull Path base, @NotNull Path path, boolean directory,
                                 long size, long lastModified) {
        String name = relativeName(base, path);
        if (name.isEmpty()) {
            return;
        }
        String entryName = directory ? name + "/" : name;
        if (!names.add(entryName)) {
            return;
        }
        entries.add(new Entry(path, entryName, directory, size, lastModified));
    }

    private static void writeZip(@NotNull List<Entry> entries, @NotNull Path destination,
                                 @NotNull ProgressIndicator indicator) throws IOException {
        long totalBytes = 0;
        for (Entry entry : entries) {
            totalBytes += entry.size();
        }

        boolean success = false;
        try (OutputStream fileOut = Files.newOutputStream(destination,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut),
                     StandardCharsets.UTF_8)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long written = 0;
            int index = 0;
            for (Entry entry : entries) {
                ProgressManager.checkCanceled();
                indicator.setText2(entry.name());
                if (totalBytes > 0) {
                    indicator.setFraction((double) written / totalBytes);
                } else {
                    indicator.setFraction((double) index / Math.max(1, entries.size()));
                }

                ZipEntry zipEntry = new ZipEntry(entry.name());
                zipEntry.setLastModifiedTime(FileTime.fromMillis(entry.lastModified()));
                zip.putNextEntry(zipEntry);
                if (!entry.directory()) {
                    try (InputStream in = Files.newInputStream(entry.source())) {
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            ProgressManager.checkCanceled();
                            zip.write(buffer, 0, read);
                            written += read;
                            if (totalBytes > 0) {
                                indicator.setFraction((double) written / totalBytes);
                            }
                        }
                    }
                }
                zip.closeEntry();
                index++;
            }
            success = true;
        } finally {
            if (!success) {
                try {
                    Files.deleteIfExists(destination);
                } catch (IOException ignored) {
                    // 清理失败不覆盖原始异常
                }
            }
        }
        indicator.setFraction(1);
    }

    // ------------------------------------------------------------------ 解压

    private static void extractZip(@NotNull Path archive, @NotNull Path destinationDir,
                                   @NotNull ProgressIndicator indicator) throws IOException {
        Charset charset = detectZipCharset(archive);
        try (ZipFile zip = ZipFile.builder().setPath(archive).setCharset(charset).get()) {
            List<ZipArchiveEntry> entries = new ArrayList<>();
            Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                entries.add(enumeration.nextElement());
            }

            indicator.setIndeterminate(false);
            Set<Path> used = new HashSet<>();
            for (int i = 0; i < entries.size(); i++) {
                ProgressManager.checkCanceled();
                ZipArchiveEntry entry = entries.get(i);
                indicator.setText2(entry.getName());
                indicator.setFraction((double) i / Math.max(1, entries.size()));

                if (entry.isUnixSymlink()) {
                    continue;
                }
                if (entry.isDirectory()) {
                    createDirectory(destinationDir, entry.getName(), entry.getTime());
                } else {
                    try (InputStream in = zip.getInputStream(entry)) {
                        extractFile(destinationDir, entry.getName(), entry.getTime(), in, used);
                    }
                }
            }
            indicator.setFraction(1);
        }
    }

    private static void extractSevenZ(@NotNull Path archive, @NotNull Path destinationDir,
                                      @NotNull ProgressIndicator indicator) throws IOException {
        try (SevenZFile sevenZ = SevenZFile.builder().setPath(archive).get()) {
            List<SevenZArchiveEntry> entries = new ArrayList<>();
            for (SevenZArchiveEntry entry : sevenZ.getEntries()) {
                entries.add(entry);
            }

            indicator.setIndeterminate(false);
            Set<Path> used = new HashSet<>();
            for (int i = 0; i < entries.size(); i++) {
                ProgressManager.checkCanceled();
                SevenZArchiveEntry entry = entries.get(i);
                indicator.setText2(entry.getName());
                indicator.setFraction((double) i / Math.max(1, entries.size()));

                long modified = entry.getHasLastModifiedDate()
                        ? entry.getLastModifiedTime().toMillis() : -1;
                if (entry.isDirectory()) {
                    createDirectory(destinationDir, entry.getName(), modified);
                } else if (entry.hasStream()) {
                    try (InputStream in = sevenZ.getInputStream(entry)) {
                        extractFile(destinationDir, entry.getName(), modified, in, used);
                    }
                }
                // 没有数据流且不是目录的条目是符号链接/反项，直接跳过
            }
            indicator.setFraction(1);
        }
    }

    private static void extractTar(@NotNull Path archive, @NotNull Path destinationDir,
                                   @NotNull ArchiveFormat format,
                                   @NotNull ProgressIndicator indicator) throws IOException {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                openTarStream(archive, format), StandardCharsets.UTF_8.name())) {
            indicator.setIndeterminate(true);
            Set<Path> used = new HashSet<>();
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                ProgressManager.checkCanceled();
                indicator.setText2(entry.getName());

                if (entry.isSymbolicLink() || entry.isLink()) {
                    continue;
                }
                long modified = entry.getModTime() == null ? -1 : entry.getModTime().getTime();
                if (entry.isDirectory()) {
                    createDirectory(destinationDir, entry.getName(), modified);
                } else {
                    extractFile(destinationDir, entry.getName(), modified, tar, used);
                }
            }
            indicator.setFraction(1);
        }
    }

    private static void extractSingleFile(@NotNull Path archive, @NotNull Path destinationDir,
                                          @NotNull ArchiveFormat format,
                                          @NotNull ProgressIndicator indicator) throws IOException {
        String archiveName = archive.getFileName().toString();
        String suffix = "." + format.primaryExtension();
        String targetName = archiveName.length() > suffix.length()
                ? archiveName.substring(0, archiveName.length() - suffix.length())
                : "";
        if (targetName.isBlank()) {
            targetName = ThrexoBundle.message("error.target.name");
        }

        Path target = uniquePath(destinationDir.resolve(targetName));
        indicator.setIndeterminate(true);
        indicator.setText2(target.getFileName().toString());

        try (InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
             InputStream in = switch (format) {
                 case GZIP -> new GzipCompressorInputStream(raw);
                 case BZIP2 -> new BZip2CompressorInputStream(raw);
                 case XZ -> new XZCompressorInputStream(raw);
                 default -> throw new IllegalStateException(
                         ThrexoBundle.message("error.not.single.file", format));
             };
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                ProgressManager.checkCanceled();
                out.write(buffer, 0, read);
            }
        }
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(archive));
    }

    private static @NotNull InputStream openTarStream(@NotNull Path archive,
                                                      @NotNull ArchiveFormat format) throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(archive));
        try {
            return switch (format) {
                case TAR -> raw;
                case TAR_GZ -> new GzipCompressorInputStream(raw);
                case TAR_BZ2 -> new BZip2CompressorInputStream(raw);
                case TAR_XZ -> new XZCompressorInputStream(raw);
                default -> throw new IllegalStateException(
                        ThrexoBundle.message("error.not.tar", format));
            };
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    /**
     * Java 的 ZipFile 对没打 UTF-8 标记的文件名按指定编码解码。这里直接检查原始文件名字节：
     * 是否能用严格 UTF-8 解码，失败就说明大概率是 Windows 下打的 GBK 包，改用 GB18030。
     */
    private static @NotNull Charset detectZipCharset(@NotNull Path archive) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (ZipFile zip = ZipFile.builder().setPath(archive)
                .setCharset(StandardCharsets.UTF_8).get()) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (entry.getGeneralPurposeBit().usesUTF8ForNames()) {
                    continue;
                }
                byte[] rawName = entry.getRawName();
                if (rawName == null) {
                    continue;
                }
                try {
                    decoder.reset();
                    decoder.decode(ByteBuffer.wrap(rawName));
                } catch (CharacterCodingException e) {
                    return Charset.forName("GB18030");
                }
            }
        } catch (IOException ignored) {
            // 打不开的包交给正式解压流程报错
        }
        return StandardCharsets.UTF_8;
    }

    private static void createDirectory(@NotNull Path destinationDir, @NotNull String rawName,
                                        long lastModified) throws IOException {
        String name = normalizeEntryName(rawName);
        if (name == null || isAuxiliaryPath(name)) {
            return;
        }
        Path target = resolveTarget(destinationDir, name);
        Files.createDirectories(target);
        if (lastModified > 0) {
            Files.setLastModifiedTime(target, FileTime.fromMillis(lastModified));
        }
    }

    /**
     * 写入单个文件条目。{@code in} 由调用方关闭（TAR 场景下就是归档流本身）。
     */
    private static void extractFile(@NotNull Path destinationDir, @NotNull String rawName,
                                    long lastModified, @NotNull InputStream in,
                                    @NotNull Set<Path> used) throws IOException {
        String name = normalizeEntryName(rawName);
        if (name == null) {
            throw new IOException(ThrexoBundle.message("error.unsafe.path", rawName));
        }
        if (isAuxiliaryPath(name)) {
            return;
        }

        Path target = resolveTarget(destinationDir, name);
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || used.contains(target)) {
            target = uniquePath(target);
        }
        used.add(target);

        try (OutputStream out = Files.newOutputStream(target,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = in.read(buffer)) != -1) {
                ProgressManager.checkCanceled();
                out.write(buffer, 0, read);
            }
        }
        if (lastModified > 0) {
            Files.setLastModifiedTime(target, FileTime.fromMillis(lastModified));
        }
    }

    // ------------------------------------------------------------------ 路径与命名工具

    private static @NotNull Path commonParent(@NotNull List<Path> paths) throws IOException {
        Path common = paths.get(0).getParent();
        for (int i = 1; i < paths.size() && common != null; i++) {
            Path path = paths.get(i);
            while (common != null && !path.startsWith(common)) {
                common = common.getParent();
            }
        }
        if (common == null) {
            throw new IOException(ThrexoBundle.message("error.relative.path"));
        }
        return common;
    }

    private static @NotNull String relativeName(@NotNull Path base, @NotNull Path path) {
        Path relative;
        try {
            relative = base.relativize(path);
        } catch (IllegalArgumentException e) {
            return path.getFileName().toString();
        }
        String name = relative.toString().replace(File.separatorChar, '/').replace('\\', '/');
        if (name.startsWith("../") || relative.isAbsolute()) {
            return path.getFileName().toString();
        }
        return name;
    }

    private static @NotNull String defaultArchiveBaseName(@NotNull List<Path> sources) {
        if (sources.size() != 1) {
            return ThrexoBundle.message("archive.default.name");
        }
        Path source = sources.get(0);
        String name = source.getFileName().toString();
        if (Files.isRegularFile(source)) {
            String stem = ArchiveFormat.archiveStem(name);
            if (!stem.isBlank()) {
                return stem;
            }
        }
        return name.isBlank() ? ThrexoBundle.message("archive.default.name") : name;
    }

    private static @NotNull Path uniqueArchivePath(@NotNull Path directory, @NotNull String baseName) {
        Path candidate = directory.resolve(baseName + ".zip");
        int counter = 2;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = directory.resolve(baseName + " " + counter + ".zip");
            counter++;
        }
        return candidate;
    }

    /**
     * 解压同名冲突时的改名规则：{@code a.txt} → {@code a_1.txt}。
     */
    private static @NotNull Path uniquePath(@NotNull Path target) {
        String fileName = target.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem;
        String extension;
        if (dot > 0) {
            stem = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        } else {
            stem = fileName;
            extension = "";
        }
        Path parent = target.getParent();
        int counter = 1;
        Path candidate = target;
        while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            candidate = parent.resolve(stem + "_" + counter + extension);
            counter++;
        }
        return candidate;
    }

    private static @Nullable String normalizeEntryName(@NotNull String rawName) {
        String name = rawName.replace('\\', '/');
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.isEmpty() || name.indexOf('\0') >= 0) {
            return null;
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String segment : name.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return name;
    }

    private static @NotNull Path resolveTarget(@NotNull Path destinationDir, @NotNull String name)
            throws IOException {
        Path target = destinationDir.resolve(name).normalize();
        if (!target.startsWith(destinationDir)) {
            throw new IOException(ThrexoBundle.message("error.outside.path", name));
        }
        return target;
    }

    private static boolean isMacMetadataDir(@NotNull String name) {
        return name.equals(MAC_METADATA_DIR);
    }

    private static boolean isAuxiliaryFile(@NotNull String name) {
        return name.equals(".DS_Store")
                || name.startsWith("._")
                || name.equals("Thumbs.db")
                || name.equals("desktop.ini");
    }

    private static boolean isAuxiliaryPath(@NotNull String name) {
        for (String segment : name.split("/")) {
            if (isMacMetadataDir(segment) || isAuxiliaryFile(segment)) {
                return true;
            }
        }
        return false;
    }
}
