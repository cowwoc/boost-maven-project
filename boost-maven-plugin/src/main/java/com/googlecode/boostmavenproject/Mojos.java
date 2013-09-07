package com.googlecode.boostmavenproject;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterators;
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
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

/**
 * Mojo helper functions.
 * <p>
 * @author Gili Tzabari
 */
public class Mojos
{
	/**
	 * Converts a project version to a boost version.
	 * <p/>
	 * @param projectVersion the project version
	 * @return the boost version
	 * @throws NullPointerException     if projectVersion is null
	 * @throws IllegalArgumentException if projectVersion is empty or has an unexpected format
	 */
	public static String projectToBoostVersion(String projectVersion)
	{
		Preconditions.checkNotNull(projectVersion, "projectVersion may not be null");
		Preconditions.checkArgument(!projectVersion.isEmpty(), "projectVersion may not be empty");

		Pattern pattern = Pattern.compile("^(.*?)-.+");
		Matcher matcher = pattern.matcher(projectVersion);
		if (!matcher.find())
			throw new IllegalArgumentException("Unexpected version format: " + projectVersion);
		return matcher.group(1);
	}

	/**
	 * Downloads a file.
	 * <p/>
	 * @param url       the file to download
	 * @param directory the directory to download into
	 * @param log the Maven logger
	 * @return the path of the downloaded file
	 * @throws MojoExecutionException if an error occurs downloading the file
	 */
	public static Path download(URL url, Path directory, Log log) throws MojoExecutionException
	{
		String filename = new File(url.getPath()).getName();
		Path result = directory.resolve(filename);
		try
		{
			if (Files.notExists(result))
			{
				if (log.isInfoEnabled())
					log.info("Downloading: " + url.toString());
				HttpURLConnection connection = (HttpURLConnection) url.openConnection();

				try
				{
					BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
					Files.createDirectories(directory);
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
	 * @param log    the Maven logger
	 * @throws IOException if an I/O error occurs
	 */
	public static void extract(Path source, Path target, Log log) throws IOException
	{
		ByteBuffer buffer = ByteBuffer.allocate(10 * 1024);
		try
		{
			extractCompressor(source, target, buffer, log);
		}
		catch (IOException e)
		{
			if (!(e.getCause() instanceof CompressorException))
				throw e;

			// Perhaps the file is an archive
			extractArchive(source, target, buffer, log);
		}
	}

	/**
	 * Extracts the contents of an archive.
	 * <p/>
	 * @param source the file to extract
	 * @param target the directory to extract to
	 * @param buffer the buffer used to transfer data from source to target
	 * @param log    the Maven logger
	 * @throws IOException if an I/O error occurs
	 */
	private static void extractArchive(Path source, Path target, ByteBuffer buffer, Log log)
		throws IOException
	{
		Path tempDir = Files.createTempDirectory("boost");
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
					log.warn("Unsupported entry type for " + entry.getName() + ", skipping...");
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
						Set<PosixFilePermission> permissions = (Set<PosixFilePermission>) attributes[0].value();
						Files.setPosixFilePermissions(directory, permissions);
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

			// Move extracted files from tempDir to target.
			// Can't use Files.move() because tempDir might reside on a different drive than target
			copyDirectory(tempDir, target);
			deleteRecursively(tempDir);
		}
		catch (ArchiveException e)
		{
			throw new IOException("Could not uncompress: " + source, e);
		}
	}

	/**
	 * Copies a directory.
	 * <p>
	 * NOTE: This method is not thread-safe.
	 * <p>
	 * @param source the directory to copy from
	 * @param target the directory to copy into
	 * @throws IOException if an I/O error occurs
	 */
	private static void copyDirectory(final Path source, final Path target) throws IOException
	{
		Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
			new FileVisitor<Path>()
			{
				@Override
				public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
				throws IOException
				{
					Files.createDirectories(target.resolve(source.relativize(dir)));
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
				{
					Files.copy(file, target.resolve(source.relativize(file)),
						StandardCopyOption.COPY_ATTRIBUTES);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException e) throws IOException
				{
					throw e;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException
				{
					if (e != null)
						throw e;
					return FileVisitResult.CONTINUE;
				}
			});
	}

	/**
	 * Extracts the contents of an archive.
	 * <p/>
	 * @param source the file to extract
	 * @param target the directory to extract to
	 * @param log    the Maven logger
	 * @throws IOException if an I/O error occurs
	 */
	private static void extractCompressor(Path source, Path target, ByteBuffer buffer, Log log)
		throws IOException
	{
		String filename = source.getFileName().toString();
		String extension = getFileExtension(filename);
		String nameWithoutExtension = filename.substring(0, filename.length() - extension.length());
		String nextExtension = getFileExtension(nameWithoutExtension);
		try (CompressorInputStream in = new CompressorStreamFactory().createCompressorInputStream(
			new BufferedInputStream(Files.newInputStream(source))))
		{
			Path tempDir = Files.createTempDirectory("boost");
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
				extract(intermediateTarget, target, log);
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
	private static boolean supportsPosix(InputStream in)
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
	private static Set<PosixFilePermission> getPosixPermissions(ArchiveEntry entry)
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
	 * Returns a filename extension. For example, {@literal getFileExtension("foo.tar.gz")} returns
	 * {@literal .gz}. Unix hidden files (e.g. ".hidden") have no extension.
	 * <p/>
	 * @param filename the filename
	 * @return an empty string if no extension is found
	 * @throws NullArgumentException if filename is null
	 */
	private static String getFileExtension(String filename)
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
	public static void normalizeDirectories(final Path source) throws IOException
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
	public static void deleteRecursively(Path path) throws IOException
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

	/**
	 * Prevent construction.
	 */
	private Mojos()
	{
	}
}
