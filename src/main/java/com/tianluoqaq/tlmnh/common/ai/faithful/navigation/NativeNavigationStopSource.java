package com.tianluoqaq.tlmnh.common.ai.faithful.navigation;

/** 原生导航器上的惰性观察状态，不修改路径执行 */
public interface NativeNavigationStopSource {

    NativeNavigationStopState tlmnh$navigationStopState();
}
