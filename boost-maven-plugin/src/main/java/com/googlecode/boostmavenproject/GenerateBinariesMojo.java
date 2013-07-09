package com.googlecode.boostmavenproject;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveException;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.twdata.maven.mojoexecutor.MojoExecutor;
import org.twdata.maven.mojoexecutor.MojoExecutor.Element;
import org.twdata.maven.mojoexecutor.MojoExecutor.ExecutionEnvironment;

/**
 * Compiles the Boost C++ library and installs it into the local Maven repository.
 * <p/>
 * @goal generate-binaries
 * @phase compile
 * @author Gili Tzabari
 */
public class GenerateBinariesMojo
	extends AbstractMojo
{
	/**
	 * Converts the plugin version to the Boost version.
	 * <p/>
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
	 * <p/>
	 * @parameter expression="${classifier}"
	 * @required
	 * @readonly
	 */
	@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
	private String classifier;
	/**
	 * Extra arguments to pass to the build process.
	 * <p/>
	 * @parameter
	 */
	private List<String> arguments;
	/**
	 * @component
	 */
	@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
	private BuildPluginManager pluginManager;
	/**
	 * @parameter expression="${project}"
	 * @required
	 * @readonly
	 */
	@SuppressFBWarnings(
		{
			"UWF_UNWRITTEN_FIELD", "NP_UNWRITTEN_FIELD"
		})
	private MavenProject project;
	/**
	 * @parameter expression="${session}"
	 * @required
	 * @readonly
	 */
	@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
	private MavenSession session;

	@Override
	@SuppressFBWarnings("NP_UNWRITTEN_FIELD")
	public void execute()
		throws MojoExecutionException, MojoFailureException
	{
		PluginDescriptor pluginDescriptor = (PluginDescriptor) getPluginContext().
			get("pluginDescriptor");
		String version = getBoostVersion(pluginDescriptor.getVersion());
		String extension;
		List<String> bootstrapCommand;

		String addressModel;
		if (classifier.contains("-i386-"))
			addressModel = "32";
		else if (classifier.contains("-amd64-"))
			addressModel = "64";
		else
			throw new MojoExecutionException("Unexpected boost.classifier: " + classifier);

		String buildMode;
		if (classifier.contains("-debug"))
			buildMode = "debug";
		else if (classifier.contains("-release"))
			buildMode = "release";
		else
			throw new MojoExecutionException("Unexpected boost.classifier: " + classifier);
		Runtime runtime = Runtime.getRuntime();

		// --hash prevents the output path from exceeding the 255-character filesystem limit
		// REFERENCE: https://svn.boost.org/trac/boost/ticket/5155
		//
		// boost-context fails to build under OSX using version 1.53.0. Version 1.54.0 seems to work,
		// but fails later on due to https://svn.boost.org/trac/boost/ticket/8800
		LinkedList<String> bjamCommand = Lists.newLinkedList(Lists.newArrayList(
			"address-model=" + addressModel, "--stagedir=.", "--without-python",
			"--without-mpi", "--without-context", "--layout=system",
			"variant=" + buildMode, "link=shared", "threading=multi", "runtime-link=shared", "stage", "-j",
			String.valueOf(runtime.availableProcessors()), "--hash"));

		if (classifier.startsWith("windows-"))
		{
			extension = "zip";
			bootstrapCommand = ImmutableList.of("cmd.exe", "/c", "bootstrap.bat");
			bjamCommand.addAll(0, ImmutableList.of("cmd.exe", "/c", "b2"));
		}
		else if (classifier.startsWith("linux-") || classifier.startsWith("mac-"))
		{
			extension = "tar.gz";
			bootstrapCommand = ImmutableList.of("./bootstrap.sh");
			bjamCommand.addAll(0, ImmutableList.of("./bjam"));
		}
		else
			throw new MojoExecutionException("Unexpected classifier: " + classifier);

		Path target = Paths.get(project.getBuild().getDirectory(), "dependency/boost");
		try
		{
			Path archive = download(new URL(
				"http://sourceforge.net/projects/boost/files/boost/" + version +
				"/boost_" + version.replace('.', '_') + "." + extension + "?use_mirror=autoselect"));
			if (Files.notExists(target.resolve("lib")))
			{
				deleteRecursively(target);

				// Directories not normalized, begin by unpacking the binaries
				extract(archive, target);
				normalizeDirectories(target);
			}
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("", e);
		}

		// Build boost
		exec(new ProcessBuilder(bootstrapCommand).directory(target.toFile()));
		exec(new ProcessBuilder(bjamCommand).directory(target.toFile()));
	}

	/**
	 * Executes an external command.
	 * <p/>
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

		Element argumentsElement = new Element("arguments", argumentsList.toArray(
			new Element[argumentsList.size()]));
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
			toArray(new Element[environmentVariables.size()]));

		Xpp3Dom configuration = MojoExecutor.configuration(executableElement, workingDirectoryElement,
			argumentsElement, environmentVariablesElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(execPlugin, "exec", configuration, environment);
	}

	/**
	 * Downloads a file.
	 * <p/>
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
	 * <p/>
	 * @param source the file to extract
	 * @param target the directory to extract to
	 * @throws IOException if an I/O error occurs
	 */
	private void extract(Path source, Path target) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);
		try
		{
			extractCompressor(source, target, buffer);
		}
		catch (IOException e)
		{
			if (!(e.getCause() instanceof CompressorException))
				throw e;

			// Perhaps the file is an archive
			extractArchive(source, target, buffer);
		}
	}

	/**
	 * Extracts the contents of an archive.
	 * <p/>
	 * @param source the file to extract
	 * @param target the directory to extract to
	 * @param buffer the buffer used to transfer data from source to target
	 * @throws IOException if an I/O error occurs
	 */
	private void extractArchive(Path source, Path target, ByteBuffer buffer) throws IOException
	{
		Path tempDir = Files.createTempDirectory("cmake");
		FileAttribute<?>[] attributes;
		try (ArchiveInputStream in = new ArchiveStreamFactory().createArchiveInputStream(
			new BufferedInputStream(Files.newInputStream(source))))
		{
			if (supportsPosix(in))
				attributes = new FileAttribute<?>[1];
			else
				attributes = new FileAttribute<?>[0];
			while (true)
			{
				ArchiveEntry entry = in.getNextEntry();
				if (entry == null)
					break;
				if (!in.canReadEntryData(entry))
				{
					getLog().warn("Unsupported entry type for " + entry.getName() + ", skipping...");
					in.skip(entry.getSize());
					continue;
				}
				if (attributes.length > 0)
					attributes[0] = PosixFilePermissions.asFileAttribute(getPosixPermissions(entry));
				if (entry.isDirectory())
				{
					Path directory = tempDir.resolve(entry.getName());
					Files.createDirectories(directory);

					if (attributes.length > 0)
					{
						@SuppressWarnings("unchecked")
						Set<PosixFilePermission> temp = (Set<PosixFilePermission>) attributes[0].value();
						Files.setPosixFilePermissions(directory, temp);
					}
					continue;
				}
				ReadableByteChannel reader = Channels.newChannel(in);
				Path targetFile = tempDir.resolve(entry.getName());

				// Omitted directories are created using the default permissions
				Files.createDirectories(targetFile.getParent());

				try (SeekableByteChannel out = Files.newByteChannel(targetFile,
					ImmutableSet.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
					StandardOpenOption.WRITE), attributes))
				{
					while (true)
					{
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
					}
				}
			}
			Files.createDirectories(target.getParent());
			Files.move(tempDir, target);
		}
		catch (ArchiveException e)
		{
			throw new IOException("Could not uncompress: " + source, e);
		}
	}

	/**
	 * Extracts the contents of an archive.
	 * <p/>
	 * @param source the file to extract
	 * @param target the directory to extract to
	 * @throws IOException if an I/O error occurs
	 */
	private void extractCompressor(Path source, Path target, ByteBuffer buffer) throws IOException
	{
		String filename = source.getFileName().toString();
		String extension = getFileExtension(filename);
		String nameWithoutExtension = filename.substring(0, filename.length() - extension.length());
		String nextExtension = getFileExtension(nameWithoutExtension);
		try (CompressorInputStream in = new CompressorStreamFactory().createCompressorInputStream(
			new BufferedInputStream(Files.newInputStream(source))))
		{
			Path tempDir = Files.createTempDirectory("cmake");
			ReadableByteChannel reader = Channels.newChannel(in);
			Path intermediateTarget = tempDir.resolve(nameWithoutExtension);

			try (SeekableByteChannel out = Files.newByteChannel(intermediateTarget,
				ImmutableSet.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
				StandardOpenOption.WRITE)))
			{
				while (true)
				{
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
				}
			}
			if (!nextExtension.isEmpty())
			{
				extract(intermediateTarget, target);
				deleteRecursively(tempDir);
			}
			else
			{
				Files.createDirectories(target.getParent());
				Files.move(tempDir, target);
			}
		}
		catch (CompressorException e)
		{
			throw new IOException("Could not uncompress: " + source, e);
		}
	}

	/**
	 * @param in the InputStream associated with the archive
	 * @return true if the platform and archive supports POSIX attributes
	 */
	private boolean supportsPosix(InputStream in)
	{
		return !System.getProperty("os.name").toLowerCase().startsWith("windows") &&
			(in instanceof ArchiveInputStream || in instanceof ZipArchiveInputStream ||
			in instanceof TarArchiveInputStream);
	}

	/**
	 * Converts an integer mode to a set of PosixFilePermissions.
	 * <p/>
	 * @param entry the archive entry
	 * @return the PosixFilePermissions, or null if the default permissions should be used
	 * @see http://stackoverflow.com/a/9445853/14731
	 */
	private Set<PosixFilePermission> getPosixPermissions(ArchiveEntry entry)
	{
		int mode;
		if (entry instanceof ArArchiveEntry)
		{
			ArArchiveEntry arEntry = (ArArchiveEntry) entry;
			mode = arEntry.getMode();
		}
		else if (entry instanceof ZipArchiveEntry)
		{
			ZipArchiveEntry zipEntry = (ZipArchiveEntry) entry;
			mode = zipEntry.getUnixMode();
		}
		else if (entry instanceof TarArchiveEntry)
		{
			TarArchiveEntry tarEntry = (TarArchiveEntry) entry;
			mode = tarEntry.getMode();
		}
		else
		{
			throw new IllegalArgumentException(entry.getClass().getName() +
				" does not support POSIX permissions");
		}
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
	 * Returns a filename extension. For example, {@code getFileExtension("foo.tar.gz")} returns
	 * {@code .gz}. Unix hidden files (e.g. ".hidden") have no extension.
	 * <p/>
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
	 * <p/>
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
		deleteRecursively(topDirectory);
	}

	/**
	 * Deletes a path recursively.
	 * <p/>
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
