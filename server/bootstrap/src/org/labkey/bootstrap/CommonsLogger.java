/*
 * Copyright (c) 2009-2013 LabKey Corporation
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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * User: jeckels
 * Date: Mar 11, 2009
 */
public class CommonsLogger implements SimpleLogger
{
    private Object _log = null;
    private Method _errorEx = null;
    private Method _error = null;
    private Method _info = null;

    public CommonsLogger(Class c)
    {
        try
        {
            // The Tomcat 6 compatible approach
            _log = getFactoryClass("org.apache.juli.logging.LogFactory", c);
            // The implementation class is package protected, but the interface is public

            // Class or interface that declares the methods that we'll be permitted to call
            Class<Object> interfaceClass = (Class<Object>) Class.forName("org.apache.juli.logging.Log");

            _errorEx = interfaceClass.getMethod("error", Object.class, Throwable.class);
            _error = interfaceClass.getMethod("error", Object.class);
            _info = interfaceClass.getMethod("info", Object.class);
        }
        catch (Exception x)
        {
            System.err.println("CommonsLogger: not initialized");
            x.printStackTrace(System.err);
        }
    }

    private Object getFactoryClass(String factoryClassName, Class logTarget) throws ClassNotFoundException, NoSuchMethodException, IllegalAccessException, InvocationTargetException
    {
        Class factoryClass = Class.forName(factoryClassName);
        Method getLog = factoryClass.getMethod("getLog", Class.class);
        return getLog.invoke(null, logTarget);
    }


    public void error(Object message, Throwable t)
    {
        try
        {
            if (null != _log && null != _error)
                _errorEx.invoke(_log, message, t);
        }
        catch (Exception ignored)
        {
            
        }
    }


    public void error(Object message)
    {
        try
        {
            if (null != _log && null != _error)
                _error.invoke(_log, message);
        }
        catch (Exception ignored)
        {

        }
    }


    public void info(Object message)
    {
        try
        {
            if (null != _log && null != _error)
                _info.invoke(_log, message);
        }
        catch (Exception ignored)
        {

        }
    }
}
