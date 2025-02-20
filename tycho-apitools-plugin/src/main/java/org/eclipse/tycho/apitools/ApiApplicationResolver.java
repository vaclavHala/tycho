/*******************************************************************************
 * Copyright (c) 2023 Christoph Läubrich and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Christoph Läubrich - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.apitools;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.tycho.ArtifactKey;
import org.eclipse.tycho.ArtifactType;
import org.eclipse.tycho.IllegalArtifactReferenceException;
import org.eclipse.tycho.MavenRepositoryLocation;
import org.eclipse.tycho.TargetPlatform;
import org.eclipse.tycho.core.resolver.P2ResolutionResult;
import org.eclipse.tycho.core.resolver.P2ResolutionResult.Entry;
import org.eclipse.tycho.core.resolver.P2Resolver;
import org.eclipse.tycho.osgi.framework.EclipseApplication;
import org.eclipse.tycho.osgi.framework.EclipseApplicationFactory;
import org.osgi.framework.BundleException;
import org.osgi.service.log.LogEntry;

/**
 * Component that resolves the bundles that make up the ApiApplication from a
 * given URI
 */
@Component(role = ApiApplicationResolver.class)
public class ApiApplicationResolver {

	private static final String FRAGMENT_COMPATIBILITY = "org.eclipse.osgi.compatibility.state";

	private static final String BUNDLE_API_TOOLS = "org.eclipse.pde.api.tools";

	private static final String BUNDLE_LAUNCHING_MACOS = "org.eclipse.jdt.launching.macosx";

	private static final String FILTER_MACOS = "(osgi.os=macosx)";

	private final Map<URI, EclipseApplication> cache = new ConcurrentHashMap<>();

	@Requirement
	private Logger logger;

	@Requirement
	private EclipseApplicationFactory applicationFactory;

	public Collection<Path> getApiBaselineBundles(Collection<MavenRepositoryLocation> baselineRepoLocations,
			ArtifactKey artifactKey) throws IllegalArtifactReferenceException {
		P2Resolver resolver = applicationFactory.createResolver();
		resolver.addDependency(ArtifactType.TYPE_INSTALLABLE_UNIT, artifactKey.getId(), "0.0.0");
		List<Path> resolvedBundles = new ArrayList<>();
		TargetPlatform targetPlatform = applicationFactory.createTargetPlatform(baselineRepoLocations);
		for (P2ResolutionResult result : resolver.resolveTargetDependencies(targetPlatform, null).values()) {
			for (Entry entry : result.getArtifacts()) {
				if (ArtifactType.TYPE_ECLIPSE_PLUGIN.equals(entry.getType())
						&& !"org.eclipse.osgi".equals(entry.getId())) {
					resolvedBundles.add(entry.getLocation(true).toPath());
				}
			}
		}
		return resolvedBundles;
	}

	public EclipseApplication getApiApplication(MavenRepositoryLocation apiToolsRepo) {
		return cache.computeIfAbsent(apiToolsRepo.getURL().normalize(), x -> {
			logger.info("Resolve API tools runtime from " + apiToolsRepo + "...");
			EclipseApplication application = applicationFactory.createEclipseApplication(apiToolsRepo,
					"ApiToolsApplication");
			application.addBundle(BUNDLE_API_TOOLS);
			application.addBundle(FRAGMENT_COMPATIBILITY);
			application.addConditionalBundle(BUNDLE_LAUNCHING_MACOS, FILTER_MACOS);
			application.setLoggingFilter(ApiApplicationResolver::isOnlyDebug);
			return application;
		});
	}

	private static boolean isOnlyDebug(LogEntry entry) {
		String message = entry.getMessage();
		if (message.contains("The workspace ") && message.contains("with unsaved changes")) {
			return true;
		}
		if (message.contains("Workspace was not properly initialized or has already shutdown")) {
			return true;
		}
		if (message.contains("Platform proxy API not available")) {
			return true;
		}
		if (message.contains("Error processing mirrors URL")) {
			return true;
		}
		if (entry.getException() instanceof BundleException) {
			return true;
		}
		return false;
	}

}
