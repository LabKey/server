package org.labkey.embedded;

import org.labkey.bootstrap.LabKeyBootstrapClassLoader;

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
    }

    @Override
    protected boolean filter(String name, boolean isClassName)
    {
        if (name.startsWith("org.slf4j."))
        {
            return true;
        }
        return super.filter(name, isClassName);
    }
}
