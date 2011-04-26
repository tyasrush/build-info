/*
 * Copyright (C) 2010 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jfrog.build.extractor.gradle;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.PublishArtifact;
import org.gradle.api.artifacts.ResolveException;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.ResolvedConfiguration;
import org.gradle.api.artifacts.ResolvedDependency;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.jfrog.build.ArtifactoryPluginUtils;
import org.jfrog.build.api.Agent;
import org.jfrog.build.api.Artifact;
import org.jfrog.build.api.Build;
import org.jfrog.build.api.BuildAgent;
import org.jfrog.build.api.BuildRetention;
import org.jfrog.build.api.BuildType;
import org.jfrog.build.api.Dependency;
import org.jfrog.build.api.LicenseControl;
import org.jfrog.build.api.Module;
import org.jfrog.build.api.builder.ArtifactBuilder;
import org.jfrog.build.api.builder.BuildInfoBuilder;
import org.jfrog.build.api.builder.DependencyBuilder;
import org.jfrog.build.api.builder.ModuleBuilder;
import org.jfrog.build.api.builder.PromotionStatusBuilder;
import org.jfrog.build.api.release.Promotion;
import org.jfrog.build.api.util.FileChecksumCalculator;
import org.jfrog.build.client.ArtifactoryClientConfiguration;
import org.jfrog.build.client.DeployDetails;
import org.jfrog.build.extractor.BuildInfoExtractor;
import org.jfrog.build.extractor.BuildInfoExtractorSpec;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.google.common.collect.Iterables.*;
import static com.google.common.collect.Lists.newArrayList;

/**
 * An upload task uploads files to the repositories assigned to it.  The files that get uploaded are the artifacts of
 * your project, if they belong to the configuration associated with the upload task.
 *
 * @author Tomer Cohen
 */
public class GradleBuildInfoExtractor implements BuildInfoExtractor<Project, Build> {
    private static final Logger log = Logging.getLogger(GradleBuildInfoExtractor.class);

    private static final String SHA1 = "sha1";
    private static final String MD5 = "md5";
    private final ArtifactoryClientConfiguration clientConf;
    private final Set<GradleDeployDetails> gradleDeployDetails;


    public GradleBuildInfoExtractor(ArtifactoryClientConfiguration clientConf,
            Set<GradleDeployDetails> gradleDeployDetails) {
        this.clientConf = clientConf;
        this.gradleDeployDetails = gradleDeployDetails;
    }

    public Build extract(Project rootProject, BuildInfoExtractorSpec spec) {
        BuildInfoBuilder buildInfoBuilder = new BuildInfoBuilder(clientConf.info.getBuildName());
        Date startedDate = new Date();
        long startTime = Long.parseLong(clientConf.info.getBuildStarted());
        startedDate.setTime(startTime);
        buildInfoBuilder.type(BuildType.GRADLE);
        GradleInternal gradleInternals = (GradleInternal) rootProject.getGradle();
        BuildAgent buildAgent = new BuildAgent("Gradle", gradleInternals.getGradleVersion());
        String agentName = clientConf.info.getAgentName();
        String agentVersion = clientConf.info.getAgentVersion();
        if (StringUtils.isNotBlank(agentName) && StringUtils.isNotBlank(agentVersion)) {
            buildInfoBuilder.agent(new Agent(agentName, agentVersion));
        }
        buildInfoBuilder.durationMillis(System.currentTimeMillis() - startTime)
                .startedDate(startedDate).number(clientConf.info.getBuildNumber())
                .buildAgent(buildAgent);
        Set<Project> allProjects = rootProject.getAllprojects();
        for (Project project : allProjects) {
            BuildInfoRecorderTask buildInfoRecorderTask = getBuildInfoRecorderTask(project);
            if (buildInfoRecorderTask != null && buildInfoRecorderTask.hasConfigurations()) {
                buildInfoBuilder.addModule(extractModule(project));
            }
        }
        String parentName = clientConf.info.getParentBuildName();
        String parentNumber = clientConf.info.getParentBuildNumber();
        if (parentName != null && parentNumber != null) {
            buildInfoBuilder.parentName(parentName);
            buildInfoBuilder.parentNumber(parentNumber);
        }
        String principal = clientConf.info.getPrincipal();
        if (StringUtils.isBlank(principal)) {
            principal = System.getProperty("user.name");
        }
        buildInfoBuilder.principal(principal);
        String artifactoryPrincipal = clientConf.publisher.getUserName();
        if (StringUtils.isBlank(artifactoryPrincipal)) {
            artifactoryPrincipal = System.getProperty("user.name");
        }
        buildInfoBuilder.artifactoryPrincipal(artifactoryPrincipal);
        String buildUrl = clientConf.info.getBuildUrl();
        if (StringUtils.isNotBlank(buildUrl)) {
            buildInfoBuilder.url(buildUrl);
        }
        String vcsRevision = clientConf.info.getVcsRevision();
        if (StringUtils.isNotBlank(vcsRevision)) {
            buildInfoBuilder.vcsRevision(vcsRevision);
        }

        LicenseControl licenseControl = new LicenseControl(clientConf.info.licenseControl.isRunChecks());
        String notificationRecipients = clientConf.info.licenseControl.getViolationRecipients();
        if (StringUtils.isNotBlank(notificationRecipients)) {
            licenseControl.setLicenseViolationsRecipientsList(notificationRecipients);
        }
        licenseControl.setIncludePublishedArtifacts(clientConf.info.licenseControl.isIncludePublishedArtifacts());
        String scopes = clientConf.info.licenseControl.getScopes();
        if (StringUtils.isNotBlank(scopes)) {
            licenseControl.setScopesList(scopes);
        }
        licenseControl.setAutoDiscover(clientConf.info.licenseControl.isAutoDiscover());
        buildInfoBuilder.licenseControl(licenseControl);
        BuildRetention buildRetention = new BuildRetention(clientConf.info.isDeleteBuildArtifacts());
        Integer count = clientConf.info.getBuildRetentionDays();
        if (count != null) {
            buildRetention.setCount(count);
        }
        String buildRetentionMinimumDays = clientConf.info.getBuildRetentionMinimumDate();
        if (StringUtils.isNotBlank(buildRetentionMinimumDays)) {
            int minimumDays = Integer.parseInt(buildRetentionMinimumDays);
            if (minimumDays > -1) {
                Calendar calendar = Calendar.getInstance();
                calendar.roll(Calendar.DAY_OF_YEAR, -minimumDays);
                buildRetention.setMinimumBuildDate(calendar.getTime());
            }
        }
        buildInfoBuilder.buildRetention(buildRetention);
        if (clientConf.info.isReleaseEnabled()) {
            String stagingRepository = clientConf.publisher.getRepoKey();
            String comment = clientConf.info.getReleaseComment();
            if (comment == null) {
                comment = "";
            }
            buildInfoBuilder.addStatus(new PromotionStatusBuilder(Promotion.STAGED).timestampDate(startedDate)
                    .comment(comment).repository(stagingRepository)
                    .ciUser(principal).user(artifactoryPrincipal).build());
        }
        clientConf.info.fillCommonSysProps();
        Properties props = new Properties();
        props.putAll(clientConf.info.getBuildVariables());
        buildInfoBuilder.properties(props);
        log.debug("buildInfoBuilder = " + buildInfoBuilder);
        // for backward compatibility for Artifactory 2.2.3
        Build build = buildInfoBuilder.build();
        if (parentName != null && parentNumber != null) {
            build.setParentBuildId(parentName);
        }
        return build;
    }

    private BuildInfoRecorderTask getBuildInfoRecorderTask(Project project) {
        Set<Task> tasks = project.getTasksByName(ArtifactoryPluginUtils.BUILD_INFO_TASK_NAME, false);
        if (tasks.isEmpty()) {
            return null;
        }
        return (BuildInfoRecorderTask) tasks.iterator().next();
    }


    public Module extractModule(Project project) {
        String artifactName = project.getName();
        BuildInfoRecorderTask task = getBuildInfoRecorderTask(project);
        if (task != null) {
            artifactName = task.getArtifactName();
        }
        ModuleBuilder builder = new ModuleBuilder()
                .id(project.getGroup() + ":" + artifactName + ":" + project.getVersion().toString());
        try {
            builder.artifacts(calculateArtifacts(project))
                    .dependencies(calculateDependencies(project));
        } catch (Exception e) {
            log.error("Error during extraction: ", e);
        }
        return builder.build();
    }

    private List<Artifact> calculateArtifacts(final Project project) throws Exception {
        Iterable<GradleDeployDetails> filter = getProjectDeployDetails(project);
        List<Artifact> artifacts = newArrayList(transform(filter, new Function<GradleDeployDetails, Artifact>() {
            public Artifact apply(GradleDeployDetails from) {
                PublishArtifact publishArtifact = from.getPublishArtifact();
                DeployDetails deployDetails = from.getDeployDetails();
                int index = deployDetails.getArtifactPath().lastIndexOf('/');
                String type = StringUtils.isBlank(publishArtifact.getClassifier()) ? publishArtifact.getType() :
                        publishArtifact.getType() + "-" + publishArtifact.getClassifier();
                return new ArtifactBuilder(deployDetails.getArtifactPath().substring(index + 1))
                        .type(type).md5(deployDetails.getMd5()).sha1(deployDetails.getSha1()).build();
            }
        }));
        return artifacts;
    }

    private Iterable<GradleDeployDetails> getProjectDeployDetails(final Project project) {
        return Iterables.filter(gradleDeployDetails, new Predicate<GradleDeployDetails>() {
            @Override
            public boolean apply(@Nullable GradleDeployDetails input) {
                return input.getProject().equals(project);
            }
        });
    }

    private List<Dependency> calculateDependencies(Project project) throws Exception {
        Set<Configuration> configurationSet = project.getConfigurations().getAll();
        List<Dependency> dependencies = newArrayList();
        for (Configuration configuration : configurationSet) {
            ResolvedConfiguration resolvedConfiguration = configuration.getResolvedConfiguration();
            Set<ResolvedArtifact> resolvedArtifactSet = null;
            try {
                resolvedArtifactSet = resolvedConfiguration.getResolvedArtifacts();
            } catch (ResolveException e) {
                log.debug("Artifacts for configuration '{}' were not all resolved, skipping", configuration.getName());
                continue;
            }
            for (final ResolvedArtifact artifact : resolvedArtifactSet) {
                ResolvedDependency resolvedDependency = artifact.getResolvedDependency();
                /**
                 * If including sources jar the jars will have the same ID despite one of them having a sources
                 * classifier, this fix will remain until GAP-13 is fixed, on both our side and the Gradle side.
                 */
                File file = artifact.getFile();
                if (file != null && file.exists() && !file.getName().endsWith("-sources.jar")) {
                    String depId = resolvedDependency.getName();
                    final String finalDepId = depId;
                    Predicate<Dependency> idEqualsPredicate = new Predicate<Dependency>() {
                        public boolean apply(@Nullable Dependency input) {
                            return input.getId().equals(finalDepId);
                        }
                    };
                    // if it's already in the dependencies list just add the current scope
                    if (any(dependencies, idEqualsPredicate)) {
                        Dependency existingDependency = find(dependencies, idEqualsPredicate);
                        List<String> existingScopes = existingDependency.getScopes();
                        String configScope = configuration.getName();
                        if (!existingScopes.contains(configScope)) {
                            existingScopes.add(configScope);
                        }
                    } else {
                        Map<String, String> checksums = calculateChecksumsForFile(file);
                        DependencyBuilder dependencyBuilder = new DependencyBuilder()
                                .type(artifact.getType()).id(depId)
                                .scopes(newArrayList(configuration.getName())).
                                        md5(checksums.get(MD5)).sha1(checksums.get(SHA1));
                        dependencies.add(dependencyBuilder.build());
                    }
                }
            }
        }
        return dependencies;
    }

    private Map<String, String> calculateChecksumsForFile(File file)
            throws NoSuchAlgorithmException, IOException {
        Map<String, String> checkSums =
                FileChecksumCalculator.calculateChecksums(file, MD5, SHA1);
        return checkSums;
    }
}