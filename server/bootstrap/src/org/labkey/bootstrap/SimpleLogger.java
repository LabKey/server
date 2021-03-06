/*
 * Copyright (c) 2009-2018 LabKey Corporation
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

/**
 * Simple interface to abstract logger implementations and break dependencies on other JARs when they're not needed.
 * User: jeckels
 * Date: Mar 11, 2009
 */
public interface SimpleLogger
{
    public void error(Object message, Throwable t);
    public void error(Object message);
    public void info(Object message);
}
