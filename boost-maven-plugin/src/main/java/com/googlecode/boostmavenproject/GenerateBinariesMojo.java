package com.googlecode.boostmavenproject;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

/**
 * Compiles the Boost C++ library and installs it into the local Maven repository.
 *
 * @goal generate-binaries
 * @phase compile
 * @author Gili Tzabari
 */
public class GenerateBinariesMojo
	extends AbstractMojo
{
	/**
	 * The library version.
	 *
	 * @parameter
	 * @required
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private String version;
	/**
	 * The path to copy the binaries into.
	 *
	 * @parameter
	 * @required
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private String outputDirectory;
	/**
	 * Indicates whether to build 32-bit or 64-bit binaries.
	 * Acceptable values include: 32 or 64
	 *
	 * @parameter
	 * @required
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private String addressModel;
	/**
	 * Extra arguments to pass to the build process.
	 * 
	 * @parameter
	 */
	private List<String> arguments;
	/**
	 * @component
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private BuildPluginManager pluginManager;
	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	@SuppressWarnings(
	{
		"UWF_UNWRITTEN_FIELD", "NP_UNWRITTEN_FIELD"
	})
	private MavenProject project;
	/**
	 * @parameter expression="${session}"
	 * @required
	 * @readonly
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private MavenSession session;

	@Override
	@SuppressWarnings("NP_UNWRITTEN_FIELD")
	public void execute()
		throws MojoExecutionException, MojoFailureException
	{
		final String groupId = "com.googlecode.boost-maven-project";
		final String artifactId = "boost-sources";
		final String classifier = "sources";
		if (!addressModel.equals("32") && !addressModel.equals("64"))
			throw new MojoExecutionException("Invalid addressModel value: " + addressModel);
		try
		{
			File outputDirectoryFile = new File(outputDirectory);
			File markerFile = new File(project.getBuild().getDirectory(),
				"dependency-maven-plugin-markers/" + groupId + "-" + artifactId
				+ "-zip-" + classifier + "-" + version + ".marker");
			boolean boostAlreadyUnpacked = markerFile.exists();
			unpack(groupId, artifactId, version, classifier, outputDirectoryFile);

			// Strip top-level directory
			if (!boostAlreadyUnpacked)
			{
				move(new File(outputDirectoryFile, "boost_" + version.replace('.', '_')),
					outputDirectoryFile);
			}

			// Build boost
			exec(new ProcessBuilder("bootstrap").directory(outputDirectoryFile));
			Runtime runtime = Runtime.getRuntime();

			// --hash prevents the output path from exceeding the 255-character filesystem limit
			// REFERENCE: https://svn.boost.org/trac/boost/ticket/5155
			LinkedList<String> commandLine = Lists.newLinkedList(Lists.newArrayList("bjam",
				"toolset=msvc", "address-model=" + addressModel, "--stagedir=stage" + addressModel,
				"--build-type=complete", "stage", "-j", String.valueOf(runtime.availableProcessors()),
				"--hash"));
			if (System.getProperty("os.name").contains("Windows"))
				commandLine.addAll(0, ImmutableList.of("cmd.exe", "/c"));
			exec(new ProcessBuilder(commandLine).directory(outputDirectoryFile));
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("", e);
		}
	}

	/**
	 * Executes an external command.
	 *
	 * @param process the command to execute
	 * @throws MojoExecutionException if the unpack operation fails
	 */
	private void exec(ProcessBuilder process)
		throws MojoExecutionException
	{
		Plugin execPlugin = MojoExecutor.plugin("org.codehaus.mojo",
			"exec-maven-plugin", "1.2");
		Element executableElement = new Element("executable", process.command().get(0));

		List<Element> argumentsList = Lists.newArrayList();
		List<String> command = process.command();
		for (String entry: command.subList(1, command.size()))
			argumentsList.add(new Element("argument", entry));
		if (arguments != null)
		{
			for (String entry: arguments)
				argumentsList.add(new Element("argument", entry));
		}

		File workingDirectory = process.directory();
		if (workingDirectory == null)
			workingDirectory = new File(System.getProperty("user.dir"));
		Element workingDirectoryElement = new Element("workingDirectory", workingDirectory.
			getAbsolutePath());

		Element argumentsElement = new Element("arguments", argumentsList.toArray(new Element[0]));
		List<Element> environmentVariables = Lists.newArrayList();
		for (Entry<String, String> entry: process.environment().entrySet())
			environmentVariables.add(new Element(entry.getKey(), entry.getValue()));
		Element environmentVariablesElement = new Element("environmentVariables", environmentVariables.
			toArray(new Element[0]));

		Xpp3Dom configuration = MojoExecutor.configuration(executableElement, workingDirectoryElement,
			argumentsElement, environmentVariablesElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(execPlugin, "exec", configuration, environment);
	}

	/**
	 * Moves files recursively from one path to another.
	 *
	 * @param source the source file or directory
	 * @param target the target file or directory
	 * @throws IOException if an error occurs while moving the file
	 */
	private void move(File source, File target) throws IOException
	{
		File[] children = source.listFiles();
		if (children != null)
		{
			// source is a directory
			for (File sourceChild: children)
			{
				File targetChild = new File(target, sourceChild.getName());
				if (!targetChild.exists() && !sourceChild.renameTo(targetChild))
				{
					throw new IOException("Cannot move " + sourceChild.getAbsolutePath() + " to "
																+ targetChild.getAbsolutePath());
				}
			}
		}
		else if (!target.exists() && !source.renameTo(target))
		{
			throw new IOException("Cannot move " + source.getAbsolutePath() + " to "
														+ target.getAbsolutePath());
		}
		delete(source);
	}

	/**
	 * Deletes a file or directory recursively.
	 *
	 * @param file the file or directory to delete
	 * @throws IOException if a file cannot be deleted
	 */
	private void delete(File file) throws IOException
	{
		File[] children = file.listFiles();
		if (children != null)
		{
			// source is a directory
			for (File child: children)
				delete(child);
		}
		if (file.exists() && !file.delete())
			throw new IOException("Cannot delete " + file.getAbsolutePath());
	}

	/**
	 * Unpacks an artifact into a directory.
	 *
	 * @param groupId the artifact group id
	 * @param artifactId the artifact id
	 * @param version the artifact version
	 * @param classifier the artifact classifier
	 * @param outputDirectory the directory to unpack into
	 * @throws MojoExecutionException if the unpack operation fails
	 */
	private void unpack(String groupId, String artifactId, String version, String classifier,
											File outputDirectory)
		throws MojoExecutionException
	{
		Plugin unpackPlugin = MojoExecutor.plugin("org.apache.maven.plugins",
			"maven-dependency-plugin", "2.2");
		Element groupIdElement = new Element("groupId", groupId);
		Element artifactIdElement = new Element("artifactId", artifactId);
		Element versionElement = new Element("version", version);
		Element packagingElement = new Element("type", "zip");
		Element outputDirectoryElement = new Element("outputDirectory",
			outputDirectory.getAbsolutePath());
		List<Element> elements = Lists.newArrayList(groupIdElement, artifactIdElement, versionElement,
			packagingElement, outputDirectoryElement);
		if (classifier != null)
		{
			Element classifierElement = new Element("classifier", classifier);
			elements.add(classifierElement);
		}
		Element artifactItemElement = new Element("artifactItem", elements.toArray(new Element[0]));
		Element artifactItemsElement = new Element("artifactItems", artifactItemElement);
		Xpp3Dom configuration = MojoExecutor.configuration(artifactItemsElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(unpackPlugin, "unpack", configuration, environment);
	}
}
