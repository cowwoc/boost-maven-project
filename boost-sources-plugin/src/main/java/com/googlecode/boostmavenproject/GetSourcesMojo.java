package com.googlecode.boostmavenproject;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Downloads and caches the Boost sources.
 * <p/>
 * @author Gili Tzabari
 */
@Mojo(name = "get-sources", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class GetSourcesMojo
	extends AbstractMojo
{
	/**
	 * The release platform.
	 */
	@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
	@Parameter(property = "classifier", required = true, readonly = true)
	private String classifier;
	@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
	@Parameter(property = "project", required = true, readonly = true)
	private MavenProject project;
	/**
	 * The project version.
	 */
	@SuppressFBWarnings("UWF_UNWRITTEN_FIELD")
	@Parameter(property = "project.version")
	private String projectVersion;

	@Override
	@SuppressFBWarnings("NP_UNWRITTEN_FIELD")
	public void execute()
		throws MojoExecutionException
	{
		final Path target = Paths.get(project.getBuild().getDirectory(), "dependency/boost");

		String extension;
		switch (classifier)
		{
			case "windows":
			{
				extension = "zip";
				break;
			}
			case "linux":
			case "mac":
			{
				extension = "tar.gz";
				break;
			}
			default:
				throw new MojoExecutionException("Unexpected classifier: " + classifier);
		}

		String boostVersion = Mojos.projectToBoostVersion(projectVersion);
		Log log = getLog();
		try
		{
			Path archive = Mojos.download(new URL(
				"http://sourceforge.net/projects/boost/files/boost/" + boostVersion +
				"/boost_" + boostVersion.replace('.', '_') + "." + extension + "?use_mirror=autoselect"),
				Paths.get(project.getBuild().getDirectory()), log);
			if (Files.notExists(target.resolve("bin")))
			{
				Mojos.deleteRecursively(target);

				// Directories not normalized, begin by unpacking the sources
				if (log.isInfoEnabled())
					log.info("Extracting " + archive + " to " + target);
				Mojos.extract(archive, target, log);
				Mojos.normalizeDirectories(target);
			}
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("", e);
		}
	}
}
