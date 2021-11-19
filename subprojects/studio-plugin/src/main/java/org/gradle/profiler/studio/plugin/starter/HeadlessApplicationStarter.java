package org.gradle.profiler.studio.plugin.starter;

import com.intellij.concurrency.IdeaForkJoinWorkerThreadFactory;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.ProjectManager;
import org.gradle.profiler.studio.plugin.GradleProfilerPreloadingActivity;
import org.jdom.JDOMException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/**
 * A custom starter that runs in headless mode, used mostly for tests running on CI/CD.
 */
public class HeadlessApplicationStarter implements ApplicationStarter {

    private final GradleProfilerPreloadingActivity preloadingActivity = new GradleProfilerPreloadingActivity();

    @Override
    public void main(@NotNull List<String> args) {
        try {
            // Preloading is not called in headless mode, so we need to call it manually
            IdeaForkJoinWorkerThreadFactory.setupForkJoinCommonPool(true);
            ApplicationManager.getApplication().isUnitTestMode();
            preloadingActivity.preload(new ProgressIndicatorBase());
            ProjectManager.getInstance().loadAndOpenProject(args.get(1));
        } catch (IOException | JDOMException e) {
            throw new RuntimeException(e);
        }
    }

    @Nullable
    @Override
    public String getCommandName() {
        return "headless-starter";
    }

    @Override
    public boolean isHeadless() {
        return true;
    }
}
