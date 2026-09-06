package com.trouter.core.internal;

import android.os.Bundle;

/**
 * 跨进程导航/服务通道（V4.0 + G2 跨进程服务）：
 * - navigate：路由导航（返回结构化字符串结果，见 RemoteReplyCodec）；
 * - callService：按名称调用远端进程注册的服务端点（端点返回结构化字符串）。
 */
interface IRouterService {
    String navigate(String path, in Bundle bundle);
    String callService(String name, in Bundle args);
}
