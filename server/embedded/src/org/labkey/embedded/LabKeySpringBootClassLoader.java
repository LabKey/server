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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.labkey.bootstrap.LabKeyBootstrapClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Variant of the classloader that supports Spring Boot by deferring to the parent classloader for SLF4J and Log4J classes
 * to avoid duplicate copies (even if they're the same version) between the parent and webapp classloaders.
 */
public class LabKeySpringBootClassLoader extends LabKeyBootstrapClassLoader
{
    private static final Logger LOG = LogManager.getLogger(LabKeySpringBootClassLoader.class);

    public LabKeySpringBootClassLoader()
    {
        super();
    }

    public LabKeySpringBootClassLoader(ClassLoader parent)
    {
        super(parent);

        // HACK: This ensures that the jar containing our canonical log4j2.xml file gets added first, so it's found
        // even if dependencies include their own version. See Issue 51286.
        if (parent instanceof URLClassLoader ucl)
        {
            Arrays.stream(ucl.getURLs())
                .forEach(url -> {
                    // Test this url via a class loader with no parent; if URL resolves a log4j2.xml file, add it and log.
                    try (URLClassLoader loader = new URLClassLoader(new URL[]{url}, null); InputStream is = loader.getResourceAsStream("log4j2.xml"))
                    {
                        if (is != null)
                        {
                            addURL(url);
                            LOG.info("Added URL that resolves log4j2.xml to class loader: {}", url);
                        }
                    }
                    catch (IOException e)
                    {
                        throw new RuntimeException(e);
                    }
                });
        }
    }

    @Override
    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        // Hack to make us only load one copy of SessionAppender. It loads via a parent classloader early
        // during Log4J initialization, and if we load a second copy the log events aren't captured in a place
        // where we can expose them to users in the Server JavaScript Console.
        if (name.equals("org.labkey.embedded.SessionAppender"))
        {
            ClassLoader parent = getParent();
            while (parent != null)
            {
                LOG.debug("Looking for SessionAppending - checking ClassLoader {}", parent);
                if (parent.getClass().getName().equals("jdk.internal.loader.ClassLoaders$AppClassLoader") ||
                        parent.getClass().getName().equals("org.springframework.boot.loader.launch.LaunchedClassLoader"))
                {
                    return parent.loadClass(name);
                }
                parent = parent.getParent();
            }
            LOG.error("Failed to find AppClassLoader. The Server JavaScript Console will not be available");
        }
        return super.loadClass(name, resolve);
    }

    @Override
    protected boolean filter(String name, boolean isClassName)
    {
        // Defer to the Spring Boot classloader for SLF4J and Log4J classes to avoid problems with double-loading.
        // Eventually we should shift to only shipping SLF4J and Log4J via Spring Boot and not inside the webapp.
        if (name.startsWith("org.slf4j.") || name.startsWith("org.apache.logging.log4j."))
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
