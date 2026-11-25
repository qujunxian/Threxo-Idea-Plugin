package com.qujunxian.threxoideaplugin.action;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.qujunxian.threxoideaplugin.core.ArchiveFormat;
import com.qujunxian.threxoideaplugin.core.ArchiveTask;
import com.qujunxian.threxoideaplugin.i18n.ThrexoBundle;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 右键压缩包：解压到压缩包所在目录。
 */
public final class ExtractAction extends AnAction {

    public ExtractAction() {
        super(ThrexoBundle.message("action.Threxo.ExtractHere.text"),
                ThrexoBundle.message("action.Threxo.ExtractHere.description"),
                AllIcons.FileTypes.Archive);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        boolean visible = project != null && files != null && files.length > 0;
        if (visible) {
            for (VirtualFile file : files) {
                if (!file.isValid() || file.isDirectory() || !file.isInLocalFileSystem()
                        || ArchiveFormat.fromFileName(file.getName()) == null) {
                    visible = false;
                    break;
                }
            }
        }
        event.getPresentation().setEnabledAndVisible(visible);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        VirtualFile[] files = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
        if (project == null || files == null || files.length == 0) {
            return;
        }

        List<Path> archives = new ArrayList<>(files.length);
        for (VirtualFile file : files) {
            archives.add(Path.of(file.getPath()));
        }
        ArchiveTask.extract(project, archives).queue();
    }
}
