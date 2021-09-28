package com.aliware.tianchi;

import org.apache.dubbo.rpc.Invoker;

import java.util.Map;
import java.util.concurrent.*;

/**
 * @author Viber
 * @version 1.0
 * @apiNote 数值范围从1~100, 值越小则越能够使用到
 * @since 2021/9/10 13:55
 */
public class NodeManager {
    //帮助定期的减少Node的信息
    private static final Map<String, NodeState> STATES = new ConcurrentHashMap<>();

    //20毫秒的方法(这个时间可以根据这台机器的近一段时间的处理能力一定浮动的调整) -- 需要快速失败
    public static NodeState state(Invoker<?> invoker) {
        String uri = invoker.getUrl().toIdentityString();
        return STATES.computeIfAbsent(uri, s -> new NodeState());
    }
}