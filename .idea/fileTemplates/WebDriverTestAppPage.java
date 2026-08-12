#if (${PACKAGE_NAME} && ${PACKAGE_NAME} != "")package ${PACKAGE_NAME};#end

import org.labkey.test.WebDriverWrapper;
import org.labkey.test.WebTestHelper;
import org.labkey.test.pages.AppBasePage;
import org.labkey.test.tests.BaseAppTest;
import org.openqa.selenium.WebDriver;

// TODO: Page classes should contain all functionality for a single app page
public class ${NAME} extends AppBasePage<${NAME}.ElementCache>
{
    public ${NAME}(WebDriver driver)
    {
        super(driver);
    }

    public static ${NAME} beginAt(BaseAppTest test, String containerPath)
    {
        return beginAt(test, containerPath, test.getAppControllerName());
    }

    public static ${NAME} beginAt(WebDriverWrapper driver, String containerPath, String controllerName)
    {
        driver.beginAt(WebTestHelper.buildAppURL(containerPath, controllerName, "path", "parts"));
        return new ${NAME}(driver.getDriver());
    }

    @Override
    protected ElementCache newElementCache()
    {
        return new ElementCache();
    }

    protected class ElementCache extends AppBasePage<ElementCache>.ElementCache
    {
    }
}
