/**
 * $RCSfile$
 * $Revision: 3001 $
 * $Date: 2005-10-31 05:39:25 -0300 (Mon, 31 Oct 2005) $
 *
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.container;

import org.dom4j.Attribute;
import org.dom4j.Document;
import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import org.jivesoftware.admin.AdminConsole;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.LocaleUtils;
import org.jivesoftware.util.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Loads and manages plugins. The <tt>plugins</tt> directory is monitored for any
 * new plugins, and they are dynamically loaded.
 *
 * <p>An instance of this class can be obtained using:</p>
 *
 * <tt>XMPPServer.getInstance().getPluginManager()</tt>
 *
 * @author Matt Tucker
 * @see Plugin
 * @see org.jivesoftware.openfire.XMPPServer#getPluginManager()
 */
public class PluginManager
{
    private static final Logger Log = LoggerFactory.getLogger( PluginManager.class );

    private final Path pluginDirectory;
    private final Map<String, Plugin> plugins = new TreeMap<>( String.CASE_INSENSITIVE_ORDER );
    private final Map<Plugin, PluginClassLoader> classloaders = new HashMap<>();
    private final Map<Plugin, Path> pluginDirs = new HashMap<>();
    private final Map<Plugin, PluginDevEnvironment> pluginDevelopment = new HashMap<>();
    private final Map<Plugin, List<String>> parentPluginMap = new HashMap<>();
    private final Map<Plugin, String> childPluginMap = new HashMap<>();
    private final Set<PluginListener> pluginListeners = new CopyOnWriteArraySet<>();
    private final Set<PluginManagerListener> pluginManagerListeners = new CopyOnWriteArraySet<>();
    private final Map<String, Integer> failureToLoadCount = new HashMap<>();

    private final PluginMonitor pluginMonitor;
    private boolean executed = false;

    /**
     * Constructs a new plugin manager.
     *
     * @param pluginDir the directory containing all Openfire plugins, typically OPENFIRE_HOME/plugins/
     */
    public PluginManager( File pluginDir )
    {
        this.pluginDirectory = pluginDir.toPath();
        pluginMonitor = new PluginMonitor( this );
    }

    /**
     * Starts plugins and the plugin monitoring service.
     */
    public synchronized void start()
    {
        pluginMonitor.start();
    }

    /**
     * Shuts down all running plugins.
     */
    public synchronized void shutdown()
    {
        Log.info( "Shutting down. Unloading all installed plugins..." );

        // Stop the plugin monitoring service.
        pluginMonitor.stop();

        // Shutdown all installed plugins.
        for ( Map.Entry<String, Plugin> plugin : plugins.entrySet() )
        {
            try
            {
                plugin.getValue().destroyPlugin();
                Log.info( "Unloaded plugin '{}'.", plugin.getKey() );
            }
            catch ( Exception e )
            {
                Log.error( "An exception occurred while trying to unload plugin '{}':", plugin.getKey(), e );
            }
        }
        plugins.clear();
        pluginDirs.clear();
        classloaders.clear();
        pluginDevelopment.clear();
        childPluginMap.clear();
        failureToLoadCount.clear();
    }

    /**
     * Returns the directory that contains all plugins. This typically is OPENFIRE_HOME/plugins.
     *
     * @return The directory that contains all plugins.
     */
    public Path getPluginsDirectory()
    {
        return pluginDirectory;
    }

    /**
     * Installs or updates an existing plugin.
     *
     * @param in the input stream that contains the new plugin definition.
     * @param pluginFilename the filename of the plugin to create or update.
     * @return true if the plugin was successfully installed or updated.
     */
    public boolean installPlugin( InputStream in, String pluginFilename )
    {
        if ( pluginFilename == null || pluginFilename.isEmpty() )
        {
            Log.error( "Error installing plugin: pluginFilename was null or empty." );
            return false;
        }
        if ( in == null )
        {
            Log.error( "Error installing plugin '{}': Input stream was null.", pluginFilename );
            return false;
        }
        try
        {
            // If pluginFilename is a path instead of a simple file name, we only want the file name
            int index = pluginFilename.lastIndexOf( File.separator );
            if ( index != -1 )
            {
                pluginFilename = pluginFilename.substring( index + 1 );
            }
            // Absolute path to the plugin file
            Path absolutePath = pluginDirectory.resolve( pluginFilename );
            Path partFile = pluginDirectory.resolve( pluginFilename + ".part" );
            // Save input stream contents to a temp file
            Files.copy( in, partFile, StandardCopyOption.REPLACE_EXISTING );

            // Rename temp file to .jar
            Files.move( partFile, absolutePath, StandardCopyOption.REPLACE_EXISTING );
            // Ask the plugin monitor to update the plugin immediately.
            pluginMonitor.runNow( true );
        }
        catch ( IOException e )
        {
            Log.error( "An exception occurred while installing new version of plugin '{}':", pluginFilename, e );
            return false;
        }
        return true;
    }

    /**
     * Returns true if the specified filename, that belongs to a plugin, exists.
     *
     * @param pluginFilename the filename of the plugin to create or update.
     * @return true if the specified filename, that belongs to a plugin, exists.
     */
    public boolean isPluginDownloaded( String pluginFilename )
    {
        return Files.exists( pluginDirectory.resolve( pluginFilename ) );
    }

    /**
     * Returns a Collection of all installed plugins.
     *
     * @return a Collection of all installed plugins.
     */
    public Collection<Plugin> getPlugins()
    {
        return Collections.unmodifiableCollection( Arrays.asList( plugins.values().toArray( new Plugin[ plugins.size() ] ) ) );
    }

    /**
     * Returns a plugin by name or <tt>null</tt> if a plugin with that name does not
     * exist. The name is the name of the directory that the plugin is in such as
     * "broadcast".
     *
     * @param name the name of the plugin.
     * @return the plugin.
     */
    public Plugin getPlugin( String name )
    {
        return plugins.get( name );
    }

    /**
     * @deprecated Use #getPluginPath() instead.
     */
    @Deprecated
    public File getPluginDirectory( Plugin plugin )
    {
        return getPluginPath( plugin ).toFile();
    }

    /**
     * Returns the plugin's directory.
     *
     * @param plugin the plugin.
     * @return the plugin's directory.
     */
    public Path getPluginPath( Plugin plugin )
    {
        return pluginDirs.get( plugin );
    }

    /**
     * Returns true if at least one attempt to load plugins has been done. A true value does not mean
     * that available plugins have been loaded nor that plugins to be added in the future are already
     * loaded. :)<p>
     *
     * @return true if at least one attempt to load plugins has been done.
     */
    public boolean isExecuted()
    {
        return executed;
    }

    /**
     * Loads a plugin.
     *
     * @param pluginDir the plugin directory.
     */
    boolean loadPlugin( Path pluginDir )
    {
        // Only load the admin plugin during setup mode.
        final String pluginName = pluginDir.getFileName().toString();
        if ( XMPPServer.getInstance().isSetupMode() && !( pluginName.equals( "admin" ) ) )
        {
            return false;
        }

        if ( failureToLoadCount.containsKey( pluginName ) && failureToLoadCount.get( pluginName ) > JiveGlobals.getIntProperty( "plugins.loading.retries", 5 ) )
        {
            Log.debug( "The unloaded file for plugin '{}' is silently ignored, as it has failed to load repeatedly.", pluginName );
            return false;
        }

        Log.debug( "Loading plugin '{}'...", pluginName );
        try
        {
            final Path pluginConfig = pluginDir.resolve( "plugin.xml" );
            if ( !Files.exists( pluginConfig ) )
            {
                Log.warn( "Plugin '{}' could not be loaded: no plugin.xml file found.", pluginName );
                failureToLoadCount.put( pluginName, Integer.MAX_VALUE ); // Don't retry - this cannot be recovered from.
                return false;
            }

            final SAXReader saxReader = new SAXReader();
            saxReader.setEncoding( "UTF-8" );
            final Document pluginXML = saxReader.read( pluginConfig.toFile() );

            // See if the plugin specifies a version of Openfire required to run.
            final Element minServerVersion = (Element) pluginXML.selectSingleNode( "/plugin/minServerVersion" );
            if ( minServerVersion != null )
            {
                final Version requiredVersion = new Version( minServerVersion.getTextTrim() );
                final Version currentVersion = XMPPServer.getInstance().getServerInfo().getVersion();
                if ( requiredVersion.isNewerThan( currentVersion ) )
                {
                    Log.warn( "Ignoring plugin '{}': requires server version {}. Current server version is {}.", pluginName, requiredVersion, currentVersion );
                    failureToLoadCount.put( pluginName, Integer.MAX_VALUE ); // Don't retry - this cannot be recovered from.
                    return false;
                }
            }

            // Properties to be used to load external resources. When set, plugin is considered to run in DEV mode.
            final String devModeClassesDir = System.getProperty( pluginName + ".classes" );
            final String devModewebRoot = System.getProperty( pluginName + ".webRoot" );
            final boolean devMode = devModewebRoot != null || devModeClassesDir != null;
            final PluginDevEnvironment dev = ( devMode ? configurePluginDevEnvironment( pluginDir, devModeClassesDir, devModewebRoot ) : null );

            // Initialize the plugin class loader, which is either a new instance, or a the loader from a parent plugin.
            final PluginClassLoader pluginLoader;

            // Check to see if this is a child plugin of another plugin. If it is, we re-use the parent plugin's class
            // loader so that the plugins can interact.
            String parentPluginName = null;
            Plugin parentPlugin = null;
            final Element parentPluginNode = (Element) pluginXML.selectSingleNode( "/plugin/parentPlugin" );
            if ( parentPluginNode != null )
            {
                // The name of the parent plugin as specified in plugin.xml might have incorrect casing. Lookup the correct name.
                for ( final Map.Entry<String, Plugin> entry : plugins.entrySet() )
                {
                    if ( entry.getKey().equalsIgnoreCase( parentPluginNode.getTextTrim() ) )
                    {
                        parentPluginName = entry.getKey();
                        parentPlugin = entry.getValue();
                        break;
                    }
                }

                // See if the parent is loaded.
                if ( parentPlugin == null )
                {
                    Log.info( "Unable to load plugin '{}': parent plugin '{}' has not been loaded.", pluginName, parentPluginNode.getTextTrim() );
                    Integer count = failureToLoadCount.get( pluginName );
                    if ( count == null ) {
                        count = 0;
                    }
                    failureToLoadCount.put( pluginName, ++count );
                    return false;
                }
                pluginLoader = classloaders.get( parentPlugin );
            }
            else
            {
                // This is not a child plugin, so create a new class loader.
                pluginLoader = new PluginClassLoader();
            }

            // Add the plugin sources to the classloaded.
            pluginLoader.addDirectory( pluginDir.toFile(), devMode );

            // When running in DEV mode, add optional other sources too.
            if ( dev != null && dev.getClassesDir() != null )
            {
                pluginLoader.addURLFile( dev.getClassesDir().toURI().toURL() );
            }

            // Instantiate the plugin!
            final String className = pluginXML.selectSingleNode( "/plugin/class" ).getText().trim();
            final Plugin plugin = (Plugin) pluginLoader.loadClass( className ).newInstance();

            // Bookkeeping!
            classloaders.put( plugin, pluginLoader );
            plugins.put( pluginName, plugin );
            pluginDirs.put( plugin, pluginDir );
            if ( dev != null )
            {
                pluginDevelopment.put( plugin, dev );
            }

            // If this is a child plugin, register it as such.
            if ( parentPlugin != null )
            {
                List<String> childrenPlugins = parentPluginMap.get( parentPlugin );
                if ( childrenPlugins == null )
                {
                    childrenPlugins = new ArrayList<>();
                    parentPluginMap.put( parentPlugin, childrenPlugins );
                }
                childrenPlugins.add( pluginName );

                // Also register child to parent relationship.
                childPluginMap.put( plugin, parentPluginName );
            }

            // Check the plugin's database schema (if it requires one).
            if ( !DbConnectionManager.getSchemaManager().checkPluginSchema( plugin ) )
            {
                // The schema was not there and auto-upgrade failed.
                Log.error( "Error while loading plugin '{}': {}", pluginName, LocaleUtils.getLocalizedString( "upgrade.database.failure" ) );
            }

            // Load any JSP's defined by the plugin.
            final Path webXML = pluginDir.resolve( "web" ).resolve( "WEB-INF" ).resolve( "web.xml" );
            if ( Files.exists( webXML ) )
            {
                PluginServlet.registerServlets( this, plugin, webXML.toFile() );
            }

            // Load any custom-defined servlets.
            final Path customWebXML = pluginDir.resolve( "web" ).resolve( "WEB-INF" ).resolve( "web-custom.xml" );
            if ( Files.exists( customWebXML ) )
            {
                PluginServlet.registerServlets( this, plugin, customWebXML.toFile() );
            }

            // Configure caches of the plugin
            configureCaches( pluginDir, pluginName );

            // Initialze the plugin.
            final ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader( pluginLoader );
            plugin.initializePlugin( this, pluginDir.toFile() );
            Log.debug( "Initialized plugin '{}'.", pluginName );
            Thread.currentThread().setContextClassLoader( oldLoader );

            // If there a <adminconsole> section defined, register it.
            final Element adminElement = (Element) pluginXML.selectSingleNode( "/plugin/adminconsole" );
            if ( adminElement != null )
            {
                final Element appName = (Element) adminElement.selectSingleNode( "/plugin/adminconsole/global/appname" );
                if ( appName != null )
                {
                    // Set the plugin name so that the proper i18n String can be loaded.
                    appName.addAttribute( "plugin", pluginName );
                }

                // If global images are specified, override their URL.
                Element imageEl = (Element) adminElement.selectSingleNode( "/plugin/adminconsole/global/logo-image" );
                if ( imageEl != null )
                {
                    imageEl.setText( "plugins/" + pluginName + "/" + imageEl.getText() );
                    imageEl.addAttribute( "plugin", pluginName ); // Set the plugin name so that the proper i18n String can be loaded.
                }
                imageEl = (Element) adminElement.selectSingleNode( "/plugin/adminconsole/global/login-image" );
                if ( imageEl != null )
                {
                    imageEl.setText( "plugins/" + pluginName + "/" + imageEl.getText() );
                    imageEl.addAttribute( "plugin", pluginName ); // Set the plugin name so that the proper i18n String can be loaded.
                }

                // Modify all the URL's in the XML so that they are passed through the plugin servlet correctly.
                final List urls = adminElement.selectNodes( "//@url" );
                for ( final Object url : urls )
                {
                    final Attribute attr = (Attribute) url;
                    attr.setValue( "plugins/" + pluginName + "/" + attr.getValue() );
                }

                // In order to internationalize the names and descriptions in the model, we add a "plugin" attribute to
                // each tab, sidebar, and item so that the the renderer knows where to load the i18n Strings from.
                final String[] elementNames = new String[]{ "tab", "sidebar", "item" };
                for ( final String elementName : elementNames )
                {
                    final List values = adminElement.selectNodes( "//" + elementName );
                    for ( final Object value : values )
                    {
                        final Element element = (Element) value;
                        // Make sure there's a name or description. Otherwise, no need to i18n settings.
                        if ( element.attribute( "name" ) != null || element.attribute( "value" ) != null )
                        {
                            element.addAttribute( "plugin", pluginName );
                        }
                    }
                }

                AdminConsole.addModel( pluginName, adminElement );
            }
            firePluginCreatedEvent( pluginName, plugin );
            Log.info( "Successfully loaded plugin '{}'.", pluginName );
            return true;
        }
        catch ( Throwable e )
        {
            Log.error( "An exception occurred while loading plugin '{}':", pluginName, e );
            Integer count = failureToLoadCount.get( pluginName );
            if ( count == null ) {
                count = 0;
            }
            failureToLoadCount.put( pluginName, ++count );
            return false;
        }
    }

    private PluginDevEnvironment configurePluginDevEnvironment( final Path pluginDir, String classesDir, String webRoot ) throws IOException
    {
        final String pluginName = pluginDir.getFileName().toString();

        final Path compilationClassesDir = pluginDir.resolve( "classes" );
        if ( Files.notExists( compilationClassesDir ) )
        {
            Files.createDirectory( compilationClassesDir );
        }
        compilationClassesDir.toFile().deleteOnExit();

        final PluginDevEnvironment dev = new PluginDevEnvironment();
        Log.info( "Plugin '{}' is running in development mode.", pluginName );
        if ( webRoot != null )
        {
            Path webRootDir = Paths.get( webRoot );
            if ( Files.notExists( webRootDir ) )
            {
                // Ok, let's try it relative from this plugin dir?
                webRootDir = pluginDir.resolve( webRoot );
            }

            if ( Files.exists( webRootDir ) )
            {
                dev.setWebRoot( webRootDir.toFile() );
            }
        }

        if ( classesDir != null )
        {
            Path classes = Paths.get( classesDir );
            if ( Files.notExists( classes ) )
            {
                // ok, let's try it relative from this plugin dir?
                classes = pluginDir.resolve( classesDir );
            }

            if ( Files.exists( classes ) )
            {
                dev.setClassesDir( classes.toFile() );
            }
        }

        return dev;
    }

    private void configureCaches( Path pluginDir, String pluginName )
    {
        Path cacheConfig = pluginDir.resolve( "cache-config.xml" );
        if ( Files.exists( cacheConfig ) )
        {
            PluginCacheConfigurator configurator = new PluginCacheConfigurator();
            try
            {
                configurator.setInputStream( new BufferedInputStream( Files.newInputStream( cacheConfig ) ) );
                configurator.configure( pluginName );
            }
            catch ( Exception e )
            {
                Log.error( "An exception occurred while trying to configure caches for plugin '{}':", pluginName, e );
            }
        }
    }

    /**
     * Delete a plugin, which removes the plugin.jar/war file after which the plugin is unloaded.
     */
    public void deletePlugin( final String pluginName )
    {
        Log.debug( "Deleting plugin '{}'...", pluginName );

        try ( final DirectoryStream<Path> ds = Files.newDirectoryStream( getPluginsDirectory(), new DirectoryStream.Filter<Path>()
        {
            @Override
            public boolean accept( final Path path ) throws IOException
            {
                if ( Files.isDirectory( path ) )
                {
                    return false;
                }

                final String fileName = path.getFileName().toString().toLowerCase();
                return ( fileName.equals( pluginName + ".jar" ) || fileName.equals( pluginName + ".war" ) );
            }
        } ) )
        {
            for ( final Path pluginFile : ds )
            {
                try
                {
                    Files.delete( pluginFile );
                    pluginMonitor.runNow( true ); // trigger unload by running the monitor (which is more thread-safe than calling unloadPlugin directly).
                }
                catch ( IOException ex )
                {
                    Log.warn( "Unable to delete plugin '{}', as the plugin jar/war file cannot be deleted. File path: {}", pluginName, pluginFile, ex );
                }
            }
        }
        catch ( Throwable e )
        {
            Log.error( "An unexpected exception occurred while deleting plugin '{}'.", pluginName, e );
        }
    }

    /**
     * Unloads a plugin. The {@link Plugin#destroyPlugin()} method will be called and then any resources will be
     * released. The name should be the canonical name of the plugin (based on the plugin directory name) and not the
     * human readable name as given by the plugin meta-data.
     *
     * This method only removes the plugin but does not delete the plugin JAR file. Therefore, if the plugin JAR still
     * exists after this method is called, the plugin will be started again the next  time the plugin monitor process
     * runs. This is useful for "restarting" plugins. To completely remove the plugin, use {@link #deletePlugin(String)}
     * instead.
     *
     * This method is called automatically when a plugin's JAR file is deleted.
     *
     * @param pluginName the name of the plugin to unload.
     */
    public void unloadPlugin( String pluginName )
    {
        Log.debug( "Unloading plugin '{}'...", pluginName );

        failureToLoadCount.remove( pluginName );

        Plugin plugin = plugins.get( pluginName );
        if ( plugin != null )
        {
            // Remove from dev mode if it exists.
            pluginDevelopment.remove( plugin );

            // See if any child plugins are defined.
            if ( parentPluginMap.containsKey( plugin ) )
            {
                String[] childPlugins =
                        parentPluginMap.get( plugin ).toArray( new String[ parentPluginMap.get( plugin ).size() ] );
                parentPluginMap.remove( plugin );
                for ( String childPlugin : childPlugins )
                {
                    Log.debug( "Unloading child plugin: '{}'.", childPlugin );
                    childPluginMap.remove( plugins.get( childPlugin ) );
                    unloadPlugin( childPlugin );
                }
            }

            Path webXML = pluginDirectory.resolve( pluginName ).resolve( "web" ).resolve( "WEB-INF" ).resolve( "web.xml" );
            if ( Files.exists( webXML ) )
            {
                AdminConsole.removeModel( pluginName );
                PluginServlet.unregisterServlets( webXML.toFile() );
            }
            Path customWebXML = pluginDirectory.resolve( pluginName ).resolve( "web" ).resolve( "WEB-INF" ).resolve( "web-custom.xml" );
            if ( Files.exists( customWebXML ) )
            {
                PluginServlet.unregisterServlets( customWebXML.toFile() );
            }

            // Wrap destroying the plugin in a try/catch block. Otherwise, an exception raised
            // in the destroy plugin process will disrupt the whole unloading process. It's still
            // possible that classloader destruction won't work in the case that destroying the plugin
            // fails. In that case, Openfire may need to be restarted to fully cleanup the plugin
            // resources.
            try
            {
                plugin.destroyPlugin();
                Log.debug( "Destroyed plugin '{}'.", pluginName );
            }
            catch ( Exception e )
            {
                Log.error( "An exception occurred while unloading plugin '{}':", pluginName, e );
            }
        }

        // Remove references to the plugin so it can be unloaded from memory
        // If plugin still fails to be removed then we will add references back
        // Anyway, for a few seconds admins may not see the plugin in the admin console
        // and in a subsequent refresh it will appear if failed to be removed
        plugins.remove( pluginName );
        Path pluginFile = pluginDirs.remove( plugin );
        PluginClassLoader pluginLoader = classloaders.remove( plugin );

        // try to close the cached jar files from the plugin class loader
        if ( pluginLoader != null )
        {
            pluginLoader.unloadJarFiles();
        }
        else
        {
            Log.warn( "No plugin loader found for '{}'.", pluginName );
        }

        // Try to remove the folder where the plugin was exploded. If this works then
        // the plugin was successfully removed. Otherwise, some objects created by the
        // plugin are still in memory.
        Path dir = pluginDirectory.resolve( pluginName );
        // Give the plugin 2 seconds to unload.
        try
        {
            Thread.sleep( 2000 );
            // Ask the system to clean up references.
            System.gc();
            int count = 0;
            while ( !deleteDir( dir ) && count++ < 5 )
            {
                Log.warn( "Error unloading plugin '{}'. Will attempt again momentarily.", pluginName );
                Thread.sleep( 8000 );
                // Ask the system to clean up references.
                System.gc();
            }
        }
        catch ( InterruptedException e )
        {
            Log.debug( "Stopped waiting for plugin '{}' to be fully unloaded.", pluginName, e );
        }

        if ( plugin != null && Files.notExists( dir ) )
        {
            // Unregister plugin caches
            PluginCacheRegistry.getInstance().unregisterCaches( pluginName );

            // See if this is a child plugin. If it is, we should unload
            // the parent plugin as well.
            if ( childPluginMap.containsKey( plugin ) )
            {
                String parentPluginName = childPluginMap.get( plugin );
                Plugin parentPlugin = plugins.get( parentPluginName );
                List<String> childrenPlugins = parentPluginMap.get( parentPlugin );

                childrenPlugins.remove( pluginName );
                childPluginMap.remove( plugin );

                // When the parent plugin implements PluginListener, its pluginDestroyed() method
                // isn't called if it dies first before its child. Athough the parent will die anyway,
                // it's proper if the parent "gets informed first" about the dying child when the
                // child is the one being killed first.
                if ( parentPlugin instanceof PluginListener )
                {
                    PluginListener listener;
                    listener = (PluginListener) parentPlugin;
                    listener.pluginDestroyed( pluginName, plugin );
                }
                unloadPlugin( parentPluginName );
            }
            firePluginDestroyedEvent( pluginName, plugin );
            Log.info( "Successfully unloaded plugin '{}'.", pluginName );
        }
        else if ( plugin != null )
        {
            Log.info( "Restore references since we failed to remove the plugin '{}'.", pluginName );
            plugins.put( pluginName, plugin );
            pluginDirs.put( plugin, pluginFile );
            classloaders.put( plugin, pluginLoader );
        }
    }

    /**
     * Loads a class from the classloader of a plugin.
     *
     * @param plugin the plugin.
     * @param className the name of the class to load.
     * @return the class.
     * @throws ClassNotFoundException if the class was not found.
     * @throws IllegalAccessException if not allowed to access the class.
     * @throws InstantiationException if the class could not be created.
     */
    public Class loadClass( Plugin plugin, String className ) throws ClassNotFoundException,
            IllegalAccessException, InstantiationException
    {
        PluginClassLoader loader = classloaders.get( plugin );
        return loader.loadClass( className );
    }

    /**
     * Returns a plugin's dev environment if development mode is enabled for
     * the plugin.
     *
     * @param plugin the plugin.
     * @return the plugin dev environment, or <tt>null</tt> if development
     *         mode is not enabled for the plugin.
     */
    public PluginDevEnvironment getDevEnvironment( Plugin plugin )
    {
        return pluginDevelopment.get( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getName(Plugin)}.
     */
    @Deprecated
    public String getName( Plugin plugin )
    {
        return PluginMetadataHelper.getName( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getDescription(Plugin)}.
     */
    @Deprecated
    public String getDescription( Plugin plugin )
    {
        return PluginMetadataHelper.getDescription( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getAuthor(Plugin)}.
     */
    @Deprecated
    public String getAuthor( Plugin plugin )
    {
        return PluginMetadataHelper.getAuthor( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getVersion(Plugin)}.
     */
    @Deprecated
    public String getVersion( Plugin plugin )
    {
        return PluginMetadataHelper.getVersion( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getMinServerVersion(Plugin)}.
     */
    @Deprecated
    public String getMinServerVersion( Plugin plugin )
    {
        return PluginMetadataHelper.getMinServerVersion( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getDatabaseKey(Plugin)}.
     */
    @Deprecated
    public String getDatabaseKey( Plugin plugin )
    {
        return PluginMetadataHelper.getDatabaseKey( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getDatabaseVersion(Plugin)}.
     */
    @Deprecated
    public int getDatabaseVersion( Plugin plugin )
    {
        return PluginMetadataHelper.getDatabaseVersion( plugin );
    }

    /**
     * @deprecated Moved to {@link PluginMetadataHelper#getLicense(Plugin)}.
     */
    @Deprecated
    public License getLicense( Plugin plugin )
    {
        return PluginMetadataHelper.getLicense( plugin );
    }

    /**
     * Returns the classloader of a plugin.
     *
     * @param plugin the plugin.
     * @return the classloader of the plugin.
     */
    public PluginClassLoader getPluginClassloader( Plugin plugin )
    {
        return classloaders.get( plugin );
    }


    /**
     * Deletes a directory.
     *
     * @param dir the directory to delete.
     * @return true if the directory was deleted.
     */
    static boolean deleteDir( Path dir )
    {
        try
        {
            if ( Files.isDirectory( dir ) )
            {
                Files.walkFileTree( dir, new SimpleFileVisitor<Path>()
                {
                    @Override
                    public FileVisitResult visitFile( Path file, BasicFileAttributes attrs ) throws IOException
                    {
                        try
                        {
                            Files.deleteIfExists( file );
                        }
                        catch ( IOException e )
                        {
                            Log.debug( "Plugin removal: could not delete: {}", file );
                            throw e;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory( Path dir, IOException exc ) throws IOException
                    {
                        try
                        {
                            Files.deleteIfExists( dir );
                        }
                        catch ( IOException e )
                        {
                            Log.debug( "Plugin removal: could not delete: {}", dir );
                            throw e;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                } );
            }
            return Files.notExists( dir ) || Files.deleteIfExists( dir );
        }
        catch ( IOException e )
        {
            return Files.notExists( dir );
        }
    }

    /**
     * Registers a PluginListener, which will now start receiving events regarding plugin creation and destruction.
     *
     * When the listener was already registered, this method will have no effect.
     *
     * @param listener the listener to be notified (cannot be null).
     */
    public void addPluginListener( PluginListener listener )
    {
        pluginListeners.add( listener );
    }

    /**
     * Deregisters a PluginListener, which will no longer receive events.
     *
     * When the listener was never added, this method will have no effect.
     *
     * @param listener the listener to be removed (cannot be null).
     */
    public void removePluginListener( PluginListener listener )
    {
        pluginListeners.remove( listener );
    }

    /**
     * Registers a PluginManagerListener, which will now start receiving events regarding plugin management.
     *
     * @param listener the listener to be notified (cannot be null).
     */
    public void addPluginManagerListener( PluginManagerListener listener )
    {
        pluginManagerListeners.add( listener );
        if ( isExecuted() )
        {
            firePluginsMonitored();
        }
    }

    /**
     * Deregisters a PluginManagerListener, which will no longer receive events.
     *
     * When the listener was never added, this method will have no effect.
     *
     * @param listener the listener to be notified (cannot be null).
     */
    public void removePluginManagerListener( PluginManagerListener listener )
    {
        pluginManagerListeners.remove( listener );
    }

    /**
     * Notifies all registered PluginListener instances that a new plugin was created.
     *
     * @param name The name of the plugin
     * @param plugin the plugin.
     */
    void firePluginCreatedEvent( String name, Plugin plugin )
    {
        for ( final PluginListener listener : pluginListeners )
        {
            try
            {
                listener.pluginCreated( name, plugin );
            }
            catch ( Exception ex )
            {
                Log.warn( "An exception was thrown when one of the pluginManagerListeners was notified of a 'created' event for plugin '{}'!", name, ex );
            }
        }
    }

    /**
     * Notifies all registered PluginListener instances that a plugin was destroyed.
     *
     * @param name The name of the plugin
     * @param plugin the plugin.
     */
    void firePluginDestroyedEvent( String name, Plugin plugin )
    {
        for ( final PluginListener listener : pluginListeners )
        {
            try
            {
                listener.pluginDestroyed( name, plugin );
            }
            catch ( Exception ex )
            {
                Log.warn( "An exception was thrown when one of the pluginManagerListeners was notified of a 'destroyed' event for plugin '{}'!", name, ex );
            }

        }
    }

    /**
     * Notifies all registered PluginManagerListener instances that the service monitoring for plugin changes completed a
     * periodic check.
     */
    void firePluginsMonitored()
    {
        // Set that at least one iteration was done. That means that "all available" plugins
        // have been loaded by now.
        if ( !XMPPServer.getInstance().isSetupMode() )
        {
            executed = true;
        }

        for ( final PluginManagerListener listener : pluginManagerListeners )
        {
            try
            {
                listener.pluginsMonitored();
            }
            catch ( Exception ex )
            {
                Log.warn( "An exception was thrown when one of the pluginManagerListeners was notified of a 'monitored' event!", ex );
            }
        }
    }
}