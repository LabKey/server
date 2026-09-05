/*
 * Copyright (c) 2024-2026 LabKey Corporation
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

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.core.StandardHost;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.JsonAccessLogValve;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.labkey.bootstrap.ConfigException;
import org.labkey.embedded.LabKeyServer.BootProperties;
import org.labkey.embedded.LabKeyServer.ContextProperties;
import org.labkey.embedded.LabKeyServer.CorsProperties;
import org.labkey.embedded.LabKeyServer.GraphMailProperties;
import org.labkey.embedded.LabKeyServer.JsonAccessLog;
import org.labkey.embedded.LabKeyServer.MailProperties;
import org.labkey.embedded.LabKeyServer.ServerSslProperties;
import org.labkey.embedded.LabKeyServer.TomcatProperties;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;

import javax.sql.DataSource;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

class LabKeyTomcatServletWebServerFactory extends TomcatServletWebServerFactory
{
    private static final Logger LOG = LogManager.getLogger(LabKeyTomcatServletWebServerFactory.class);
    private static final String SERVER_GUID = "serverGUID";
    private static final String SERVER_GUID_PARAMETER_NAME = "org.labkey.mothership." + SERVER_GUID;
    private static final String SERVER_SSL_KEYSTORE = "org.labkey.serverSslKeystore";
    private static final String CUSTOM_LOG4J_CONFIG = "org.labkey.customLog4JConfig";
    private static final String CORS_PREFIX = "cors.";
    private static final String BYPASS_2FA = "bypass2FA";

    private final BootProperties _properties;

    public LabKeyTomcatServletWebServerFactory(BootProperties properties)
    {
        _properties = properties;

        addConnectorCustomizers(connector -> {
            TomcatProperties props = _properties.tomcat();

            if (props.useBodyEncodingForURI() != null)
            {
                connector.setUseBodyEncodingForURI(props.useBodyEncodingForURI());
            }

            if (connector.getProtocolHandler() instanceof AbstractHttp11Protocol<?> handler)
            {
                if (props.disableUploadTimeout() != null)
                {
                    handler.setDisableUploadTimeout(props.disableUploadTimeout());
                }
                if (props.useSendfile() != null)
                {
                    handler.setUseSendfile(props.useSendfile());
                }
            }
        });

        addContextCustomizers(context -> {
            final Container parent = context.getParent();
            if (parent instanceof StandardHost sh)
            {
                sh.setErrorReportValveClass(LabKeyErrorReportValve.class.getName());
            }
        });
    }

    @Override
    protected void prepareContext(Host host, ServletContextInitializer[] initializers, TempDirs tempDirs)
    {
        // Prevent the Spring Boot webapp from trying to deserialize the LabKey sessions
        getSettings().getSession().setPersistent(false);

        // Don't use Spring Boot's error pages, as we want to render our own
        setErrorPages(Collections.emptySet());

        super.prepareContext(host, initializers, tempDirs);
    }

    @Override
    protected TomcatWebServer getTomcatWebServer(Tomcat tomcat)
    {
        // Don't deploy LK webapp on the separate instance running on the management port
        if (_properties.managementServer().port() != getPort())
        {
            tomcat.enableNaming();

            ContextProperties contextProperties = _properties.context();

            try
            {
                final File currentDir = new File("").getAbsoluteFile();
                final File webAppLocation;

                if (contextProperties.webAppLocation() == null)
                {
                    webAppLocation = new File(currentDir, "labkeywebapp");
                }
                else
                {
                    webAppLocation = new File(contextProperties.webAppLocation());
                }

                EmbeddedExtractor extractor = new EmbeddedExtractor(false);
                if (contextProperties.webAppLocation() == null || extractor.foundLabkeyServerJar())
                {
                    extractor.extractDistribution(webAppLocation);
                } // else, probably a local build deployment

                // Turn off the default web.xml behavior so that we don't stomp over customized values
                // from application.properties, such as session timeouts
                tomcat.setAddDefaultWebXmlToWebapp(false);

                // We want our own Webdav servlet handling requests, not Tomcat's default servlet
                setRegisterDefaultServlet(false);

                // We want the LK webapp to serialize/deserialize sessions during restarts
                getSettings().getSession().setPersistent(true);

                // Spring Boot's webapp is being deployed to the root. We have to deploy elsewhere in this initial
                // call, but can immediately swap it with the desired place
                StandardContext context = (StandardContext) tomcat.addWebapp("/labkey", webAppLocation.getAbsolutePath());
                // set the root path to the context explicitly
                context.setPath(contextProperties.contextPath());

                // Propagate standard Spring Boot properties such as the session timeout
                configureContext(context, Collections.emptyList());

                addContextProperty(context, _properties.csp().enforce(), "csp.enforce");
                addContextProperty(context, _properties.csp().report(), "csp.report");

                // Issue 48426: Allow config for desired work directory
                if (contextProperties.workDirLocation() != null)
                {
                    context.setWorkDir(contextProperties.workDirLocation());
                }

                // Push the JDBC connection for the primary DB into the context so that the LabKey webapp finds them
                addDataSourceResources(contextProperties, context);

                // Add extra resources to context (e.g. LDAP, JMS)
                addExtraContextResources(contextProperties, context);

                // Add the mail transport config (SMTP or Microsoft Graph)
                addSmtpProperties(context);
                addGraphProperties(context);

                // Add the encryption key(s)
                context.addParameter("EncryptionKey", contextProperties.requireEncryptionKey());
                addContextProperty(context, contextProperties.oldEncryptionKey(), "OldEncryptionKey");

                if (contextProperties.legacyContextPath() != null)
                {
                    if (contextProperties.contextPath() != null && !contextProperties.contextPath().isEmpty() && !contextProperties.contextPath().equals("/"))
                    {
                        throw new ConfigException("contextPath.legacyContextPath is only intended for use when deploying the LabKey application to the root context path. Please update application.properties.");
                    }
                    context.addParameter("legacyContextPath", contextProperties.legacyContextPath());
                }
                addContextProperty(context, contextProperties.requiredModules(), "requiredModules");
                if (contextProperties.externalModules() != null)
                {
                    // We've long supported configuring this via a system property so propagate the value
                    System.setProperty("labkey.externalModulesDir", contextProperties.externalModules());
                }
                addContextProperty(context, contextProperties.pipelineConfig(), "org.labkey.api.pipeline.config");
                if (contextProperties.bypass2FA())
                {
                    context.addParameter(BYPASS_2FA, "true");
                }

                boolean customLog4J = System.getProperty("log4j.configurationFile") != null;
                if (!customLog4J)
                {
                    customLog4J = _properties.logging().config() != null;
                }
                context.addParameter(CUSTOM_LOG4J_CONFIG, Boolean.toString(customLog4J));

                // Add serverGUID for mothership - it tells mothership that 2 instances of a server should be considered the same for metrics gathering purposes
                addContextProperty(context, contextProperties.serverGUID(), SERVER_GUID_PARAMETER_NAME);

                CorsProperties corsProperties = _properties.tomcat().cors();
                if (corsProperties != null)
                {
                    // Push these into the context so that ApiModule can register Tomcat's CorsFilter as desired
                    addContextProperty(context, corsProperties.allowedOrigins(), CORS_PREFIX + "allowedOrigins");
                    addContextProperty(context, corsProperties.allowedMethods(), CORS_PREFIX + "allowedMethods");
                    addContextProperty(context, corsProperties.allowedHeaders(), CORS_PREFIX + "allowedHeaders");
                    addContextProperty(context, corsProperties.exposedHeaders(), CORS_PREFIX + "exposedHeaders");
                    addContextProperty(context, corsProperties.supportCredentials(), CORS_PREFIX + "supportCredentials");
                    addContextProperty(context, corsProperties.urlPattern(), CORS_PREFIX + "urlPattern");
                    addContextProperty(context, corsProperties.preflightMaxAge(), CORS_PREFIX + "preflightMaxAge");
                    addContextProperty(context, corsProperties.requestDecorate(), CORS_PREFIX + "requestDecorate");
                }

                ServerSslProperties sslProps = _properties.serverSsl();
                addContextProperty(context, sslProps.keyStore(), SERVER_SSL_KEYSTORE);

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

            JsonAccessLog logConfig = _properties.jsonAccessLog();
            if (logConfig.enabled())
            {
                configureJsonAccessLogging(tomcat, logConfig);
            }

            Map<String, String> additionalWebapps = contextProperties.additionalWebapps();
            if (additionalWebapps != null)
            {
                setRegisterDefaultServlet(true);

                for (Map.Entry<String, String> entry : additionalWebapps.entrySet())
                {
                    String contextPath = entry.getKey();
                    if (!contextPath.startsWith("/"))
                    {
                        contextPath = "/" + contextPath;
                    }
                    String docBase = entry.getValue();
                    if (docBase == null || docBase.isEmpty())
                    {
                        throw new ConfigException("No docBase supplied additional webapp at context path " + contextPath);
                    }
                    Context ctx = tomcat.addWebapp(contextPath, docBase);


                    // Issue 53723: register the default servlet without enabling JSPs

                    // Code copied from Tomcat.initWebappDefaults(), removing JSP-specific initialization
                    Wrapper servlet = Tomcat.addServlet(ctx, "default", "org.apache.catalina.servlets.DefaultServlet");
                    servlet.setLoadOnStartup(1);
                    servlet.setOverridable(true);
                    ctx.addServletMappingDecoded("/", "default");
                    Tomcat.addDefaultMimeTypeMappings(ctx);
                    ctx.addWelcomeFile("index.html");
                    ctx.addWelcomeFile("index.htm");
                    // Any application configured welcome files should override the defaults.
                    if (ctx instanceof StandardContext) {
                        ((StandardContext) ctx).setReplaceWelcomeFiles(true);
                    }
                }
            }
        }

        return super.getTomcatWebServer(tomcat);
    }

    private void addContextProperty(StandardContext context, String value, String name)
    {
        if (null != value)
        {
            context.addParameter(name, value);
        }
    }

    // Issue 48565: allow for JSON-formatted access logs
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
        v.setPattern(logConfig.pattern());
        v.setConditionIf(logConfig.conditionIf());
        v.setConditionUnless(logConfig.conditionUnless());

        tomcat.getEngine().getPipeline().addValve(v);
    }

    /**
     * Wires up data sources from the older indexed config approach, like:
     * context.dataSourceName[0]=jdbc/labkeyDataSource
     * context.driverClassName[0]=org.postgresql.Driver
     */
    private void addDataSourceResources(ContextProperties props, StandardContext context) throws ConfigException
    {
        var numOfDataResources = props.url().size();

        if (numOfDataResources != props.dataSourceName().size() ||
                numOfDataResources != props.driverClassName().size() ||
                numOfDataResources != props.username().size() ||
                numOfDataResources != props.password().size())
        {
            throw new ConfigException("DataSources not configured properly. Must have all the required properties for all datasources: dataSourceName, driverClassName, userName, password, url");
        }

        for (int i = 0; i < numOfDataResources; i++)
        {
            ContextResource dataSourceResource = new ContextResource();
            dataSourceResource.setName(props.dataSourceName().get(i));
            dataSourceResource.setAuth("Container");
            dataSourceResource.setType(DataSource.class.getName());
            dataSourceResource.setProperty("driverClassName", props.driverClassName().get(i));
            dataSourceResource.setProperty("url", props.url().get(i));
            dataSourceResource.setProperty("password", props.password().get(i));
            dataSourceResource.setProperty("username", props.username().get(i));

            dataSourceResource.setProperty("maxTotal", getPropValue(props.maxTotal(), i, LabKeyServer.MAX_TOTAL_CONNECTIONS_DEFAULT, "maxTotal"));
            dataSourceResource.setProperty("maxIdle", getPropValue(props.maxIdle(), i, LabKeyServer.MAX_IDLE_DEFAULT, "maxIdle"));
            dataSourceResource.setProperty("maxWaitMillis", getPropValue(props.maxWaitMillis(), i, LabKeyServer.MAX_WAIT_MILLIS_DEFAULT, "maxWaitMillis"));
            dataSourceResource.setProperty("accessToUnderlyingConnectionAllowed", getPropValue(props.accessToUnderlyingConnectionAllowed(), i, LabKeyServer.ACCESS_TO_CONNECTION_ALLOWED_DEFAULT, "accessToUnderlyingConnectionAllowed"));
            dataSourceResource.setProperty("validationQuery", getPropValue(props.validationQuery(), i, LabKeyServer.VALIDATION_QUERY_DEFAULT, "validationQuery"));

            // These two properties are handled differently, as separate parameters
            String displayName = getPropValue(props.displayName(), i, null, "displayName");
            if (displayName != null)
            {
                context.addParameter(dataSourceResource.getName() + ":DisplayName", displayName);
            }
            String logQueries = getPropValue(props.logQueries(), i, null, "logQueries");
            if (logQueries != null)
            {
                context.addParameter(dataSourceResource.getName() + ":LogQueries", logQueries);
            }

            context.getNamingResources().addResource(dataSourceResource);
        }
    }

    private void addExtraContextResources(ContextProperties contextProperties, StandardContext context) throws ConfigException
    {
        Map<String, Map<String, Map<String, String>>> resourceMaps = Objects.requireNonNullElse(contextProperties.resources(), Collections.emptyMap());

        for (Map.Entry<String, Map<String, Map<String, String>>> parentEntry : resourceMaps.entrySet())
        {
            for (Map.Entry<String, Map<String, String>> entry : parentEntry.getValue().entrySet())
            {
                String resourceTypeString = parentEntry.getKey();

                ResourceType resourceType;
                try
                {
                    resourceType = ResourceType.valueOf(resourceTypeString);
                }
                catch (IllegalArgumentException e)
                {
                    resourceType = ResourceType.generic;
                }

                String name = parentEntry.getKey() + "/" + entry.getKey();

                resourceType.addResource(name, entry.getValue(), context);
            }
        }
    }

    private String getPropValue(Map<Integer, String> propValues, Integer resourceKey, String defaultValue, String propName)
    {
        if (propValues == null)
        {
            LOG.debug("{} property was not provided, using default", propName);
            return defaultValue;
        }

        if (!propValues.containsKey(resourceKey))
            LOG.debug("{} property was not provided for resource [{}], using default [{}]", propName, resourceKey, defaultValue);

        String val = propValues.getOrDefault(resourceKey, defaultValue);
        return val != null && !val.isBlank() ? val.trim() : defaultValue;
    }

    private void addSmtpProperties(StandardContext context)
    {
        // Get session/mail properties
        MailProperties mailProps = _properties.mail();

        addSmtpProperty(context, "host", mailProps.smtpHost());
        addSmtpProperty(context, "user", mailProps.smtpUser());
        addSmtpProperty(context, "port", mailProps.smtpPort());
        addSmtpProperty(context, "from", mailProps.smtpFrom());
        addSmtpProperty(context, "password", mailProps.smtpPassword());
        addSmtpProperty(context, "starttls.enable", mailProps.smtpStartTlsEnable());
        addSmtpProperty(context, "socketFactory.class", mailProps.smtpSocketFactoryClass());
        addSmtpProperty(context, "auth", mailProps.smtpAuth());
        addSmtpProperty(context, "connectiontimeout", mailProps.smtpConnectionTimeout());
        addSmtpProperty(context, "timeout", mailProps.smtpTimeout());
        addSmtpProperty(context, "writetimeout", mailProps.smtpWriteTimeout());
    }

    private void addSmtpProperty(StandardContext context, String name, String value)
    {
        if (value != null)
        {
            context.addParameter("mail.smtp." + name, value);
        }
    }

    private void addSmtpProperty(StandardContext context, String name, Integer value)
    {
        if (value != null)
            addSmtpProperty(context, name, String.valueOf(value));
    }

    private void addGraphProperties(StandardContext context)
    {
        // Get Microsoft Graph mail properties
        GraphMailProperties graphProps = _properties.graphMail();

        addContextProperty(context, graphProps.tenantId(), "mail.graph.tenantId");
        addContextProperty(context, graphProps.clientId(), "mail.graph.clientId");
        addContextProperty(context, graphProps.clientSecret(), "mail.graph.clientSecret");
        addContextProperty(context, graphProps.fromAddress(), "mail.graph.fromAddress");
    }
}
