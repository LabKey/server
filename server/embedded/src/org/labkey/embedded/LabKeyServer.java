package org.labkey.embedded;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.labkey.bootstrap.ConfigException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import javax.sql.DataSource;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SpringBootApplication
public class LabKeyServer
{
	private static final int BUFFER_SIZE = 4096;

	public static void main(String[] args)
	{
		ApplicationContext ctx = SpringApplication.run(LabKeyServer.class, args);
	}

	@Bean
	public ContextProperties labkeyDataSource()
	{
		return new ContextProperties();
	}

	@Bean
	public MailProperties smtpSource()
	{
		return new MailProperties();
	}

	@Bean
	public TomcatServletWebServerFactory servletContainerFactory()
	{
		return new TomcatServletWebServerFactory()
		{
			@Override
			protected TomcatWebServer getTomcatWebServer(Tomcat tomcat)
			{
				tomcat.enableNaming();

				// Get the datasource properties from Spring injection
				ContextProperties props = labkeyDataSource();

				// for development, point to the local deploy/labkeyWebapp directory
				boolean webAppLocationPresent = props.getWebAppLocation() != null;
				var webAppLocation = "";

				if (!webAppLocationPresent)
				{
					var destDirectory = new File("").getAbsolutePath() + "/server";
					webAppLocation = destDirectory + "/labkeywebapp";
					boolean extracted = new File(webAppLocation).exists();

					if (!extracted)
					{
						try (JarFile jar = new JarFile("labkeyServer-21.1-SNAPSHOT.jar"))
						{
							boolean foundDistributionZip = false;
							Enumeration<JarEntry> entries = jar.entries();
							while (entries.hasMoreElements())
							{
								JarEntry entry = entries.nextElement();
								String entryName = entry.getName();

								if ("labkey/distribution.zip".equals(entryName))
								{
									foundDistributionZip = true;
									try (var distInputStream = jar.getInputStream(entry))
									{
										LabKeyServer.extractZip(distInputStream, destDirectory);
									}
								}
							}

							if (!foundDistributionZip)
							{
								throw new ConfigException("Unable to find distribution zip required to run LabKey server.");
							}
						}
						catch (IOException | ConfigException e)
						{
							throw new RuntimeException(e);
						}
					}
				}
				else
				{
					webAppLocation = props.getWebAppLocation();
				}

				// TODO : 8021 :fix context path - put app at root by default
				StandardContext context = (StandardContext)tomcat.addWebapp("/labkey", webAppLocation);

                // Push the JDBC connection for the primary DB into the context so that the LabKey webapp finds them
				getDataSourceResources(props).forEach(contextResource -> context.getNamingResources().addResource(contextResource));
				// Add the SMTP config
				context.getNamingResources().addResource(getMailResource());

				// And the master encryption key
				context.addParameter("MasterEncryptionKey", props.getMasterEncryptionKey());

				// Point at the special classloader with the hack for SLF4J
				WebappLoader loader = new WebappLoader();
				loader.setLoaderClass(LabKeySpringBootClassLoader.class.getName());
				context.setLoader(loader);
				context.setParentClassLoader(this.getClass().getClassLoader());

				return super.getTomcatWebServer(tomcat);
			}

			private List<ContextResource> getDataSourceResources(ContextProperties props)
			{
				List<ContextResource> dataSourceResources = new ArrayList<>();
				var numOfDataResources = props.getUrl().size();

				for (int i = 0; i < numOfDataResources; i++)
				{
					ContextResource dataSourceResource = new ContextResource();
					dataSourceResource.setName(props.getDataSourceName().get(i));
					dataSourceResource.setAuth("Container");
					dataSourceResource.setType(DataSource.class.getName());
					dataSourceResource.setProperty("driverClassName", props.getDriverClassName().get(i));
					dataSourceResource.setProperty("url", props.getUrl().get(i));
					dataSourceResource.setProperty("password", props.getPassword().get(i));
					dataSourceResource.setProperty("username", props.getUsername().get(i));
					dataSourceResource.setProperty("maxTotal", "20");
					dataSourceResource.setProperty("maxIdle", "10");
					dataSourceResource.setProperty("maxWaitMillis", "120000");
					dataSourceResource.setProperty("accessToUnderlyingConnectionAllowed", "true");
					dataSourceResource.setProperty("validationQuery", "SELECT 1");
					dataSourceResources.add(dataSourceResource);
				}

				return  dataSourceResources;
			}

			private ContextResource getMailResource()
			{
				// Get session/mail properties
				MailProperties mailProps = smtpSource();
				ContextResource mailResource = new ContextResource();
				mailResource.setName("mail/Session");
				mailResource.setAuth("Container");
				mailResource.setType("javax.mail.Session");
				mailResource.setProperty("mail.smtp.host", mailProps.smtpHost);
				mailResource.setProperty("mail.smtp.user", mailProps.smtpUser);
				mailResource.setProperty("mail.smtp.port", mailProps.smtpPort);
				return mailResource;
			}
		};
	}

	/**
	 * Extracts a zip file specified by the zipFilePath to a directory specified by
	 * destDirectory (will be created if does not exists)
	 * @param zipInputStream
	 * @param destDirectory
	 * @throws IOException
	 */
	public static void extractZip(InputStream zipInputStream, String destDirectory) throws IOException
	{
		File destDir = new File(destDirectory);
		if (!destDir.exists())
		{
			destDir.mkdir();
		}
		try (ZipInputStream zipIn = new ZipInputStream(zipInputStream))
		{
			ZipEntry entry = zipIn.getNextEntry();
			// iterates over entries in the zip file
			while (entry != null)
			{
				String filePath = destDirectory + File.separator + entry.getName();
				if (!entry.isDirectory())
				{
					// if the entry is a file, extracts it
					extractFile(zipIn, filePath);
				}
				else
				{
					// if the entry is a directory, make the directory
					File dir = new File(filePath);
					dir.mkdirs();
				}
				zipIn.closeEntry();
				entry = zipIn.getNextEntry();
			}
		}
	}

	private static void extractFile(ZipInputStream zipIn, String filePath) throws IOException
	{
		try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath)))
		{
			byte[] bytesIn = new byte[BUFFER_SIZE];
			int read;
			while ((read = zipIn.read(bytesIn)) != -1)
			{
				bos.write(bytesIn, 0, read);
			}
		}
	}

	@Validated
	@Configuration
	@ConfigurationProperties("labkey")
	public static class ContextProperties
	{
		@NotEmpty (message = "Must provide dataSourceName")
		private List<String> dataSourceName;
		@NotEmpty (message = "Must provide database url")
		private List<String> url;
		@NotEmpty (message = "Must provide database username")
		private List<String> username;
		@NotEmpty (message = "Must provide database password")
		private List<String> password;
		@NotEmpty (message = "Must provide database driverClassName")
		private List<String> driverClassName;
		@NotNull (message = "Must provide masterEncryptionKey")
		private String masterEncryptionKey;
		private String webAppLocation;

		public List<String> getDataSourceName()
		{
			return dataSourceName;
		}

		public void setDataSourceName(List<String> dataSourceName)
		{
			this.dataSourceName = dataSourceName;
		}

		public List<String> getUrl()
		{
			return url;
		}

		public void setUrl(List<String> url)
		{
			this.url = url;
		}

		public List<String> getUsername()
		{
			return username;
		}

		public void setUsername(List<String> username)
		{
			this.username = username;
		}

		public List<String> getPassword()
		{
			return password;
		}

		public void setPassword(List<String> password)
		{
			this.password = password;
		}

		public List<String> getDriverClassName()
		{
			return driverClassName;
		}

		public void setDriverClassName(List<String> driverClassName)
		{
			this.driverClassName = driverClassName;
		}

		public String getMasterEncryptionKey()
		{
			return masterEncryptionKey;
		}

		public void setMasterEncryptionKey(String masterEncryptionKey)
		{
			this.masterEncryptionKey = masterEncryptionKey;
		}

		public String getWebAppLocation()
		{
			return webAppLocation;
		}

		public void setWebAppLocation(String webAppLocation)
		{
			this.webAppLocation = webAppLocation;
		}
	}

	@Configuration
	@ConfigurationProperties("mail")
	public static class MailProperties
	{
		public String smtpHost;
		public String smtpUser;
		public String smtpPort;

		public String getSmtpHost()
		{
			return smtpHost;
		}

		public void setSmtpHost(String smtpHost)
		{
			this.smtpHost = smtpHost;
		}

		public String getSmtpUser()
		{
			return smtpUser;
		}

		public void setSmtpUser(String smtpUser)
		{
			this.smtpUser = smtpUser;
		}

		public String getSmtpPort()
		{
			return smtpPort;
		}

		public void setSmtpPort(String smtpPort)
		{
			this.smtpPort = smtpPort;
		}
	}

}
