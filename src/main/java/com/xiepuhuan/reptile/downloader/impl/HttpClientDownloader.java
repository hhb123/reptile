package com.xiepuhuan.reptile.downloader.impl;

import com.xiepuhuan.reptile.config.DownloaderConfig;
import com.xiepuhuan.reptile.downloader.Downloader;
import com.xiepuhuan.reptile.common.pool.Pool;
import com.xiepuhuan.reptile.common.pool.impl.FixedPool;
import com.xiepuhuan.reptile.downloader.constants.UserAgentConstants;
import com.xiepuhuan.reptile.downloader.model.Proxy;
import com.xiepuhuan.reptile.model.Content;
import com.xiepuhuan.reptile.model.Request;
import com.xiepuhuan.reptile.model.Response;
import com.xiepuhuan.reptile.utils.ArgUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.UnsupportedCharsetException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.apache.http.HttpEntity;
import org.apache.http.ParseException;
import org.apache.http.client.CookieStore;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.EntityBuilder;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.ssl.SSLContexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.net.ssl.SSLContext;

/**
 * @author xiepuhuan
 */
public class HttpClientDownloader implements Downloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpClientDownloader.class);

    private final Pool<String> userAgentPool;

    private final Pool<Proxy> proxyPool;

    private final CookieStore cookieStore;

    private final DownloaderConfig config;

    private final CloseableHttpClient httpClient;

    private volatile PoolingHttpClientConnectionManager clientConnectionManager;

    public HttpClientDownloader() {
        this(DownloaderConfig.DEFAULT_DOWNLOADER_CONFIG);
    }

    public HttpClientDownloader(DownloaderConfig config) {
        ArgUtils.notNull(config, "DownloaderConfig");
        this.userAgentPool = config.isEnableUserAgentPoolConfig() ? new FixedPool<>(config.getUserAgentPoolConfig()) : null;
        this.proxyPool = config.isEnableProxyPoolConfig() ? new FixedPool<>(config.getProxyPoolConfig()) : null;
        this.config = config;
        this.cookieStore = new BasicCookieStore();
        this.clientConnectionManager = buildConnectionManager();
        this.httpClient = buildHttpClient();
    }

    public static HttpClientDownloader create(DownloaderConfig config) {
        return new HttpClientDownloader(config);
    }

    @Override
    public Response download(Request request) throws ParseException, UnsupportedCharsetException, IOException {
        if (request == null) {
            return null;
        }

        if (request.getCookies() != null) {
            Arrays.stream(request.getCookies()).forEach(cookieStore::addCookie);
        }

        RequestBuilder requestBuilder = RequestBuilder.create(request.getMethod())
                .setUri(request.getUrl());

        if (proxyPool != null) {
            requestBuilder.setConfig(RequestConfig.custom().setProxy(proxyPool.selectOne().buildHttpHost()).build());
        }

        if (userAgentPool != null) {
            request.setHeader(UserAgentConstants.USER_AGENT_NAME, userAgentPool.selectOne());
        }

        if (request.getHeaders() != null) {
            request.getHeaders().forEach(requestBuilder::addHeader);
        }

        if (request.getBody() != null) {
            requestBuilder.setEntity(EntityBuilder.create()
                    .setContentType(request.getContentType())
                    .setBinary(request.getBody())
                    .build()
            );
        }

        if (clientConnectionManager == null) {
            throw new IOException("HttpClientDownloader has been closed");
        }

        CloseableHttpResponse httpResponse = httpClient.execute(requestBuilder.build());

        return new Response(httpResponse.getStatusLine().getStatusCode(), httpResponse.getAllHeaders())
                .setContent(parse(httpResponse.getEntity()));
    }

    private Content parse(HttpEntity entity) throws ParseException, UnsupportedCharsetException, IOException {

        try {
            byte[] body = toByteArray(entity);

            if (!ArgUtils.notEmpty(body)) {
                return null;
            }

            return new Content(ContentType.getOrDefault(entity), body);
        } catch (ParseException | UnsupportedCharsetException | IOException e) {
            LOGGER.warn("Failed Get the contents of an InputStreamas a byte[]: {}", e.getMessage());
            throw e;
        }
    }

    private byte[] toByteArray(HttpEntity entity) throws IOException {
        if (entity == null || entity.getContent() == null) {
            return null;
        }

        try (InputStream inputStream = entity.getContent()) {

            int capacity = Math.max(4096, Math.max((int) entity.getContentLength(), inputStream.available()));

            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(capacity);
            final byte[] tmp = new byte[capacity];
            int l;
            while ((l = inputStream.read(tmp)) != -1) {
                buffer.write(tmp, 0, l);
            }
            return buffer.toByteArray();
        }
    }

    private CloseableHttpClient buildHttpClient() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setSocketTimeout(config.getSocketTimeout())
                .setConnectTimeout(config.getConnectTimeout())
                .setConnectionRequestTimeout(config.getConnectRequestTimeout())
                .setCookieSpec(CookieSpecs.STANDARD)
                .build();

        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create()
                .setUserAgent(config.getGeneralUserAgent())
                .setMaxConnPerRoute(config.getDefaultMaxPerRoute())
                .setMaxConnTotal(config.getMaxTotalConnections())
                .setDefaultHeaders(config.getHeaders())
                .setDefaultCookieStore(cookieStore)
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(new DefaultHttpRequestRetryHandler(3, true))
                .setConnectionManager(clientConnectionManager)
                .setProxy(config.getGeneralProxy() != null ? config.getGeneralProxy().buildHttpHost() : null);

        return httpClientBuilder.build();
    }

    private PoolingHttpClientConnectionManager buildConnectionManager() {
        Registry<ConnectionSocketFactory> registry = RegistryBuilder.<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.INSTANCE)
                .register("https", buildSSLConnectionSocketFactory())
                .build();

        clientConnectionManager = new PoolingHttpClientConnectionManager(registry);
        clientConnectionManager.setDefaultSocketConfig(SocketConfig.custom().setSoKeepAlive(true).setTcpNoDelay(true).build());
        clientConnectionManager.setDefaultMaxPerRoute(config.getDefaultMaxPerRoute());
        return clientConnectionManager;
    }

    private SSLConnectionSocketFactory buildSSLConnectionSocketFactory() {
        try {
            SSLContext sslContext = SSLContexts.custom()
                    .loadTrustMaterial(null, (chain, authType) -> true).build();

            return new SSLConnectionSocketFactory(sslContext, NoopHostnameVerifier.INSTANCE);
        } catch (NoSuchAlgorithmException | KeyManagementException | KeyStoreException | RuntimeException e) {
            LOGGER.warn("Failed to build SSLConnectionSocketFactory");
        }

        return SSLConnectionSocketFactory.getSocketFactory();
    }

    @Override
    public void close() {
        if (clientConnectionManager != null) {
            synchronized (this) {
                if (clientConnectionManager != null) {
                    try {
                        clientConnectionManager = null;
                        httpClient.close();
                        LOGGER.info("HttpClientDownloader has been successfully closed");
                    } catch (IOException e) {
                        LOGGER.warn("Failed to close httpClient: {}", e.getMessage());
                    }
                }
            }
        }
    }
}
