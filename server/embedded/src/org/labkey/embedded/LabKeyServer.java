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
import org.labkey.bootstrap.StartupEnvironment;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.ApplicationPidFileWriter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNullElse;

@SpringBootApplication
@EnableConfigurationProperties({
    LabKeyServer.ContextProperties.class,
    LabKeyServer.MailProperties.class,
    LabKeyServer.GraphMailProperties.class,
    LabKeyServer.CSPFilterProperties.class,
    LabKeyServer.ServerSslProperties.class,
    LabKeyServer.JsonAccessLog.class,
    LabKeyServer.ManagementServerProperties.class,
    LabKeyServer.LoggingProperties.class,
    LabKeyServer.TomcatProperties.class
})
public class LabKeyServer
{
    private static final String TERMINATE_ON_STARTUP_FAILURE = "terminateOnStartupFailure";
    private static final String JARS_TO_SKIP = "tomcat.util.scan.StandardJarScanFilter.jarsToSkip";
    private static final String JARS_TO_SCAN = "tomcat.util.scan.StandardJarScanFilter.jarsToScan";

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

        StartupEnvironment.ensureLabKeyHomeSet(new File("").getAbsoluteFile());
        // Resolves the labkey.log.home system property that application.properties reads
        PipelineBootstrapConfig.ensureLogHomeSet("logs");

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
        application.setDefaultProperties(getLog4JProperties());
        application.setBannerMode(Banner.Mode.OFF);
        application.run(args);
    }

    /**
     * GitHub Issue 796: JSON logging stopped after Tomcat/Spring update
     * Propagate log4j configuration to Spring Boot config, which is necessary with Spring Boot 4.x. Can't live in
     * application.properties because it's derived from a system property.
     */
    private static Map<String, Object> getLog4JProperties()
    {
        Map<String, Object> properties = new HashMap<>();
        String log4JConfig = System.getProperty("log4j.configurationFile");

        if (log4JConfig != null)
        {
            String[] parts = log4JConfig.split(",");
            if (parts.length > 0)
            {
                // "log4j2.xml" is the one packaged with our embedded build and on the classpath
                properties.put("logging.config", "log4j2.xml".equals(parts[0]) ? "classpath:log4j2.xml" : parts[0]);
                if (parts.length > 1)
                {
                    properties.put("logging.log4j2.config.override", String.join(",", Arrays.asList(parts).subList(1, parts.length)));
                }
            }
        }

        return properties;
    }

    @Bean
    public BootProperties bootProperties(ContextProperties context, MailProperties mail, GraphMailProperties graphMail,
                                         CSPFilterProperties csp, ServerSslProperties serverSsl, JsonAccessLog jsonAccessLog,
                                         ManagementServerProperties managementServer, LoggingProperties logging,
                                         TomcatProperties tomcat)
    {
        return new BootProperties(context, mail, graphMail, csp, serverSsl, jsonAccessLog, managementServer, logging, tomcat);
    }

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> customizer()
    {
        // Needed to expose JMX for Tomcat/Catalina internals
        return customizer -> customizer.setDisableMBeanRegistry(false);
    }

    @Bean
    public TomcatServletWebServerFactory servletContainerFactory(BootProperties properties)
    {
        var result = new LabKeyTomcatServletWebServerFactory(properties);

        Integer httpPort = properties.context().httpPort();

        if (httpPort != null)
        {
            Connector httpConnector = new Connector();
            httpConnector.setScheme("http");
            httpConnector.setPort(httpPort);
            result.addAdditionalConnectors(httpConnector);
        }

        return result;
    }

    /** Everything the boot layer binds from application.properties and hands to the webapp */
    public record BootProperties(
        ContextProperties context,
        MailProperties mail,
        GraphMailProperties graphMail,
        CSPFilterProperties csp,
        ServerSslProperties serverSsl,
        JsonAccessLog jsonAccessLog,
        ManagementServerProperties managementServer,
        LoggingProperties logging,
        TomcatProperties tomcat
    ) {}

    @ConfigurationProperties("jsonaccesslog")
    public record JsonAccessLog(
        @DefaultValue("false") boolean enabled,
        String pattern,
        String conditionIf,
        String conditionUnless
    ) {}

    /**
     * This lets us snoop on the Spring Boot config for deploying the management endpoint on a different port, as
     * we don't want to deploy LK on that port
     */
    @ConfigurationProperties("management.server")
    public record ManagementServerProperties(@DefaultValue("0") int port) {}

    /** Values that we'll propagate to org.apache.catalina.filters.CorsFilter */
    public record CorsProperties(
        String allowedOrigins,
        String allowedMethods,
        String allowedHeaders,
        String exposedHeaders,
        String supportCredentials,
        String urlPattern,
        String preflightMaxAge,
        String requestDecorate
    ) {}

    /** Add some properties that Spring Boot doesn't support setting. See issue 50690 */
    @ConfigurationProperties("server.tomcat")
    public record TomcatProperties(
        Boolean useSendfile,
        Boolean disableUploadTimeout,
        Boolean useBodyEncodingForURI,
        CorsProperties cors
    ) {}

    /**
     * This lets us snoop on the Spring Boot config for log4j so we can report it via a mothership metric
     */
    @ConfigurationProperties("logging")
    public record LoggingProperties(String config) {}

    @ConfigurationProperties("context")
    public record ContextProperties(
        List<String> dataSourceName,
        List<String> url,
        List<String> username,
        List<String> password,
        List<String> driverClassName,
        String webAppLocation,
        String workDirLocation,
        String encryptionKey,
        String oldEncryptionKey,
        String legacyContextPath,
        @DefaultValue("") String contextPath,
        String pipelineConfig,
        String requiredModules,
        // Path to external modules directory
        String externalModules,
        @DefaultValue("false") boolean bypass2FA,
        String serverGUID,
        Integer httpPort,
        Map<Integer, String> maxTotal,
        Map<Integer, String> maxIdle,
        Map<Integer, String> maxWaitMillis,
        Map<Integer, String> accessToUnderlyingConnectionAllowed,
        Map<Integer, String> validationQuery,
        Map<Integer, String> displayName,
        Map<Integer, String> logQueries,
        Map<String, Map<String, Map<String, String>>> resources,
        Map<String, String> additionalWebapps
    )
    {
        public ContextProperties
        {
            dataSourceName = requireNonNullElse(dataSourceName, List.of());
            url = requireNonNullElse(url, List.of());
            username = requireNonNullElse(username, List.of());
            password = requireNonNullElse(password, List.of());
            driverClassName = requireNonNullElse(driverClassName, List.of());
        }

        /** The server can't start without it, but nothing validates it until we're actually deploying the webapp */
        public String requireEncryptionKey()
        {
            if (null == encryptionKey)
                throw new RuntimeException("Must provide encryptionKey");
            return encryptionKey;
        }
    }

    @ConfigurationProperties("mail")
    public record MailProperties(
        String smtpHost,
        String smtpUser,
        String smtpPort,
        String smtpFrom,
        String smtpPassword,
        String smtpStartTlsEnable,
        String smtpSocketFactoryClass,
        String smtpAuth,
        Integer smtpConnectionTimeout,
        Integer smtpTimeout,
        Integer smtpWriteTimeout
    ) {}

    @ConfigurationProperties("mail.graph")
    public record GraphMailProperties(
        String tenantId,
        String clientId,
        String clientSecret,
        String fromAddress
    ) {}

    @ConfigurationProperties("csp")
    public record CSPFilterProperties(String enforce, String report) {}

    /**
     * Spring Boot doesn't propagate the keystore path into Tomcat's SSL config so we need to grab it and stash
     * it for potential use via the Connectors module.
     */
    @ConfigurationProperties("server.ssl")
    public record ServerSslProperties(String keyStore) {}
}
