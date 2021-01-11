# embedded
LabKey Server can now be started with embedded Tomcat. **This feature is in development phase and not ready for use in production. Free versions of IntelliJ may lack support for Spring boot debug configurations required to run the embedded Tomcat.**

### Setup
1. Within the root-level `gradle.properties` file, uncomment the `useEmbeddedTomcat` and `useLocalBuild` properties.  If using SSL, also uncomment the `useSsl` property (and see further instructions below).
2. `gradlew cleanBuild`
3. `gradlew pickPg` or `gradlew pickMSSQL`
4. `gradlew deployApp`
5. Within IntelliJ, do a Gradle Refresh in the Gradle Window
6. To start Tomcat within IntelliJ, select LabKeyEmbedded_Dev from the Run Configurations. (It will appear in a different section of the menu from the other LabKey configurations.)

#### Embedded tomcat gradle properties explained:
+ `useEmbeddedTomcat` - if present, this will cause the :server:embedded project to be included in your local set of Gradle projects to be built.  This also will affect the behavior of the `pickPg`, `pickMSSQL`, and `deployApp` tasks and is required to be present in order to build a distribution with an embedded Tomcat server. This property is present in the root-level `gradle.properties` file, but commented out by default.
+ `useLocalBuild` - If present, will allow the embedded server to use the locally built modules from `build/deploy/modules`.  This property is present in the root-level `gradle.properties` file, but commented out by default.
+ `useSsl` - If present, the default port for the server will be 8443 instead of 8080 and the `server.ssl` properties in the `application.properties` file will be uncommented.  Note, however, that these ssl properties will still need to be updated by hand to reflect your local certificate location, password, etc.

Note that it is only the presence of these properties that matter, not the value. Setting one of these properties to false will not achieve what you expect. In addition, there is a new property, `serverPort`, that can be used to override the default port for your server instance.

#### Updated and new gradle tasks
The following tasks have been updated with new logic based on the presence of the `useEmbeddedTomcat` property:

+ `startTomcat` - If `useEmbeddedTomcat` is defined, starts the server using whatever executable jar is in the `build/deploy/embedded directory`.  If there is more than one jar file there, the task will fail.
+ `stopTomcat` - If `useEmbeddedTomcat` is defined, stops the server that is running on the port provided in `build/deploy/embedded/config/application.properties`.  This uses the Spring Actuator so if you have a non-embedded server running that does not have the actuator, this command will have no effect. The command logs a message to give an indication of whether it was successful or not.  For example:
> Task :server:stopEmbeddedTomcat
Shutdown successful
OR
> Task :server:stopEmbeddedTomcat
Shutdown command exited with non-zero status 7.

+ `deployDistribution` - If `useEmbeddedTomcat` is defined, this will find a distribution file with the `-embedded` suffix in its name and deploy it.  The deployment puts the executable jar file in `build/deploy/embedded` and copies the contents of the `bin` directory in the distribution to `build/deploy/embedded/bin`. This task has a dependency on the `setup` task, so after it has run the `build/deploy/embedded/configs` directory will also be populated with the current `application.properties` file, provided the `useEmbeddedTomcat` property has been set.

The following task has been added as well
+ `cleanEmbeddedDeploy` - This task is really only incidental.  It is a dependency of `deployDistribution` and `deployApp` so likely won’t be called individually.  It removes the `build/deploy/embedded` directory.


#### Troubleshooting
+ If starting your server from the LabKeyEmbedded_Dev configuration fails, this is likely due to IntelliJ not being able to find the embedded project on which the configuration depends. There are a few things you should check:
    + Ensure that the `useEmbeddedTomcat` property is uncommented in the root-level `build.gradle` file
    + Within the Gradle window, ensure that the `:server:embedded` project is listed.  If it is not, run the task `gradle projects` on the command line to see if it appears in that listing.  If it does, try a Gradle refresh within IntelliJ.  If it is not in the output from the `projects` command, look at your `settings.gradle` file to see why this might be.
    + From the Configurations menu, choose the "Edit Configurations ..." and then under the Spring Boot section, choose the `LabKeyEmbedded_Dev` configuration.
    + If the there is nothing selected for "Use classpath of module", open the dropdown and choose `<root>.server.embedded.main`, where `<root>` is the name of the root of your enlistment.
    + If there are no options presented for "Use classpath of module" and the embedded module does appear in the Gradle projects listing, try `File -> Invalidate Caches / Restart`
