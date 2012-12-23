package com.googlecode.boostmavenproject;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
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
	 * Converts the plugin version to the Boost version.
	 *
	 * @param pluginVersion the plugin version
	 * @return the boost version
	 * @throws MojoExecutionException if pluginVersion cannot be parsed
	 */
	static String getBoostVersion(String pluginVersion) throws MojoExecutionException
	{
		int index = pluginVersion.indexOf('-');
		if (index == -1)
			throw new MojoExecutionException("Unexpected version: " + pluginVersion);
		return pluginVersion.substring(0, index);
	}
	/**
	 * The release platform.
	 *
	 * @parameter expression="${boost.classifier}"
	 * @required
	 * @readonly
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private String boostClassifier;
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
	/**
	 * To look up Archiver/UnArchiver implementations.
	 *
	 * @component role="org.codehaus.plexus.archiver.manager.ArchiverManager"
	 * @required
	 */
	@Component
	private ArchiverManager archiverManager;

	@Override
	@SuppressWarnings("NP_UNWRITTEN_FIELD")
	public void execute()
		throws MojoExecutionException, MojoFailureException
	{
		PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().
			get("pluginDescriptor");
		String version = getBoostVersion(pluginDescriptor.getVersion());
		String extension;
		List<String> bootstrapCommand;

		String addressModel;
		if (boostClassifier.contains("-i386-"))
			addressModel = "32";
		else if (boostClassifier.contains("-amd64-"))
			addressModel = "64";
		else
			throw new MojoExecutionException("Unexpected boost.classifier: " + boostClassifier);

		String buildMode;
		if (boostClassifier.contains("-debug"))
			buildMode = "debug";
		else if (boostClassifier.contains("-release"))
			buildMode = "release";
		else
			throw new MojoExecutionException("Unexpected boost.classifier: " + boostClassifier);
		Runtime runtime = Runtime.getRuntime();

		// --hash prevents the output path from exceeding the 255-character filesystem limit
		// REFERENCE: https://svn.boost.org/trac/boost/ticket/5155
		LinkedList<String> bjamCommand = Lists.newLinkedList(Lists.newArrayList(
			"address-model=" + addressModel, "--stagedir=.", "--without-python",
			"--without-mpi", "--layout=system", "variant=" + buildMode, "link=shared", "threading=multi",
			"runtime-link=shared", "stage", "-j", String.valueOf(runtime.availableProcessors()),
			"--hash"));

		if (boostClassifier.startsWith("windows-"))
		{
			extension = "zip";
			bootstrapCommand = ImmutableList.of("cmd.exe", "/c", "bootstrap.bat");
			bjamCommand.addAll(0, ImmutableList.of("cmd.exe", "/c", "bjam"));
		}
		else if (boostClassifier.startsWith("linux-") || boostClassifier.startsWith("mac-"))
		{
			extension = "tar.gz";
			bootstrapCommand = ImmutableList.of("./bootstrap.sh");
			bjamCommand.addAll(0, ImmutableList.of("./bjam"));
		}
		else
			throw new MojoExecutionException("Unexpected boost.classifier: " + boostClassifier);

		Path buildPath = Paths.get(project.getBuild().getDirectory(), "dependency/boost");
		try
		{
			Path result = download(new URL("http://sourceforge.net/projects/boost/files/boost/" + version
				+ "/boost_" + version.replace('.', '_') + "." + extension + "?use_mirror=autoselect"));
			if (Files.notExists(buildPath.resolve("lib")))
			{
				Files.createDirectories(buildPath);
				// Build process has not begun
				try
				{
					// Based on AbstractDependencyMojo.java in maven-dependency-plugin revision 1403449
					UnArchiver unArchiver;
					try
					{
						unArchiver = archiverManager.getUnArchiver(result.toFile());
						getLog().debug("Found unArchiver by type: " + unArchiver);
					}
					catch (NoSuchArchiverException e)
					{
						getLog().debug("Unknown archiver type", e);
						return;
					}

					unArchiver.setUseJvmChmod(true);
					unArchiver.setSourceFile(result.toFile());
					unArchiver.setDestDirectory(buildPath.toFile());
					unArchiver.extract();
				}
				catch (ArchiverException e)
				{
					throw new MojoExecutionException("Error unpacking file: " + result + " to: " + buildPath
						+ "\r\n" + e.toString(), e);
				}
				normalizeDirectories(buildPath);
			}
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("", e);
		}

		// Build boost
		exec(new ProcessBuilder(bootstrapCommand).directory(buildPath.toFile()));
		exec(new ProcessBuilder(bjamCommand).directory(buildPath.toFile()));
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
			"exec-maven-plugin", "1.2.1");
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

		Element argumentsElement = new Element("arguments", argumentsList.toArray(
			new Element[argumentsList.size()]));
		List<Element> environmentVariables = Lists.newArrayList();
		for (Entry<String, String> entry : process.environment().entrySet())
		{
			// Skip empty values.
			//
			// WORKAROUND: http://jira.codehaus.org/browse/MNG-5248
			String value = entry.getValue();
			if (!value.isEmpty())
				environmentVariables.add(new Element(entry.getKey(), entry.getValue()));
		}
		Element environmentVariablesElement = new Element("environmentVariables", environmentVariables.
			toArray(new Element[environmentVariables.size()]));

		Xpp3Dom configuration = MojoExecutor.configuration(executableElement, workingDirectoryElement,
			argumentsElement, environmentVariablesElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(execPlugin, "exec", configuration, environment);
	}

	/**
	 * Downloads a file.
	 *
	 * @param url the file to download
	 * @return the path of the downloaded file
	 * @throws MojoExecutionException if an error occurs downloading the file
	 */
	private Path download(URL url) throws MojoExecutionException
	{
		String filename = new File(url.getPath()).getName();
		Path result = Paths.get(project.getBuild().getDirectory(), filename);
		try
		{
			if (Files.notExists(result))
			{
				Log log = getLog();
				if (log.isInfoEnabled())
					log.info("Downloading: " + url.toString());
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();

				try
				{
					BufferedInputStream in = new BufferedInputStream(connection.getInputStream());

					Files.createDirectories(Paths.get(project.getBuild().getDirectory()));
					BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(result));
					byte[] buffer = new byte[10 * 1024];
					try
					{
						while (true)
						{
							int count = in.read(buffer);
							if (count == -1)
								break;
							out.write(buffer, 0, count);
						}
					}
					finally
					{
						in.close();
						out.close();
					}
				}
				finally
				{
					connection.disconnect();
				}
			}
			return result;
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("", e);
		}
	}

	/**
	 * Returns a filename extension. For example, {@code getFileExtension("foo.tar.gz")} returns
	 * {@code .gz}. Unix hidden files (e.g. ".hidden") have no extension.
	 *
	 * @param filename the filename
	 * @return an empty string if no extension is found
	 * @throws NullPointerException if filename is null
	 */
	private String getFileExtension(String filename)
	{
		Preconditions.checkNotNull(filename, "filename may not be null");

		Pattern pattern = Pattern.compile("[^\\.]+(\\.[\\p{Alnum}]+)$");
		Matcher matcher = pattern.matcher(filename);
		if (!matcher.find())
			return "";
		return matcher.group(1);
	}

	/**
	 * Normalize the directory structure across all platforms.
	 *
	 * @param source the binary path
	 * @throws IOException if an I/O error occurs
	 */
	private void normalizeDirectories(final Path source) throws IOException
	{
		// Strip top-level directory
		final Path topDirectory = Iterators.getOnlyElement(Files.newDirectoryStream(source).iterator());
		Files.walkFileTree(topDirectory, new SimpleFileVisitor<Path>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
			{
				Files.move(file, source.resolve(topDirectory.relativize(file)),
					StandardCopyOption.ATOMIC_MOVE);
				return super.visitFile(file, attrs);
			}

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws
				IOException
			{
				if (dir.equals(topDirectory))
					return FileVisitResult.CONTINUE;
				Files.move(dir, source.resolve(topDirectory.relativize(dir)),
					StandardCopyOption.ATOMIC_MOVE);
				return FileVisitResult.SKIP_SUBTREE;
			}
		});
		Files.deleteIfExists(topDirectory);
	}
}
