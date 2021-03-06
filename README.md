# 简介

> Reptile是一个具有高拓展性的可支持单机与集群部署Java多线程爬虫框架，该框架可简化爬虫的开发流程。该框架各个组件高内聚松耦合的特性让用户可以对不同组件进行定制来满足不同的需求。


# 架构

![Reptile.png](https://upload-images.jianshu.io/upload_images/4750376-3f10253975343c38.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

`Reptile`作为爬虫主体可在主线程运行也可以异步运行，爬虫主要有四个核心组件：
+ `Scheduler`执行请求调度，可以往其添加新的爬取请求，并支持去重处理
+ `Downloader`执行请求下载与解析响应
+ `ResponseHandler`由使用者提供实现来对响应处理，形成`Result`结果与新的爬取请求`Request`
+ `Consumer`来对处理的结果`Result`进行消费，例如持久化存储，用户可自定义其具体实现

四个组件之间的关系如架构图所示，它们之间的互相调用形成一个完整的工作流并在`Workflow`线程中运行，`Reptile`爬虫会根据配置的线程数量通过线程池创建指定数量的工作流线程并发执行工作流任务。

# 特性

+ 模块化设计，具有高度拓展性
+ 支持单机多线程部署
+ 支持简单集群部署
+ 配置简单清晰
+ 单机部署时，请求爬取完毕并且无其他线程产生新请求时会自动停止并清除理资源
+ 整合Jsoup，支持HTML页面解析
+ 请求调度器支持URL或请求的去重处理，提供布隆过滤器与集合去重实现，默认使用布隆过滤器，可在配置类进行指定
+ 支持设置UserAgent池与Proxy池，并且可设置请求对UserAgent与Proxy的选择策略，如随机或循环顺序选择

# 快速开始

## 使用Maven

1. clone项目并构建发布到本地仓库
```
git clone git@github.com:xiepuhuan/reptile.git
cd reptile
mvn -Dmaven.test.skip=true
```
2. 在项目中使用Maven引入对应的依赖

```
<dependency>
    <groupId>com.xiepuhuan</groupId>
    <artifactId>reptile</artifactId>
    <version>0.2</version>
</dependency>
```

# 使用方式

1. 实现`ResponseHandler`接口，重写`isSupport`与`handle`方法。
    + `isSupport`方法根据`request`和`response`参数判断是否需要处理该响应，是则返回`true`，否则返回`false`。
    + `handle`方法处理该响应，并将处理结果存储到`result`，如果从响应中有提取到要爬取的新请求则将其作为返回值返回。
    + 如果没有找到支持处理该响应的处理器则响应会被忽略。
2. 实现`Consumer`接口，重写`consume`方法，执行对数据的消费，可在该方法中对响应处理结果进行持久化等操作，目前提供了`ConsoleConsumer`与`JsonFileConsumer`等实现，默认使用`ConsoleConsumer`。

# 示例

## 单机部署
``` java
public class ZhihuPageHandler implements ResponseHandler {

    private static final String[] URLS = new String[] {
            "https://www.zhihu.com/api/v4/search_v3?t=general&q=java"
    };

    @Override
    public List<Request> handle(Response response, Result result) {
        Content content = response.getContent();
        JSONObject jsonObject = JSON.parseObject(content.getContent(), JSONObject.class);
        result.setResults(jsonObject.getInnerMap());

        JSONObject paging = jsonObject.getJSONObject("paging");

        if (!paging.getBoolean("is_end")) {
            List<Request> requests = new ArrayList<>();
            requests.add(new Request(paging.getString("next")));
            return requests;
        }
        return null;
    }

    @Override
    public boolean isSupport(Request request, Response response) {
        return true;
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        // 构建Reptile爬虫配置类，
        ReptileConfig config = ReptileConfig.Builder.cutom()
                .setThreadCount(8)
                .appendResponseHandlers(new ZhihuPageHandler())
                .setDeploymentMode(DeploymentModeEnum.SINGLE)
                .setConsumer(new ConsoleConsumer())
                .build();
        // 构建Reptile爬虫对象并添加初始爬取URL
        Reptile reptile = Reptile.create(config).addUrls(URLS);
        // 启动爬虫
        reptile.start();
    }
}
```

## 分布式部署

分布式部署时，创建配置类时需要通过`setDeploymentMode`方法指定部署模式为`DeploymentModeEnum.Distributed`，并且需要通过`setScheduler`方法设置一个Redis队列调度器，可以使用`RedisFIFOQueueScheduler`作为实现。

``` java
public class ZhihuPageHandler implements ResponseHandler {

    private static final String[] URLS = new String[] {
            "https://www.zhihu.com/api/v4/search_v3?t=general&q=java"
    };

    @Override
    public List<Request> handle(Response response, Result result) {
        Content content = response.getContent();
        JSONObject jsonObject = JSON.parseObject(content.getContent(), JSONObject.class);
        result.setResults(jsonObject.getInnerMap());

        JSONObject paging = jsonObject.getJSONObject("paging");

        if (!paging.getBoolean("is_end")) {
            List<Request> requests = new ArrayList<>();
            requests.add(new Request(paging.getString("next")));
            return requests;
        }
        return null;
    }

    @Override
    public boolean isSupport(Request request, Response response) {
        return true;
    }

    public static void main(String[] args) throws IOException, InterruptedException {

        // 构建Reptile爬虫配置类，

        ReptileConfig config = ReptileConfig.Builder.cutom()
                .setThreadCount(8)
                .appendResponseHandlers(new ZhihuPageHandler())
                .setDeploymentMode(DeploymentModeEnum.Distributed)
                .setScheduler(new RedisFIFOQueueScheduler())
                .setConsumer(new ConsoleConsumer())
                .build();
        // 构建Reptile爬虫对象并添加爬去的URL
        Reptile reptile = Reptile.create(config).addUrls(URLS);
        // 启动爬虫
        reptile.start();
    }
}
```
