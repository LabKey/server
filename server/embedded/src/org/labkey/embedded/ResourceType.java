package org.labkey.embedded;

import org.apache.catalina.core.StandardContext;
import org.apache.tomcat.util.collections.CaseInsensitiveKeyMap;
import org.apache.tomcat.util.descriptor.web.ContextResource;
import org.labkey.bootstrap.ConfigException;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Objects;

import static org.labkey.embedded.LabKeyServer.ACCESS_TO_CONNECTION_ALLOWED_DEFAULT;
import static org.labkey.embedded.LabKeyServer.MAX_IDLE_DEFAULT;
import static org.labkey.embedded.LabKeyServer.MAX_TOTAL_CONNECTIONS_DEFAULT;
import static org.labkey.embedded.LabKeyServer.MAX_WAIT_MILLIS_DEFAULT;
import static org.labkey.embedded.LabKeyServer.VALIDATION_QUERY_DEFAULT;

public enum ResourceType
{
    jdbc
            {
                @Override
                protected Map<String, String> mergeWithDefaults(Map<String, String> props)
                {
                    Map<String, String> result = super.mergeWithDefaults(props);
                    result.putIfAbsent("type", DataSource.class.getName());
                    result.putIfAbsent("maxTotal", MAX_TOTAL_CONNECTIONS_DEFAULT);
                    result.putIfAbsent("maxIdle", MAX_IDLE_DEFAULT);
                    result.putIfAbsent("maxWaitMillis", MAX_WAIT_MILLIS_DEFAULT);
                    result.putIfAbsent("accessToUnderlyingConnectionAllowed", ACCESS_TO_CONNECTION_ALLOWED_DEFAULT);
                    result.putIfAbsent("validationQuery", VALIDATION_QUERY_DEFAULT);
                    return result;
                }

                @Override
                public void addResource(String name, Map<String, String> props, StandardContext context) throws ConfigException
                {
                    String displayName = props.remove("displayName");
                    if (displayName != null)
                    {
                        context.addParameter(name + ":DisplayName", displayName);
                    }
                    String logQueries = props.remove("logQueries");
                    if (logQueries != null)
                    {
                        context.addParameter(name + ":LogQueries", displayName);
                    }

                    super.addResource(name, props, context);
                }
            },
    jms
            {
                @Override
                protected Map<String, String> mergeWithDefaults(Map<String, String> props)
                {
                    Map<String, String> result = super.mergeWithDefaults(props);
                    result.putIfAbsent("type", "org.apache.activemq.ActiveMQConnectionFactory");
                    result.putIfAbsent("factory", "org.apache.activemq.jndi.JNDIReferenceFactory");
                    result.putIfAbsent("description", "JMS Connection Factory");
                    result.putIfAbsent("brokerName", "LocalActiveMQBroker");
                    return result;
                }
            },
    ldap
            {
                @Override
                protected Map<String, String> mergeWithDefaults(Map<String, String> props)
                {
                    Map<String, String> result = super.mergeWithDefaults(props);
                    result.putIfAbsent("type", "org.labkey.premium.ldap.LdapConnectionConfigFactory");
                    result.putIfAbsent("factory", "org.labkey.premium.ldap.LdapConnectionConfigFactory");
                    return result;
                }
            },
    generic;

    protected Map<String, String> mergeWithDefaults(Map<String, String> props)
    {
        Map<String, String> result = new CaseInsensitiveKeyMap<>();
        result.putAll(props);
        return result;
    }

    public void addResource(String name, Map<String, String> props, StandardContext context) throws ConfigException
    {
        props = mergeWithDefaults(props);
        ContextResource contextResource = new ContextResource();
        // Handle resource properties with explicit setters
        contextResource.setName(name);

        if (!props.containsKey("type"))
        {
            throw new ConfigException("Resource configuration error: 'type' is not defined for resource '%s'".formatted(name));
        }

        contextResource.setType(props.remove("type"));
        contextResource.setDescription(props.remove("description"));
        contextResource.setLookupName(props.remove("lookupName"));
        if (props.containsKey("scope"))
        {
            contextResource.setScope(props.remove("scope"));
        }
        contextResource.setAuth(Objects.requireNonNullElse(props.remove("auth"), "Container"));

        // Set remaining properties
        for (Map.Entry<String, String> prop : props.entrySet())
        {
            contextResource.setProperty(prop.getKey(), prop.getValue());
        }
        context.getNamingResources().addResource(contextResource);
    }
}
