/*******************************************************************************
 * Copyright (c) 2021 Christoph Läubrich and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.plugins.p2.repository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.archiver.util.DefaultFileSet;
import org.codehaus.plexus.archiver.zip.ZipArchiver;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.sisu.equinox.EquinoxServiceFactory;
import org.eclipse.sisu.equinox.launching.internal.P2ApplicationLauncher;
import org.eclipse.tycho.p2.repository.RepositoryLayoutHelper;

/**
 * <p>
 * This goals produces a "p2-maven-site" from the projects declared &lt;dependencies&gt; (and
 * &lt;dependencyManagement&gt; if desired). A p2-maven-site is completely manageable by standard
 * maven tools and has the following properties:
 * <ul>
 * <li>The artifacts are not stored in the site itself but referenced as maven-coordinates, that
 * means you don't have to upload your artifacts to a dedicated place, everything is fetched from
 * the maven repository</li>
 * <li>The metadata of the page is attached to the current project with type=zip and
 * classifier=p2site and could be deployed using standard maven techniques</li>
 * </ul>
 * 
 * <b>Please note:</b> Only valid OSGi bundles are included, there is no way to automatically wrap
 * plain jars and they are silently ignored. This is intentional, as the goal of a p2-maven-site is
 * to use exactly the same artifact that is deployed in the maven repository.
 * </p>
 * <p>
 * The produced p2-maven-site can then be consumed by Tycho or PDE targets (m2eclipse is required
 * for this), in the following way: A tycho-repository section:
 * 
 * <pre>
    &lt;repository>
    &lt;id>my-p2-maven-site</id>
        &lt;url>mvn:[grouId]:[artifactId]:[version]:zip:p2site</url>
        &lt;layout>p2</layout>
    &lt;/repository>
 * </pre>
 * 
 * A target location of type software-site:
 * 
 * <pre>
 *  &lt;location includeAllPlatforms="false" includeConfigurePhase="true" includeMode="planner" includeSource="true" type="InstallableUnit">
        &lt;repository location="mvn:[grouId]:[artifactId]:[version]:zip:p2site"/>
        -- list desired units here --
    &lt;/location>
 * </pre>
 * </p>
 *
 */
@Mojo(name = "assemble-maven-repository", requiresDependencyResolution = ResolutionScope.COMPILE)
public class MavenP2SiteMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /**
     * Flag whether declared &lt;dependencies&gt; of the projects should be included.
     */
    @Parameter(defaultValue = "true")
    private boolean includeDependencies = true;

    /**
     * Flag that controls if &lt;dependencyManagement&gt; managed dependencies should be included,
     * this is useful if your are using a BOM and like to include all materials in the update-site
     * regardless of if they are explicitly included in the &lt;dependencies&gt; section
     */
    @Parameter(defaultValue = "false")
    private boolean includeManaged = false;

    /**
     * Flag that controls if reactor projects should be considered, this is useful if your are
     * simply like to make an update side of all your current reactor projects
     */
    @Parameter(defaultValue = "false")
    private boolean includeReactor = false;

    /**
     * Flag whether dependencies of the projects declared &lt;dependencies&gt; (and
     * &lt;dependencyManagement&gt; if desired) should be included. If enabled this creates the
     * maven equivalent of a self-contained P2 site
     */
    @Parameter(defaultValue = "false")
    private boolean includeTransitiveDependencies;

    @Parameter(defaultValue = "300")
    private int timeoutInSeconds = 300;

    /**
     * Location of the category definition. If the file does not exits, a generic category
     * definition is generated including all bundles under one category
     */
    @Parameter(defaultValue = "${project.basedir}/category.xml")
    private File categoryFile;
    /**
     * Name for the automatically generated category
     */
    @Parameter(defaultValue = "Bundles")
    private String categoryName;

    @Parameter(defaultValue = "${project.build.directory}/repository")
    private File destination;

    @Component
    private EquinoxServiceFactory equinox;

    @Component
    private Logger logger;
    @Component
    private RepositorySystem repositorySystem;

    @Component
    private P2ApplicationLauncher launcher;

    @Component
    private MavenProjectHelper projectHelper;

    /**
     * The output directory of the jar file
     * 
     * By default this is the Maven "target/" directory.
     */
    @Parameter(property = "project.build.directory", required = true)
    protected File buildDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        logger.debug("categoryName =        " + categoryName);
        logger.debug("includeDependencies = " + includeDependencies);
        logger.debug("includeManaged =      " + includeManaged);
        logger.debug("includeReactor =      " + includeReactor);
        logger.debug("includeTransitive =   " + includeTransitiveDependencies);

        Set<String> filesAdded = new HashSet<>();
        List<Dependency> dependencies = project.getDependencies();
        List<File> bundles = new ArrayList<>();
        List<File> advices = new ArrayList<>();
        if (includeDependencies) {
            resolve(dependencies, bundles, advices, filesAdded);
        }
        if (includeManaged) {
            resolve(project.getDependencyManagement().getDependencies(), bundles, advices, filesAdded);
        }
        if (includeReactor) {
            List<MavenProject> allProjects = session.getAllProjects();
            for (MavenProject mavenProject : allProjects) {
                if (skipProject(mavenProject)) {
                    continue;
                }
                Artifact artifact = mavenProject.getArtifact();
                File file = artifact.getFile();
                bundles.add(file);
                advices.add(createMavenAdvice(artifact));
            }
        }
        String categoryURI;
        if (categoryFile.exists()) {
            categoryURI = categoryFile.toURI().toASCIIString();
        } else {
            try {
                File categoryGenFile = File.createTempFile("category", ".xml");

                try (PrintWriter writer = new PrintWriter(
                        new OutputStreamWriter(new FileOutputStream(categoryGenFile), StandardCharsets.UTF_8))) {
                    writer.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
                    writer.println("<site>");
                    writer.println("<category-def name=\"bundles\" label=\"" + categoryName + "\"/>");
                    writer.println("<iu>");
                    writer.println("<category name=\"bundles\"/>");
                    writer.println(
                            " <query><expression type=\"match\">providedCapabilities.exists(p | p.namespace == 'osgi.bundle')</expression></query>");
                    writer.println("</iu>");
                    writer.println("</site>");
                }
                categoryGenFile.deleteOnExit();
                categoryURI = categoryGenFile.toURI().toASCIIString();
            } catch (IOException e) {
                throw new MojoExecutionException("failed to generate category.xml", e);
            }
        }

        File bundlesFile;
        try {
            bundlesFile = File.createTempFile("bundles", ".txt");
            FileUtils.writeLines(bundlesFile, StandardCharsets.UTF_8.name(),
                    bundles.stream().map(File::getAbsolutePath).collect(Collectors.toList()));
        } catch (IOException e) {
            throw new MojoExecutionException("failed to generate bundles list", e);
        }
        File advicesFile;
        try {
            advicesFile = File.createTempFile("advices", ".txt");
            FileUtils.writeLines(advicesFile, StandardCharsets.UTF_8.name(),
                    advices.stream().map(File::getAbsolutePath).collect(Collectors.toList()));
        } catch (IOException e) {
            throw new MojoExecutionException("failed to generate bundles list", e);
        }

        destination.mkdirs();
        launcher.setWorkingDirectory(destination);
        launcher.setApplicationName("org.eclipse.tycho.p2.tools.publisher.TychoFeaturesAndBundlesPublisher");
        launcher.addArguments("-artifactRepository", destination.toURI().toString(), //
                "-metadataRepository", destination.toURI().toString(), //
                "-bundlesFile", //
                bundlesFile.getAbsolutePath(), //
                "-advicesFile", //
                advicesFile.getAbsolutePath(), //
                "-categoryDefinition", categoryURI, //
                "-artifactRepositoryName", //
                project.getName(), //
                "-metadataRepositoryName", //
                project.getName(), //
                "-rules", //
                "(&(classifier=osgi.bundle));mvn:${maven.groupId}:${maven.artifactId}:${maven.version}:${maven.extension}:${maven.classifier}");
        int result = launcher.execute(timeoutInSeconds);
        for (File file : advices) {
            file.delete();
        }
        bundlesFile.delete();
        if (result != 0) {
            throw new MojoFailureException("P2 publisher return code was " + result);
        }
        ZipArchiver archiver = new ZipArchiver();
        File destFile = new File(buildDirectory, "p2-site.zip");
        archiver.setDestFile(destFile);
        archiver.addFileSet(new DefaultFileSet(destination));
        try {
            archiver.createArchive();
        } catch (IOException e) {
            throw new MojoExecutionException("failed to createa archive", e);
        }
        projectHelper.attachArtifact(project, "zip", "p2site", destFile);
    }

    protected void resolve(List<Dependency> dependencies, List<File> bundles, List<File> advices,
            Set<String> filesAdded) throws MojoExecutionException {
        for (Dependency dependency : dependencies) {
            logger.debug("resolving " + dependency.getGroupId() + "::" + dependency.getArtifactId() + "::"
                    + dependency.getVersion() + "::" + dependency.getClassifier());
            Artifact artifact = repositorySystem.createArtifactWithClassifier(dependency.getGroupId(),
                    dependency.getArtifactId(), dependency.getVersion(), dependency.getType(),
                    dependency.getClassifier());
            Set<Artifact> artifacts = resolveArtifact(artifact, includeTransitiveDependencies);
            for (Artifact resolvedArtifact : artifacts) {
                logger.debug("    resolved " + resolvedArtifact.getGroupId() + "::" + resolvedArtifact.getArtifactId()
                        + "::" + resolvedArtifact.getVersion() + "::" + resolvedArtifact.getClassifier());
                File file = resolvedArtifact.getFile();
                if (filesAdded.add(file.getAbsolutePath())) {
                    bundles.add(file);
                    advices.add(createMavenAdvice(resolvedArtifact));
                    //TODO pgp.signatures --> getSignatureFile(resolvedArtifact)
                }
            }
        }
    }

    protected File createMavenAdvice(Artifact artifact) throws MojoExecutionException {
        try {
            int cnt = 0;
            File p2 = File.createTempFile("p2properties", ".inf");
            p2.deleteOnExit();
            Properties properties = new Properties();
            addProvidesAndProperty(properties, RepositoryLayoutHelper.PROP_GROUP_ID, artifact.getGroupId(), cnt++);
            addProvidesAndProperty(properties, RepositoryLayoutHelper.PROP_ARTIFACT_ID, artifact.getArtifactId(),
                    cnt++);
            addProvidesAndProperty(properties, RepositoryLayoutHelper.PROP_VERSION, artifact.getVersion(), cnt++);
            addProvidesAndProperty(properties, RepositoryLayoutHelper.PROP_EXTENSION, artifact.getType(), cnt++);
            addProvidesAndProperty(properties, RepositoryLayoutHelper.PROP_CLASSIFIER, artifact.getClassifier(), cnt++);
            addProvidesAndProperty(properties, "maven-scope", artifact.getScope(), cnt++);
            properties.store(new FileOutputStream(p2), null);
            return p2;
        } catch (IOException e) {
            throw new MojoExecutionException("failed to generate p2.inf", e);
        }
    }

    private void addProvidesAndProperty(Properties properties, String key, String value, int i) {
        //see https://help.eclipse.org/2021-03/index.jsp?topic=%2Forg.eclipse.platform.doc.isv%2Fguide%2Fp2_customizing_metadata.html
        addProvides(properties, key.replace('-', '.'), value, null, i);
        addProperty(properties, key, value, i);
    }

    private void addProvides(Properties properties, String namespace, String name, String version, int i) {
        if (name != null && !name.isBlank()) {
            properties.setProperty("provides." + i + ".namespace", namespace);
            properties.setProperty("provides." + i + ".name", name);
            if (version != null && !version.isBlank()) {
                properties.setProperty("provides." + i + ".version", version);
            }
        }
    }

    private void addProperty(Properties properties, String name, String value, int i) {
        if (value != null && !value.isBlank()) {
            properties.setProperty("properties." + i + ".name", name);
            properties.setProperty("properties." + i + ".value", value);
        }

    }

    protected Set<Artifact> resolveArtifact(Artifact artifact, boolean resolveTransitively) {
        ArtifactResolutionRequest request = new ArtifactResolutionRequest();
        request.setArtifact(artifact);
        request.setOffline(session.isOffline());
        request.setLocalRepository(session.getLocalRepository());
        request.setResolveTransitively(resolveTransitively);
        request.setRemoteRepositories(session.getCurrentProject().getRemoteArtifactRepositories());
        ArtifactResolutionResult result = repositorySystem.resolve(request);
        return result.getArtifacts();
    }

    private File getSignatureFile(Artifact artifact) {
        final Artifact signatureArtifact = repositorySystem.createArtifactWithClassifier(artifact.getGroupId(),
                artifact.getArtifactId(), artifact.getVersion(), artifact.getType(), artifact.getClassifier());

        signatureArtifact.setArtifactHandler(new ArtifactHandler() {

            @Override
            public boolean isIncludesDependencies() {
                return artifact.getArtifactHandler().isIncludesDependencies();
            }

            @Override
            public boolean isAddedToClasspath() {
                return artifact.getArtifactHandler().isAddedToClasspath();
            }

            @Override
            public String getPackaging() {
                return artifact.getArtifactHandler().getPackaging();
            }

            @Override
            public String getLanguage() {
                return artifact.getArtifactHandler().getLanguage();
            }

            @Override
            public String getExtension() {
                return artifact.getArtifactHandler().getExtension() + ".asc";
            }

            @Override
            public String getDirectory() {
                return artifact.getArtifactHandler().getDirectory();
            }

            @Override
            public String getClassifier() {
                return artifact.getArtifactHandler().getClassifier();
            }
        });
        for (Artifact signature : resolveArtifact(signatureArtifact, false)) {
            return signature.getFile();
        }
        return null;
    }

    /**
     *
     * @return <code>true</code> if this project should not be packaged in the p2 site
     */
    protected boolean skipProject(MavenProject mavenProject) {
        String packaging = mavenProject.getPackaging();
        if (packaging.equalsIgnoreCase("pom")) {
            return true;
        }
        Artifact artifact = mavenProject.getArtifact();
        if (artifact == null) {
            return true;
        }
        File file = artifact.getFile();
        if (file == null || !file.isFile()) {
            return true;
        }
        if (isSkippedDeploy(mavenProject)){
            return true;
        }
        return false;
    }

    /**
     *
     * @return <code>true</code> if the pom configuration skip deploy for the project
     */
    protected boolean isSkippedDeploy(MavenProject mavenProject) {
        String property = mavenProject.getProperties().getProperty("maven.deploy.skip");
        if (property != null) {
            boolean skip = BooleanUtils.toBoolean(property);
            getLog().debug("deploy is " + (skip?"":"not") + " skipped in MavenProject "
                    + mavenProject.getName() + " because of property 'maven.deploy.skip'");
            return skip;
        }
        String pluginId = "org.apache.maven.plugins:maven-deploy-plugin";
        property = getPluginParameter(mavenProject, pluginId, "skip");
        if (property != null) {
            boolean skip = BooleanUtils.toBoolean(property);
            getLog().debug("deploy is " + (skip?"":"not") + " skipped in MavenProject "
                    + mavenProject.getName() + " because of configuration of the plugin 'org.apache.maven.plugins:maven-deploy-plugin'");
            return skip;
        }
        if (mavenProject.getParent() != null) {
            return isSkippedDeploy(mavenProject.getParent());
        }
        getLog().debug("not skipping deploy of MavenProject '" + mavenProject.getName() + "'");
        return false;
    }

    /**
     * @param p        not null
     * @param pluginId not null
     * @param param    not null
     * @return the simple parameter as String defined in the plugin configuration by <code>param</code> key
     *         or <code>null</code> if not found.
     * @since 2.6
     */
    private static String getPluginParameter(MavenProject p, String pluginId, String param) {
        Plugin plugin = getPlugin(p, pluginId);
        if (plugin != null) {
            Xpp3Dom xpp3Dom = (Xpp3Dom) plugin.getConfiguration();
            if (xpp3Dom != null && xpp3Dom.getChild(param) != null
                    && StringUtils.isNotEmpty(xpp3Dom.getChild( param ).getValue())) {
                return xpp3Dom.getChild(param).getValue();
            }
        }

        return null;
    }

    /**
     * @param p        not null
     * @param pluginId not null key of the plugin defined in {@link org.apache.maven.model.Build#getPluginsAsMap()}
     *                 or in {@link org.apache.maven.model.PluginManagement#getPluginsAsMap()}
     * @return the Maven plugin defined in <code>${project.build.plugins}</code> or in
     *         <code>${project.build.pluginManagement}</code>, or <code>null</code> if not defined.
     */
    private static Plugin getPlugin(MavenProject p, String pluginId) {
        if ((p.getBuild() == null) || (p.getBuild().getPluginsAsMap() == null)) {
            return null;
        }

        Plugin plugin = p.getBuild().getPluginsAsMap().get(pluginId);

        if ((plugin == null) && (p.getBuild().getPluginManagement() != null) &&
                (p.getBuild().getPluginManagement().getPluginsAsMap() != null)) {
            plugin = p.getBuild().getPluginManagement().getPluginsAsMap().get(pluginId);
        }

        return plugin;
    }

}