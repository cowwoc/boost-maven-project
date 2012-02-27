package com.googlecode.boostmavenproject;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.lang.NullArgumentException;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.RepositorySystem;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

/**
 * Compiles the Boost C++ library and installs it into the local Maven repository.
 *
 * @goal generate-binaries
 * @phase compile
 *
 * @author Gili Tzabari
 */
public class GenerateBinariesMojo
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
		final String artifactId = "boost-binaries";
		PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().
			get("pluginDescriptor");
		String version = getBoostVersion(pluginDescriptor.getVersion());
		Artifact artifact = getArtifact(groupId, artifactId, version, boostClassifier);
		if (artifact != null)
			return;
		String extension;
		List<String> bootstrapCommand;

		String addressModel;
		if (boostClassifier.contains("-i386-"))
			addressModel = "32";
		else if (boostClassifier.contains("-amd64"))
			addressModel = "64";
		else
			throw new MojoExecutionException("Unexpected boost.classifier: " + boostClassifier);
		Runtime runtime = Runtime.getRuntime();

		// --hash prevents the output path from exceeding the 255-character filesystem limit
		// REFERENCE: https://svn.boost.org/trac/boost/ticket/5155
		LinkedList<String> bjamCommand = Lists.newLinkedList(Lists.newArrayList(
			"address-model=" + addressModel, "--stagedir=.", "--without-python",
			"--without-mpi", "--layout=versioned", "--build-type=complete", "stage", "-j", 
			String.valueOf(runtime.availableProcessors()), "--hash"));

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
			deleteRecursively(buildPath);
			Path result = download(new URL("http://sourceforge.net/projects/boost/files/boost/" + version
				+ "/boost_" + version.replace('.', '_') + "." + extension + "?use_mirror=autoselect"));
			extract(result, buildPath);
			normalizeDirectories(buildPath);
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
		{
			// Skip empty values.
			//
			// WORKAROUND: http://jira.codehaus.org/browse/MNG-5248
			String value = entry.getValue();
			if (!value.isEmpty())
				environmentVariables.add(new Element(entry.getKey(), entry.getValue()));
		}
		Element environmentVariablesElement = new Element("environmentVariables", environmentVariables.
			toArray(new Element[0]));

		Xpp3Dom configuration = MojoExecutor.configuration(executableElement, workingDirectoryElement,
			argumentsElement, environmentVariablesElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(execPlugin, "exec", configuration, environment);
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
	private org.apache.maven.artifact.Artifact getArtifact(String groupId, String artifactId,
		String version,
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
	 * Extracts the contents of an archive.
	 *
	 * @param source the file to extract
	 * @param target the directory to extract to
	 * @throws IOException if an I/O error occurs
	 */
	private void extract(Path source, Path target) throws IOException
	{
		Files.createDirectories(target);
		String filename = source.getFileName().toString();
		String extension = getFileExtension(filename);
		String nameWithoutExtension = filename.substring(0, filename.length() - extension.length());
		String nextExtension = getFileExtension(nameWithoutExtension);
		switch (extension)
		{
			case ".zip":
			{
				if (!nextExtension.isEmpty())
					throw new UnsupportedOperationException("Unsupported file type: " + source);

				extractZip(source, target);
				break;
			}
			case ".gz":
			{
				if (!nextExtension.isEmpty())
				{
					Path outputDir = Files.createTempDirectory("cmake");
					Path result = extractGzip(source, outputDir);
					extract(result, target);
					Files.deleteIfExists(result);
					Files.deleteIfExists(outputDir);
				}
				else
					extractGzip(source, target);
				break;
			}
			case ".tar":
			{
				if (!nextExtension.isEmpty())
					throw new UnsupportedOperationException("Unsupported file type: " + source);
				extractTar(source, target);
				break;
			}
			default:
				throw new UnsupportedOperationException("Unsupported file type: " + source);
		}
	}

	/**
	 * Extracts a zip file.
	 *
	 * @param source the source file
	 * @param target the target directory
	 * @throws IOException if an I/O error occurs
	 */
	private void extractZip(Path source, Path target) throws IOException
	{
		ZipFile zipFile = new ZipFile(source.toFile());
		try
		{
			final byte[] buffer = new byte[10 * 1024];
			Enumeration<ZipArchiveEntry> entries = zipFile.getEntriesInPhysicalOrder();
			while (entries.hasMoreElements())
			{
				ZipArchiveEntry entry = entries.nextElement();
				try (InputStream in = zipFile.getInputStream(entry))
				{
					try (OutputStream out = Files.newOutputStream(target.resolve(entry.getName())))
					{
						while (true)
						{
							int count = in.read(buffer);
							if (count == -1)
								break;
							out.write(buffer, 0, count);
						}
					}
				}
			}
		}
		finally
		{
			zipFile.close();
		}
	}

	/**
	 * Extracts a tar file.
	 *
	 * @param source the source file
	 * @param target the target directory
	 * @throws IOException if an I/O error occurs
	 */
	private void extractTar(Path source, Path target) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);
		try (TarArchiveInputStream in = new TarArchiveInputStream(Files.newInputStream(source)))
		{
			while (true)
			{
				TarArchiveEntry entry = in.getNextTarEntry();
				if (entry == null)
					break;
				FileAttribute<Set<PosixFilePermission>> attribute =
					PosixFilePermissions.asFileAttribute(getPosixPermissions(entry.getMode()));
				if (entry.isDirectory())
				{
					Path directory = target.resolve(entry.getName());
					Files.createDirectories(directory);

					Files.setPosixFilePermissions(directory, attribute.value());
					continue;
				}
				ReadableByteChannel reader = Channels.newChannel(in);
				Path targetFile = target.resolve(entry.getName());

				// Omitted directories are created using the default permissions
				Files.createDirectories(targetFile.getParent());

				try (SeekableByteChannel out = Files.newByteChannel(targetFile,
						ImmutableSet.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
						StandardOpenOption.WRITE), attribute))
				{
					long bytesLeft = entry.getSize();
					while (bytesLeft > 0)
					{
						if (bytesLeft < buffer.limit())
							buffer.limit((int) bytesLeft);
						int count = reader.read(buffer);
						if (count == -1)
							break;
						buffer.flip();
						do
						{
							out.write(buffer);
						}
						while (buffer.hasRemaining());
						buffer.clear();
						bytesLeft -= count;
					}
				}
			}
		}
	}

	/**
	 * Converts an integer mode to a set of PosixFilePermissions.
	 *
	 * @param mode the integer mode
	 * @return the PosixFilePermissions
	 * @see http://stackoverflow.com/a/9445853/14731
	 */
	private Set<PosixFilePermission> getPosixPermissions(int mode)
	{
		StringBuilder result = new StringBuilder(9);

		// Extract digits from left to right
		//
		// REFERENCE: http://stackoverflow.com/questions/203854/how-to-get-the-nth-digit-of-an-integer-with-bit-wise-operations
		for (int i = 3; i >= 1; --i)
		{
			// Octal is base-8
			mode %= Math.pow(8, i);
			int digit = (int) (mode / Math.pow(8, i - 1));
			if ((digit & 0b0000_0100) != 0)
				result.append("r");
			else
				result.append("-");
			if ((digit & 0b0000_0010) != 0)
				result.append("w");
			else
				result.append("-");
			if ((digit & 0b0000_0001) != 0)
				result.append("x");
			else
				result.append("-");
		}
		return PosixFilePermissions.fromString(result.toString());
	}

	/**
	 * Extracts a Gzip file.
	 *
	 * @param source the source file
	 * @param target the target directory
	 * @return the output file
	 * @throws IOException if an I/O error occurs
	 */
	private Path extractGzip(Path source, Path target) throws IOException
	{
		String filename = source.getFileName().toString();
		String extension = getFileExtension(filename);
		String nameWithoutExtension = filename.substring(0, filename.length() - extension.length());
		Path outPath = target.resolve(nameWithoutExtension);
		try (GzipCompressorInputStream in = new GzipCompressorInputStream(Files.newInputStream(
				source)))
		{
			try (OutputStream out = Files.newOutputStream(outPath))
			{
				final byte[] buffer = new byte[10 * 1024];
				while (true)
				{
					int count = in.read(buffer);
					if (count == -1)
						break;
					out.write(buffer, 0, count);
				}
			}
		}
		return outPath;
	}

	/**
	 * Returns a filename extension. For example, {@code getFileExtension("foo.tar.gz")} returns
	 * {@code .gz}. Unix hidden files (e.g. ".hidden") have no extension.
	 *
	 * @param filename the filename
	 * @return an empty string if no extension is found
	 * @throws NullArgumentException if filename is null
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
		final Path topDirectory = Iterators.getOnlyElement(Files.newDirectoryStream(source).
			iterator());
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

	/**
	 * Deletes a path recursively.
	 *
	 * @param path the path to delete
	 * @throws IOException if an I/O error occurs
	 */
	private void deleteRecursively(Path path) throws IOException
	{
		// This method is vulnerable to race-conditions but it's the best we can do.
		//
		// BUG: http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=7148952
		if (Files.notExists(path))
			return;
		Files.walkFileTree(path, new SimpleFileVisitor<Path>()
		{
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
			{
				Files.deleteIfExists(file);
				return super.visitFile(file, attrs);
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException t) throws IOException
			{
				if (t == null)
					Files.deleteIfExists(dir);
				return super.postVisitDirectory(dir, t);
			}
		});
	}
}
