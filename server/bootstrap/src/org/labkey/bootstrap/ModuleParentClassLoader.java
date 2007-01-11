package org.labkey.bootstrap;

import java.io.InputStream;

/**
 * User: jeckels
 * Date: Sep 12, 2006
 */
class ModuleParentClassLoader extends ClassLoader
{
    private LabkeyServerBootstrapClassLoader _webappLoader;

    protected ModuleParentClassLoader(LabkeyServerBootstrapClassLoader parent)
    {
        _webappLoader = parent;
    }

    public Class<?> loadClass(String name) throws ClassNotFoundException
    {
        return _webappLoader.loadClass(name, false, false);
    }

    public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
    {
        return _webappLoader.loadClass(name, resolve, false);
    }

    public InputStream getResourceAsStream(String name)
    {
        return _webappLoader.getResourceAsStream(name, false);
    }
}
