package com.trouter.core.internal;

import android.os.Bundle;

/**
 * 跨进程导航服务（V4.0）：remote 进程侧实现，host 进程经 bind 调用。
 * 返回结构化字符串结果（见 RemoteReplyCodec），跨 AIDL 不引入自定义 Parcelable。
 */
interface IRouterService {
    String navigate(String path, in Bundle bundle);
}
