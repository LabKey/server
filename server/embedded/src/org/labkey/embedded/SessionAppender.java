/*
 * Copyright (c) 2024-2026 LabKey Corporation
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
package org.labkey.embedded;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;

import java.io.Serializable;
import java.util.function.Consumer;

/**
 * As configured in log4j2.xml, captures logging from server-side JavaScript to make it available through the
 * web UI's Server JavaScript Console. Since Log4J is initialized in the outer Spring Boot code, this loads early and
 * separate from the webapp. Thus, it lets the webapp register a consumer for the LogEvents, which this class
 * forwards along.
 */
@Plugin(name = "SessionAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE, printObject = true)
public class SessionAppender extends AbstractAppender
{
    @PluginFactory
    public static SessionAppender createAppender(@PluginAttribute("name") String name,
                                                 @PluginElement("Layout") Layout<? extends Serializable> layout,
                                                 @PluginElement("Filter") final Filter filter)
    {
        return new SessionAppender(name, filter, layout, false, null);
    }

    protected SessionAppender(String name, Filter filter, Layout<? extends Serializable> layout, boolean ignoreExceptions, Property[] properties)
    {
        super(name, filter, layout, ignoreExceptions, properties);
    }

    /** Set via reflection in org.labkey.api.util.SessionAppender */
    public static Consumer<LogEvent> _consumer;

    @Override
    public void append(LogEvent event)
    {
        if (_consumer != null)
        {
            _consumer.accept(event);
        }
    }
}
