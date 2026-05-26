/*
 * Copyright (c) 2018-2026 LabKey Corporation
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
#if (${PACKAGE_NAME} && ${PACKAGE_NAME} != "")package ${PACKAGE_NAME};#end

import org.junit.BeforeClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.labkey.test.BaseWebDriverTest;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@Category({})
public class ${NAME} extends BaseWebDriverTest
{
    private static final String USER = "template_user@${NAME.toLowerCase()}.test";

    @Override
    protected void doCleanup(boolean afterTest)
    {
        _containerHelper.deleteProject(getProjectName(), afterTest);
        _userHelper.deleteUsers(afterTest, USER);
    }

    @BeforeClass
    public static void setupProject()
    {
        ${NAME} init = getCurrentTest();
       
        init.doSetup();
    }
   
    private void doSetup()
    {
        _containerHelper.createProject(getProjectName(), null);
        _userHelper.createUser(USER);
    }

    @Before
    public void preTest()
    {
        goToProjectHome(); // TODO: Remove if this is not necessary before each test
    }

    @Test
    public void testSomething()
    {
        assertTrue("Failing stub test", false);
    }

    @Override
    protected String getProjectName()
    {
        return "${NAME} Project";
    }

    @Override
    public List<String> getAssociatedModules()
    {
        return Arrays.asList();
    }
}
