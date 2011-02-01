package com.googlecode.boostmavenproject;

import com.googlecode.boostmavenproject.MojoExecutor.Element;
import com.googlecode.boostmavenproject.MojoExecutor.ExecutionEnvironment;
import edu.umd.cs.findbugs.annotations.SuppressWarnings;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import java.net.URL;
import java.util.Collections;
import java.util.List;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginManager;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/**
 * Downloads and installs the Boost C++ library sources into the local Maven
 * repository.
 *
 * @goal get-sources
 * @phase generate-sources
 * @author Gili Tzabari
 */
public class GetSourceslMojo
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
	 * The maven plugin manager.
	 *
	 * @component
	 */
	@SuppressWarnings("UWF_UNWRITTEN_FIELD")
	private PluginManager pluginManager;
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
	private ArtifactResolver artifactResolver;
	/**
	 * @component
	 */
	private ArtifactFactory artifactFactory;
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
		throws MojoExecutionException, MojoFailureException
	{
		final String groupId = "com.googlecode.boost-maven-project";
		final String artifactId = "boost-sources";
		Artifact artifact = getArtifact(groupId, artifactId, version, "sources");

		File file;
		try
		{
			file = download(new URL("http://sourceforge.net/projects/boost/files/boost/" + version
															+ "/boost_" + version.replace('.', '_')
															+ ".zip?use_mirror=autoselect"), artifact);
			if (file == null)
				return;
		}
		catch (MalformedURLException e)
		{
			throw new MojoFailureException("", e);
		}
		catch (IOException e)
		{
			throw new MojoFailureException("", e);
		}
		Plugin installPlugin = MojoExecutor.plugin("org.apache.maven.plugins",
			"maven-install-plugin", "2.3.1");
		Element fileElement = new Element("file", file.getAbsolutePath());
		Element groupIdElement = new Element("groupId", groupId);
		Element artifactIdElement = new Element("artifactId", artifactId);
		Element versionElement = new Element("version", version);
		Element classifierElement = new Element("classifier", "sources");
		Element packagingElement = new Element("packaging", "zip");
		Xpp3Dom configuration = MojoExecutor.configuration(fileElement, groupIdElement,
			artifactIdElement, versionElement, classifierElement, packagingElement);
		ExecutionEnvironment environment = MojoExecutor.executionEnvironment(project, session,
			pluginManager);
		MojoExecutor.executeMojo(installPlugin, "install-file", configuration, environment);
		Log log = getLog();
		if (file.exists() && !file.delete() && log.isWarnEnabled())
			log.warn("Cannot delete " + file.getAbsolutePath());
	}

	/**
	 * Returns an artifact.
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
		Artifact artifact = artifactFactory.createArtifactWithClassifier(groupId, artifactId, version,
			"zip", classifier);
		try
		{
			artifactResolver.resolve(artifact, Collections.emptyList(), localRepository);
		}
		catch (ArtifactResolutionException e)
		{
			throw new MojoExecutionException("", e);
		}
		catch (ArtifactNotFoundException e)
		{
			return null;
		}

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
	private File download(URL url, Artifact artifact) throws MojoExecutionException
	{
		Log log = getLog();
		if (log.isInfoEnabled())
			log.info("Downloading: " + url.toString());
		File result;
		try
		{
			HttpURLConnection connection = (HttpURLConnection) url.openConnection();

			if (artifact != null)
			{
				List<String> contentLengths = connection.getHeaderFields().get("Content-Length");
				if (contentLengths != null)
				{
					long downloadSize = Long.parseLong(contentLengths.get(0));
					long artifactSize = artifact.getFile().length();
					if (log.isDebugEnabled())
						log.debug("downloadSize=" + downloadSize + ", artifactSize=" + artifactSize);
					if (artifactSize == downloadSize)
					{
						if (log.isDebugEnabled())
							log.debug("Artifact already up-to-date");
						return null;
					}
				}
			}
			try
			{
				BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
				result = File.createTempFile("boost", "zip");
				if (log.isDebugEnabled())
					log.debug("tempFile: " + result.getAbsolutePath());
				BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(
					result));
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
				return result;
			}
			finally
			{
				connection.disconnect();
			}
		}
		catch (IOException e)
		{
			throw new MojoExecutionException("", e);
		}
	}
}
