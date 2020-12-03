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

@SpringBootApplication
public class LabKeyServer
{

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
				StandardContext context = (StandardContext)tomcat.addWebapp("/labkey", "/Users/ankurjuneja/labkey/server/build/deploy/labkeyWebapp");
                // Could continue to use labkey.xml/ROOT.xml but we presumably want to push them all into application.properties
                // or the environment variables
//				try
//				{
//					context.setConfigFile(new File("/Users/ankurjuneja/apps/apache-tomcat-9.0.22/conf/Catalina/localhost/labkey.xml").toURI().toURL());
//				}
//				catch (MalformedURLException e)
//				{
//					throw new RuntimeException(e);
//				}


                // Get the minimum properties from Spring injection
				ContextProperties props = labkeyDataSource();

				// Push them into the context so that the LabKey webapp finds them in the expected spot

                // Add the JDBC connection for the primary DB
                ContextResource resource = new ContextResource();
				resource.setName("jdbc/labkeyDataSource");
				resource.setType(DataSource.class.getName());
				resource.setProperty("driverClassName", props.driverClassName);
				resource.setProperty("url", props.url);
				resource.setProperty("password", props.password);
				resource.setProperty("username", props.username);
				context.getNamingResources().addResource(resource);

				// And the master encryption key
//				context.addParameter("MasterEncryptionKey", props.masterEncryptionKey);

				// Point at the special classloader with the hack for SLF4J
				WebappLoader loader = new WebappLoader();
				loader.setLoaderClass(LabKeySpringBootClassLoader.class.getName());
				context.setLoader(loader);

				return super.getTomcatWebServer(tomcat);
			}
		};
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
	}

}
