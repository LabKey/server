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
        ${NAME} init = (${NAME})getCurrentTest();
       
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
