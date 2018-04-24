#if (${PACKAGE_NAME} && ${PACKAGE_NAME} != "")package ${PACKAGE_NAME};#end

import org.labkey.test.Locator;
import org.labkey.test.components.WebDriverComponent;
import org.labkey.test.components.html.Input;
import org.labkey.test.pages.LabKeyPage;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

import static org.labkey.test.components.html.Input.Input;

// TODO: Component classes should contain all functionality for a component
public class ${NAME} extends WebDriverComponent<${NAME}.ElementCache>
{
    final WebElement _el;
    final WebDriver _driver;

    public ${NAME}(WebElement element, WebDriver driver)
    {
        _el = element;
        _driver = driver;
    }

    @Override
    public WebElement getComponentElement()
    {
        return _el;
    }

    @Override
    public WebDriver getDriver()
    {
        return _driver;
    }

    // TODO: Add methods for actual interactions
    public ${NAME} setInput(String value)
    {
        elementCache().input.set(value);

        // TODO: Methods that don't navigate should return this object
        return this;
    }

    public LabKeyPage clickButton()
    {
        getWrapper().clickAndWait(elementCache().button);

        // TODO: Methods that navigate should return an appropriate page object
        return new LabKeyPage(getDriver());
    }

    protected ElementCache newElementCache()
    {
        return new ElementCache();
    }

    protected class ElementCache extends WebDriverComponent.ElementCache
    {
        // TODO: Add elements that are in the component
        Input input = Input(Locator.css("input"), getDriver()).findWhenNeeded(this);
        WebElement button = Locator.css("button").findWhenNeeded(this);
    }
}
