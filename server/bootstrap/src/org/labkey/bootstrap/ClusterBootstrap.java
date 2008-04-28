package org.labkey.bootstrap;

import java.io.File;
import java.io.FileFilter;
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
    private static final String MODULES_DIR = "modulesdir";
    private static final String CONFIG_DIR = "configdir";
    private static final String WEBAPP_DIR = "webappdir";

    public static void main(String... rawArgs) throws Exception
    {
        File modulesDir = new File(".").getAbsoluteFile();
        
        ArgumentParser args = new ArgumentParser(rawArgs);
        if (args.hasOption(MODULES_DIR))
        {
            modulesDir = new File(args.getOption(MODULES_DIR)).getAbsoluteFile();
        }

        if (!modulesDir.isDirectory())
        {
            printUsage("Could not find modules directory at " + modulesDir.getAbsolutePath());
        }

        File webappDir = new File(modulesDir.getCanonicalFile().getParentFile(), "explodedWar");
        if (args.hasOption(WEBAPP_DIR))
        {
            webappDir = new File(args.getOption(WEBAPP_DIR)).getAbsoluteFile();
        }

        if (!webappDir.isDirectory())
        {
            printUsage("Could not find webapp directory at " + modulesDir.getAbsolutePath());
        }

        File webinfDir = new File(webappDir, "WEB-INF");
        File libDir = new File(webinfDir, "lib");

        if (!libDir.isDirectory())
        {
            printUsage("Could not find subdirectory WEB-INF/lib in webapp, expected to be at " + libDir.getAbsolutePath());
        }

        ModuleExtractor extractor = new ModuleExtractor(Arrays.asList(modulesDir));
        ExtractionResult extractionResult = extractor.extractModules(null);
        List<File> jarFiles = extractionResult.getJarFiles();

        List<URL> urls = new ArrayList<URL>();
        for (File jarFile : jarFiles)
        {
            urls.add(jarFile.toURI().toURL());
        }

        for (File file : libDir.listFiles())
        {
            urls.add(file.toURI().toURL());
        }

        List<File> configFiles = extractionResult.getSpringConfigFiles();
        if (args.hasOption(CONFIG_DIR))
        {
            File configDir = new File(args.getOption(CONFIG_DIR));
            if (!configDir.isDirectory())
            {
                printUsage("Could not find configuration directory at " + configDir.getAbsolutePath());
            }

            addConfigFiles(configDir, configFiles);
        }

        URLClassLoader classLoader = new URLClassLoader(urls.toArray(new URL[urls.size()]), ClusterBootstrap.class.getClassLoader());
        Thread.currentThread().setContextClassLoader(classLoader);

        Class runnerClass = classLoader.loadClass("org.labkey.pipeline.cluster.ClusterJobRunner");
        Object runner = runnerClass.newInstance();
        Method runMethod = runnerClass.getMethod("run", List.class, String[].class);

        runMethod.invoke(runner, configFiles, args.getParameters().toArray(new String[args.getParameters().size()]));
    }

    // Traverse the directory structure looking for files that match **/*.xml
    private static void addConfigFiles(File configDir, List<File> configFiles)
    {
        File[] subDirs = configDir.listFiles(new FileFilter()
        {
            public boolean accept(File pathname)
            {
                return pathname.isDirectory();
            }
        });
        for (File subDir : subDirs)
        {
            addConfigFiles(subDir, configFiles);
        }

        File[] xmlFiles = configDir.listFiles(new FileFilter()
        {
            public boolean accept(File pathname)
            {
                return pathname.getName().toLowerCase().endsWith(".xml");
            }
        });
        configFiles.addAll(Arrays.asList(xmlFiles));
    }

    private static void printUsage(String error)
    {
        if (error != null)
        {
            System.err.println(error);
            System.err.println();
        }

        System.err.println("java " + ClusterBootstrap.class + " [-" + MODULES_DIR + "=<MODULE_DIR>] [-" + WEBAPP_DIR + "=<WEBAPP_DIR>] [-" + CONFIG_DIR + "=<CONFIG_DIR>] <JOB_XML_FILE>");

        System.exit(1);
    }
}
