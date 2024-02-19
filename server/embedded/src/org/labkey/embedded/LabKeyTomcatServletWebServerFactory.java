package org.labkey.embedded;

import org.apache.catalina.Host;
import org.apache.catalina.core.StandardContext;
import org.apache.catalina.loader.WebappLoader;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.JsonAccessLogValve;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.labkey.bootstrap.ConfigException;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.ServletContextInitializer;

import javax.sql.DataSource;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import static org.labkey.embedded.LabKeyServer.SERVER_GUID_PARAMETER_NAME;
import static org.labkey.embedded.LabKeyServer.SERVER_SSL_KEYSTORE;

class LabKeyTomcatServletWebServerFactory extends TomcatServletWebServerFactory
{
    private static final Log LOG = LogFactory.getLog(LabKeyTomcatServletWebServerFactory.class);
    private final LabKeyServer _server;

    public LabKeyTomcatServletWebServerFactory(LabKeyServer server)
    {
        _server = server;
    }

    @Override
    protected void prepareContext(Host host, ServletContextInitializer[] initializers)
    {
        // Prevent the Spring Boot webapp from trying to deserialize the LabKey sessions
        getSession().setPersistent(false);

        // Don't use Spring Boot's error pages, as we want to render our own
        setErrorPages(Collections.emptySet());

        super.prepareContext(host, initializers);
    }

    @Override
    protected TomcatWebServer getTomcatWebServer(Tomcat tomcat)
    {
        LabKeyServer.ManagementProperties props = _server.managementSource();

        // Don't deploy LK webapp on the separate instance running on the management port
        if (props == null || props.getServer() == null || props.getServer().getPort() != getPort())
        {
            tomcat.enableNaming();

            // Get the context properties from Spring injection
            LabKeyServer.ContextProperties contextProperties = _server.contextSource();

            // for development, point to the local deploy/labkeyWebapp directory in configs/application.properties
            boolean webAppLocationPresent = contextProperties.getWebAppLocation() != null;
            File webAppLocation;

            try
            {
                if (!webAppLocationPresent)
                {
                    final var currentPath = new File("");
                    var destDirectory = new File(currentPath, "server");
                    webAppLocation = new File(destDirectory, "labkeywebapp");

                    if (!webAppLocation.exists())
                    {
                        EmbeddedExtractor extractor = new EmbeddedExtractor();
                        extractor.extractExecutableJarFromDir(currentPath, destDirectory, false);
                    }
                }
                else
                {
                    webAppLocation = new File(contextProperties.getWebAppLocation());
                }

                // Turn off the default web.xml behavior so that we don't stomp over customized values
                // from application.properties, such as session timeouts
                tomcat.setAddDefaultWebXmlToWebapp(false);

                // We want our own Webdav servlet handling requests, not Tomcat's default servlet
                setRegisterDefaultServlet(false);

                // We want the LK webapp to serialize/deserialize sessions during restarts
                getSession().setPersistent(true);

                // Spring Boot's webapp is being deployed to the root. We have to deploy elsewhere in this initial
                // call, but can immediately swap it with the desired place
                StandardContext context = (StandardContext) tomcat.addWebapp("/labkey", webAppLocation.getAbsolutePath());
                // set the root path to the context explicitly
                context.setPath(contextProperties.getContextPath());

                // Propagate standard Spring Boot properties such as the session timeout
                configureContext(context, new ServletContextInitializer[0]);

                LabKeyServer.CSPFilterProperties cspFilterProperties = _server.cspSource();

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

                // Push the JDBC connection for the primary DB into the context so that the LabKey webapp finds them
                addDataSourceResources(contextProperties, context);

                // Add extra resources to context (e.g. LDAP, JMS)
                addExtraContextResources(contextProperties, context);

                // Add the SMTP config
                context.getNamingResources().addResource(getMailResource());

                // Signal that we started up via Embedded Tomcat for reporting purposes
                context.addParameter("embeddedTomcat", "true");

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
                        throw new ConfigException("contextPath.legacyContextPath is only intended for use when deploying the LabKey application to the root context path. Please update application.properties.");
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

                LabKeyServer.ServerSslProperties sslProps = _server.serverSslSource();
                if (null != sslProps)
                {
                    context.addParameter(SERVER_SSL_KEYSTORE, sslProps.getKeyStore());
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

            LabKeyServer.JsonAccessLog logConfig = _server.jsonAccessLog();
            if (logConfig.isEnabled())
            {
                configureJsonAccessLogging(tomcat, logConfig);
            }

            Map<String, String> additionalWebapps = contextProperties.getAdditionalWebapps();
            if (additionalWebapps != null)
            {
                // Turn the default web.xml behavior back on so that Tomcat serves up static files as normal
                tomcat.setAddDefaultWebXmlToWebapp(true);
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
                    tomcat.addWebapp(contextPath, docBase);
                }
            }
        }

        return super.getTomcatWebServer(tomcat);
    }

    // Issue 48565: allow for JSON-formatted access logs in embedded tomcat
    private void configureJsonAccessLogging(Tomcat tomcat, LabKeyServer.JsonAccessLog logConfig)
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

    /**
     * Wires up data sources from the older indexed config approach, like:
     * context.dataSourceName[0]=jdbc/labkeyDataSource
     * context.driverClassName[0]=org.postgresql.Driver
     */
    private void addDataSourceResources(LabKeyServer.ContextProperties props, StandardContext context) throws ConfigException
    {
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

            dataSourceResource.setProperty("maxTotal", getPropValue(props.getMaxTotal(), i, LabKeyServer.MAX_TOTAL_CONNECTIONS_DEFAULT, "maxTotal"));
            dataSourceResource.setProperty("maxIdle", getPropValue(props.getMaxIdle(), i, LabKeyServer.MAX_IDLE_DEFAULT, "maxIdle"));
            dataSourceResource.setProperty("maxWaitMillis", getPropValue(props.getMaxWaitMillis(), i, LabKeyServer.MAX_WAIT_MILLIS_DEFAULT, "maxWaitMillis"));
            dataSourceResource.setProperty("accessToUnderlyingConnectionAllowed", getPropValue(props.getAccessToUnderlyingConnectionAllowed(), i, LabKeyServer.ACCESS_TO_CONNECTION_ALLOWED_DEFAULT, "accessToUnderlyingConnectionAllowed"));
            dataSourceResource.setProperty("validationQuery", getPropValue(props.getValidationQuery(), i, LabKeyServer.VALIDATION_QUERY_DEFAULT, "validationQuery"));

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

            context.getNamingResources().addResource(dataSourceResource);
        }
    }

    private void addExtraContextResources(LabKeyServer.ContextProperties contextProperties, StandardContext context) throws ConfigException
    {
        Map<String, Map<String, Map<String, String>>> resourceMaps = Objects.requireNonNullElse(contextProperties.getResources(), Collections.emptyMap());

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
            LOG.debug(String.format("%1$s property was not provided, using default", propName));
            return defaultValue;
        }

        if (!propValues.containsKey(resourceKey))
            LOG.debug(String.format("%1$s property was not provided for resource [%2$s], using default [%3$s]", propName, resourceKey, defaultValue));

        String val = propValues.getOrDefault(resourceKey, defaultValue);
        return val != null && !val.isBlank() ? val.trim() : defaultValue;
    }

    private ContextResource getMailResource()
    {
        // Get session/mail properties
        LabKeyServer.MailProperties mailProps = _server.smtpSource();
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
}
