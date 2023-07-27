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
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SpringBootApplication
public class LabKeyServer
{
    private static final int BUFFER_SIZE = 4096;
    private static final String TERMINATE_ON_STARTUP_FAILURE = "terminateOnStartupFailure";
    private static final String SERVER_GUID = "serverGUID";
    private static final String SERVER_GUID_PARAMETER_NAME = "org.labkey.mothership." + SERVER_GUID;
    private static final String MAX_TOTAL_CONNECTIONS_DEFAULT = "50";
    private static final String MAX_IDLE_DEFAULT = "10";
    private static final String MAX_WAIT_MILLIS_DEFAULT = "120000";
    private static final String ACCESS_TO_CONNECTION_ALLOWED_DEFAULT = "true";
    private static final String VALIDATION_QUERY_DEFAULT = "SELECT 1";

    public static void main(String[] args)
    {
        // Issue 40038: Ride-or-die Mode - default to shutting down by default in embedded deployment scenario
        if (System.getProperty(TERMINATE_ON_STARTUP_FAILURE) == null)
        {
            System.setProperty(TERMINATE_ON_STARTUP_FAILURE, "true");
        }

        SpringApplication.run(LabKeyServer.class, args);
    }

    @Bean
    public ContextProperties contextSource()
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

                // Get the context properties from Spring injection
                ContextProperties contextProperties = contextSource();

                // for development, point to the local deploy/labkeyWebapp directory in configs/application.properties
                boolean webAppLocationPresent = contextProperties.getWebAppLocation() != null;
                var webAppLocation = "";

                try
                {
                    if (!webAppLocationPresent)
                    {
                        final var currentPath = new File("").getAbsolutePath();
                        var destDirectory = currentPath + "/server";
                        webAppLocation = destDirectory + "/labkeywebapp";
                        boolean extracted = new File(webAppLocation).exists();
                        String jarFilePath = getExecutableJar(currentPath);

                        if (!extracted)
                        {
                            extractExecutableJar(destDirectory, jarFilePath);
                        }
                    }
                    else
                    {
                        webAppLocation = contextProperties.getWebAppLocation();
                    }

                    // tomcat requires a unique context path other than root here
                    // can not set context path as "" because em tomcat complains "Child name [] is not unique"
                    StandardContext context = (StandardContext) tomcat.addWebapp("/labkey", webAppLocation);
                    // set the root path to the context explicitly
                    context.setPath("");

                    // Push the JDBC connection for the primary DB into the context so that the LabKey webapp finds them
                    getDataSourceResources(contextProperties).forEach(contextResource -> context.getNamingResources().addResource(contextResource));

                    // Add the SMTP config
                    context.getNamingResources().addResource(getMailResource());

                    // And the master encryption key
                    context.addParameter("EncryptionKey", contextProperties.getEncryptionKey());

                    // Add serverGUID for mothership - it tells mothership that 2 instances of a server should be considered the same for metrics gathering purposes.
                    if (null != contextProperties.getServerGUID())
                    {
                        context.addParameter(SERVER_GUID_PARAMETER_NAME, contextProperties.getServerGUID());
                    }

                    // Point at the special classloader with the hack for SLF4J
                    WebappLoader loader = new WebappLoader();
                    loader.setLoaderClass(LabKeySpringBootClassLoader.class.getName());
                    context.setLoader(loader);
                    context.setParentClassLoader(this.getClass().getClassLoader());
                }
                catch (ConfigException e)
                {
                    throw new RuntimeException(e);
                }

                return super.getTomcatWebServer(tomcat);
            }

            private List<ContextResource> getDataSourceResources(ContextProperties props) throws ConfigException
            {
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

                    dataSourceResource.setProperty("maxTotal", getPropValue(props.getMaxTotal(), i, MAX_TOTAL_CONNECTIONS_DEFAULT, "maxTotal"));
                    dataSourceResource.setProperty("maxIdle", getPropValue(props.getMaxIdle(), i, MAX_IDLE_DEFAULT, "maxIdle"));
                    dataSourceResource.setProperty("maxWaitMillis", getPropValue(props.getMaxWaitMillis(), i, MAX_WAIT_MILLIS_DEFAULT, "maxWaitMillis"));
                    dataSourceResource.setProperty("accessToUnderlyingConnectionAllowed", getPropValue(props.getAccessToUnderlyingConnectionAllowed(), i, ACCESS_TO_CONNECTION_ALLOWED_DEFAULT, "accessToUnderlyingConnectionAllowed"));
                    dataSourceResource.setProperty("validationQuery", getPropValue(props.getValidationQuery(), i, VALIDATION_QUERY_DEFAULT, "validationQuery"));

                    dataSourceResources.add(dataSourceResource);
                }

                return  dataSourceResources;
            }

            private String getPropValue(Map<Integer, String> propValues, Integer resourceKey, String defaultValue, String propName)
            {
                if (propValues == null)
                {
                    logger.debug(String.format("%1$s property was not provided, using default", propName));
                    return defaultValue;
                }

                if (!propValues.containsKey(resourceKey))
                    logger.debug(String.format("%1$s property was not provided for resource [%2$s], using default [%3$s]", propName, resourceKey, defaultValue));

                String val = propValues.getOrDefault(resourceKey, defaultValue);
                return val != null && !val.isBlank() ? val.trim() : defaultValue;
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

                if (mailProps.getSmtpFrom() != null)
                {
                    mailResource.setProperty("mail.smtp.from", mailProps.getSmtpFrom());
                }
                if (mailProps.getSmtpPassword() != null)
                {
                    mailResource.setProperty("mail.smtp.password", mailProps.getSmtpPassword());
                }
                if (mailProps.getSmtpStartTlsEnable() != null)
                {
                    mailResource.setProperty("mail.smtp.starttls.enable", mailProps.getSmtpStartTlsEnable());
                }
                if (mailProps.getSmtpSocketFactoryClass() != null)
                {
                    mailResource.setProperty("mail.smtp.socketFactory.class", mailProps.getSmtpSocketFactoryClass());
                }
                if (mailProps.getSmtpAuth() != null)
                {
                    mailResource.setProperty("mail.smtp.auth", mailProps.getSmtpAuth());
                }

                return mailResource;
            }
        };
    }

    private static void extractExecutableJar(String destDirectory, String jarFilePath)
    {
        try
        {
            try (JarFile jar = new JarFile(jarFilePath))
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
        File currentDir = new File(currentPath);
        List<String> jarsPresent = new ArrayList<>();

        for (File file: currentDir.listFiles())
        {
            if (file.getName().toLowerCase().endsWith(".jar"))
            {
                jarsPresent.add(file.getName());
            }
        }

        if (jarsPresent.size() == 0)
        {
            throw new ConfigException("Executable jar not found.");
        }

        // only 1 jar should be there
        if (jarsPresent.size() == 1)
        {
            return jarsPresent.get(0);
        }

        throw new ConfigException("Multiple jars found - " + jarsPresent.toString() + ". Must provide only one jar.");
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
    @ConfigurationProperties("context")
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

        private String webAppLocation;
        @NotNull (message = "Must provide encryptionKey")
        private String encryptionKey;
        private String serverGUID;
        private Map<Integer, String> maxTotal;
        private Map<Integer, String> maxIdle;
        private Map<Integer, String> maxWaitMillis;
        private Map<Integer, String> accessToUnderlyingConnectionAllowed;
        private Map<Integer, String> validationQuery;

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

        public String getWebAppLocation()
        {
            return webAppLocation;
        }

        public void setWebAppLocation(String webAppLocation)
        {
            this.webAppLocation = webAppLocation;
        }

        public String getEncryptionKey()
        {
            return encryptionKey;
        }

        public void setEncryptionKey(String encryptionKey)
        {
            this.encryptionKey = encryptionKey;
        }

        public String getServerGUID()
        {
            return serverGUID;
        }

        public void setServerGUID(String serverGUID)
        {
            this.serverGUID = serverGUID;
        }

        public Map<Integer, String> getMaxTotal()
        {
            return maxTotal;
        }

        public void setMaxTotal(Map<Integer, String> maxTotal)
        {
            this.maxTotal = maxTotal;
        }


        public void setMaxIdle(Map<Integer, String> maxIdle)
        {
            this.maxIdle = maxIdle;
        }

        public Map<Integer, String> getMaxIdle()
        {
            return this.maxIdle;
        }

        public void setAccessToUnderlyingConnectionAllowed(Map<Integer, String> accessToUnderlyingConnectionAllowed)
        {
            this.accessToUnderlyingConnectionAllowed = accessToUnderlyingConnectionAllowed;
        }

        public Map<Integer, String> getAccessToUnderlyingConnectionAllowed()
        {
            return this.accessToUnderlyingConnectionAllowed;
        }

        public void setMaxWaitMillis(Map<Integer, String> maxWaitMillis)
        {
            this.maxWaitMillis = maxWaitMillis;
        }

        public Map<Integer, String> getMaxWaitMillis()
        {
            return this.maxWaitMillis;
        }

        public Map<Integer, String> getValidationQuery()
        {
            return validationQuery;
        }

        public void setValidationQuery(Map<Integer, String> validationQuery)
        {
            this.validationQuery = validationQuery;
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
