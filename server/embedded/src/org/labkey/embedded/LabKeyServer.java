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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
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
	public ContextProperties contextSource()
	{
		return new ContextProperties();
	}

	@Bean
	public DataSourceProperties labkeyDataSource()
	{
		return new DataSourceProperties();
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

				// Get the context properties from Spring injection
				ContextProperties contextProperties = contextSource();

				// for development, point to the local deploy/labkeyWebapp directory in configs/application.properties
				boolean webAppLocationPresent = contextProperties.getWebAppLocation() != null;
				var webAppLocation = "";

				if (!webAppLocationPresent)
				{
					var currentPath = new File("").getAbsolutePath();
					var destDirectory = currentPath + "/server";
					webAppLocation = destDirectory + "/labkeywebapp";
					boolean extracted = new File(webAppLocation).exists();

					if (!extracted)
					{
						extractExecutableJar(destDirectory, currentPath);
					}
				}
				else
				{
					webAppLocation = contextProperties.getWebAppLocation();
				}

				// TODO : 8021 :fix context path - put app at root by default
				StandardContext context = (StandardContext)tomcat.addWebapp("/labkey", webAppLocation);

                // Push the JDBC connection for the primary DB into the context so that the LabKey webapp finds them
				try
				{
					getDataSourceResources().forEach(contextResource -> context.getNamingResources().addResource(contextResource));
				}
				catch (ConfigException e)
				{
					throw new RuntimeException(e);
				}
				// Add the SMTP config
				context.getNamingResources().addResource(getMailResource());

				// And the master encryption key
				context.addParameter("MasterEncryptionKey", contextProperties.getMasterEncryptionKey());

				// Point at the special classloader with the hack for SLF4J
				WebappLoader loader = new WebappLoader();
				loader.setLoaderClass(LabKeySpringBootClassLoader.class.getName());
				context.setLoader(loader);
				context.setParentClassLoader(this.getClass().getClassLoader());

				return super.getTomcatWebServer(tomcat);
			}

			private List<ContextResource> getDataSourceResources() throws ConfigException
			{
				DataSourceProperties props = labkeyDataSource();
				List<ContextResource> dataSourceResources = new ArrayList<>();
				var numOfDataResources = props.getUrl().size();

				if (numOfDataResources != props.getDataSourceName().size() ||
						numOfDataResources != props.getDriverClassName().size() ||
						numOfDataResources != props.getUsername().size() ||
						numOfDataResources != props.getPassword().size())
				{
					throw new ConfigException("DataSources not configured properly. Must have all the required properties for all datasources: dataSourceName, driverClassName, userName, password, url");
				}

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
					// TODO : 8021 move this properties to application.properties
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
				mailResource.setProperty("mail.smtp.host", mailProps.getSmtpHost());
				mailResource.setProperty("mail.smtp.user", mailProps.getSmtpUser());
				mailResource.setProperty("mail.smtp.port", mailProps.getSmtpPort());

				if (mailProps.getSmtpFrom() != null &&
					mailProps.getSmtpPassword() != null &&
					mailProps.getSmtpStartTlsEnable() != null &&
					mailProps.getSmtpSocketFactoryClass() != null &&
					mailProps.getSmtpAuth() != null)
				{
					mailResource.setProperty("mail.smtp.from", mailProps.getSmtpFrom());
					mailResource.setProperty("mail.smtp.password", mailProps.getSmtpPassword());
					mailResource.setProperty("mail.smtp.starttls.enable", mailProps.getSmtpStartTlsEnable());
					mailResource.setProperty("mail.smtp.socketFactory.class", mailProps.getSmtpSocketFactoryClass());
					mailResource.setProperty("mail.smtp.auth", mailProps.getSmtpAuth());
				}

				return mailResource;
			}
		};
	}

	private static void extractExecutableJar(String destDirectory, String currentPath)
	{
		try
		{
			// check for multiple jars and blow up if multiple are present
			try (JarFile jar = new JarFile(getExecutableJar(currentPath)))
			{
				boolean foundDistributionZip = false;
				var entries = jar.entries();
				while (entries.hasMoreElements())
				{
					var entry = entries.nextElement();
					var entryName = entry.getName();

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
		}
		catch (IOException | ConfigException e)
		{
			throw new RuntimeException(e);
		}
	}

	private static String getExecutableJar(String currentPath) throws ConfigException
	{
		try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(currentPath)))
		{
			ZipEntry entry = zipIn.getNextEntry();
			var jarCount = 0;
			var jarFileName = "";
			// iterates over entries in the current dir
			while (entry != null)
			{
				if (entry.getName().endsWith(".jar"))
				{
					jarFileName = entry.getName();
					jarCount++;
				}
				zipIn.closeEntry();
				entry = zipIn.getNextEntry();
			}
			// only 1 executable jar should be there
			if (jarCount == 1)
			{
				return jarFileName;
			}
			else
			{
				throw new ConfigException("Multiple executable jars found. Must provide only one executable jar");
			}
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
	}

	private static void extractZip(InputStream zipInputStream, String destDirectory) throws IOException
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
	@ConfigurationProperties("labkeydatasource")
	public static class DataSourceProperties
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
	}

	@Validated
	@Configuration
	@ConfigurationProperties("context")
	public static class ContextProperties
	{
		private String webAppLocation;
		@NotNull (message = "Must provide masterEncryptionKey")
		private String masterEncryptionKey;

		public String getWebAppLocation()
		{
			return webAppLocation;
		}

		public void setWebAppLocation(String webAppLocation)
		{
			this.webAppLocation = webAppLocation;
		}

		public String getMasterEncryptionKey()
		{
			return masterEncryptionKey;
		}

		public void setMasterEncryptionKey(String masterEncryptionKey)
		{
			this.masterEncryptionKey = masterEncryptionKey;
		}
	}

	@Configuration
	@ConfigurationProperties("mail")
	public static class MailProperties
	{
		private String smtpHost;
		private String smtpUser;
		private String smtpPort;
		private String smtpFrom;
		private String smtpPassword;
		private String smtpStartTlsEnable;
		private String smtpSocketFactoryClass;
		private String smtpAuth;

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

		public String getSmtpFrom()
		{
			return smtpFrom;
		}

		public void setSmtpFrom(String smtpFrom)
		{
			this.smtpFrom = smtpFrom;
		}

		public String getSmtpPassword()
		{
			return smtpPassword;
		}

		public void setSmtpPassword(String smtpPassword)
		{
			this.smtpPassword = smtpPassword;
		}

		public String getSmtpStartTlsEnable()
		{
			return smtpStartTlsEnable;
		}

		public void setSmtpStartTlsEnable(String smtpStartTlsEnable)
		{
			this.smtpStartTlsEnable = smtpStartTlsEnable;
		}

		public String getSmtpSocketFactoryClass()
		{
			return smtpSocketFactoryClass;
		}

		public void setSmtpSocketFactoryClass(String smtpSocketFactoryClass)
		{
			this.smtpSocketFactoryClass = smtpSocketFactoryClass;
		}

		public String getSmtpAuth()
		{
			return smtpAuth;
		}

		public void setSmtpAuth(String smtpAuth)
		{
			this.smtpAuth = smtpAuth;
		}
	}

}
