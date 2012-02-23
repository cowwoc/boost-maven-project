package com.googlecode.boostmavenproject;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import de.schlichtherle.truezip.fs.FsSyncOptions;
import de.schlichtherle.truezip.nio.file.TPath;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import org.apache.commons.lang.NullArgumentException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

/**
 * Downloads and installs the Boost C++ library sources into the local Maven
 * repository.
 *
 * @goal get-sources
 * @phase generate-resources
 * @author Gili Tzabari
 */
public class GetSourcesMojo
	extends AbstractMojo
{
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
	 * The maven plugin manager.
	 *
	 * @component
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private BuildPluginManager pluginManager;
	/**
	 * The local maven repository.
	 *
	 * @parameter expression="${localRepository}"
	 * @required
	 * @readonly
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private ArtifactRepository localRepository;
	/**
	 * @component
	 */
	private RepositorySystem repositorySystem;
	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
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
		throws MojoExecutionException
	{
		final String groupId = "com.googlecode.boost-maven-project";
		final String artifactId = "boost-sources";
		PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().
			get("pluginDescriptor");
		String version = getBoostVersion(pluginDescriptor.getVersion());
		Artifact artifact = getArtifact(groupId, artifactId, version, boostClassifier);
		if (artifact != null)
			return;
		String extension;
		if (boostClassifier.startsWith("windows-"))
			extension = "zip";
		else if (boostClassifier.startsWith("linux-") || boostClassifier.startsWith("mac-"))
			extension = "tar.gz";
		else
			throw new MojoExecutionException("Unexpected boost.classifier: " + boostClassifier);

		Path file;
		try
		{
			file = download(new URL("http://sourceforge.net/projects/boost/files/boost/" + version
															+ "/boost_" + version.replace('.', '_')
															+ "." + extension + "?use_mirror=autoselect"));
		}
		catch (MalformedURLException e)
		{
			throw new MojoExecutionException("", e);
		}
		Plugin installPlugin = MojoExecutor.plugin("org.apache.maven.plugins",
			"maven-install-plugin", "2.3.1");
		Element fileElement = new Element("file", file.toAbsolutePath().toString());
		Element groupIdElement = new Element("groupId", groupId);
		Element artifactIdElement = new Element("artifactId", artifactId);
		Element versionElement = new Element("version", version);
		Element classifierElement = new Element("classifier", boostClassifier);
		Element packagingElement = new Element("packaging", "jar");
		Xpp3Dom configuration = MojoExecutor.configuration(fileElement, groupIdElement,
			artifactIdElement, versionElement, classifierElement, packagingElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(installPlugin, "install-file", configuration, environment);
	}

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
	 * Returns a local artifact.
	 *
	 * @param groupId the artifact group id
	 * @param artifactId the artifact id
	 * @param version the artifact version
	 * @param classifier the artifact classifier, empty string if there is none
	 * @return null if the artifact is not installed
	 * @throws MojoExecutionException if an error occurs while resolving the artifact
	 */
	private Artifact getArtifact(String groupId, String artifactId, String version,
															 String classifier)
		throws MojoExecutionException
	{
		Artifact artifact = repositorySystem.createArtifactWithClassifier(groupId, artifactId, version,
			"jar", classifier);
		artifact.setFile(new File(localRepository.getBasedir(), localRepository.pathOf(artifact)));
		if (!artifact.getFile().exists())
			return null;

		Log log = getLog();
		if (log.isDebugEnabled())
			log.debug("Artifact already installed: " + artifact.getFile().getAbsolutePath());
		return artifact;
	}

	/**
	 * Downloads a file.
	 *
	 * @param url the file to download
	 * @param artifact the artifact if one is already installed, otherwise null
	 * @return the downloaded File or null if the artifact is already up-to-date
	 * @throws MojoExecutionException if an error occurs downloading the file
	 */
	private Path download(URL url) throws MojoExecutionException
	{
		Log log = getLog();
		if (log.isInfoEnabled())
			log.info("Downloading: " + url.toString());
		String filename = new File(url.getPath()).getName();
		Path result = Paths.get(project.getBuild().getDirectory(), filename);
		try
		{
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
			return convertToJar(result);
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("", e);
		}
	}

	/**
	 * Converts a compressed file to JAR format, removing the top-level directory at the same
	 * time.
	 *
	 * @param path the file to convert
	 * @return the JAR file
	 * @throws IOException if an I/O error occurs
	 */
	private Path convertToJar(Path path) throws IOException
	{
		final TPath sourceFile = new TPath(path);
		String filename = path.getFileName().toString();
		String extension = getFileExtension(filename);
		String nameWithoutExtension = filename.substring(0, filename.length() - extension.length());
		String nextExtension = getFileExtension(nameWithoutExtension);
		if (!nextExtension.isEmpty())
		{
			if (nextExtension.equals(".tar"))
			{
				// BUG: http://java.net/jira/browse/TRUEZIP-219
				Iterator<Path> files = Files.newDirectoryStream(sourceFile).iterator();

				// WORKAROUND: http://java.net/jira/browse/TRUEZIP-223
				files.hasNext();

				Path tarFile;
				try
				{
					tarFile = Iterators.getOnlyElement(files);
				}
				catch (IllegalArgumentException e)
				{
					throw new IOException("File contained multiple TAR files: " + path, e);
				}
				return convertToJar(tarFile);
			}
			else
				throw new IllegalArgumentException("Unsupported extension: " + path);
		}
		Path result = Paths.get(project.getBuild().getDirectory(), nameWithoutExtension + ".jar");
		Files.deleteIfExists(result);
		final TPath targetFile = new TPath(result);

		// Strip top-level directory
		final Path rootPath;
		Iterator<Path> files = Files.newDirectoryStream(sourceFile).iterator();

		// WORKAROUND: http://java.net/jira/browse/TRUEZIP-223
		files.hasNext();

		try
		{
			rootPath = Iterators.getOnlyElement(files);
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("File contained multiple TAR files: " + path, e);
		}

		Files.walkFileTree(sourceFile, new SimpleFileVisitor<Path>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
			{
				Files.copy(file, targetFile.resolve(rootPath.relativize(file)),
					StandardCopyOption.COPY_ATTRIBUTES);
				return super.visitFile(file, attrs);
			}
		});
		targetFile.getFileSystem().sync(FsSyncOptions.UMOUNT);
		return result;
	}

	/**
	 * Returns a filename extension. For example, {@code getFileExtension("foo.tar.gz")} returns
	 * {@code .gz}
	 *
	 * @param filename the filename
	 * @return an empty string if no extension is found
	 * @throws NullArgumentException if filename is null
	 */
	private String getFileExtension(String filename)
	{
		Preconditions.checkNotNull(filename, "filename may not be null");

		int index = filename.lastIndexOf('.');

		// Unix-style ".hidden" files have no extension
		if (index <= 0)
			return "";
		return filename.substring(index);
	}
}
