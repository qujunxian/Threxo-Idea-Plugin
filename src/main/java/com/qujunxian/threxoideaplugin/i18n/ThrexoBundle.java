package com.qujunxian.threxoideaplugin.i18n;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 按 IDE 当前语言加载文案：中文语言包启用时走 {@code _zh}，否则走默认英文。
 * 语言由平台 {@link DynamicBundle#getLocale()} 决定；取不到时退回系统语言。
 */
public final class ThrexoBundle extends DynamicBundle {

    private static final String BUNDLE = "messages.ThrexoBundle";
    private static final ThrexoBundle INSTANCE = new ThrexoBundle();

    private ThrexoBundle() {
        super(BUNDLE);
    }

    public static @NotNull String message(
            @NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object... params) {
        return INSTANCE.getMessage(key, params);
    }

    @Override
    protected @NotNull ResourceBundle findBundle(@NotNull String pathToBundle,
                                                 @NotNull ClassLoader loader,
                                                 @NotNull ResourceBundle.Control control) {
        // 关掉 JDK 默认的「回退到系统语言」行为：英文 IDE + 中文系统不能串成中文
        return ResourceBundle.getBundle(pathToBundle, ideLocale(), loader,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    private static @NotNull Locale ideLocale() {
        try {
            Locale locale = DynamicBundle.getLocale();
            if (locale != null) {
                return locale;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 平台 API 不可用时退回系统语言
        }
        return Locale.getDefault();
    }
}
