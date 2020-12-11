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
	public TomcatServletWebServerFactory servletContainerFactory()
	{
		return new TomcatServletWebServerFactory()
		{

			@Override
			protected TomcatWebServer getTomcatWebServer(Tomcat tomcat)
			{
				tomcat.enableNaming();
				// Get the minimum properties from Spring injection
				ContextProperties props = labkeyDataSource();

				// for development, point to the local deploy/labkeyWebapp directory
				boolean webAppLocationPresent = props.getWebAppLocation() != null;
				var webAppLocation = "";

				if (!webAppLocationPresent)
				{
					try
					{
						// TODO : 8021 :replace zipFilePath with zip location and destDirectory with apt location
						var zipFilePath = "/Users/ankurjuneja/Downloads/LabKey21.1-SNAPSHOT-1515-community.zip";
						var destDirectory = "/Users/ankurjuneja/labkey/server";
						LabKeyServer.extractZip(zipFilePath, destDirectory);
						webAppLocation = destDirectory + "/LabKey21.1-SNAPSHOT-1515-community/labkeyWebapp";
					}
					catch (IOException e)
					{
						throw new RuntimeException(e);
					}
				}
				else
				{
					webAppLocation = props.getWebAppLocation();
				}

				StandardContext context = (StandardContext)tomcat.addWebapp("", webAppLocation);

                // Add the JDBC connection for the primary DB
                ContextResource resource = new ContextResource();
				resource.setName("jdbc/labkeyDataSource");
				resource.setType(DataSource.class.getName());
				resource.setProperty("driverClassName", props.driverClassName);
				resource.setProperty("url", props.url);
				resource.setProperty("password", props.password);
				resource.setProperty("username", props.username);

				// Push the properties into the context so that the LabKey webapp finds them in the expected spot
				context.getNamingResources().addResource(resource);

				// And the master encryption key
				context.addParameter("MasterEncryptionKey", props.masterEncryptionKey);

				// Point at the special classloader with the hack for SLF4J
				WebappLoader loader = new WebappLoader();
				loader.setLoaderClass(LabKeySpringBootClassLoader.class.getName());
				context.setLoader(loader);
				context.setParentClassLoader(this.getClass().getClassLoader());

				return super.getTomcatWebServer(tomcat);
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

}
