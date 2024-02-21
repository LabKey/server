package org.labkey.embedded;

import jakarta.validation.constraints.NotNull;
import org.apache.catalina.connector.Connector;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.labkey.bootstrap.PipelineBootstrapConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.ApplicationPidFileWriter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class LabKeyServer
{
    private static final Log LOG = LogFactory.getLog(LabKeyServer.class);

    private static final String TERMINATE_ON_STARTUP_FAILURE = "terminateOnStartupFailure";
    private static final String JARS_TO_SKIP = "tomcat.util.scan.StandardJarScanFilter.jarsToSkip";
    private static final String JARS_TO_SCAN = "tomcat.util.scan.StandardJarScanFilter.jarsToScan";
    private static final String SERVER_GUID = "serverGUID";
    public static final String SERVER_GUID_PARAMETER_NAME = "org.labkey.mothership." + SERVER_GUID;
    public static final String SERVER_SSL_KEYSTORE = "org.labkey.serverSslKeystore";
    static final String MAX_TOTAL_CONNECTIONS_DEFAULT = "50";
    static final String MAX_IDLE_DEFAULT = "10";
    static final String MAX_WAIT_MILLIS_DEFAULT = "120000";
    static final String ACCESS_TO_CONNECTION_ALLOWED_DEFAULT = "true";
    static final String VALIDATION_QUERY_DEFAULT = "SELECT 1";

    public static void main(String[] args)
    {
        if (args.length > 0 && args[0].equalsIgnoreCase("-extract"))
        {
            File currentDir = new File("").getAbsoluteFile();
            new EmbeddedExtractor().extractExecutableJarFromDir(currentDir, currentDir, true);
            return;
        }

        // Issue 40038: Ride-or-die Mode - default to shutting down by default in embedded deployment scenario
        if (System.getProperty(TERMINATE_ON_STARTUP_FAILURE) == null)
        {
            System.setProperty(TERMINATE_ON_STARTUP_FAILURE, "true");
        }

        // On most installs, catalina.home and catalina.base point to the same directory. However, it's possible
        // to have multiple instances share the Tomcat binaries but have their own ./logs, ./conf, etc. directories
        // Thus, we want to use catalina.base for our place to find log files. http://www.jguru.com/faq/view.jsp?EID=1121565
        String catalinaBase = System.getProperty("catalina.base");
        PipelineBootstrapConfig.ensureLogHomeSet((null != catalinaBase ? catalinaBase + "/" : "") + "logs");

        // Restrict Tomcat's jar scanning to the absolute minimum to speed up server startup. Downside is we need to
        // update the jarsToScan list any time we add a new @WebListener annotation... but this happens very rarely.
        // More elegant approaches (e.g., constructing, configuring, and setting a JarScanner/JarScanFilter pair in
        // LabKeyTomcatServletWebServerFactory.postProcessContext()) don't seem to work. There's evidence that Spring
        // Boot overwrites settings and also that Tomcat's property vs. code behavior differs.
        if (System.getProperty(JARS_TO_SKIP) == null && System.getProperty(JARS_TO_SCAN) == null)
        {
            System.setProperty(JARS_TO_SKIP, "*");
            System.setProperty(JARS_TO_SCAN, "rstudio-??.?*.jar,cas-??.?*.jar,core-??.?*.jar,connectors-??.?*.jar,devtools-??.?*.jar");
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
    public CSPFilterProperties cspSource()
    {
        return new CSPFilterProperties();
    }

    @Bean
    public ServerSslProperties serverSslSource()
    {
        return new ServerSslProperties();
    }

    @Bean
    public JsonAccessLog jsonAccessLog()
    {
        return new JsonAccessLog();
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer()
    {
        // Needed to expose JMX for Tomcat/Catalina internals
        return customizer -> customizer.setDisableMBeanRegistry(false);
    }

    @Bean
    public TomcatServletWebServerFactory servletContainerFactory()
    {
        var result = new LabKeyTomcatServletWebServerFactory(this);

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

    @Bean
    public ManagementProperties managementSource()
    {
        return new ManagementProperties();
    }


    /**
     * This lets us snoop on the Spring Boot config for deploying the management endpoint on a different port, as
     * we don't want to deploy LK on that port
     */
    @Configuration
    @ConfigurationProperties("management")
    public static class ManagementProperties
    {
        private ServerProperties _server;

        public ServerProperties getServer()
        {
            return _server;
        }

        public void setServer(ServerProperties server)
        {
            _server = server;
        }
    }

    public static class ServerProperties
    {
        private int _port;

        public int getPort()
        {
            return _port;
        }

        public void setPort(int port)
        {
            _port = port;
        }
    }


    @Validated
    @Configuration
    @ConfigurationProperties("context")
    public static class ContextProperties
    {
        private List<String> dataSourceName = new ArrayList<>();
        private List<String> url = new ArrayList<>();
        private List<String> username = new ArrayList<>();
        private List<String> password = new ArrayList<>();
        private List<String> driverClassName = new ArrayList<>();

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
        private Integer httpPort;
        private Map<Integer, String> maxTotal;
        private Map<Integer, String> maxIdle;
        private Map<Integer, String> maxWaitMillis;
        private Map<Integer, String> accessToUnderlyingConnectionAllowed;
        private Map<Integer, String> validationQuery;
        private Map<Integer, String> displayName;
        private Map<Integer, String> logQueries;
        private Map<String, Map<String, Map<String, String>>> resources;
        private Map<String, String> additionalWebapps;

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
            return httpPort;
        }

        public void setHttpPort(Integer httpPort)
        {
            this.httpPort = httpPort;
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

        public Map<String, Map<String, Map<String, String>>> getResources()
        {
            return resources;
        }

        public void setResources(Map<String, Map<String, Map<String, String>>> resources)
        {
            this.resources = resources;
        }

        public Map<String, String> getAdditionalWebapps()
        {
            return additionalWebapps;
        }

        public void setAdditionalWebapps(Map<String, String> additionalWebapps)
        {
            this.additionalWebapps = additionalWebapps;
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

    /**
     * Spring Boot doesn't propagate the keystore path into Tomcat's SSL config so we need to grab it and stash
     * it for potential use via the Connectors module.
     */
    @Configuration
    @ConfigurationProperties("server.ssl")
    public static class ServerSslProperties
    {
        private String keyStore;

        public String getKeyStore()
        {
            return keyStore;
        }

        public void setKeyStore(String keyStore)
        {
            this.keyStore = keyStore;
        }
    }

}
