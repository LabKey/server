package org.labkey.bootstrap;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.reflect.Method;

/**
 * User: jeckels
 * Date: Apr 10, 2008
 */
public class ClusterBootstrap
{
    public static void main(String... args) throws Exception
    {
        ModuleExtractor extractor = new ModuleExtractor(Arrays.asList(new File(".")));
        ExtractionResult extractionResult = extractor.extractModules(null);
        List<File> jarFiles = extractionResult.getJarFiles();

        List<URL> urls = new ArrayList<URL>();
        for (File jarFile : jarFiles)
        {
            urls.add(jarFile.toURI().toURL());
        }
        
        File webappDir = new File(new File(".").getCanonicalFile().getParentFile(), "explodedWar");
        File webinf = new File(webappDir, "WEB-INF");
        File lib = new File(webinf, "lib");
        for (File file : lib.listFiles())
        {
            urls.add(file.toURI().toURL());
        }
        urls.add(new File("c:/tomcat/common/lib/servlet-api.jar").toURI().toURL());

        URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), ClusterBootstrap.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(classLoader);

        Class runnerClass = classLoader.loadClass("org.labkey.pipeline.cluster.ClusterJobRunner");
        Object runner = runnerClass.newInstance();
        Method runMethod = runnerClass.getMethod("run", List.class, String[].class);
        runMethod.invoke(runner, new Object[] { extractionResult.getSpringConfigFiles(), args });
    }
}
