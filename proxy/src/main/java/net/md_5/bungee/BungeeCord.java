package net.md_5.bungee;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Timer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import lombok.Getter;
import static net.md_5.bungee.Logger.$;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.TabListHandler;
import net.md_5.bungee.api.config.ConfigurationAdapter;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.command.*;
import net.md_5.bungee.packet.DefinedPacket;
import net.md_5.bungee.packet.PacketFAPluginMessage;
import net.md_5.bungee.tablist.GlobalPingTabList;
import net.md_5.bungee.tablist.GlobalTabList;
import net.md_5.bungee.tablist.ServerUniqueTabList;

/**
 * Main BungeeCord proxy class.
 */
public class BungeeCord extends ProxyServer
{

    /**
     * Server protocol version.
     */
    public static final int PROTOCOL_VERSION = 51;
    /**
     * Server game version.
     */
    public static final String GAME_VERSION = "1.4.6";
    /**
     * Current operation state.
     */
    public volatile boolean isRunning;
    /**
     * Configuration.
     */
    public final Configuration config = new Configuration();
    /**
     * Thread pool.
     */
    public final ExecutorService threadPool = Executors.newCachedThreadPool();
    /**
     * locations.yml save thread.
     */
    private final Timer saveThread = new Timer("Reconnect Saver");
    /**
     * Server socket listener.
     */
    private ListenThread listener;
    /**
     * Current version.
     */
    public static String version = (BungeeCord.class.getPackage().getImplementationVersion() == null) ? "unknown" : BungeeCord.class.getPackage().getImplementationVersion();
    /**
     * Fully qualified connections.
     */
    public Map<String, UserConnection> connections = new ConcurrentHashMap<>();
    public Map<String, List<UserConnection>> connectionsByServer = new ConcurrentHashMap<>();
    /**
     * Tab list handler
     */
    public TabListHandler tabListHandler;
    /**
     * Registered Global Plugin Channels
     */
    public Queue<String> globalPluginChannels = new ConcurrentLinkedQueue<>();
    /**
     * Plugin manager.
     */
    @Getter
    public final PluginManager pluginManager = new PluginManager();


    {
        getPluginManager().registerCommand(new CommandReload());
        getPluginManager().registerCommand(new CommandReload());
        getPluginManager().registerCommand(new CommandEnd());
        getPluginManager().registerCommand(new CommandList());
        getPluginManager().registerCommand(new CommandServer());
        getPluginManager().registerCommand(new CommandIP());
        getPluginManager().registerCommand(new CommandAlert());
        getPluginManager().registerCommand(new CommandMotd());
        getPluginManager().registerCommand(new CommandBungee());
    }

    public static BungeeCord getInstance()
    {
        return (BungeeCord) ProxyServer.getInstance();
    }

    /**
     * Starts a new instance of BungeeCord.
     *
     * @param args command line arguments, currently none are used
     * @throws IOException when the server cannot be started
     */
    public static void main(String[] args) throws IOException
    {
        BungeeCord bungee = new BungeeCord();
        ProxyServer.setInstance(bungee);
        $().info("Enabled BungeeCord version " + bungee.getVersion());
        bungee.start();

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        while (bungee.isRunning)
        {
            String line = br.readLine();
            if (line != null)
            {
                boolean handled = getInstance().getPluginManager().dispatchCommand(ConsoleCommandSender.instance, line);
                if (!handled)
                {
                    System.err.println("Command not found");
                }
            }
        }
    }

    /**
     * Start this proxy instance by loading the configuration, plugins and
     * starting the connect thread.
     *
     * @throws IOException
     */
    public void start() throws IOException
    {
        config.load();
        isRunning = true;

        File plugins = new File("plugins");
        plugins.mkdir();
        pluginManager.loadPlugins(plugins);

        switch (config.tabList)
        {
            default:
            case 1:
                tabListHandler = new GlobalPingTabList();
                break;
            case 2:
                tabListHandler = new GlobalTabList();
                break;
            case 3:
                tabListHandler = new ServerUniqueTabList();
                break;
        }

        // Add RubberBand to the global plugin channel list
        globalPluginChannels.add("RubberBand");

        InetSocketAddress addr = Util.getAddr(config.bindHost);
        listener = new ListenThread(addr);
        listener.start();

        saveThread.start();
        $().info("Listening on " + addr);

        if (config.metricsEnabled)
        {
            new Metrics().start();
        }
    }

    /**
     * Destroy this proxy instance cleanly by kicking all users, saving the
     * configuration and closing all sockets.
     */
    public void stop()
    {
        this.isRunning = false;
        $().info("Disabling plugin");
        pluginManager.onDisable();

        $().info("Closing listen thread");
        try
        {
            listener.socket.close();
            listener.join();
        } catch (InterruptedException | IOException ex)
        {
            $().severe("Could not close listen thread");
        }

        $().info("Closing pending connections");
        threadPool.shutdown();

        $().info("Disconnecting " + connections.size() + " connections");
        for (UserConnection user : connections.values())
        {
            user.disconnect("Proxy restarting, brb.");
        }

        $().info("Saving reconnect locations");
        saveThread.interrupt();
        try
        {
            saveThread.join();
        } catch (InterruptedException ex)
        {
        }

        $().info("Thank you and goodbye");
        System.exit(0);
    }

    /**
     * Miscellaneous method to set options on a socket based on those in the
     * configuration.
     *
     * @param socket to set the options on
     * @throws IOException when the underlying set methods thrown an exception
     */
    public void setSocketOptions(Socket socket) throws IOException
    {
        socket.setSoTimeout(config.timeout);
        socket.setTrafficClass(0x18);
        socket.setTcpNoDelay(true);
    }

    /**
     * Broadcasts a packet to all clients that is connected to this instance.
     *
     * @param packet the packet to send
     */
    public void broadcast(DefinedPacket packet)
    {
        for (UserConnection con : connections.values())
        {
            con.packetQueue.add(packet);
        }
    }

    /**
     * Broadcasts a plugin message to all servers with currently connected
     * players.
     *
     * @param channel name
     * @param message to send
     */
    public void broadcastPluginMessage(String channel, String message)
    {
        broadcastPluginMessage(channel, message, null);
    }

    /**
     * Broadcasts a plugin message to all servers with currently connected
     * players.
     *
     * @param channel name
     * @param message to send
     * @param server the message was sent from originally
     */
    public void broadcastPluginMessage(String channel, String message, String sourceServer)
    {
        for (String server : connectionsByServer.keySet())
        {
            if (sourceServer == null || !sourceServer.equals(server))
            {
                List<UserConnection> conns = BungeeCord.instance.connectionsByServer.get(server);
                if (conns != null && conns.size() > 0)
                {
                    UserConnection user = conns.get(0);
                    user.sendPluginMessage(channel, message.getBytes());
                }
            }
        }
    }

    /**
     * Send a plugin message to a specific server if it has currently connected
     * players.
     *
     * @param channel name
     * @param message to send
     * @param server the message is to be sent to
     */
    public void sendPluginMessage(String channel, String message, String targetServer)
    {
        List<UserConnection> conns = connectionsByServer.get(targetServer);
        if (conns != null && conns.size() > 0)
        {
            UserConnection user = conns.get(0);
            user.sendPluginMessage(channel, message.getBytes());
        }
    }

    /**
     * Register a plugin channel for all users
     *
     * @param channel name
     */
    public void registerPluginChannel(String channel)
    {
        globalPluginChannels.add(channel);
        broadcast(new PacketFAPluginMessage("REGISTER", channel.getBytes()));
    }

    @Override
    public String getName()
    {
        return "BungeeCord";
    }

    @Override
    public String getVersion()
    {
        return version;
    }

    @Override
    public Logger getLogger()
    {
        return $();
    }

    @Override
    public Collection<ProxiedPlayer> getPlayers()
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public ProxiedPlayer getPlayer(String name)
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Server getServer(String name)
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public Collection<Server> getServers()
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void setConfigurationAdapter(ConfigurationAdapter adapter)
    {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }
}
