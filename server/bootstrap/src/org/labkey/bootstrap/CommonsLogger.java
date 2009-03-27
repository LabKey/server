/*
 * Copyright (c) 2009 LabKey Corporation
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * User: jeckels
 * Date: Mar 11, 2009
 */
public class CommonsLogger implements SimpleLogger
{
    private final Log _log;

    public CommonsLogger(Class c)
    {
        _log = LogFactory.getLog(c);
    }

    public void error(Object message, Throwable t)
    {
        _log.error(message, t);
    }

    public void error(Object message)
    {
        _log.error(message);
    }

    public void info(Object message)
    {
        _log.info(message);
    }
}
