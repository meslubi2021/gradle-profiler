package org.gradle.profiler.studio.plugin;

import com.android.tools.idea.gradle.project.GradleProjectInfo;
import com.intellij.ide.impl.TrustedProjects;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import org.gradle.profiler.client.protocol.Client;
import org.gradle.profiler.client.protocol.messages.StudioRequest;
import org.gradle.profiler.studio.plugin.client.GradleProfilerClient;
import org.gradle.profiler.studio.plugin.system.AndroidStudioSystemHelper;
import org.gradle.profiler.studio.plugin.system.GradleSystemListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import static org.gradle.profiler.client.protocol.messages.StudioRequest.StudioRequestType.EXIT_IDE;

public class GradleProfilerStartupActivity implements StartupActivity.DumbAware {

    private static final Logger LOG = Logger.getInstance(GradleProfilerStartupActivity.class);

    public static final String PROFILER_PORT_PROPERTY = "gradle.profiler.port";

    @Override
    public void runActivity(@NotNull Project project) {
        LOG.info("Project opened");
        logModifiedRegistryEntries();
        if (System.getProperty(PROFILER_PORT_PROPERTY) != null) {
            // This solves the issue where Android Studio would run the Gradle sync automatically on the first import.
            // Unfortunately it seems we can't always detect it because it happens very late and due to that there might
            // a case where two simultaneous syncs would be run: one from automatic sync trigger and one from our trigger.
            // With this line we disable that automatic sync, but we still trigger our sync later in the code.
            GradleProjectInfo.getInstance(project).setSkipStartupActivity(true);
            // If we don't disable external annotations, Android Studio will download some artifacts
            // to .m2 folder if some project has for example com.fasterxml.jackson.core:jackson-core as a dependency
            disableDownloadOfExternalAnnotations(project);
            // Register system listener already here, so we can catch any failure for syncs that are automatically started
            GradleSystemListener gradleSystemListener = registerGradleSystemListener();
            TrustedProjects.setTrusted(project, true);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                StudioRequest lastRequest = listenForSyncRequests(project, gradleSystemListener);
                if (lastRequest.getType() == EXIT_IDE) {
                    AndroidStudioSystemHelper.exit(project);
                }
            });
        }
    }

    private void logModifiedRegistryEntries() {
        String studioPropertiesPath = System.getenv("STUDIO_PROPERTIES");
        if (studioPropertiesPath == null || !new File(studioPropertiesPath).exists()) {
            return;
        }
        try {
            Properties properties = new Properties();
            properties.load(new FileInputStream(studioPropertiesPath));
            List<String> modifiedValues = Registry.getAll().stream()
                .filter(value -> properties.containsKey(value.getKey()))
                .map(RegistryValue::toString)
                .sorted()
                .collect(Collectors.toList());
            LOG.info("Modified registry entries: " + modifiedValues);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void disableDownloadOfExternalAnnotations(Project project) {
        GradleSettings gradleSettings = GradleSettings.getInstance(project);
        gradleSettings.getLinkedProjectsSettings()
            .forEach(settings -> settings.setResolveExternalAnnotations(false));
        gradleSettings.subscribe(new DefaultGradleSettingsListener() {
            @Override
            public void onProjectsLinked(@NotNull Collection<GradleProjectSettings> linkedProjectsSettings) {
                linkedProjectsSettings.forEach(settings -> settings.setResolveExternalAnnotations(false));
            }
        }, gradleSettings);
    }

    private GradleSystemListener registerGradleSystemListener() {
        GradleSystemListener gradleSystemListener = new GradleSystemListener();
        ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(gradleSystemListener);
        return gradleSystemListener;
    }

    private StudioRequest listenForSyncRequests(@NotNull Project project, @NotNull GradleSystemListener gradleStartupListener) {
        int port = Integer.getInteger(PROFILER_PORT_PROPERTY);
        try (Client client = new Client(port)) {
            return new GradleProfilerClient(client).listenForSyncRequests(project, gradleStartupListener);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
