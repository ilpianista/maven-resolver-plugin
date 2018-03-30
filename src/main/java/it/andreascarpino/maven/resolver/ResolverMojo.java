package it.andreascarpino.maven.resolver;

/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Andrea Scarpino <me@andreascarpino.it>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * NOTICE: This product includes software developed at The Apache Software Foundation (http://www.apache.org/).
 */

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.artifact.repository.layout.ArtifactRepositoryLayout;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.util.StringUtils;

/**
 * Add services implementations to the WAR file.
 */
@Mojo(name = "resolve", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class ResolverMojo extends AbstractMojo {

	/** The Constant ALT_REPO_SYNTAX_PATTERN. */
	private static final Pattern ALT_REPO_SYNTAX_PATTERN = Pattern.compile("(.+)::(.*)::(.+)");

	/** The dependency resolver. */
	@Component
	private DependencyResolver dependencyResolver;

	/** The session. */
	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	/**
	 * Map that contains the layouts.
	 */
	@Component(role = ArtifactRepositoryLayout.class)
	private Map<String, ArtifactRepositoryLayout> repositoryLayouts;

	/**
	 * Repositories in the format id::[layout]::url or just url, separated by comma.
	 * ie.
	 * central::default::http://repo1.maven.apache.org/maven2,myrepo::::http://repo.acme.com,http://repo.acme2.com
	 */
	@Parameter(property = "remoteRepositories")
	private String remoteRepositories;

	/** The pom remote repositories. */
	@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
	private List<ArtifactRepository> pomRemoteRepositories;

	/**
	 * Location of the file.
	 */
	@Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
	private File outputDirectory;

	/** The packaging. */
	@Parameter(defaultValue = "${project.packaging}", property = "jar", required = true)
	private String packaging;

	/** The final name. */
	@Parameter(defaultValue = "${project.build.finalName}", required = true)
	private String finalName;

	/** The artifacts. */
	@Parameter(required = true)
	private List<Artifact> artifacts;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (!"war".equalsIgnoreCase(packaging)) {
			// Nothing to do
			return;
		}

		File f = new File(outputDirectory + File.separator + finalName + ".war");
		getLog().debug("WAR file is " + f.getAbsoluteFile());
		if (!f.exists()) {
			getLog().debug("Cannot find WAR file: " + f);
			throw new MojoExecutionException("Cannot find WAR file: " + f);
		}

		List<ArtifactRepository> repoList = getRepositories();

		Path warFile = Paths.get(f.getAbsolutePath());
		try (FileSystem war = FileSystems.newFileSystem(warFile, null)) {
			Path webInf = war.getPath("/WEB-INF/lib");

			for (Artifact a : artifacts) {
				getLog().debug("Resolving artifact: " + a);

				try {
					ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(
							session.getProjectBuildingRequest());
					buildingRequest.setRemoteRepositories(repoList);

					DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();
					coordinate.setArtifactId(a.getArtifactId());
					coordinate.setGroupId(a.getGroupId());
					coordinate.setVersion(a.getVersion());
					coordinate.setType("jar");

					for (ArtifactResult artifact : dependencyResolver.resolveDependencies(buildingRequest, coordinate,
							null)) {
						File jar = artifact.getArtifact().getFile();

						PathMatcher matcher = FileSystems.getDefault().getPathMatcher("regex:/WEB-INF/lib/"
								+ jar.getName().replaceAll("(-[0-9]+.*)?\\.jar", "") + "(-[0-9]+.*)?\\.jar");

						MutableBoolean skip = new MutableBoolean(false);
						Files.walkFileTree(webInf, new SimpleFileVisitor<Path>() {
							@Override
							public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
								if (matcher.matches(file)) {
									skip.setTrue();
									return FileVisitResult.TERMINATE;
								}

								return super.visitFile(file, attrs);
							}
						});

						Path pathInZip = war.getPath("/WEB-INF/lib/" + jar.getName());
						if (skip.isFalse()) {
							getLog().debug("Adding: " + jar.getAbsoluteFile() + " to WAR file: " + f.getName());
							Files.copy(Paths.get(jar.getAbsolutePath()), pathInZip,
									StandardCopyOption.REPLACE_EXISTING);
						}
					}
				} catch (DependencyResolverException e) {
					throw new MojoExecutionException("Couldn't download artifact: " + e.getMessage(), e);
				}
			}
		} catch (IOException e) {
			getLog().debug(e.getMessage());
			throw new MojoExecutionException("Error opening WAR file " + f, e);
		}
	}

	/**
	 * Copied from the Maven Plugin maven-dependency-plugin of Apache.
	 */
	private List<ArtifactRepository> getRepositories() throws MojoFailureException {
		List<ArtifactRepository> repoList = new ArrayList<>();

		if (pomRemoteRepositories != null) {
			repoList.addAll(pomRemoteRepositories);
		}

		if (remoteRepositories != null) {
			ArtifactRepositoryPolicy always = new ArtifactRepositoryPolicy(true,
					ArtifactRepositoryPolicy.UPDATE_POLICY_ALWAYS, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN);

			// Use the same format as in the deploy plugin id::layout::url
			List<String> repos = Arrays.asList(StringUtils.split(remoteRepositories, ","));
			for (String repo : repos) {
				repoList.add(parseRepository(repo, always));
			}
		}

		return repoList;
	}

	/**
	 * Copied from the Maven Plugin maven-dependency-plugin of Apache.
	 */
	private ArtifactRepository parseRepository(String repo, ArtifactRepositoryPolicy policy)
			throws MojoFailureException {
		// if it's a simple url
		String id = "temp";
		ArtifactRepositoryLayout layout = getLayout("default");
		String url = repo;

		// if it's an extended repo URL of the form id::layout::url
		if (repo.contains("::")) {
			Matcher matcher = ALT_REPO_SYNTAX_PATTERN.matcher(repo);
			if (!matcher.matches()) {
				throw new MojoFailureException(repo, "Invalid syntax for repository: " + repo,
						"Invalid syntax for repository. Use \"id::layout::url\" or \"URL\".");
			}

			id = matcher.group(1).trim();
			if (!StringUtils.isEmpty(matcher.group(2))) {
				layout = getLayout(matcher.group(2).trim());
			}
			url = matcher.group(3).trim();
		}

		return new MavenArtifactRepository(id, url, layout, policy, policy);
	}

	/**
	 * Copied from the Maven Plugin maven-dependency-plugin of Apache.
	 */
	private ArtifactRepositoryLayout getLayout(String id) throws MojoFailureException {
		ArtifactRepositoryLayout layout = repositoryLayouts.get(id);

		if (layout == null) {
			throw new MojoFailureException(id, "Invalid repository layout", "Invalid repository layout: " + id);
		}

		return layout;
	}

}
