package net.lightbody.bmp.proxy;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.lightbody.bmp.core.har.Har;
import net.lightbody.bmp.core.har.HarEntry;
import net.lightbody.bmp.core.har.HarLog;
import net.lightbody.bmp.core.har.HarNameVersion;
import net.lightbody.bmp.core.har.HarPage;
import net.lightbody.bmp.core.util.ThreadUtils;
import net.lightbody.bmp.proxy.http.BrowserMobHttpClient;
import net.lightbody.bmp.proxy.http.RequestInterceptor;
import net.lightbody.bmp.proxy.http.ResponseInterceptor;
import net.lightbody.bmp.proxy.jetty.http.HttpContext;
import net.lightbody.bmp.proxy.jetty.http.HttpListener;
import net.lightbody.bmp.proxy.jetty.http.SocketListener;
import net.lightbody.bmp.proxy.jetty.jetty.Server;
import net.lightbody.bmp.proxy.jetty.util.InetAddrPort;
import net.lightbody.bmp.proxy.util.Log;

import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponseInterceptor;
import org.java_bandwidthlimiter.BandwidthLimiter;
import org.java_bandwidthlimiter.StreamManager;
import org.openqa.selenium.Proxy;

public class ProxyServer {
    private static final HarNameVersion CREATOR = new HarNameVersion("BrowserMob Proxy", "2.0");
    private static final Log LOG = new Log();

    /*
     * The Jetty HttpServer use in BrowserMobProxyHandler
     */
    private Server server;
    /*
     * Init the port use to bind the socket
     * value -1 means that the ProxyServer is it well configured yet
     * 
     * The port value can be change thanks to the setter method or by directly giving it as a constructor param
     */
    private int port = -1;
    private InetAddress localHost;
    private BrowserMobHttpClient client;
    private StreamManager streamManager;
    private HarPage currentPage;
    private BrowserMobProxyHandler handler;
    private int pageCount = 1;
    private AtomicInteger requestCounter = new AtomicInteger(0);

    public ProxyServer() {
    }

    public ProxyServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        if (port == -1) {
            throw new IllegalStateException("Must set port before starting");
        }

        //create a stream manager that will be capped to 100 Megabits
        //remember that by default it is disabled!
        streamManager = new StreamManager( 100 * BandwidthLimiter.OneMbps );

        server = new Server();
        HttpListener listener = new SocketListener(new InetAddrPort(getLocalHost(), getPort()));
        server.addListener(listener);
        HttpContext context = new HttpContext();
        context.setContextPath("/");
        server.addContext(context);

        handler = new BrowserMobProxyHandler();
        handler.setJettyServer(server);
        handler.setShutdownLock(new Object());
        client = new BrowserMobHttpClient(streamManager, requestCounter);
        client.prepareForBrowser();
        handler.setHttpClient(client);

        context.addHandler(handler);
        server.start();

        setPort(listener.getPort());
    }

    public org.openqa.selenium.Proxy seleniumProxy() throws UnknownHostException {
        Proxy proxy = new Proxy();
        proxy.setProxyType(Proxy.ProxyType.MANUAL);
        String proxyStr = String.format("%s:%d", getConnectableLocalHost().getCanonicalHostName(), getPort());
        proxy.setHttpProxy(proxyStr);
        proxy.setSslProxy(proxyStr);

        return proxy;
    }

    public void cleanup() {
        handler.cleanup();
    }

    public void stop() throws Exception {
        cleanup();
        client.shutdown();
        server.stop();
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    /**
     * Get the the InetAddress that the Proxy server binds to when it starts.
     *
     * If not otherwise set via {@link #setLocalHost(InetAddress)}, defaults to
     * 0.0.0.0 (i.e. bind to any interface).
     *
     * Note - just because we bound to the address, doesn't mean that it can be
     * reached. E.g. trying to connect to 0.0.0.0 is going to fail. Use
     * {@link #getConnectableLocalHost()} if you're looking for a host that can be
     * connected to.
     */
    public InetAddress getLocalHost() throws UnknownHostException {
        if (localHost == null) {
            localHost = InetAddress.getByName("0.0.0.0");
        }
        return localHost;
    }

    /**
     * Return a plausible {@link InetAddress} that other processes can use to
     * contact the proxy.
     *
     * In essence, this is the same as {@link #getLocalHost()}, but avoids
     * returning 0.0.0.0. as no-one can connect to that. If no other host has
     * been set via {@link #setLocalHost(InetAddress)}, will return
     * {@link InetAddress#getLocalHost()}
     *
     * No attempt is made to check the address for reachability before it is
     * returned.
     */
    public InetAddress getConnectableLocalHost() throws UnknownHostException {
        if (getLocalHost().equals(InetAddress.getByName("0.0.0.0"))) {
            return InetAddress.getLocalHost();
        } else {
            return getLocalHost();
        }
    }

    public void setLocalHost(InetAddress localHost) throws SocketException {
        if (localHost.isAnyLocalAddress() ||
                localHost.isLoopbackAddress() ||
                NetworkInterface.getByInetAddress(localHost) != null)
        {
            this.localHost = localHost;
        } else
        {
            throw new IllegalArgumentException("Must be address of a local adapter");
        }
    }

    public Har getHar() {
        // Wait up to 5 seconds for all active requests to cease before returning the HAR.
        // This helps with race conditions but won't cause deadlocks should a request hang
        // or error out in an unexpected way (which of course would be a bug!)
        boolean success = ThreadUtils.waitFor(new ThreadUtils.WaitCondition() {
            @Override
            public boolean checkCondition(long elapsedTimeInMs) {
                return requestCounter.get() == 0;
            }
        }, TimeUnit.SECONDS, 5);

        if (!success) {
            LOG.warn("Waited 5 seconds for requests to cease before returning HAR; giving up!");
        }

        return client.getHar();
    }

    public Har newHar(String initialPageRef) {
        pageCount = 1;

        Har oldHar = getHar();

        Har har = new Har(new HarLog(CREATOR));
        client.setHar(har);
        newPage(initialPageRef);

        return oldHar;
    }

    public void newPage(String pageRef) {
        if (pageRef == null) {
            pageRef = "Page " + pageCount;
        }

        client.setHarPageRef(pageRef);
        currentPage = new HarPage(pageRef);
        client.getHar().getLog().addPage(currentPage);

        pageCount++;
    }

    public void endPage() {
        if (currentPage == null) {
            return;
        }

        currentPage.getPageTimings().setOnLoad(new Date().getTime() - currentPage.getStartedDateTime().getTime());
        client.setHarPageRef(null);
        currentPage = null;
    }

    public void setRetryCount(int count) {
        client.setRetryCount(count);
    }

    public void remapHost(String source, String target) {
        client.remapHost(source, target);
    }

    @Deprecated
    public void addRequestInterceptor(HttpRequestInterceptor i) {
        client.addRequestInterceptor(i);
    }

    public void addRequestInterceptor(RequestInterceptor interceptor) {
        client.addRequestInterceptor(interceptor);
    }

    @Deprecated
    public void addResponseInterceptor(HttpResponseInterceptor i) {
        client.addResponseInterceptor(i);
    }

    public void addResponseInterceptor(ResponseInterceptor interceptor) {
        client.addResponseInterceptor(interceptor);
    }

    public StreamManager getStreamManager() {
        return streamManager;
    }

    //use getStreamManager().setDownstreamKbps instead
    @Deprecated
    public void setDownstreamKbps(long downstreamKbps) {
        streamManager.setDownstreamKbps(downstreamKbps);
        streamManager.enable();
    }

    //use getStreamManager().setUpstreamKbps instead
    @Deprecated
    public void setUpstreamKbps(long upstreamKbps) {
        streamManager.setUpstreamKbps(upstreamKbps);
        streamManager.enable();
    }

    //use getStreamManager().setLatency instead
    @Deprecated
    public void setLatency(long latency) {
        streamManager.setLatency(latency);
        streamManager.enable();
    }

    public void setRequestTimeout(int requestTimeout) {
        client.setRequestTimeout(requestTimeout);
    }

    public void setSocketOperationTimeout(int readTimeout) {
        client.setSocketOperationTimeout(readTimeout);
    }

    public void setConnectionTimeout(int connectionTimeout) {
        client.setConnectionTimeout(connectionTimeout);
    }

    public void autoBasicAuthorization(String domain, String username, String password) {
        client.autoBasicAuthorization(domain, username, password);
    }

    public void rewriteUrl(String match, String replace) {
        client.rewriteUrl(match, replace);
    }

    public void clearRewriteRules() {
        client.clearRewriteRules();
    }

    public void blacklistRequests(String pattern, int responseCode) {
        client.blacklistRequests(pattern, responseCode);
    }

    public List<BlacklistEntry> getBlacklistedRequests() {
        return client.getBlacklistedRequests();
    }

    public WhitelistEntry getWhitelistRequests() {
        return client.getWhitelistRequests();
    }

    public void clearBlacklist() {
        client.clearBlacklist();
    }

    public void whitelistRequests(String[] patterns, int responseCode) {
        client.whitelistRequests(patterns, responseCode);
    }

    public void clearWhitelist() {
        client.clearWhitelist();
    }

    public void addHeader(String name, String value) {
        client.addHeader(name, value);
    }

    public void setCaptureHeaders(boolean captureHeaders) {
        client.setCaptureHeaders(captureHeaders);
    }

    public void setCaptureContent(boolean captureContent) {
        client.setCaptureContent(captureContent);
    }

    public void setCaptureBinaryContent(boolean captureBinaryContent) {
        client.setCaptureBinaryContent(captureBinaryContent);
    }

    public void clearDNSCache() {
        client.clearDNSCache();
    }

    public void setDNSCacheTimeout(int timeout) {
        client.setDNSCacheTimeout(timeout);
    }

    public void waitForNetworkTrafficToStop(final long quietPeriodInMs, long timeoutInMs) {
        boolean result = ThreadUtils.waitFor(new ThreadUtils.WaitCondition() {
            @Override
            public boolean checkCondition(long elapsedTimeInMs) {
                Date lastCompleted = null;
                Har har = client.getHar();
                if (har == null || har.getLog() == null) {
                    return true;
                }

                for (HarEntry entry : har.getLog().getEntries()) {
                    // if there is an active request, just stop looking
                    if (entry.getResponse().getStatus() < 0) {
                        return false;
                    }

                    Date end = new Date(entry.getStartedDateTime().getTime() + entry.getTime());
                    if (lastCompleted == null) {
                        lastCompleted = end;
                    } else if (end.after(lastCompleted)) {
                        lastCompleted = end;
                    }
                }

                return lastCompleted != null && System.currentTimeMillis() - lastCompleted.getTime() >= quietPeriodInMs;
            }
        }, TimeUnit.MILLISECONDS, timeoutInMs);

        if (!result) {
            throw new RuntimeException("Timed out after " + timeoutInMs + " ms while waiting for network traffic to stop");
        }
    }

    public void setOptions(Map<String, String> options) {
        if (options.containsKey("httpProxy")) {
            client.setHttpProxy(options.get("httpProxy"));
        }
    }

    /** TestLabs **/

    public void mockResponses(String method, String pattern, int responseCode, String responseBody) {
//        client.mockResponses(method, pattern, responseCode, responseBody);
    }

    public void setSocksProxy(String proxyHost, int proxyPort, String username, String password) {
        client.setSocksProxy(proxyHost, proxyPort, username, password);
    }
}
