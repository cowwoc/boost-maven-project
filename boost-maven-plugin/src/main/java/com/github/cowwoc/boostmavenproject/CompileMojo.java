package com.github.cowwoc.boostmavenproject;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

/**
 * Compiles the Boost C++ library.
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.COMPILE)
public class CompileMojo
	extends AbstractMojo
{
	/**
	 * The release platform.
	 */
	@Parameter(property = "classifier", required = true)
	private String classifier;
	/**
	 * The sources platform.
	 */
	@Parameter(property = "sources.classifier", required = true)
	private String sourcesClassifier;
	/**
	 * Extra arguments to pass to the build process.
	 */
	@Parameter
	private List<String> arguments;
	@Component
	private BuildPluginManager pluginManager;
	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;
	/**
	 * The project groupId.
	 */
	@Parameter(property = "project.groupId")
	private String projectGroupId;
	/**
	 * The project version.
	 */
	@Parameter(property = "project.version")
	private String projectVersion;
	@Parameter(property = "session", required = true, readonly = true)
	private MavenSession session;

	@Override
	public void execute()
		throws MojoExecutionException
	{
		List<String> bootstrapCommand;

		String addressModel;
		if (classifier.contains("-x86_64-"))
			addressModel = "64";
		else
			throw new MojoExecutionException("Unexpected classifier: " + classifier);

		String buildMode;
		if (classifier.contains("-debug"))
			buildMode = "debug";
		else if (classifier.contains("-release"))
			buildMode = "release";
		else
			throw new MojoExecutionException("Unexpected classifier: " + classifier);
		Runtime runtime = Runtime.getRuntime();

		// --hash prevents the output path from exceeding the 255-character filesystem limit
		// REFERENCE: https://svn.boost.org/trac/boost/ticket/5155
		//
		// boost-context fails to build under OSX using version 1.53.0. Version 1.54.0 seems to work,
		// but fails later on due to https://svn.boost.org/trac/boost/ticket/8800
		LinkedList<String> b2Command = Lists.newLinkedList(Lists.newArrayList(
			"address-model=" + addressModel, "--stagedir=.", "--without-python",
			"--without-mpi", "--without-context", "--layout=system",
			"variant=" + buildMode, "link=shared", "threading=multi", "runtime-link=shared", "stage", "-j",
			String.valueOf(runtime.availableProcessors()), "--hash"));

		if (classifier.startsWith("windows-"))
		{
			bootstrapCommand = ImmutableList.of("cmd.exe", "/c", "bootstrap.bat");
			b2Command.addAll(0, ImmutableList.of("cmd.exe", "/c", "b2"));
		}
		else if (classifier.startsWith("linux-") || classifier.startsWith("mac-"))
		{
			bootstrapCommand = ImmutableList.of("./bootstrap.sh");
			b2Command.addAll(0, ImmutableList.of("./b2 install"));
		}
		else
			throw new MojoExecutionException("Unexpected classifier: " + classifier);

		Path boostDir = Paths.get(project.getBuild().getDirectory(), "dependency/boost");
		String sourcesArtifact = "boost-sources";

		Element groupIdElement = new Element("groupId", projectGroupId);
		Element artifactIdElement = new Element("artifactId", sourcesArtifact);
		Element versionElement = new Element("version", projectVersion);
		Element classifierElement = new Element("classifier", sourcesClassifier);
		Element outputDirectoryElement = new Element("outputDirectory", boostDir.toString());
		Element artifactItemElement = new Element("artifactItem", groupIdElement, artifactIdElement,
			versionElement, classifierElement, outputDirectoryElement);
		Element artifactItemsItem = new Element("artifactItems", artifactItemElement);
		Xpp3Dom configuration = MojoExecutor.configuration(artifactItemsItem);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session, pluginManager);
		Plugin dependencyPlugin = MojoExecutor.plugin("org.apache.maven.plugins",
			"maven-dependency-plugin", "3.1.1");
		MojoExecutor.executeMojo(dependencyPlugin, "unpack", configuration, environment);

		// Build boost
		exec(new ProcessBuilder(bootstrapCommand).directory(boostDir.toFile()));
		exec(new ProcessBuilder(b2Command).directory(boostDir.toFile()));
	}

	/**
	 * Executes an external command.
	 * <p/>
	 *
	 * @param process the command to execute
	 * @throws MojoExecutionException if the unpack operation fails
	 */
	private void exec(ProcessBuilder process)
		throws MojoExecutionException
	{
		Plugin execPlugin = MojoExecutor.plugin("org.codehaus.mojo",
			"exec-maven-plugin", "1.6.0");
		Element executableElement = new Element("executable", process.command().get(0));

		List<Element> argumentsList = Lists.newArrayList();
		List<String> command = process.command();
		for (String entry : command.subList(1, command.size()))
			argumentsList.add(new Element("argument", entry));
		if (arguments != null)
		{
			for (String entry : arguments)
				argumentsList.add(new Element("argument", entry));
		}

		File workingDirectory = process.directory();
		if (workingDirectory == null)
			workingDirectory = new File(System.getProperty("user.dir"));
		Element workingDirectoryElement = new Element("workingDirectory", workingDirectory.
			getAbsolutePath());

		Element argumentsElement = new Element("arguments", argumentsList.toArray(new Element[0]));
		List<Element> environmentVariables = Lists.newArrayList();
		for (Entry<String, String> entry : process.environment().entrySet())
		{
			// Skip empty values.
			//
			// WORKAROUND: https://issues.apache.org/jira/browse/MNG-6581
			String value = entry.getValue();
			if (!value.isEmpty())
				environmentVariables.add(new Element(entry.getKey(), entry.getValue()));
		}
		Element environmentVariablesElement = new Element("environmentVariables",
			environmentVariables.toArray(new Element[0]));

		Xpp3Dom configuration = MojoExecutor.configuration(executableElement, workingDirectoryElement,
			argumentsElement, environmentVariablesElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(execPlugin, "exec", configuration, environment);
	}
}
