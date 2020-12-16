package org.labkey.embedded;

import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
					var destDirectory = "/path/to/labkey/server";
					webAppLocation = destDirectory + "/LabKey21.1-SNAPSHOT-1515-community/labkeyWebapp";
					boolean extracted = new File(webAppLocation).exists();

					if (!extracted)
					{
						try
						{
							// TODO : 8021 :replace zipFilePath with zip location and destDirectory with apt location
							var zipFilePath = "/path/to/LabKey21.1-SNAPSHOT-1515-community.zip";
							LabKeyServer.extractZip(zipFilePath, destDirectory);
						}
						catch (IOException e)
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
				context.getNamingResources().addResource(getDataSourceResource(props));
				// Add the SMTP config
				context.getNamingResources().addResource(getMailResource());

				// And the master encryption key
				context.addParameter("MasterEncryptionKey", props.masterEncryptionKey);

				// Point at the special classloader with the hack for SLF4J
				WebappLoader loader = new WebappLoader();
				loader.setLoaderClass(LabKeySpringBootClassLoader.class.getName());
				context.setLoader(loader);
				context.setParentClassLoader(this.getClass().getClassLoader());

				return super.getTomcatWebServer(tomcat);
			}

			private ContextResource getDataSourceResource(ContextProperties props)
			{
				ContextResource dataSourceResource = new ContextResource();
				dataSourceResource.setName("jdbc/labkeyDataSource");
				dataSourceResource.setAuth("Container");
				dataSourceResource.setType(DataSource.class.getName());
				dataSourceResource.setProperty("driverClassName", props.driverClassName);
				dataSourceResource.setProperty("url", props.url);
				dataSourceResource.setProperty("password", props.password);
				dataSourceResource.setProperty("username", props.username);
				dataSourceResource.setProperty("maxTotal", "20");
				dataSourceResource.setProperty("maxIdle", "10");
				dataSourceResource.setProperty("maxWaitMillis", "120000");
				dataSourceResource.setProperty("accessToUnderlyingConnectionAllowed", "true");
				dataSourceResource.setProperty("validationQuery", "SELECT 1");

				return  dataSourceResource;
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
	 * @param zipFilePath
	 * @param destDirectory
	 * @throws IOException
	 */
	public static void extractZip(String zipFilePath, String destDirectory) throws IOException
	{
		File destDir = new File(destDirectory);
		if (!destDir.exists())
		{
			destDir.mkdir();
		}
		ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
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
		zipIn.close();
	}

	private static void extractFile(ZipInputStream zipIn, String filePath) throws IOException
	{
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
		byte[] bytesIn = new byte[BUFFER_SIZE];
		int read;
		while ((read = zipIn.read(bytesIn)) != -1)
		{
			bos.write(bytesIn, 0, read);
		}
		bos.close();
	}

	@Configuration
	@ConfigurationProperties("labkey")
	public static class ContextProperties
	{

		public String url;
		public String username;
		public String password;
		public String driverClassName;
		public String masterEncryptionKey;
		public String webAppLocation;

		public String getUrl()
		{
			return url;
		}

		public void setUrl(String url)
		{
			this.url = url;
		}

		public String getUsername()
		{
			return username;
		}

		public void setUsername(String username)
		{
			this.username = username;
		}

		public String getPassword()
		{
			return password;
		}

		public void setPassword(String password)
		{
			this.password = password;
		}

		public String getDriverClassName()
		{
			return driverClassName;
		}

		public void setDriverClassName(String driverClassName)
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
