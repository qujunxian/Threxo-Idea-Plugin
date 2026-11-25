package com.qujunxian.threxoideaplugin.core;

import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.qujunxian.threxoideaplugin.i18n.ThrexoBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 压缩/解压的后台任务：带进度、可取消，完成后刷新 VFS 并弹出通知。
 */
public final class ArchiveTask extends Task.Backgroundable {

    private static final Logger LOG = Logger.getInstance(ArchiveTask.class);
    private static final String NOTIFICATION_GROUP_ID = "Threxo";

    private final boolean compress;
    private final List<Path> payload;
    private List<Path> results = List.of();

    private ArchiveTask(@Nullable Project project, @NotNull String title,
                        @NotNull List<Path> payload, boolean compress) {
        super(project, title, true);
        this.payload = List.copyOf(payload);
        this.compress = compress;
    }

    public static @NotNull ArchiveTask compress(@NotNull Project project, @NotNull List<Path> sources) {
        return new ArchiveTask(project, ThrexoBundle.message("task.compress.title"), sources, true);
    }

    public static @NotNull ArchiveTask extract(@NotNull Project project, @NotNull List<Path> archives) {
        String title = archives.size() == 1
                ? ThrexoBundle.message("task.extract.title", archives.get(0).getFileName())
                : ThrexoBundle.message("task.extract.multiple.title", archives.size());
        return new ArchiveTask(project, title, archives, false);
    }

    @Override
    public void run(@NotNull ProgressIndicator indicator) {
        try {
            if (compress) {
                results = List.of(ArchiveService.compress(payload, indicator));
            } else {
                List<Path> destinationDirs = new ArrayList<>(payload.size());
                for (int i = 0; i < payload.size(); i++) {
                    ProgressManager.checkCanceled();
                    if (payload.size() > 1) {
                        indicator.setText(ThrexoBundle.message("task.extract.progress",
                                i + 1, payload.size(), payload.get(i).getFileName()));
                    }
                    destinationDirs.add(ArchiveService.extract(payload.get(i), indicator));
                }
                results = destinationDirs;
            }
            refreshAfterRun();
        } catch (IOException e) {
            throw new ArchiveOperationException(e);
        }
    }

    private void refreshAfterRun() {
        try {
            if (compress) {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(results.get(0).getParent());
            } else {
                for (Path directory : results) {
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(directory);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("刷新 VFS 失败", e);
        }
    }

    @Override
    public void onSuccess() {
        if (results.isEmpty()) {
            return;
        }
        if (compress) {
            Path archive = results.get(0);
            notify(NotificationType.INFORMATION,
                    ThrexoBundle.message("notification.compress.success.title"),
                    ThrexoBundle.message("notification.compress.success.content",
                            archive.getFileName(), archive.getParent()));
        } else if (payload.size() == 1) {
            notify(NotificationType.INFORMATION,
                    ThrexoBundle.message("notification.extract.success.title"),
                    ThrexoBundle.message("notification.extract.single.content",
                            payload.get(0).getFileName(), results.get(0)));
        } else {
            notify(NotificationType.INFORMATION,
                    ThrexoBundle.message("notification.extract.success.title"),
                    ThrexoBundle.message("notification.extract.multiple.content",
                            payload.size(), results.get(0)));
        }
    }

    @Override
    public void onThrowable(@NotNull Throwable error) {
        if (error instanceof ProcessCanceledException) {
            return;
        }
        Throwable cause = error instanceof ArchiveOperationException && error.getCause() != null
                ? error.getCause() : error;
        LOG.warn("Threxo 操作失败", cause);
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = cause.getClass().getSimpleName();
        }
        notify(NotificationType.ERROR,
                ThrexoBundle.message(compress
                        ? "notification.compress.failed.title"
                        : "notification.extract.failed.title"),
                message);
    }

    private void notify(@NotNull NotificationType type, @NotNull String title,
                        @NotNull String content) {
        NotificationGroup group = NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID);
        group.createNotification(title, content, type).notify(getProject());
    }

    /**
     * 把 IOException 包装成 RuntimeException，交给 Task 的 onThrowable 统一提示。
     */
    private static final class ArchiveOperationException extends RuntimeException {
        ArchiveOperationException(@NotNull IOException cause) {
            super(cause);
        }
    }
}
