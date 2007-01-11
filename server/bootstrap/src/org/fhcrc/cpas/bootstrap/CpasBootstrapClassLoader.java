package org.fhcrc.cpas.bootstrap;

import org.labkey.bootstrap.LabkeyServerBootstrapClassLoader;

/**
 * User: jeckels
 * Date: Jan 5, 2007
 */
public class CpasBootstrapClassLoader extends LabkeyServerBootstrapClassLoader
{
    public CpasBootstrapClassLoader()
    {
        super();
    }

    public CpasBootstrapClassLoader(ClassLoader parent)
    {
        super(parent);
    }
}
