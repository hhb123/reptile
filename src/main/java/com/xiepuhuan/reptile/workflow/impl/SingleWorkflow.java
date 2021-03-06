package com.xiepuhuan.reptile.workflow.impl;

import com.xiepuhuan.reptile.config.ReptileConfig;
import com.xiepuhuan.reptile.handler.ResponseHandler;
import com.xiepuhuan.reptile.model.Request;
import com.xiepuhuan.reptile.model.Response;
import com.xiepuhuan.reptile.model.Result;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author xiepuhuan
 */
public class SingleWorkflow extends AbstractWorkflow {

    private final Logger LOGGER = LoggerFactory.getLogger(SingleWorkflow.class);

    private final AtomicInteger activeThreadCount;

    private final Object requestArrived;

    private final CountDownLatch latch;

    public SingleWorkflow(CountDownLatch latch, AtomicInteger activeThreadCount, Object requestArrived,
                          String name, ReptileConfig config) {

        super(name, config.getScheduler(), config.getDownloader(), config.getResponseHandlers(), config.getConsumer(), config.getSleepTime());

        this.activeThreadCount = activeThreadCount;
        this.requestArrived = requestArrived;
        this.latch = latch;
    }

    @Override
    public void run() {
        LOGGER.info("SingleWorkflow [{}] start up", getName());
            activeThreadCount.incrementAndGet();
            for (; !Thread.interrupted(); ) {

                Request request = null;
                try {
                    request = getScheduler().poll();
                    if (request == null) {

                        if (activeThreadCount.decrementAndGet() == 0) {
                            synchronized (requestArrived) {
                                requestArrived.notify();
                            }
                            LOGGER.info("All requests have been handled and workflow [{}] exits", getName());
                            latch.countDown();
                            return;
                        }
                        synchronized (requestArrived) {
                            requestArrived.wait();
                        }

                        activeThreadCount.incrementAndGet();
                        continue;
                    }
                    Response response = null;

                    try {
                        response = getDownloader().download(request);
                    } catch (IOException | IllegalStateException e) {
                        LOGGER.warn("Failed to download response about request [{}]: {}", request.toString(), e.getMessage());
                        continue;
                    }

                    ResponseHandler requestResponseHandler = selectHandler(request, response);

                    if (requestResponseHandler == null) {
                        LOGGER.warn("No response was found for the response handler to handle the request [{}], the nonResponseResponseHandler will be used", request.toString());
                        continue;
                    }

                    Result result = new Result();
                    List<Request> requests = null;
                    try {
                        requests = requestResponseHandler.handle(response, result);
                    } catch (Exception e) {
                        LOGGER.warn("Failed to handle response: [{}]", e.getMessage());
                        continue;
                    }

                    int requestSize = requests == null ? 0 : requests.size();
                    getScheduler().push(requests);

                    if (requestSize > 0) {
                        synchronized (requestArrived) {
                            requestArrived.notifyAll();
                        }
                    }
                    try {
                        getConsumer().consume(result);
                    } catch (IOException e) {
                        LOGGER.warn("Failed to comsume result: [{}]", e.getMessage());
                    }

                    if (getSleepTime() > 0) {
                        Thread.sleep(getSleepTime());
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        latch.countDown();
        LOGGER.info("Thread [{}] are interrupted to exit workflow [{}]", Thread.currentThread().getName(), getName());
    }
}
