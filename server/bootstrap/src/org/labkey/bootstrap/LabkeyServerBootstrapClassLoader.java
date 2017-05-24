package org.labkey.bootstrap;

/**
 * Here for backwards compatibility with labkey.xml (or similar) deployment descriptors that still refer to the this class
 * by name. Using LabKeyBootstrapClassLoader is the preferred class.
 */
@Deprecated
public class LabkeyServerBootstrapClassLoader extends LabKeyBootstrapClassLoader
{
    public LabkeyServerBootstrapClassLoader()
    {
        super();
    }

    public LabkeyServerBootstrapClassLoader(ClassLoader parent)
    {
        super(parent);
    }
}
