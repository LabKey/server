#if (${PACKAGE_NAME} && ${PACKAGE_NAME} != "")package ${PACKAGE_NAME};#end

import org.labkey.test.Locator;
import org.labkey.test.WebDriverWrapper;
import org.labkey.test.WebTestHelper;
import org.labkey.test.pages.LabKeyPage;
import org.labkey.test.selenium.LazyWebElement;
import org.openqa.selenium.SearchContext;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

// TODO: Page classes should contain all functionality for a single page/action
public class ${NAME} extends LabKeyPage<${NAME}.ElementCache>
{
    public ${NAME}(WebDriver driver)
    {
        super(driver);
    }

    public static ${NAME} beginAt(WebDriverWrapper driver)
    {
        return beginAt(driver, driver.getCurrentContainerPath());
    }

    public static ${NAME} beginAt(WebDriverWrapper driver, String containerPath)
    {
        driver.beginAt(WebTestHelper.buildURL("controller", containerPath, "action"));
        return new ${NAME}(driver.getDriver());
    }

    // TODO: Add methods for other actions on this page
    public LabKeyPage clickButton()
    {
        clickAndWait(elementCache().example);
        
        // TODO: Methods that navigate should return an appropriate page object
        return new LabKeyPage(getDriver());
    }

    protected ElementCache newElementCache()
    {
        return new ElementCache();
    }

    protected class ElementCache extends LabKeyPage.ElementCache
    {
        // TODO: Add other elements that are on the page
        WebElement example = Locator.css("button").findWhenNeeded(this);
    }
}
