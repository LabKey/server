/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.labkey.embedded;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import javax.script.SimpleBindings;

import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.appender.rolling.action.PathWithAttributes;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.script.AbstractScript;
import org.apache.logging.log4j.core.script.ScriptFile;
import org.apache.logging.log4j.core.script.ScriptRef;
import org.apache.logging.log4j.status.StatusLogger;

/**
 * A condition of the {@link LabKeyDeleteAction} where a user-provided script selects the files to delete from a provided
 * list. The specified script may be a {@link org.apache.logging.log4j.core.script.Script}, a {@link ScriptFile} or a {@link ScriptRef}.
 *
 * @see #createCondition(AbstractScript, Configuration)
 */
@Plugin(name = "LabKeyDeleteCondition", category = Core.CATEGORY_NAME, printObject = true)
public class LabKeyDeleteCondition
{
    private final Configuration configuration;

    /**
     * Constructs a new ScriptCondition.
     *
     * @param configuration configuration containing the StrSubstitutor passed to the script
     */
    public LabKeyDeleteCondition(final Configuration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
    }

    /**
     * Executes the script
     *
     * @param basePath base directory for files to delete
     * @param candidates a list of paths, that can be deleted by the script
     * @return a list of paths selected to delete by the script execution
     */
    @SuppressWarnings("unchecked")
    public List<PathWithAttributes> selectFilesToDelete(final Path basePath, final List<PathWithAttributes> candidates)
    {
        final SimpleBindings bindings = new SimpleBindings();
        bindings.put("basePath", basePath);
        bindings.put("pathList", candidates);
        bindings.putAll(configuration.getProperties());
        bindings.put("configuration", configuration);
        bindings.put("substitutor", configuration.getStrSubstitutor());
        return (List<PathWithAttributes>) candidates;
    }

    /**
     * Creates the LabKeyDeleteCondition.
     *
     * @param script The script to run. This may be a {@link org.apache.logging.log4j.core.script.Script}, a {@link ScriptFile} or a {@link ScriptRef}. The
     *            script must return a {@code List<PathWithAttributes>}. When the script is executed, it is provided the
     *            following bindings:
     *            <ul>
     *            <li>basePath - the directory from where the {@link LabKeyDeleteAction Delete} action started scanning for
     *            files to delete. Can be used to relativize the paths in the pathList.</li>
     *            <li>pathList - a {@code java.util.List} containing {@link org.apache.logging.log4j.core.appender.rolling.action.PathWithAttributes} objects. (The script is
     *            free to modify and return this list.)</li>
     *            <li>substitutor - a {@link org.apache.logging.log4j.core.lookup.StrSubstitutor} that can be used to look up variables embedded in the base
     *            dir or other properties
     *            <li>statusLogger - the {@link StatusLogger} that can be used to log events during script execution
     *            <li>any properties declared in the configuration</li>
     *            </ul>
     * @param configuration the configuration
     * @return A ScriptCondition.
     */
    @PluginFactory
    public static LabKeyDeleteCondition createCondition(@PluginConfiguration final Configuration configuration)
    {
        return new LabKeyDeleteCondition(configuration);
    }
}
