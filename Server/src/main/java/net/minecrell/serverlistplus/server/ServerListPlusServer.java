/*
 * ServerListPlus - https://git.io/slp
 * Copyright (C) 2014 Minecrell (https://github.com/Minecrell)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.minecrell.serverlistplus.server;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Splitter;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheBuilderSpec;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecrell.serverlistplus.core.ServerListPlusCore;
import net.minecrell.serverlistplus.core.config.PluginConf;
import net.minecrell.serverlistplus.core.config.storage.InstanceStorage;
import net.minecrell.serverlistplus.core.favicon.FaviconHelper;
import net.minecrell.serverlistplus.core.favicon.FaviconSource;
import net.minecrell.serverlistplus.core.logging.Log4j2ServerListPlusLogger;
import net.minecrell.serverlistplus.core.logging.ServerListPlusLogger;
import net.minecrell.serverlistplus.core.player.ban.NoBanProvider;
import net.minecrell.serverlistplus.core.plugin.ScheduledTask;
import net.minecrell.serverlistplus.core.plugin.ServerListPlusPlugin;
import net.minecrell.serverlistplus.core.plugin.ServerType;
import net.minecrell.serverlistplus.core.replacement.ReplacementManager;
import net.minecrell.serverlistplus.core.replacement.util.Literals;
import net.minecrell.serverlistplus.core.status.ResponseFetcher;
import net.minecrell.serverlistplus.core.status.StatusManager;
import net.minecrell.serverlistplus.core.status.StatusRequest;
import net.minecrell.serverlistplus.core.status.StatusResponse;
import net.minecrell.serverlistplus.core.util.FormattingCodes;
import net.minecrell.serverlistplus.core.util.Helper;
import net.minecrell.serverlistplus.core.util.Randoms;
import net.minecrell.serverlistplus.core.util.UUIDs;
import net.minecrell.serverlistplus.server.config.ServerConf;
import net.minecrell.serverlistplus.server.network.Netty;
import net.minecrell.serverlistplus.server.network.NetworkManager;
import net.minecrell.serverlistplus.server.status.Favicon;
import net.minecrell.serverlistplus.server.status.StatusClient;
import net.minecrell.serverlistplus.server.status.StatusPingResponse;
import net.minecrell.serverlistplus.server.status.UserProfile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ServerListPlusServer implements ServerListPlusPlugin {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.builder().hexColors().build();

    private static final Logger logger = LogManager.getLogger();
    private static ServerListPlusServer instance;

    private final ServerListPlusCore core;
    private final Path workingDir;

    private final NetworkManager network;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private boolean started;

    private boolean playerTracking;
    private ImmutableList<String> loginMessages;

    // Favicon cache
    private final CacheLoader<FaviconSource, Optional<String>> faviconLoader =
            new CacheLoader<FaviconSource, Optional<String>>() {
                @Override
                public Optional<String> load(FaviconSource source) throws Exception {
                    // Try loading the favicon
                    BufferedImage image = FaviconHelper.loadSafely(core, source);
                    if (image == null) return Optional.empty(); // Favicon loading failed
                    else return Optional.of(Favicon.create(image));
                }
            };
    private LoadingCache<FaviconSource, Optional<String>> faviconCache;

    public ServerListPlusServer() throws UnknownHostException {
        checkState(instance == null, "Server was already initialized");
        instance = this;

        this.workingDir = Paths.get("");

        logger.info("Loading...");
        this.core = new ServerListPlusCore(this, new ServerProfileManager());

        ServerConf conf = this.core.getConf(ServerConf.class);
        this.network = new NetworkManager(this, Netty.parseAddress(conf.Address));
        logger.info("Successfully loaded!");
    }

    public boolean start() {
        this.started = true;
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));

        try {
            this.network.start();
        } catch (Exception e) {
            logger.error("Failed to start network manager", e);
            this.stop();
            return false;
        }

        core.setBanProvider(new NoBanProvider());

        return true;
    }

    public boolean isRunning() {
        return this.started;
    }

    public void join() throws InterruptedException {
        this.network.join();
    }

    public boolean stop() {
        if (this.started) {
            logger.info("Stopping...");

            try {
                this.network.stop();
            } catch (Exception e) {
                logger.error("Failed to stop network manager", e);
                return false;
            }

            this.core.stop();

            this.started = false;
            return true;
        }

        return false;
    }

    public static StatusPingResponse postLegacy(InetSocketAddress address, InetSocketAddress virtualHost) {
        StatusPingResponse response = instance.handle(new StatusClient(address, OptionalInt.empty(), virtualHost));
        response.getVersion().setProtocol(Byte.MAX_VALUE);
        if (response.getPlayers() == null) {
            response.setPlayers(new StatusPingResponse.Players(0, -1, null));
        }
        return response;
    }

    public static StatusPingResponse post(StatusClient client) {
        return instance.handle(client);
    }

    public static Component postLogin(StatusClient client, String name) {
        return instance.handleLogin(client, name);
    }

    public Component handleLogin(StatusClient client, String name) {
        if (this.playerTracking) {
            core.updateClient(client.getAddress().getAddress(), null, name);
        }

        String message = Randoms.nextEntry(this.loginMessages);
        return LEGACY_SERIALIZER.deserialize(Literals.replace(message, "%player%", name));
    }

    public StatusPingResponse handle(StatusClient client) {
        StatusPingResponse ping = new StatusPingResponse();

        StatusRequest request = core.createRequest(client.getAddress().getAddress());
        client.getProtocol().ifPresent(request::setProtocolVersion);

        InetSocketAddress host = client.getVirtualHost();
        if (host != null) {
            request.setTarget(host);
        }

        final StatusPingResponse.Players players = ping.getPlayers();
        final StatusPingResponse.Version version = ping.getVersion();

        StatusResponse response = request.createResponse(core.getStatus(),
                // Return unknown player counts if it has been hidden
                new ResponseFetcher() {
                    @Override
                    public Integer getOnlinePlayers() {
                        return players != null ? players.getOnline() : null;
                    }

                    @Override
                    public Integer getMaxPlayers() {
                        return players != null ? players.getMax() : null;
                    }

                    @Override
                    public int getProtocolVersion() {
                        return version != null ? version.getProtocol() : 0;
                    }
                });

        // Description
        String message = response.getDescription();
        if (message != null) ping.setDescription(LEGACY_SERIALIZER.deserialize(message));

        if (version != null) {
            // Version name
            message = response.getVersion();
            if (message != null) version.setName(message);
            // Protocol version
            Integer protocol = response.getProtocolVersion();
            if (protocol != null) version.setProtocol(protocol);
        }

        // Favicon
        FaviconSource favicon = response.getFavicon();
        if (favicon != null && favicon != FaviconSource.NONE) {
            Optional<String> icon = faviconCache.getUnchecked(favicon);
            icon.ifPresent(ping::setFavicon);
        }

        if (response.hidePlayers()) {
            ping.setPlayers(null);
        } else {
            StatusPingResponse.Players newPlayers = players;
            if (newPlayers == null) {
                newPlayers = new StatusPingResponse.Players(0, 0, null);
                ping.setPlayers(newPlayers);
            }

            // Online players
            Integer count = response.getOnlinePlayers();
            if (count != null) newPlayers.setOnline(count);
            // Max players
            count = response.getMaxPlayers();
            if (count != null) newPlayers.setMax(count);

            // Player hover
            message = response.getPlayerHover();
            if (message != null && !message.isEmpty()) {
                List<String> lines = Helper.splitLinesToList(message);

                UserProfile[] sample = new UserProfile[lines.size()];
                for (int i = 0; i < sample.length; i++)
                    sample[i] = new UserProfile(lines.get(i), UUIDs.EMPTY);

                newPlayers.setSample(sample);
            }
        }

        return ping;
    }

    private static final ImmutableSet<String> COMMAND_ALIASES = ImmutableSet.of("serverlistplus", "slp");
    private static final Splitter COMMAND_SPLITTER = Splitter.on(' ').trimResults().omitEmptyStrings();

    public void processCommand(String command) {
        if (command.charAt(0) == '/') {
            command = command.substring(1);
        }

        String root = command;
        int pos = command.indexOf(' ');
        if (pos >= 0) {
            root = command.substring(0, pos).toLowerCase(Locale.ENGLISH);
        }

        if (COMMAND_ALIASES.contains(root)) {
            if (pos >= 0) {
                command = command.substring(pos + 1);
            } else {
                command = "";
            }
        } else {
            root = "serverlistplus";
        }

        command = command.trim();
        List<String> args = COMMAND_SPLITTER.splitToList(command);
        String subcommand = args.isEmpty() ? "" : args.get(0);
        if (subcommand.equalsIgnoreCase("stop")) {
            this.stop();
            return;
        }

        this.core.executeCommand(ConsoleCommandSender.INSTANCE, root, args.toArray(new String[args.size()]));
        if (subcommand.equalsIgnoreCase("help")) {
            ConsoleCommandSender.INSTANCE.sendMessage(ServerListPlusCore.buildCommandHelp(
                    "stop", null, "Stop the server."));
        }
    }

    @Override
    public ServerListPlusCore getCore() {
        return this.core;
    }

    @Override
    public ServerType getServerType() {
        return ServerType.SERVER;
    }

    @Override
    public String getServerImplementation() {
        return "ServerListPlusServer";
    }

    @Override
    public Path getPluginFolder() {
        return this.workingDir;
    }

    @Override
    public Integer getOnlinePlayers(String location) {
        return null;
    }

    @Override
    public Iterator<String> getRandomPlayers() {
        return null;
    }

    @Override
    public Iterator<String> getRandomPlayers(String location) {
        return null;
    }

    @Override
    public Cache<?, ?> getRequestCache() {
        return null;
    }

    @Override
    public LoadingCache<FaviconSource, Optional<String>> getFaviconCache() {
        return this.faviconCache;
    }

    @Override
    public void runAsync(Runnable task) {
        this.scheduler.execute(task);
    }

    @Override
    public ScheduledTask scheduleAsync(Runnable task, long repeat, TimeUnit unit) {
        return new ScheduledFutureTask(this.scheduler.scheduleAtFixedRate(task, 0, repeat, unit));
    }

    @Override
    public String colorize(String s) {
        return FormattingCodes.colorizeHex(s);
    }

    @Override
    public ServerListPlusLogger createLogger(ServerListPlusCore core) {
        return new Log4j2ServerListPlusLogger(this.core, LogManager.getLogger(ServerListPlusCore.class));
    }

    @Override
    public void initialize(ServerListPlusCore core) {
        core.registerConf(ServerConf.class, new ServerConf(), ServerConf.getExample(), "Server");
    }

    @Override
    public void reloadCaches(ServerListPlusCore core) {

    }

    @Override
    public void reloadFaviconCache(CacheBuilderSpec spec) {
        if (spec != null) {
            this.faviconCache = CacheBuilder.from(spec).build(faviconLoader);
        } else {
            // Delete favicon cache
            faviconCache.invalidateAll();
            faviconCache.cleanUp();
            this.faviconCache = null;
        }
    }

    @Override
    public void configChanged(ServerListPlusCore core, InstanceStorage<Object> confs) {
        this.playerTracking = confs.get(PluginConf.class).PlayerTracking.Enabled;

        ServerConf conf = confs.get(ServerConf.class);

        ImmutableList.Builder<String> builder = ImmutableList.builder();
        for (String message : conf.Login.Message) {
            builder.add(ReplacementManager.replaceStatic(core, message));
        }

        this.loginMessages = builder.build();
    }

    @Override
    public void statusChanged(StatusManager status, boolean hasChanges) {

    }
}
