/*
 * Copyright (c) 2021-2026 LabKey Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.labkey.embedded;

import org.apache.catalina.connector.Connector;
import org.labkey.bootstrap.PipelineBootstrapConfig;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.ApplicationPidFileWriter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class LabKeyServer
{
    private static final String TERMINATE_ON_STARTUP_FAILURE = "terminateOnStartupFailure";
    private static final String JARS_TO_SKIP = "tomcat.util.scan.StandardJarScanFilter.jarsToSkip";
    private static final String JARS_TO_SCAN = "tomcat.util.scan.StandardJarScanFilter.jarsToScan";
    private static final String SERVER_GUID = "serverGUID";
    public static final String SERVER_GUID_PARAMETER_NAME = "org.labkey.mothership." + SERVER_GUID;
    public static final String SERVER_SSL_KEYSTORE = "org.labkey.serverSslKeystore";
    public static final String CUSTOM_LOG4J_CONFIG = "org.labkey.customLog4JConfig";
    public static final String CORS_PREFIX = "cors.";
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
            EmbeddedExtractor embeddedExtractor = new EmbeddedExtractor(false);
            embeddedExtractor.extractExecutableJar(currentDir, true);
            return;
        }

        // Issue 40038: Ride-or-die Mode - default to shutting down by default
        if (System.getProperty(TERMINATE_ON_STARTUP_FAILURE) == null)
        {
            System.setProperty(TERMINATE_ON_STARTUP_FAILURE, "true");
        }

        String logHome = PipelineBootstrapConfig.ensureLogHomeSet("logs");

        // Restrict Tomcat's jar scanning to the absolute minimum to speed up server startup. Downside is we need to
        // update the jarsToScan list any time we add a new @WebListener annotation... but this happens very rarely.
        // More elegant approaches (e.g., constructing, configuring, and setting a JarScanner/JarScanFilter pair in
        // LabKeyTomcatServletWebServerFactory.postProcessContext()) don't seem to work. There's evidence that Spring
        // Boot overwrites settings and also that Tomcat's property vs. code behavior differs.
        if (System.getProperty(JARS_TO_SKIP) == null && System.getProperty(JARS_TO_SCAN) == null)
        {
            System.setProperty(JARS_TO_SKIP, "*");
            System.setProperty(JARS_TO_SCAN, "rstudio-??.?*.jar,cas-??.?*.jar,core-??.?*.jar,connectors-??.?*.jar,devtools-??.?*.jar,labbook-??.?*.jar");
        }

        SpringApplication application = new SpringApplication(LabKeyServer.class);
        application.addListeners(new ApplicationPidFileWriter("./labkey.pid"));
        // A strong Content Security Policy
        String baseCsp = """
                default-src 'self' ;
                connect-src 'self' ${CONNECTION.SOURCES} ;
                object-src ${OBJECT.SOURCES} ;  /* Substitution value defaults to 'none' unless overridden by an admin */
                style-src 'self' 'unsafe-inline' ${STYLE.SOURCES} ;
                img-src 'self' data: ${IMAGE.SOURCES} ;
                font-src 'self' data: ${FONT.SOURCES} ;
                script-src 'unsafe-eval' 'strict-dynamic' 'nonce-${REQUEST.SCRIPT.NONCE}' ${SCRIPT.SOURCES} ;
                base-uri 'self' ;
                frame-src 'self' ${FRAME.SOURCES} ;
                report-uri ${context.contextPath:}/admin-contentSecurityPolicyReport.api ;
            """;
        // Add upgrade_insecure_requests substitution, frame-ancestors, and enforce version
        String enforceCsp = baseCsp + """
                ${UPGRADE.INSECURE.REQUESTS}
                frame-ancestors 'self' ${FRAMEANCESTORS.SOURCES} ;
                /* cspVersion=e16 */
            """;
        // Leave out upgrade_insecure_requests and frame-ancestors directives, since they produce warnings on some browsers
        String reportCsp = baseCsp + """
                /* cspVersion=r16 */
            """;

        application.setDefaultProperties(new HashMap<>()
             {{
                // GitHub Issue 796: JSON logging stopped after Tomcat/Spring update
                 // Propagate log4j configuration to Spring Boot config, which is necessary with Spring Boot 4.x
                 String log4JConfig = System.getProperty("log4j.configurationFile");
                 if (log4JConfig != null)
                 {
                     String[] log4JConfigParts = log4JConfig.split(",");
                     if (log4JConfigParts.length > 0)
                     {
                         if ("log4j2.xml".equals(log4JConfigParts[0]))
                         {
                             // Assume this is the one packaged with our embedded build and on the classpath
                             put("logging.config", "classpath:log4j2.xml");
                         }
                         else
                         {
                             put("logging.config", log4JConfigParts[0]);
                         }
                         if (log4JConfigParts.length > 1)
                         {
                            put("logging.log4j2.config.override", String.join(",", Arrays.asList(log4JConfigParts).subList(1, log4JConfigParts.length)));
                         }
                     }
                 }

                 put("server.tomcat.basedir", ".");
                 put("server.tomcat.accesslog.directory", logHome);

                 // Boost limits imposed by Tomcat v10.1.42
                 put("server.tomcat.max-part-count", 500);
                 put("server.tomcat.max-part-header-size", 1024);  // GitHub Issue 161: LKS insert forms can't handle long file field names
                 put("server.tomcat.max-connections", 250);
                // Boost limit back to Tomcat 10 level
                 put("server.tomcat.max-parameter-count", 10_000);

                 // Enable HTTP compression for response content
                 put("server.compression.enabled", "true");
                 // Spring Boot compresses HTML, JSON and other types by default, but not TSV, CSV, or SVG.
                 // We have to duplicate the defaults and add those types
                 put("server.compression.mime-types", "text/html,text/xml,text/plain,text/css,text/javascript,application/javascript,application/json,application/xml,text/tab-separated-values,text/csv,image/svg+xml");

                 put("server.tomcat.accesslog.enabled", "true");
                 put("server.tomcat.accesslog.pattern", "%h %l %u %t \"%r\" %s %b %D %S %I \"%{Referer}i\" \"%{User-Agent}i\" %{LABKEY.username}s %{X-Forwarded-For}i");
                 put("jsonaccesslog.pattern", "%h %t %m %U %s %b %D %S \"%{Referer}i\" \"%{User-Agent}i\" %{LABKEY.username}s %{X-Forwarded-For}i");

                 // Issue 52415: Omit stack traces from Tomcat error pages by default, but propagate error messages
                 put("server.error.include-stacktrace", "never");
                 put("server.error.include-message", "always");

                 put("csp.enforce", enforceCsp);
                 put("csp.report", reportCsp);

                 // GitHub Issue 692: Stop using CBC in HTTPS ciphers
                 // These settings configure HTTPS. Admins must opt in with additional settings
                 // in application.properties, like the key store. Without those other settings,
                 // HTTP-only startup fails unless "server.ssl.enabled" is explicitly set to false here
                 put("server.ssl.enabled", "false");
                 put("server.ssl.protocol", "TLS");
                 put("server.ssl.enabled-protocols", "TLSv1.3,TLSv1.2");
                 // Use explicit JSSE cipher suite names to avoid CBC-mode suites
                 put("server.ssl.ciphers",
                     String.join(",",
                         // TLS 1.3
                         "TLS_AES_256_GCM_SHA384",
                         "TLS_AES_128_GCM_SHA256",
                         "TLS_CHACHA20_POLY1305_SHA256",
                         // TLS 1.2 (AEAD only, no CBC)
                         "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                         "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                         "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
                         "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                         "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                         "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"
                     )
                 );
                 put("server.ssl.use-cipher-suites-order", "true");
             }}
        );
        application.setBannerMode(Banner.Mode.OFF);
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
    public GraphMailProperties graphSource()
    {
        return new GraphMailProperties();
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
            result.addAdditionalConnectors(httpConnector);
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
    public ManagementServerProperties managementServerSource()
    {
        return new ManagementServerProperties();
    }

    @Bean
    public LoggingProperties loggingSource()
    {
        return new LoggingProperties();
    }

    @Bean
    public TomcatProperties tomcatProperties()
    {
        return new TomcatProperties();
    }

    /**
     * This lets us snoop on the Spring Boot config for deploying the management endpoint on a different port, as
     * we don't want to deploy LK on that port
     */
    @Configuration
    @ConfigurationProperties("management.server")
    public static class ManagementServerProperties
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

    /** Values that we'll propagate to org.apache.catalina.filters.CorsFilter */
    public static class CorsProperties
    {
        private String _allowedOrigins;
        private String _allowedMethods;
        private String _allowedHeaders;
        private String _exposedHeaders;
        private String _supportCredentials;
        private String _urlPattern;
        private String _preflightMaxAge;
        private String _requestDecorate;

        public String getAllowedOrigins()
        {
            return _allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins)
        {
            _allowedOrigins = allowedOrigins;
        }

        public String getAllowedMethods()
        {
            return _allowedMethods;
        }

        public void setAllowedMethods(String allowedMethods)
        {
            _allowedMethods = allowedMethods;
        }

        public String getAllowedHeaders()
        {
            return _allowedHeaders;
        }

        public void setAllowedHeaders(String allowedHeaders)
        {
            _allowedHeaders = allowedHeaders;
        }

        public String getExposedHeaders()
        {
            return _exposedHeaders;
        }

        public void setExposedHeaders(String exposedHeaders)
        {
            _exposedHeaders = exposedHeaders;
        }

        public String getSupportCredentials()
        {
            return _supportCredentials;
        }

        public void setSupportCredentials(String supportCredentials)
        {
            _supportCredentials = supportCredentials;
        }

        public String getUrlPattern()
        {
            return _urlPattern;
        }

        public void setUrlPattern(String urlPattern)
        {
            _urlPattern = urlPattern;
        }

        public String getPreflightMaxAge()
        {
            return _preflightMaxAge;
        }

        public void setPreflightMaxAge(String preflightMaxAge)
        {
            _preflightMaxAge = preflightMaxAge;
        }

        public String getRequestDecorate()
        {
            return _requestDecorate;
        }

        public void setRequestDecorate(String requestDecorate)
        {
            _requestDecorate = requestDecorate;
        }
    }

    /** Add some properties that Spring Boot doesn't support setting. See issue 50690 */
    @Configuration
    @ConfigurationProperties("server.tomcat")
    public static class TomcatProperties
    {
        private Boolean _useSendfile;
        private Boolean _disableUploadTimeout;
        private Boolean _useBodyEncodingForURI;

        private CorsProperties _cors;

        public Boolean getUseSendfile()
        {
            return _useSendfile;
        }

        public void setUseSendfile(Boolean useSendfile)
        {
            _useSendfile = useSendfile;
        }

        public Boolean getDisableUploadTimeout()
        {
            return _disableUploadTimeout;
        }

        public void setDisableUploadTimeout(Boolean disableUploadTimeout)
        {
            _disableUploadTimeout = disableUploadTimeout;
        }

        public Boolean getUseBodyEncodingForURI()
        {
            return _useBodyEncodingForURI;
        }

        public void setUseBodyEncodingForURI(Boolean useBodyEncodingForURI)
        {
            _useBodyEncodingForURI = useBodyEncodingForURI;
        }

        public CorsProperties getCors()
        {
            return _cors;
        }

        public void setCors(CorsProperties cors)
        {
            _cors = cors;
        }
    }

    /**
     * This lets us snoop on the Spring Boot config for log4j so we can report it via a mothership metric
     */
    @Configuration
    @ConfigurationProperties("logging")
    public static class LoggingProperties
    {
        private String _config;

        public String getConfig()
        {
            return _config;
        }

        public void setConfig(String config)
        {
            _config = config;
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
        private String encryptionKey;
        private String oldEncryptionKey;
        private String legacyContextPath;

        // Default to deploying to the root context path
        private String contextPath = "";
        private String pipelineConfig;
        private String requiredModules;
        /** Path to external modules directory */
        private String externalModules;
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
            if (null == encryptionKey)
                throw new RuntimeException("Must provide encryptionKey");
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

        public String getExternalModules()
        {
            return externalModules;
        }

        public void setExternalModules(String externalModules)
        {
            this.externalModules = externalModules;
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
    @ConfigurationProperties("mail.graph")
    public static class GraphMailProperties
    {
        private String tenantId;
        private String clientId;
        private String clientSecret;
        private String fromAddress;

        public String getTenantId()
        {
            return tenantId;
        }

        public void setTenantId(String tenantId)
        {
            this.tenantId = tenantId;
        }

        public String getClientId()
        {
            return clientId;
        }

        public void setClientId(String clientId)
        {
            this.clientId = clientId;
        }

        public String getClientSecret()
        {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret)
        {
            this.clientSecret = clientSecret;
        }

        public String getFromAddress()
        {
            return fromAddress;
        }

        public void setFromAddress(String fromAddress)
        {
            this.fromAddress = fromAddress;
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
