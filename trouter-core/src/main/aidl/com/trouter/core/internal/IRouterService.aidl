package com.trouter.core.internal;

import android.os.Bundle;

/**
 * 跨进程导航/服务通道（V4.0 + G2 跨进程服务）：
 * - navigate：路由导航（返回结构化字符串结果，见 RemoteReplyCodec）；
 * - callService：按名称调用远端进程注册的服务端点（端点返回结构化字符串）；
 * - callTyped（批次 C）：类型化调用——入参与结果都走原生 Bundle（不做字符串二次编码），
 *   由 @RemoteApi 生成物完成参数打包/结果解包。
 */
interface IRouterService {
    String navigate(String path, in Bundle bundle);
    String callService(String name, in Bundle args);
    Bundle callTyped(String service, String method, in Bundle args);
}
