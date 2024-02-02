package org.labkey.embedded;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.JsonAccessLogValve;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.util.collections.CaseInsensitiveKeyMap;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.labkey.bootstrap.ConfigException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.ApplicationPidFileWriter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import javax.sql.DataSource;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@SpringBootApplication
public class LabKeyServer
{
    private static final Log LOG = LogFactory.getLog(LabKeyServer.class);

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

        SpringApplication application = new SpringApplication(LabKeyServer.class);
        application.addListeners(new ApplicationPidFileWriter("./labkey.pid"));
        application.run(args);
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
    public LdapProperties ldapSource()
    {
        return new LdapProperties();
    }

    @Bean
    public JmsProperties jmsSource()
    {
        return new JmsProperties();
    }

    @Bean
    public WebappProperties additionalWebappSource()
    {
        return new WebappProperties();
    }

    @Bean
    public CSPFilterProperties cspSource()
    {
        return new CSPFilterProperties();
    }

    @Bean
    public JsonAccessLog jsonAccessLog()
    {
        return new JsonAccessLog();
    }

    @Bean
    public TomcatServletWebServerFactory servletContainerFactory()
    {
        var result = new TomcatServletWebServerFactory()
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

                    tomcat.setAddDefaultWebXmlToWebapp(false);

                    // tomcat requires a unique context path other than root here
                    // can not set context path as "" because em tomcat complains "Child name [] is not unique"
                    StandardContext context = (StandardContext) tomcat.addWebapp("/labkey", webAppLocation);

                    // Propagate standard Spring Boot properties such as the session timeout
                    configureContext(context, new ServletContextInitializer[0]);

                    CSPFilterProperties cspFilterProperties = cspSource();

                    if (cspFilterProperties.getEnforce() != null)
                    {
                        context.addParameter("csp.enforce", cspFilterProperties.getEnforce());
                    }
                    if (cspFilterProperties.getReport() != null)
                    {
                        context.addParameter("csp.report", cspFilterProperties.getReport());
                    }

                    // Issue 48426: Allow config for desired work directory
                    if (contextProperties.getWorkDirLocation() != null)
                    {
                        context.setWorkDir(contextProperties.getWorkDirLocation());
                    }

                    // set the root path to the context explicitly
                    context.setPath(contextProperties.getContextPath());

                    // Push the JDBC connection for the primary DB into the context so that the LabKey webapp finds them
                    getDataSourceResources(contextProperties, context).forEach(contextResource -> context.getNamingResources().addResource(contextResource));

                    // Add extra resources to context (e.g. LDAP, JMS)
                    getExtraContextResources(contextProperties).forEach(contextResource -> context.getNamingResources().addResource(contextResource));

                    // Add the SMTP config
                    context.getNamingResources().addResource(getMailResource());

                    ContextResource jmsResource = getJmsResource();
                    if (jmsResource != null)
                    {
                        context.getNamingResources().addResource(jmsResource);
                    }

                    ContextResource ldapResource = getLdapResource();
                    if (ldapResource != null)
                    {
                        context.getNamingResources().addResource(ldapResource);
                    }

                    // And the master encryption key
                    context.addParameter("EncryptionKey", contextProperties.getEncryptionKey());
                    if (contextProperties.getOldEncryptionKey() != null)
                    {
                        context.addParameter("OldEncryptionKey", contextProperties.getOldEncryptionKey());
                    }

                    if (contextProperties.getLegacyContextPath() != null)
                    {
                        if (contextProperties.getContextPath() != null && !contextProperties.getContextPath().isEmpty() && !contextProperties.getContextPath().equals("/"))
                        {
                            throw new IllegalArgumentException("contextPath.legacyContextPath is only intended for use when deploying the LabKey application to the root context path. Please update application.properties.");
                        }
                        context.addParameter("legacyContextPath", contextProperties.getLegacyContextPath());
                    }
                    if (contextProperties.getRequiredModules() != null)
                    {
                        context.addParameter("requiredModules", contextProperties.getRequiredModules());
                    }
                    if (contextProperties.getPipelineConfig() != null)
                    {
                        context.addParameter("org.labkey.api.pipeline.config", contextProperties.getPipelineConfig());
                    }
                    if (contextProperties.isBypass2FA())
                    {
                        // Expand single config into two different options. Can collapse/rename when we're embedded-only,
                        // but this provides an easy backwards compatible bridge while we still support standalone Tomcat
                        context.addParameter("org.labkey.authentication.totp.Bypass", "true");
                        context.addParameter("org.labkey.authentication.duo.Bypass", "true");
                    }

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

                JsonAccessLog logConfig = jsonAccessLog();
                if (logConfig.isEnabled())
                {
                   configureJsonAccessLogging(tomcat, logConfig);
                }

                WebappProperties additionalWebapps = additionalWebappSource();
                if (additionalWebapps.getContextPath().size() != additionalWebapps.getDocBase().size())
                {
                    throw new IllegalArgumentException("Additional webapps must have paired contextPath and docBase properties");
                }
                for (int i = 0; i < additionalWebapps.getContextPath().size(); i++)
                {
                    String contextPath = additionalWebapps.getContextPath().get(i);
                    String docBase = additionalWebapps.getDocBase().get(i);
                    tomcat.addWebapp(contextPath, docBase);
                }

                return super.getTomcatWebServer(tomcat);
            }

            // Issue 48565: allow for JSON-formatted access logs in embedded tomcat
            private void configureJsonAccessLogging(Tomcat tomcat, JsonAccessLog logConfig)
            {
                var v = new JsonAccessLogValve();

                // Configure for stdout, our only current use case
                v.setPrefix("stdout");
                v.setDirectory("/dev");
                v.setBuffered(false);
                v.setSuffix("");
                v.setFileDateFormat("");
                v.setContainer(tomcat.getHost());

                // Now the settings that we support via application.properties
                v.setPattern(logConfig.getPattern());
                v.setConditionIf(logConfig.getConditionIf());
                v.setConditionUnless(logConfig.getConditionUnless());

                tomcat.getEngine().getPipeline().addValve(v);
            }

            private List<ContextResource> getDataSourceResources(ContextProperties props, StandardContext context) throws ConfigException
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

                    // These two properties are handled differently, as separate parameters
                    String displayName = getPropValue(props.getDisplayName(), i, null, "displayName");
                    if (displayName != null)
                    {
                        context.addParameter(dataSourceResource.getName() + ":DisplayName", displayName);
                    }
                    String logQueries = getPropValue(props.getLogQueries(), i, null, "logQueries");
                    if (logQueries != null)
                    {
                        context.addParameter(dataSourceResource.getName() + ":LogQueries", logQueries);
                    }

                    dataSourceResources.add(dataSourceResource);
                }

                return dataSourceResources;
            }

            private List<ContextResource> getExtraContextResources(ContextProperties contextProperties) throws ConfigException
            {
                List<ContextResource> contextResources = new ArrayList<>();
                Map<String, Map<String, String>> resourceMaps = Objects.requireNonNullElse(contextProperties.getResources(), Collections.emptyMap());

                for (Map.Entry<String, Map<String, String>> entry : resourceMaps.entrySet())
                {
                    Map<String, String> resourceMap = new CaseInsensitiveKeyMap<>();
                    resourceMap.putAll(entry.getValue());
                    if (!resourceMap.containsKey("name"))
                    {
                        throw new ConfigException("Resource configuration error: Unnamed resource found 'context.resources.%s'".formatted(entry.getKey()));
                    }
                    if (!resourceMap.containsKey("type"))
                    {
                        throw new ConfigException("Resource configuration error: 'type' is not defined for resource '%s'".formatted(resourceMap.get("name")));
                    }

                    ContextResource contextResource = new ContextResource();
                    // Handle resource properties with explicit setters
                    contextResource.setName(resourceMap.remove("name"));
                    contextResource.setType(resourceMap.remove("type"));
                    contextResource.setDescription(resourceMap.remove("description"));
                    contextResource.setLookupName(resourceMap.remove("lookupName"));
                    if (resourceMap.containsKey("scope"))
                    {
                        contextResource.setScope(resourceMap.remove("scope"));
                    }
                    contextResource.setAuth(Objects.requireNonNullElse(resourceMap.remove("auth"), "Container"));

                    // Set remaining properties
                    for (Map.Entry<String, String> prop : resourceMap.entrySet())
                    {
                        contextResource.setProperty(prop.getKey(), prop.getValue());
                    }

                    contextResources.add(contextResource);
                }

                return  contextResources;
            }

            private String getPropValue(Map<Integer, String> propValues, Integer resourceKey, String defaultValue, String propName)
            {
                if (propValues == null)
                {
                    LOG.debug(String.format("%1$s property was not provided, using default", propName));
                    return defaultValue;
                }

                if (!propValues.containsKey(resourceKey))
                    LOG.debug(String.format("%1$s property was not provided for resource [%2$s], using default [%3$s]", propName, resourceKey, defaultValue));

                String val = propValues.getOrDefault(resourceKey, defaultValue);
                return val != null && !val.isBlank() ? val.trim() : defaultValue;
            }

            private ContextResource getJmsResource()
            {
                JmsProperties jmsProps = jmsSource();
                if (jmsProps.getBrokerURL() == null)
                {
                    return null;
                }

                ContextResource jmsResource = new ContextResource();
                jmsResource.setName("jms/ConnectionFactory");
                jmsResource.setAuth("Container");
                jmsResource.setType(jmsProps.getType());
                jmsResource.setProperty("factory", jmsProps.getFactory());
                jmsResource.setProperty("description", jmsProps.getDescription());
                jmsResource.setProperty("brokerURL", jmsProps.getBrokerURL());
                jmsResource.setProperty("brokerName", jmsProps.getBrokerName());
                return jmsResource;
            }

            private ContextResource getLdapResource()
            {
                LdapProperties ldapProps = ldapSource();
                if (ldapProps.getHost() == null)
                {
                    return null;
                }

                ContextResource ldapResource = new ContextResource();
                ldapResource.setName("ldap/ConfigFactory");
                ldapResource.setAuth("Container");
                ldapResource.setType(ldapProps.getType());
                ldapResource.setProperty("factory", ldapProps.getFactory());
                ldapResource.setProperty("host", ldapProps.getHost());
                ldapResource.setProperty("port", Integer.toString(ldapProps.getPort()));
                if (ldapProps.getPrincipal() != null)
                {
                    ldapResource.setProperty("principal", ldapProps.getPrincipal());
                }
                if (ldapProps.getCredentials() != null)
                {
                    ldapResource.setProperty("credentials", ldapProps.getCredentials());
                }
                ldapResource.setProperty("useSsl", Boolean.toString(ldapProps.isUseSsl()));
                ldapResource.setProperty("useTls", Boolean.toString(ldapProps.isUseTls()));
                if (ldapProps.getSslProtocol() != null)
                {
                    ldapResource.setProperty("sslProtocol", ldapProps.getSslProtocol());
                }
                return ldapResource;
            }

            private ContextResource getMailResource()
            {
                // Get session/mail properties
                MailProperties mailProps = smtpSource();
                ContextResource mailResource = new ContextResource();
                mailResource.setName("mail/Session");
                mailResource.setAuth("Container");
                mailResource.setType("jakarta.mail.Session");
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

        var contextProperties = contextSource();

        if (contextProperties.getHttpPort() != null)
        {
            Connector httpConnector = new Connector();
            httpConnector.setScheme("http");
            httpConnector.setPort(contextProperties.getHttpPort());
            result.addAdditionalTomcatConnectors(httpConnector);
        }

        return result;
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

        File[] files = currentDir.listFiles();
        if (files != null)
        {
            for (File file : files)
            {
                if (file.getName().toLowerCase().endsWith(".jar"))
                {
                    jarsPresent.add(file.getName());
                }
            }
        }

        if (jarsPresent.isEmpty())
        {
            throw new ConfigException("Executable jar not found.");
        }

        // only 1 jar should be there
        if (jarsPresent.size() == 1)
        {
            return jarsPresent.get(0);
        }

        throw new ConfigException("Multiple jars found - " + jarsPresent + ". Must provide only one jar.");
    }

    private static void extractZip(InputStream zipInputStream, String destDirectory) throws IOException
    {
        File destDir = new File(destDirectory);
        //noinspection SSBasedInspection
        if (!destDir.exists() && !destDir.mkdirs())
        {
            throw new IOException("Failed to create directory " + destDir + " - please check file system permissions");
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
                    //noinspection SSBasedInspection
                    if (!dir.exists() && !dir.mkdirs())
                    {
                        throw new IOException("Failed to create directory " + dir + " - please check file system permissions");
                    }
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

    @Configuration
    @ConfigurationProperties("jsonaccesslog")
    public static class JsonAccessLog
    {
        private boolean enabled;
        private String pattern = "%h %t %m %U %s %b %D %S \"%{Referer}i\" \"%{User-Agent}i\" %{LABKEY.username}s";
        private String conditionIf;
        private String conditionUnless;

        public boolean isEnabled()
        {
            return enabled;
        }

        public void setEnabled(boolean enabled)
        {
            this.enabled = enabled;
        }

        public String getPattern()
        {
            return pattern;
        }

        public void setPattern(String pattern)
        {
            this.pattern = pattern;
        }

        public String getConditionIf()
        {
            return conditionIf;
        }

        public void setConditionIf(String conditionIf)
        {
            this.conditionIf = conditionIf;
        }

        public String getConditionUnless()
        {
            return conditionUnless;
        }

        public void setConditionUnless(String conditionUnless)
        {
            this.conditionUnless = conditionUnless;
        }
    }

    @Configuration
    @ConfigurationProperties("webapps")
    public static class WebappProperties
    {
        private List<String> contextPath = new ArrayList<>();

        private List<String> docBase = new ArrayList<>();

        public List<String> getContextPath()
        {
            return contextPath;
        }

        public void setContextPath(List<String> contextPath)
        {
            this.contextPath = contextPath;
        }

        public List<String> getDocBase()
        {
            return docBase;
        }

        public void setDocBase(List<String> docBase)
        {
            this.docBase = docBase;
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
        private String workDirLocation;
        @NotNull (message = "Must provide encryptionKey")
        private String encryptionKey;
        private String oldEncryptionKey;
        private String legacyContextPath;

        // Default to deploying to the root context path
        private String contextPath = "";
        private String pipelineConfig;
        private String requiredModules;
        private boolean bypass2FA = false;
        private String serverGUID;
        private Integer httpRedirectorPort;
        private Map<Integer, String> maxTotal;
        private Map<Integer, String> maxIdle;
        private Map<Integer, String> maxWaitMillis;
        private Map<Integer, String> accessToUnderlyingConnectionAllowed;
        private Map<Integer, String> validationQuery;
        private Map<Integer, String> displayName;
        private Map<Integer, String> logQueries;
        private Map<String, Map<String, String>> resources;

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

        public String getWorkDirLocation()
        {
            return workDirLocation;
        }

        public void setWorkDirLocation(String workDirLocation)
        {
            this.workDirLocation = workDirLocation;
        }

        public String getEncryptionKey()
        {
            return encryptionKey;
        }

        public void setEncryptionKey(String encryptionKey)
        {
            this.encryptionKey = encryptionKey;
        }

        public String getOldEncryptionKey()
        {
            return oldEncryptionKey;
        }

        public void setOldEncryptionKey(String oldEncryptionKey)
        {
            this.oldEncryptionKey = oldEncryptionKey;
        }

        public String getLegacyContextPath()
        {
            return legacyContextPath;
        }

        public void setLegacyContextPath(String legacyContextPath)
        {
            this.legacyContextPath = legacyContextPath;
        }

        public String getContextPath()
        {
            return contextPath;
        }

        public void setContextPath(String contextPath)
        {
            this.contextPath = contextPath;
        }

        public String getPipelineConfig()
        {
            return pipelineConfig;
        }

        public void setPipelineConfig(String pipelineConfig)
        {
            this.pipelineConfig = pipelineConfig;
        }

        public String getRequiredModules()
        {
            return requiredModules;
        }

        public void setRequiredModules(String requiredModules)
        {
            this.requiredModules = requiredModules;
        }

        public boolean isBypass2FA()
        {
            return bypass2FA;
        }

        public void setBypass2FA(boolean bypass2FA)
        {
            this.bypass2FA = bypass2FA;
        }

        public Integer getHttpPort()
        {
            return httpRedirectorPort;
        }

        public void setHttpRedirectorPort(Integer httpRedirectorPort)
        {
            this.httpRedirectorPort = httpRedirectorPort;
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

        public Map<String, Map<String, String>> getResources()
        {
            return resources;
        }

        public void setResources(Map<String, Map<String, String>> resources)
        {
            this.resources = resources;
        }

        public Map<Integer, String> getDisplayName()
        {
            return displayName;
        }

        public void setDisplayName(Map<Integer, String> displayName)
        {
            this.displayName = displayName;
        }

        public Map<Integer, String> getLogQueries()
        {
            return logQueries;
        }

        public void setLogQueries(Map<Integer, String> logQueries)
        {
            this.logQueries = logQueries;
        }
    }

    @Configuration
    @ConfigurationProperties("ldap")
    public static class LdapProperties
    {
        private String type = "org.labkey.premium.ldap.LdapConnectionConfigFactory";
        private String factory = "org.labkey.premium.ldap.LdapConnectionConfigFactory";
        private String host = null;
        private int port = 389;
        private String principal = null;
        private String credentials = null;
        private boolean useTls = false;
        private boolean useSsl = false;
        private String sslProtocol;

        public String getType()
        {
            return type;
        }

        public void setType(String type)
        {
            this.type = type;
        }

        public String getFactory()
        {
            return factory;
        }

        public void setFactory(String factory)
        {
            this.factory = factory;
        }

        public String getHost()
        {
            return host;
        }

        public void setHost(String host)
        {
            this.host = host;
        }

        public int getPort()
        {
            return port;
        }

        public void setPort(int port)
        {
            this.port = port;
        }

        public String getPrincipal()
        {
            return principal;
        }

        public void setPrincipal(String principal)
        {
            this.principal = principal;
        }

        public String getCredentials()
        {
            return credentials;
        }

        public void setCredentials(String credentials)
        {
            this.credentials = credentials;
        }

        public boolean isUseTls()
        {
            return useTls;
        }

        public void setUseTls(boolean useTls)
        {
            this.useTls = useTls;
        }

        public boolean isUseSsl()
        {
            return useSsl;
        }

        public void setUseSsl(boolean useSsl)
        {
            this.useSsl = useSsl;
        }

        public String getSslProtocol()
        {
            return sslProtocol;
        }

        public void setSslProtocol(String sslProtocol)
        {
            this.sslProtocol = sslProtocol;
        }
    }

    @Configuration
    @ConfigurationProperties("jms")
    public static class JmsProperties
    {
        private String type = "org.apache.activemq.ActiveMQConnectionFactory";
        private String factory = "org.apache.activemq.jndi.JNDIReferenceFactory";
        private String description = "JMS Connection Factory";
        private String brokerURL = null;
        private String brokerName = "LocalActiveMQBroker";

        public String getType()
        {
            return type;
        }

        public void setType(String type)
        {
            this.type = type;
        }

        public String getFactory()
        {
            return factory;
        }

        public void setFactory(String factory)
        {
            this.factory = factory;
        }

        public String getDescription()
        {
            return description;
        }

        public void setDescription(String description)
        {
            this.description = description;
        }

        public String getBrokerURL()
        {
            return brokerURL;
        }

        public void setBrokerURL(String brokerURL)
        {
            this.brokerURL = brokerURL;
        }

        public String getBrokerName()
        {
            return brokerName;
        }

        public void setBrokerName(String brokerName)
        {
            this.brokerName = brokerName;
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

    @Configuration
    @ConfigurationProperties("csp")
    public static class CSPFilterProperties
    {
        private String enforce;
        private String report;

        public String getEnforce()
        {
            return enforce;
        }

        public void setEnforce(String enforce)
        {
            this.enforce = enforce;
        }

        public String getReport()
        {
            return report;
        }

        public void setReport(String report)
        {
            this.report = report;
        }
    }
}
