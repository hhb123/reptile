package com.xiepuhuan.reptile.model;

import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.ContentType;
import org.apache.http.message.BasicHeader;

/**
 * @author xiepuhuan
 */
public class Request {

    public static final String GET_METHOD = "GET";

    public static final String POST_METHOD = "POST";

    private String method;

    private String url;

    private List<Cookie> cookies;

    private List<Header> headers;

    private ContentType contentType;

    private byte[] body;

    private Request() {}

    public Request(String method, String url) {
        this.method = method;
        this.url = url;
    }

    public Request(String url) {
        this(GET_METHOD, url);
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public Cookie[] getCookies() {
        if (cookies == null) {
            return null;
        }

        return cookies.stream().toArray(Cookie[]::new);
    }

    public Request append(Cookie cookie) {
        if (cookies == null) {
            cookies = new ArrayList<>();
        }
        cookies.add(cookie);
        return this;
    }

    public List<Header> getHeaders() {
        return headers;
    }

    public Request setHeaders(List<Header> headers) {
        this.headers = headers;
        return this;
    }

    public Request setHeader(String name, String value) {
        if (headers == null) {
            headers = new ArrayList<>();
        }
        headers.add(new BasicHeader(name, value));
        return this;
    }

    public ContentType getContentType() {
        return contentType;
    }

    public Request setContentType(ContentType contentType) {
        this.contentType = contentType;
        return this;
    }

    public byte[] getBody() {
        return body;
    }

    public Request setBody(byte[] body) {
        this.body = body;
        return this;
    }

    @Override
    public int hashCode() {
        return method.hashCode() ^ url.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }

        if (this == obj) {
            return true;
        }

        if (obj instanceof Request) {
            Request request = (Request) obj;
            if (!StringUtils.equalsIgnoreCase(request.method, method)) {
                return false;
            }

            if (!StringUtils.equalsIgnoreCase(request.url, url)) {
                return false;
            }

            if (body == request.body) {
                return true;
            }

            if (body == null || request.body == null || body.length != request.body.length) {
                return false;
            }

            for (int i = 0; i < body.length; ++i) {
                if (body[i] != request.body[i]) {
                    return false;
                }
            }

            return true;
        }

        return false;
    }


    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("Request{");
        sb.append("method='").append(method).append('\'');
        sb.append(", url='").append(url).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
