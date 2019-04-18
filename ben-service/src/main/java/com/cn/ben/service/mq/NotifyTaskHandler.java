package com.cn.ben.service.mq;

import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;
import com.cn.ben.api.enums.MethodEnum;
import com.cn.ben.api.enums.NotifyStatusEnum;
import com.cn.ben.api.enums.ParamTypeEnum;
import com.cn.ben.api.model.dto.NotifyTask;
import com.cn.ben.api.service.INotifyLogService;
import com.cn.ben.api.service.INotifyRecordService;
import com.cn.ben.service.config.TaskHandlerConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.Reference;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * <p>Title:</p>
 * <p>Description:
 * 通知-延时任务处理器
 * </p>
 *
 * @author Chen Nan
 */
@Component
@Slf4j
public class NotifyTaskHandler {
    private static DelayQueue<NotifyTask> delayQueue = new DelayQueue<>();

    @Reference
    private INotifyRecordService notifyRecordService;
    @Reference
    private INotifyLogService notifyLogService;

    private ThreadPoolExecutor singleThreadExecutor;
    private ThreadPoolExecutor taskExecutor;
    private TaskHandlerConfig config;
    private int maxNotifyTimes;

    public NotifyTaskHandler(ThreadPoolExecutor singleThreadExecutor,
                             ThreadPoolExecutor taskExecutor,
                             TaskHandlerConfig config) {
        this.singleThreadExecutor = singleThreadExecutor;
        this.taskExecutor = taskExecutor;
        this.config = config;
        this.maxNotifyTimes = config.getInterval().size();
        init();
        // TODO 从数据库中加载未完成的通知记录，继续通知
    }

    /**
     * 通知任务处理器初始化
     */
    private void init() {
        log.info("【NotifyTaskHandler】开始初始化");
        singleThreadExecutor.execute(() -> {
            while (true) {
                try {
                    log.debug("【NotifyTaskHandler】ActiveCount={}", taskExecutor.getActiveCount());
                    if (taskExecutor.getActiveCount() < taskExecutor.getMaximumPoolSize()) {
                        NotifyTask notifyTask = delayQueue.take();
                        taskExecutor.execute(() -> handleTask(notifyTask));
                    } else {
                        handlerSleep();
                    }
                } catch (RejectedExecutionException e) {
                    handlerSleep();
                } catch (Exception e) {
                    log.error("【NotifyTaskHandler】Exception：", e);
                }
            }
        });
        log.info("【NotifyTaskHandler】初始化成功");
    }

    /**
     * 添加通知任务
     *
     * @param notifyTask 通知任务信息
     */
    public void addTask(NotifyTask notifyTask) {
        boolean notifyFail = notifyTask.getNotifyTimes() >= maxNotifyTimes;
        if (!notifyFail) {
            LocalDateTime executeTime = getExecuteTime(notifyTask);
            notifyTask.setExecuteTime(executeTime);
            delayQueue.add(notifyTask);
            log.info("【NotifyTaskHandler】添加任务到延时队列,id={},times={}", notifyTask.getId(), notifyTask.getNotifyTimes());
        }
    }

    /**
     * 处理通知
     */
    private void handleTask(NotifyTask notifyTask) {
        log.info("【NotifyTask】开始通知,id={},times={}", notifyTask.getId(), notifyTask.getNotifyTimes());
        try {
            // 修改任务的通知次数与最近通知时间
            notifyTask.setNotifyTimes((short) (notifyTask.getNotifyTimes() + 1));
            notifyTask.setUpdateTime(LocalDateTime.now());

            // 发送Http请求
            HttpResponse response = sendNotify(notifyTask);

            // 判断Http请求状态码
            if (response.isOk()) {
                handleRequestSuccess(response, notifyTask);
            } else {
                handleRequestFail(response, notifyTask);
            }
        } catch (Exception e) {
            handleRequestError(e, notifyTask);
        }
        // TODO 添加通知日志
    }

    /**
     * 处理通知Http请求成功
     */
    private void handleRequestSuccess(HttpResponse response, NotifyTask notifyTask) {
        String result = response.body();
        // 通知成功标识
        // 1、上层业务系统未指定成功标识，则只需Http状态码为2xx，即通知成功
        // 2、上层业务系统已指定成功标识，则响应内容中需包含成功标识，即通知成功
        boolean successFlag = StrUtil.isBlank(notifyTask.getSuccessFlag())
                || StrUtil.containsAnyIgnoreCase(notifyTask.getSuccessFlag(), result);
        if (successFlag) {
            log.info("【NotifyTask】通知成功,id={},result={}", notifyTask.getId(), result);
            notifyRecordService.updateNotifyStatus(notifyTask, NotifyStatusEnum.SUCCESS);
        } else {
            log.info("【NotifyTask】业务方处理失败,id={},result={}", notifyTask.getId(), result);
            notifyRecordService.updateNotifyStatus(notifyTask, NotifyStatusEnum.BUSINESS_FAIL);
            addTask(notifyTask);
        }

        notifyLogService.insertNotifyLog(notifyTask, response.getStatus(), result);
    }

    /**
     * 处理通知Http请求失败
     */
    private void handleRequestFail(HttpResponse response, NotifyTask notifyTask) {
        log.info("【NotifyTask】通知请求失败,id={},code={}", notifyTask.getId(), response.getStatus());
        notifyRecordService.updateNotifyStatus(notifyTask, NotifyStatusEnum.REQUEST_FAIL);
        addTask(notifyTask);

        notifyLogService.insertNotifyLog(notifyTask, response.getStatus());
    }

    /**
     * 处理通知Http请求失败
     */
    private void handleRequestError(Exception e, NotifyTask notifyTask) {
        log.error("【NotifyTask】通知请求异常,id=" + notifyTask.getId() + ":", e);
        notifyRecordService.updateNotifyStatus(notifyTask, NotifyStatusEnum.REQUEST_FAIL);
        addTask(notifyTask);

        notifyLogService.insertNotifyLog(notifyTask, e.getMessage());
    }

    /**
     * 发送通知
     *
     * @param task 通知任务
     * @return 通知响应
     */
    private HttpResponse sendNotify(NotifyTask task) {
        HttpRequest request = httpRequest(task.getNotifyMethod(), task.getNotifyUrl());
        request = param(request, task.getNotifyParamType(), task.getNotifyParam());
        return request.addHeaders(task.getNotifyHeader())
                .timeout(task.getNotifyTimeout())
                .execute();
    }

    /**
     * 创建http请求
     *
     * @param methodEnum 请求方式
     * @param url        请求地址
     * @return 请求
     */
    private HttpRequest httpRequest(MethodEnum methodEnum, String url) {
        switch (methodEnum) {
            case GET:
                return HttpRequest.get(url);
            case POST:
                return HttpRequest.post(url);
            case HEAD:
                return HttpRequest.head(url);
            case OPTIONS:
                return HttpRequest.options(url);
            case PUT:
                return HttpRequest.put(url);
            case DELETE:
                return HttpRequest.delete(url);
            case TRACE:
                return HttpRequest.trace(url);
            case PATCH:
                return HttpRequest.patch(url);
            default:
                return HttpRequest.post(url);
        }
    }

    /**
     * 设置请求参数
     *
     * @param httpRequest 请求对象
     * @param paramType   请求参数类型
     * @param param       请求参数
     * @return 请求对象
     */
    private HttpRequest param(HttpRequest httpRequest, ParamTypeEnum paramType, Map<String, Object> param) {
        switch (paramType) {
            case FORM:
                return httpRequest.form(param);
            case BODY:
                return httpRequest.body(JSONUtil.toJsonStr(param));
            default:
                return httpRequest.form(param);
        }
    }

    /**
     * 计算本次通知任务执行时间
     *
     * @param notifyTask 通知任务信息
     * @return 执行时间
     */
    private LocalDateTime getExecuteTime(NotifyTask notifyTask) {
        LocalDateTime lastNotifyTime = notifyTask.getUpdateTime();
        int currNotifyTimes = notifyTask.getNotifyTimes();
        long interval = config.getInterval().get(currNotifyTimes) * 60 * 1000 + 1;
        return lastNotifyTime.plus(interval, ChronoUnit.MILLIS);
    }

    /**
     * 通知任务处理器睡眠
     * 当处理通知任务的线程池耗尽时，通知任务处理器将根据配置睡眠一段时间
     */
    private void handlerSleep() {
        // 线程池耗尽，延时任务将产生堆积（1、调整服务器资源，考虑增加线程数。2、调整通知超时时间。）
        log.warn("【NotifyTaskHandler】注意：线程池耗尽，任务处理器将休眠{}毫秒", config.getHandlerSleep());
        ThreadUtil.sleep(config.getHandlerSleep());
    }
}
