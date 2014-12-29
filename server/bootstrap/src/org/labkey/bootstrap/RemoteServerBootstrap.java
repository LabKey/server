/*
 * Copyright (c) 2008-2014 LabKey Corporation
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
package org.labkey.bootstrap;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/*
* User: jeckels
* Date: Jun 26, 2008
*/
public class RemoteServerBootstrap
{
    public static void main(String... rawArgs)
    {
        try
        {
            PipelineBootstrapConfig config = null;
            try
            {
                config = new PipelineBootstrapConfig(rawArgs, true);
            }
            catch (ConfigException e)
            {
                printUsage(e.getMessage());
                System.exit(1);
            }

            PipelineBootstrapConfig.ensureLogHomeSet(config.getLogDir().getAbsolutePath());

            ClassLoader classLoader = config.getClassLoader();
            Thread.currentThread().setContextClassLoader(classLoader);

            Class runnerClass = classLoader.loadClass("org.labkey.pipeline.mule.RemoteServerStartup");
            Object runner = runnerClass.newInstance();
            Method runMethod = runnerClass.getMethod("run", List.class, List.class, List.class, File.class, String[].class);

            runMethod.invoke(runner, config.getModuleFiles(), config.getModuleSpringConfigFiles(), config.getCustomSpringConfigFiles(), config.getWebappDir(), config.getProgramArgs());

            synchronized(runner)
            {
                runner.wait();
            }
        }
        catch (Throwable t)
        {
            t.printStackTrace(System.err);
        }
    }

    private static void printUsage(String error)
    {
        if (error != null)
        {
            System.err.println(error);
            System.err.println();
        }

        System.err.println("java " + RemoteServerBootstrap.class + " [-" + PipelineBootstrapConfig.WEBAPP_DIR + "=<WEBAPP_DIR>] [-" + PipelineBootstrapConfig.CONFIG_DIR + "=<CONFIG_DIR>] [-" + PipelineBootstrapConfig.PIPELINE_LIB_DIR + "=<PIPELINE_LIB_DIR>]");

        System.exit(1);
    }
}
