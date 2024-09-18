package org.labkey.embedded;

import org.labkey.bootstrap.LabKeyBootstrapClassLoader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Variant of the classloader that supports Spring Boot by deferring to the parent classloader for SLF4J classes
 * to avoid conflicting copies (even if they're the same version) between the parent and webapp classloaders.
 */
public class LabKeySpringBootClassLoader extends LabKeyBootstrapClassLoader
{
    public LabKeySpringBootClassLoader()
    {
        super();
    }

    public LabKeySpringBootClassLoader(ClassLoader parent)
    {
        super(parent);

        // HACK: This ensures that the "embedded" URLs get added first, so our canonical log4j2.xml file is found even
        // if dependencies include their own version. See Issue 51286.
        if (parent instanceof URLClassLoader ucl)
        {
            Arrays.stream(ucl.getURLs())
                .forEach(this::addURL);
        }
    }

    @Override
    protected boolean filter(String name, boolean isClassName)
    {
        // Defer to the Spring Boot classloader for SLF4J classes to avoid problems with double-loading.
        // Eventually we should shift to only configuring and loading SLF4J and Log4J via Spring Boot and not
        // from inside the webapp.
        if (name.startsWith("org.slf4j."))
        {
            return true;
        }
        return super.filter(name, isClassName);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        // Spring Boot has its own Log4J configuration files that come from Spring Boot JARs. Log4J uses
        // number of ServiceLoader-based configurations that enumerate possible sources of additional configuration.
        // It iterates through them all to see if they're present. We need to filter out the ones that are coming
        // from Spring Boot because the Log4J loaded within the webapp classloader will fall back to them
        // and cause problems because they're handled by the outer classloader.

        // Eventually we should shift to only configuring and loading SLF4J and Log4J via Spring Boot and not
        // from inside the webapp.
        if (name.equalsIgnoreCase("META-INF/services/org.apache.logging.log4j.util.PropertySource") ||
            name.equalsIgnoreCase("META-INF/services/org.apache.logging.log4j.spi.Provider") ||
            name.equalsIgnoreCase("META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat"))
        {
            List<URL> urls = new ArrayList<>();
            Enumeration<URL> parent = super.getResources(name);
            while (parent.hasMoreElements())
            {
                URL url = parent.nextElement();

                // Filter out the references to Spring Boot scoped JARs
                if (!url.toString().contains("spring-boot") && !url.toString().contains("log4j-to-slf4j"))
                {
                    urls.add(url);
                }
            }
            return Collections.enumeration(urls);
        }
        return super.getResources(name);
    }
}
